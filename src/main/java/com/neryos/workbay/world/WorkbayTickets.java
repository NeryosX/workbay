package com.neryos.workbay.world;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.config.WorkbayConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Force-loading for the Backshop and for a Workbay's own chunk. SPEC §12.
 *
 * <h2>Why this asks the chunk source directly</h2>
 *
 * <p>NeoForge's {@link TicketController#forceChunk} always asks for <b>radius 2</b>: ticket level
 * 31, which drags eight neighbours to level 32 and sixteen more to 33, so one ticket is a
 * five-by-five square of loaded chunks. That is twenty-five chunks to keep one bay column ticking,
 * and there is no argument for sixteen of them - <b>radius 1</b> is level 32, where block entities
 * still tick and random ticks still happen, and the square is three by three. Nine instead of
 * twenty-five, for the same behaviour a hosted machine needs. Only entities freeze, and the one
 * place that can matter is a mob inside an anchored room nobody is standing in.
 *
 * <p>So the radius is {@code chunkTicketRadius}, a host's knob, and the ticket goes in through
 * {@code ServerChunkCache#addRegionTicket} with {@code forceTicks} on - the flag NeoForge's
 * {@code ticking=true} sets, and the one that keeps {@code tickChunk} running with no player
 * nearby. OPEN_ISSUES #60.
 *
 * <p><b>The controller is still registered, and it is now only a purge.</b> Nothing here writes
 * NeoForge's forced-chunk save data any more, but a world written by an earlier version has some,
 * and {@link #validate} is the callback that throws it away on load. Without the registration
 * those tickets would be reinstated on every load by a mod that no longer knows about them, which
 * is the permanent leak §12 exists to prevent. Our own tickets are not persisted at all: the login
 * hook re-arms everything a network is entitled to ({@link AnchorPresence}), which is the same path
 * a restart already took, because {@link #validate} purged everything anyway.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Workbay.MOD_ID)
public final class WorkbayTickets {
    private WorkbayTickets() {}

    /**
     * One type for everything this mod holds, keyed by the owner. The owner is a room's UUID or a
     * block's derived one ({@link #owner}), never a network's - OPEN_ISSUES #53. A UUID is
     * {@code Comparable}, which is all {@code SortedArraySet} asks of a ticket's value, and it is
     * what makes two owners on one chunk two tickets rather than one.
     */
    public static final TicketType<UUID> HOLD =
        TicketType.create(Workbay.MOD_ID + ":hold", Comparator.<UUID>naturalOrder());

    public static final TicketController TICKETS =
        new TicketController(Workbay.rl("backshop"), WorkbayTickets::validate);

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterTicketControllersEvent event) -> event.register(TICKETS));
    }

    /**
     * Runs on world load. It <em>must</em> exist or forced chunks written by an older version leak
     * forever. FTB-Chunks' answer: purge everything, then re-register from the authoritative
     * registry - never try to match owners here, where the registry may not be loaded yet.
     *
     * <p>Re-registration is the login hook's job ({@link AnchorPresence#resume}), which is where it
     * has to be anyway: a released chunk is not ticking, so nothing in the world is left that could
     * notice its owner came back.
     */
    private static void validate(ServerLevel level, TicketHelper helper) {
        List.copyOf(helper.getBlockTickets().keySet()).forEach(helper::removeAllTickets);
        List.copyOf(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets);
    }

    /**
     * Who a ticket belongs to when the owner is a <em>block</em> rather than a room.
     *
     * <p>Mirroring used to register under the network's id, which is one owner for every Workbay
     * standing on one record: releasing the first block's tickets released the chunks the second
     * still believed it held. A position in a dimension already is a stable name for a block, so
     * there is nothing to store and nothing to migrate. OPEN_ISSUES #53.
     */
    public static UUID owner(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
        net.minecraft.core.BlockPos pos) {
        return UUID.nameUUIDFromBytes((dimension.location() + "@" + pos.asLong())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The range {@code chunkTicketRadius} is defined over, and what {@link #release} sweeps. */
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 2;

    /** How far one ticket reaches, in chunks. Read here, on every call, never at class load. */
    public static int radius() {
        return WorkbayConfig.SERVER.chunkTicketRadius.get();
    }

    /** One ticket: a chunk and who holds it. Per dimension, because that is what is asked. */
    private record Hold(long chunk, UUID owner) {}

    /**
     * Every ticket this mod holds right now, by dimension. {@link #force} and {@link #release} are
     * the only two things that ever create or drop one, so this cannot disagree with reality the
     * way a cache would; nothing persists it, because no ticket survives a restart either.
     */
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
        Set<Hold>> HELD = new ConcurrentHashMap<>();

    /**
     * <b>A level this mod holds a ticket in must not count as empty.</b>
     *
     * <p>This is what NeoForge's ticket controller was quietly buying, and the reason
     * OPEN_ISSUES #60 could not be answered with a smaller radius alone. {@code ServerLevel#tick}
     * skips <em>entities and every block entity in the level</em> once {@code emptyTime} passes
     * 300 ticks, and the thing that keeps it at zero with no player present is
     * {@code !players.isEmpty() || ForcedChunkManager.hasForcedChunks(level)} - which reads
     * vanilla's forceload set and NeoForge's own saved data, and knows nothing about a ticket
     * registered directly. Measured: with the radius fixed and this missing, a hosted furnace
     * smelted for fifteen seconds and then stopped, with the chunk still loaded and still
     * reporting {@code isPositionTicking}.
     *
     * <p>{@code resetEmptyTime} is vanilla's own answer to the same question, and calling it while
     * we hold a ticket says exactly what a forced chunk means. Fired before the level ticks, which
     * is where the counter is read.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Set<Hold> here = HELD.get(level.dimension());
            if (here != null && !here.isEmpty()) {
                level.resetEmptyTime();
            }
        }
    }

    /** How many tickets this mod holds in a level right now; what the bench reports as forced. */
    public static int held(ServerLevel level) {
        Set<Hold> here = HELD.get(level.dimension());
        return here == null ? 0 : here.size();
    }

    /**
     * Loads the chunk and registers the ticket for it, in that order - the sync load first so the
     * caller can touch the chunk on the same tick, then the ticket so it stays.
     *
     * <p>No {@code getChunkSource().save(false)} any more. That was there so NeoForge's
     * forced-chunk save data was crash-durable immediately, and nothing here writes it; a full
     * sweep of every dirty chunk on every force was the price of durability for a file we no
     * longer keep.
     */
    public static void force(ServerLevel level, UUID owner, ChunkPos chunk) {
        level.getChunk(chunk.x, chunk.z);
        level.getChunkSource().addRegionTicket(HOLD, chunk, radius(), owner, true);
        HELD.computeIfAbsent(level.dimension(), key -> ConcurrentHashMap.newKeySet())
            .add(new Hold(chunk.toLong(), owner));
    }

    /**
     * <b>Every legal radius, not the current one.</b> A ticket's identity includes the level the
     * radius picked, so a host who reloads the config with a different {@code chunkTicketRadius}
     * would otherwise ask to remove a ticket that was never registered - and the one that was
     * would be held for ever with nothing left that knows about it. Removing a ticket nobody holds
     * is a no-op, so asking twice costs a comparison.
     */
    public static void release(ServerLevel level, UUID owner, ChunkPos chunk) {
        for (int radius = MIN_RADIUS; radius <= MAX_RADIUS; radius++) {
            level.getChunkSource().removeRegionTicket(HOLD, chunk, radius, owner, true);
        }
        Set<Hold> here = HELD.get(level.dimension());
        if (here != null) {
            here.remove(new Hold(chunk.toLong(), owner));
        }
    }
}
