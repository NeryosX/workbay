package com.neryos.workbay.world;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.config.WorkbayConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC.md §12's last line: <b>a ticket exists only while its owner is online</b>, with a short
 * grace period after logout so a running process can finish.
 *
 * <p>This is the bill a server owner is actually afraid of. Without it a player who installs an
 * Anchor and never logs in again costs that server a permanently ticking chunk for ever - and
 * since OPEN_ISSUES #52 that chunk is in the <em>overworld</em>, not only in a dimension nobody
 * else visits. Chunkloaders conventionally stop at logout; a mod whose whole pitch is that it does
 * not cost more than the floor cannot be the exception.
 *
 * <p><b>One rule for every ticket this mod holds, not one per upgrade.</b> Three places force
 * chunks and each one answers the question differently:
 * <ul>
 *   <li><b>Anchored rooms</b> and <b>the Workbay's own chunk</b> are tickets nobody is paying for
 *       by standing anywhere, so both go through {@link #apply}.</li>
 *   <li><b>Mirroring</b> needs nothing. It holds the Backshop column exactly while the Workbay
 *       block ticks, and the block only ticks while its own chunk is loaded - by the Anchor, which
 *       this releases, or by a player being there, which is the case §12 prices at zero. Gating it
 *       as well would only mean a chain stops working while a player is standing next to it.</li>
 * </ul>
 *
 * <p><b>What happens to work in progress: it pauses, and loses nothing.</b> Nothing this mod moves
 * is ever in flight between ticks - {@link com.neryos.workbay.bus.BusRunner} takes from the source
 * and gives to the sink inside one tick, and the Workbay's FE buffer is saved. So a released
 * chunk is a chain
 * standing still, not a chain dropping a batch: every hosted machine keeps its own progress the way
 * any unloaded block entity does, and the counts on both ends of every link are the counts that
 * were there at logout.
 *
 * <p>Presence is read off the player list rather than a set kept here, because a set can desync
 * from reality and the list cannot. The grace map is the only state, it only ever holds players who
 * have logged out, and an entry is dropped the moment it is spent.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID)
public final class AnchorPresence {
    private AnchorPresence() {}

    /** Owner id to the game time their tickets drop at. Empty except while somebody is in grace. */
    private static final Map<UUID, Long> GRACE = new HashMap<>();

    /** Ticks in a minute of config. */
    private static final long MINUTE = 1200L;

    /**
     * Whether this owner's network may hold a ticket right now: online, or still inside the grace
     * period {@code anchorGraceMinutes} buys them.
     */
    public static boolean holding(MinecraftServer server, UUID owner) {
        if (server.getPlayerList().getPlayer(owner) != null) {
            return true;
        }
        Long until = GRACE.get(owner);
        return until != null && server.overworld().getGameTime() < until;
    }

    /**
     * Re-forces everything this owner's networks are entitled to. Called on login, where the
     * Workbay cannot do it for itself: its chunk was released, so it is not ticking, so nothing in
     * the world is left that could notice the owner came back.
     */
    public static void resume(MinecraftServer server, UUID owner) {
        GRACE.remove(owner);
        apply(server, owner);
    }

    /**
     * Registers or drops every ticket this owner's networks own, by asking each one what it should
     * be holding now. Idempotent both ways: a ticket already registered under the same owner and
     * chunk is not a second ticket, and releasing one that is not held is a no-op.
     */
    private static void apply(MinecraftServer server, UUID owner) {
        RoomRegistry registry = RoomRegistry.get(server);
        for (WorkbayRecord record : registry.ownedBy(owner)) {
            RoomAnchors.applyOwnChunk(server, record);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            resume(player.server, player.getUUID());
        }
    }

    /**
     * Starts the clock rather than releasing now. A player who relogs - or crashes out - inside the
     * grace never sees their base stop, which is the whole reason the knob exists.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GRACE.put(player.getUUID(), player.server.overworld().getGameTime()
                + WorkbayConfig.SERVER.anchorGraceMinutes.get() * MINUTE);
        }
    }

    /** Spends the grace. Free on every tick nobody is inside one, which is nearly all of them. */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (GRACE.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Long>> due = GRACE.entrySet().iterator(); due.hasNext(); ) {
            Map.Entry<UUID, Long> entry = due.next();
            if (entry.getValue() > now) {
                continue;
            }
            // Removed before applying, so the release reads its own answer from `holding` rather
            // than the deadline it is in the middle of spending.
            due.remove();
            apply(server, entry.getKey());
        }
    }
}
