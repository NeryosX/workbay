package com.neryos.workbay.content.workbay;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.init.WBBlockEntities;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.world.RoomAnchors;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Workbay. In v1 it is a handle: the id of a {@link WorkbayRecord} in the {@link RoomRegistry},
 * which is where the bays, the code and the upgrade counters actually live.
 *
 * <p>Keeping the state in the registry rather than here is deliberate. A Workbay can be broken while
 * its machines keep running (SPEC.md §14), so the record has to outlive the block entity, and the
 * block entity must never be the only copy of anything except the id.
 */
public class WorkbayBlockEntity extends BlockEntity {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String ID_KEY = "WorkbayId";
    private static final String ENERGY_KEY = "Energy";
    private static final String PIPS_KEY = "Pips";

    /**
     * One pip per link, in the LINKS list's own order, as far as the front of the block has room
     * for. SPEC.md §7 gives the frame the summary - "is anything wrong?" - and these give the
     * detail beside it: which of the first eight links is the one to look at.
     *
     * <p>Deliberately coarser than {@link BusRunner.BusStatus}. RUNNING and IDLE collapse into one
     * colour because a link on the wheel alternates between them every few ticks, and a pip that
     * changes colour on the wheel would be a packet per tick and a blinking block. What is left
     * changes only when the player's world does.
     */
    public enum Pip {
        /** No link in this slot, or one the player has switched off. Says nothing on purpose. */
        NONE,
        /** The link is fine: moving, or with nothing to move. */
        OK,
        /** It cannot reach, and the answer is a setting: no port, no face, chunk not loaded. */
        ATTENTION,
        /** Something the player built is gone: the Connector, or the target block. */
        BROKEN;

        /** Cached, and public so a test can name what a byte on the wire means. */
        public static final Pip[] VALUES = values();
    }

    /** How many links the front of the block can show; the sockets are drawn to match. */
    public static final int PIPS = 8;

    @Nullable
    private UUID workbayId;

    private final BusRunner runner = new BusRunner(() -> !isRemoved());

    /**
     * The Backshop chunks this Workbay currently holds a mirroring ticket on: its bay column,
     * plus every room of its network that has a Connector standing in it. SPEC.md §12.
     *
     * <p>A set rather than a flag because the second half changes while the block lives - placing
     * a Connector in a room is what puts that room on the list and breaking it is what takes it
     * off, and neither is a moment this block hears about.
     */
    private final java.util.Set<net.minecraft.world.level.ChunkPos> mirrored =
        new java.util.HashSet<>();

    /**
     * Whether this Workbay is currently holding its <em>own</em> chunk loaded. SPEC.md §12's other
     * half of the Anchor, and it was never built: mirroring keeps the bay column loaded exactly
     * while this block ticks, so the moment the player walks away the block stops ticking and the
     * links stop with it. Measured, not reasoned: a six-stage chain 68 s away with every room
     * anchored moved nothing. OPEN_ISSUES #52.
     */
    private boolean holdingOwnChunk;

    /**
     * Who a chunk ticket is registered under. <b>The block, not the network.</b>
     *
     * <p>Keyed on {@code record.id()} it was the same owner for every Workbay standing on one
     * record, so releasing A's tickets released the chunks B still believed it held - and B's
     * {@link #mirrored} set was unchanged, so its next tick saw no difference and never put them
     * back. Derived rather than stored because it has to be the same value after a reload and
     * there is nothing to migrate: a position in a dimension already is a stable name for a block.
     * OPEN_ISSUES #53.
     */
    private static UUID ticketOwner(net.minecraft.world.level.Level level, BlockPos pos) {
        return WorkbayTickets.owner(level.dimension(), pos);
    }

    /** Ticks of {@link WorkbayState#RUNNING} left to show since the last move. Not saved: a Workbay
     * that just loaded has moved nothing yet, and one tick of {@code idle} is the truth. */
    private int runningHold;

    /** {@link Pip} ordinals, one per link. Derived on the server, sent to the client, never saved
     * to disk - a Workbay that just loaded recomputes it on its first tick. */
    private byte[] pips = new byte[0];

    /** Four seconds. Long enough to bridge a link on the slowest wheel step, short enough that a
     * base that has actually stopped says so before the player has walked the length of it. */
    private static final int RUNNING_HOLD = 80;

    /**
     * SPEC.md §9's buffer, accepted on any face and never handing energy back out of the block.
     * Two of §9's three layers are spent through {@link #spend}; the third is zero. Round-robin
     * sharing to the bays is still not built -- a hosted machine keeps its own power.
     */
    private final EnergyStorage energy = new EnergyStorage(BUFFER_FE, MAX_FE_PER_TICK, 0) {
        private long tick = Long.MIN_VALUE;
        private int taken;

        /**
         * The screen prints <b>Up to 10,000 FE/t</b> and {@link EnergyStorage}'s own ceiling is per
         * <em>call</em>: three pushes in one tick put in thirty thousand, measured. Six cables on
         * six faces are six pushes, so the printed rate was out by however many things happened to
         * be pushing.
         *
         * <p>Keyed on the level's game time rather than reset from {@link #serverTick}, so a
         * Workbay in a chunk that is loaded but not ticking cannot be left holding a spent budget
         * forever. {@code deserializeNBT} writes the field directly and so is not throttled, which
         * is what lets a full buffer come back off disk in one go.
         */
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            long now = level == null ? 0 : level.getGameTime();
            if (now != tick) {
                tick = now;
                taken = 0;
            }
            int got = super.receiveEnergy(Math.min(toReceive, MAX_FE_PER_TICK - taken), simulate);
            if (!simulate) {
                taken += got;
            }
            return got;
        }
    };

    public static final int BUFFER_FE = 100_000;
    public static final int MAX_FE_PER_TICK = 10_000;

    /**
     * <b>What running the links costs, taken out of the buffer.</b> SPEC.md §9.
     *
     * <p>Not {@code energy.extractEnergy}: this buffer is built with a max extract of zero on
     * purpose, so that nothing outside the block can pull power back out of it, and the mod's own
     * running cost is not something outside the block. It writes the stored figure directly, the
     * same call {@code deserializeNBT} makes when a buffer comes back off disk.
     *
     * <p>All or nothing. Half the power buys half a move, and there is no such thing.
     *
     * @return true when the whole amount was there and has now been spent
     */
    public boolean spend(int fe) {
        if (fe <= 0) {
            return true;
        }
        int stored = energy.getEnergyStored();
        if (stored < fe) {
            return false;
        }
        energy.deserializeNBT(null, net.minecraft.nbt.IntTag.valueOf(stored - fe));
        setChanged();
        return true;
    }

    public WorkbayBlockEntity(BlockPos pos, BlockState state) {
        super(WBBlockEntities.WORKBAY.get(), pos, state);
    }

    public Optional<UUID> workbayId() {
        return Optional.ofNullable(workbayId);
    }

    public void bindTo(UUID id) {
        this.workbayId = id;
        setChanged();
    }

    /**
     * Lets go of whatever network this block was holding, leaving the block standing there empty.
     *
     * <p>The other half of a Transfer: a network moves into one block by leaving another, and the
     * one it left has to stop being a front door to it - stop ticking its links, stop answering for
     * its bays, and <b>let go of the Backshop chunks it was mirroring</b>. That last part is the
     * one a bare {@code workbayId = null} gets wrong: {@link #mirror} works out what to hold from
     * {@link #record()}, so with no record it returns early and the old column stays forced for as
     * long as the empty block stands there.
     */
    public void unbind() {
        if (level instanceof ServerLevel server) {
            ServerLevel backshop = server.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            if (backshop != null) {
                mirrored.forEach(chunk ->
                    WorkbayTickets.release(backshop, ticketOwner(server, worldPosition), chunk));
            }
            mirrored.clear();
            if (holdingOwnChunk) {
                WorkbayTickets.release(server, ticketOwner(server, worldPosition),
                    new net.minecraft.world.level.ChunkPos(worldPosition));
                holdingOwnChunk = false;
            }
        }
        // Every endpoint cache in the runner belongs to the network that just left.
        runner.invalidate();
        workbayId = null;
        setChanged();
    }

    /** The record this block is a handle for, or empty if it has not been bound yet. */
    public Optional<WorkbayRecord> record() {
        if (workbayId == null || !(level instanceof ServerLevel server)) {
            return Optional.empty();
        }
        return RoomRegistry.get(server.getServer()).byId(workbayId);
    }

    /** How many bays currently hold something. Also what a comparator reads (SPEC.md §7). */
    public int occupiedBays() {
        return record().map(r -> (int) r.bays().stream().filter(b -> b.hosted().isPresent()).count())
            .orElse(0);
    }

    /**
     * Keeps the registry's idea of where this Workbay is up to date, so {@code /workbay list} can
     * point an operator at it and a re-placed item can be told apart from a duplicated one.
     */
    public void rememberPosition() {
        if (!(level instanceof ServerLevel server) || workbayId == null) {
            return;
        }
        RoomRegistry registry = RoomRegistry.get(server.getServer());
        registry.byId(workbayId).ifPresent(record ->
            registry.put(record.withLastKnownPos(GlobalPos.of(server.dimension(), worldPosition))));
    }

    /**
     * Links used to live only in this block entity's own NBT, which is not one of the components a
     * broken Workbay's loot table copies onto the dropped item - so breaking and re-placing one,
     * even onto the very same {@link WorkbayBinding}, silently lost every link. They live on the
     * {@link WorkbayRecord} now, the same as bays, upgrades and the lock, so they survive exactly as
     * well as everything else does.
     */
    public List<BusConfig> buses() {
        return record().map(WorkbayRecord::buses).orElse(List.of());
    }

    /**
     * Adds a new link, or replaces an existing one <em>in place</em>. The row list has no sort
     * comparator by default and relies on this list's own order for that - so a naive
     * remove-then-append here silently sent a link to the bottom of the screen every time its
     * enabled flag, name, filter or anything else about it changed. Found in play: toggling a
     * link's checkbox visibly jumped it to the end of the list.
     */
    /**
     * Links one network may hold: eight bays, four resources, two directions. Past it a new link
     * is refused and an edit still lands. Unbounded, the list grew by one per Add packet forever,
     * and the snapshot carrying it disconnected every viewer past a megabyte (night audit 1A,
     * finding 5).
     */
    public static final int MAX_LINKS = 64;

    /** @return false when a <em>new</em> link did not land: the network is at {@link #MAX_LINKS}. */
    public boolean addBus(BusConfig bus) {
        return editBuses(record -> {
            List<BusConfig> updated = new ArrayList<>(record.buses());
            int existing = -1;
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).id().equals(bus.id())) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                updated.set(existing, bus);
                // The runner caches a resolved capability per link, and the position and face it
                // was resolved against are baked into that cache. Any edit can move either one --
                // retargeting an internal link at a different bay, pinning a target face -- so the
                // cache is dropped on every replacement rather than only on removal. Re-resolving
                // is a capability lookup on the next tick; getting it wrong is a link that quietly
                // keeps talking to the block it used to point at.
                runner.forget(bus.id());
            } else if (updated.size() >= MAX_LINKS) {
                return null;
            } else {
                updated.add(bus);
            }
            return updated;
        });
    }

    public void removeBus(UUID busId) {
        editBuses(record -> {
            List<BusConfig> updated = new ArrayList<>(record.buses());
            if (!updated.removeIf(bus -> bus.id().equals(busId))) {
                return null;
            }
            runner.forget(busId);
            return updated;
        });
    }

    public Optional<BusConfig> bus(UUID busId) {
        return buses().stream().filter(bus -> bus.id().equals(busId)).findFirst();
    }

    /** Every link anchored by the Connector at one position. As many as the player pulled in. */
    public List<BusConfig> linksAt(GlobalPos connector) {
        return buses().stream().filter(bus -> bus.connector().equals(connector)).toList();
    }

    /**
     * Breaking a Connector takes the Connector and every channel it carried. SPEC.md §0: the block
     * <em>is</em> the link, and there is nothing left for a channel to point at.
     */
    public void removeConnectorAt(GlobalPos connector) {
        removeLinksAt(connector);
        editRecord(record -> record.connectorAt(connector).map(found -> record.withConnectors(
            record.connectors().stream().filter(c -> !c.pos().equals(connector)).toList()))
            .orElse(null));
    }

    /** Every channel anchored by the Connector at one position, without touching the Connector. */
    public void removeLinksAt(GlobalPos connector) {
        editBuses(record -> {
            List<BusConfig> going = record.buses().stream()
                .filter(bus -> bus.connector().equals(connector)).toList();
            if (going.isEmpty()) {
                return null;
            }
            going.forEach(bus -> runner.forget(bus.id()));
            List<BusConfig> updated = new ArrayList<>(record.buses());
            updated.removeAll(going);
            return updated;
        });
    }

    /**
     * Every mutation to the link list goes through here: read the current record, compute the new
     * list, write it back. {@code edit} returns {@code null} for "nothing changed" so a no-op edit
     * (removing a link that is already gone) does not touch the registry or fire {@code setChanged}.
     */
    private boolean editBuses(java.util.function.Function<WorkbayRecord, List<BusConfig>> edit) {
        return editRecord(record -> {
            List<BusConfig> updated = edit.apply(record);
            return updated == null ? null : record.withBuses(updated);
        });
    }

    /**
     * Reads this Workbay's record, applies an edit and writes it back. Null means no change.
     *
     * @return whether anything was written
     */
    private boolean editRecord(java.util.function.UnaryOperator<WorkbayRecord> edit) {
        if (!(level instanceof ServerLevel server) || workbayId == null) {
            return false;
        }
        RoomRegistry registry = RoomRegistry.get(server.getServer());
        WorkbayRecord record = registry.byId(workbayId).orElse(null);
        if (record == null) {
            return false;
        }
        WorkbayRecord updated = edit.apply(record);
        if (updated == null) {
            return false;
        }
        registry.put(updated);
        setChanged();
        return true;
    }

    public List<WorkbayRecord.Connector> connectors() {
        return record().map(WorkbayRecord::connectors).orElse(List.of());
    }

    public Optional<WorkbayRecord.Connector> connectorAt(GlobalPos pos) {
        return record().flatMap(record -> record.connectorAt(pos));
    }

    /**
     * Remembers a Connector, or replaces what was remembered about the one already at that
     * position. Placing a Connector calls this and mints <b>no channel</b>: a channel exists
     * because a player pressed Add on a bay, and nothing else creates one.
     */
    public void addConnector(WorkbayRecord.Connector connector) {
        editRecord(record -> {
            List<WorkbayRecord.Connector> updated = new ArrayList<>(record.connectors().stream()
                .filter(c -> !c.pos().equals(connector.pos())).toList());
            updated.add(connector);
            return record.withConnectors(updated);
        });
    }

    /**
     * <b>The only way a Connector's name is written.</b> One block, one name, however many bays it
     * carries a channel on -- so the name is a field of the Connector and no channel has one of
     * its own to disagree with. OPEN_ISSUES #97.
     */
    public void renameConnector(GlobalPos pos, String name) {
        editRecord(record -> record.connectorAt(pos).map(c -> record.withConnectorRenamed(pos, name))
            .orElse(null));
    }

    /**
     * What a link row is called. An external row takes its Connector's name; an <b>internal</b>
     * bay-to-bay row has no Connector -- its anchor is the Workbay itself, shared by every internal
     * row -- so that one row is its own object and keeps its own name.
     */
    public String nameOf(BusConfig link) {
        return link.internal() ? link.name()
            : connectorAt(link.connector()).map(WorkbayRecord.Connector::name).orElse("");
    }

    /** A bay's face config changed, so its cached machine-end handler has to be re-resolved. */
    public void forgetBay(int bay) {
        runner.forgetBay(bay);
    }

    public EnergyStorage energy() {
        return energy;
    }

    public BusRunner.BusStatus busStatus(UUID busId) {
        return runner.status(busId);
    }

    /**
     * <b>The Backshop checks its own walls.</b> SPEC.md §8's shell is bedrock, which turns away
     * everything that reads hardness before it breaks a block - every quarry and every miner worth
     * the name - and nothing whatever about a machine that calls {@code level.setBlock} or
     * {@code destroyBlock} on a position it chose. That call cannot be told apart from a legitimate
     * one without a hook on every block change in the game, which is a cost paid by every player
     * for a case that may never happen. So the shell is repaired rather than defended: once a wheel
     * step, every bay holding something has its walls and its six Ports put back
     * ({@link com.neryos.workbay.world.BayBuilder#audit}), and a hole is open for at most five
     * ticks however hard the machine keeps at it.
     *
     * <p>What that leaves it standing on, and what makes five ticks enough: a bay is 128 blocks
     * from the next network's ({@code COLUMN_SPACING} is 8 chunks) and a room is most of a million
     * blocks away, so the only thing within reach of a machine that does get out is its own
     * network's other bays - and each of those repairs itself here too, from this same list. The
     * bay above and below are the machine's owner's own.
     *
     * <p>Only bays with something in them, because only a machine can be doing the writing and an
     * empty bay has nothing to lose; that is also what keeps this off every empty bay's 98 blocks
     * on every step. A bay that was breached while its network slept is repaired the moment the
     * network wakes, and nothing was running in it meanwhile.
     */
    private void audit(ServerLevel server) {
        // Offset by position, like the wheel itself, so a base full of Workbays does not audit on
        // the same tick.
        if (Math.floorMod(server.getGameTime() + worldPosition.hashCode(),
            BusRunner.STEP_TICKS) != 0) {
            return;
        }
        WorkbayRecord record = record().orElse(null);
        ServerLevel backshop = server.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        if (record == null || backshop == null) {
            return;
        }
        for (WorkbayRecord.Bay bay : record.bays()) {
            if (bay.hosted().isEmpty() || bay.index() >= record.bayCapacity()) {
                continue;
            }
            int repaired = com.neryos.workbay.world.BayBuilder.audit(backshop, record.bayColumn(),
                bay.index(), bay.shell());
            if (repaired > 0) {
                LOG.warn("bay {} of {} was missing {} block(s) of its shell; put back. Something "
                    + "racked there is writing outside its own bay.",
                    bay.index(), record.label(), repaired);
            }
        }
    }

    /**
     * SPEC.md §9's tick wheel, offset by the Workbay's own position so that a base full of them
     * staggers instead of every one firing on the same tick.
     */
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
        BlockState state, WorkbayBlockEntity workbay) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // A network has one block, and the record says which: every bind stamps
        // `rememberPosition`. A block the record does not point at is one a Transfer could not
        // reach (its chunk was unloaded) or a duplicated item -- either way it lets go, or two
        // front doors edit one record and breaking either puts the network to sleep under the
        // other. Night 2026-09-11, 1B #3c / 1A #10.
        if (workbay.record().flatMap(WorkbayRecord::lastKnownPos)
            .filter(at -> !at.equals(GlobalPos.of(server.dimension(), pos))).isPresent()) {
            workbay.unbind();
            return;
        }
        // The game's own profiler, so `/perf start` breaks this tick down by name instead of
        // reporting one lump called workbay:workbay. Free when nothing is recording -- the
        // inactive filler's push/pop are empty methods, the same bet vanilla makes everywhere.
        net.minecraft.util.profiling.ProfilerFiller profiler = server.getProfiler();
        // Before the links, and whether or not there are any: a hosted machine has to tick even
        // with nothing pointed at it.
        profiler.push("mirror");
        workbay.anchor(server);
        workbay.mirror(server);
        profiler.popPush("audit");
        workbay.audit(server);
        // Every tick, not only on a wheel step: a rising edge between two steps still has to be
        // seen, or a fast clock on PULSE would be silently ignored.
        profiler.popPush("buses");
        workbay.runner.power(server.hasNeighborSignal(pos));
        workbay.record().ifPresent(record -> {
            // Before the election, and whether or not this block wins it: a link status is a
            // property of the network, and every Workbay on the record is a front door to the same
            // one. Held per network so the blocks that lose the turn report what the runner that
            // took it found, instead of reading Idle for ever. OPEN_ISSUES #39.
            workbay.runner.shareStatuses(
                RoomRegistry.get(server.getServer()).busStatuses(record.id()));
            if (record.buses().isEmpty()) {
                return;
            }
            // One runner per network per tick. Links live on the record, not on the block, so every
            // Workbay standing on a record used to run every link on it -- two blocks moved a
            // rate-1 link twice a second, which is the panel's printed rate being wrong by however
            // many Workbays happen to be loaded. OPEN_ISSUES #40, and the reason a printed rate can
            // be a promise at all.
            if (!com.neryos.workbay.world.RoomRegistry.get(server.getServer())
                .takeBusTurn(record.id(), server.getGameTime())) {
                return;
            }
            workbay.runner.tick(server, record, record.buses(),
                Math.floorMod(pos.hashCode(), BusRunner.WHEEL), workbay::spend);
        });

        profiler.popPush("state");
        workbay.refreshLitState(server, pos, state);
        // After the lit state, not before: refreshLitState may have replaced the block, and a
        // sendBlockUpdated with the stale state would tell the client to draw the old variant.
        profiler.popPush("pips");
        workbay.refreshPips(server, pos, server.getBlockState(pos));

        // A Connector broken while this Workbay was unloaded could not tell it, so the runner spots
        // the gap instead and the link is swept here, outside the iteration that found it.
        profiler.popPush("sweep");
        var orphaned = workbay.runner.orphaned();
        if (!orphaned.isEmpty()) {
            orphaned.forEach(workbay::removeBus);
        }
        profiler.pop();
    }

    /**
     * SPEC.md §7: the block answers "is something wrong?" from across the room. The property has
     * existed since the block was written and <b>nothing ever wrote it</b> - every Workbay in every
     * world has been {@code state=idle} since the mod started, which is why three models pointing
     * at one picture never looked like a fault.
     *
     * <p>{@code RUNNING} is held for {@link #RUNNING_HOLD} ticks after the last move rather than
     * read live. A link on the wheel moves something every few seconds and reports IDLE in between,
     * so the live reading would flicker the blockstate - and a blockstate write is a lighting
     * recalculation and a packet to every player tracking the chunk. §7 says "moved something
     * recently", and this is what recently means.
     */
    private void refreshLitState(ServerLevel server, BlockPos pos, BlockState state) {
        if (!state.hasProperty(WorkbayBlock.STATE)) {
            return;
        }
        if (runner.anyRunning()) {
            runningHold = RUNNING_HOLD;
        } else if (runningHold > 0) {
            runningHold--;
        }
        WorkbayState want = runner.anyProblem() ? WorkbayState.STUCK
            : runningHold > 0 ? WorkbayState.RUNNING
            : WorkbayState.IDLE;
        boolean powered = server.hasNeighborSignal(pos);
        if (state.getValue(WorkbayBlock.STATE) == want
            && state.getValue(WorkbayBlock.POWERED) == powered) {
            return;
        }
        // The one edge worth hearing, and it is free: this branch is reached only when the reading
        // has actually changed, and RUNNING is already held for four seconds past the last move.
        // A link on the wheel therefore makes one sound when the chain starts and one when it runs
        // dry, whatever it does in between -- which is the whole reason a sound hangs off this and
        // not off BusRunner, where it would be a metronome. See WorkbaySounds.
        WorkbayState had = state.getValue(WorkbayBlock.STATE);
        if (want != had) {
            switch (want) {
                case RUNNING -> com.neryos.workbay.WorkbaySounds.started(server, pos);
                case STUCK -> com.neryos.workbay.WorkbaySounds.stuck(server, pos);
                case IDLE -> {
                    // Only from RUNNING. Coming down off STUCK is a fault the player just cleared,
                    // and it is followed by the machine starting again a moment later.
                    if (had == WorkbayState.RUNNING) {
                        com.neryos.workbay.WorkbaySounds.stopped(server, pos);
                    }
                }
            }
        }
        server.setBlock(pos, state.setValue(WorkbayBlock.STATE, want)
            .setValue(WorkbayBlock.POWERED, powered), net.minecraft.world.level.block.Block.UPDATE_ALL);
    }

    /** What the front of the block is showing. Client-side after the update tag lands. */
    public byte[] pips() {
        return pips;
    }

    /**
     * The per-link half of SPEC.md §7's "is something wrong?", and the reason the block entity
     * syncs anything at all. Recomputed every tick and sent only on a difference: the four
     * readings are all stable, so a base at rest sends nothing.
     */
    private void refreshPips(ServerLevel server, BlockPos pos, BlockState state) {
        var buses = record().map(WorkbayRecord::buses).orElse(java.util.List.of());
        byte[] want = new byte[Math.min(buses.size(), PIPS)];
        for (int i = 0; i < want.length; i++) {
            want[i] = (byte) pipFor(buses.get(i)).ordinal();
        }
        if (java.util.Arrays.equals(pips, want)) {
            return;
        }
        pips = want;
        setChanged();
        server.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    private Pip pipFor(com.neryos.workbay.bus.BusConfig bus) {
        if (!bus.enabled()) {
            return Pip.NONE;
        }
        return switch (runner.status(bus.id())) {
            case RUNNING, IDLE, DISABLED, HELD_BY_REDSTONE, DETACHED -> Pip.OK;
            case CONNECTOR_GONE, TARGET_MISSING -> Pip.BROKEN;
            // Listed rather than defaulted, so a new BusStatus is a compile error here as well as
            // in the two screens. A default would have quietly called it ATTENTION.
            case TARGET_NOT_LOADED, TARGET_NO_PORT, MACHINE_NO_PORT, MACHINE_NO_FACE,
                NEEDS_RESONATOR, NO_POWER -> Pip.ATTENTION;
        };
    }


    /**
     * SPEC.md §12's mirroring: <b>the bay column is loaded exactly while the Workbay's own chunk
     * is.</b> Costs nothing a player is not already paying for, and it is what makes a hosted
     * machine reachable at all - without it the Backshop chunk is never loaded, every link reports
     * that the machine is unreachable, and the mod does not work outside a test that force-loads
     * the chunk for itself.
     *
     * <p><b>And the rooms in its bays, when {@code roomsLoadWithWorkbay} says so.</b> SPEC.md §12.
     *
     * <p>On the tick rather than in {@code onLoad}: forcing sync-loads a chunk in another
     * dimension, which is not something to do from inside a chunk load.
     */
    /**
     * SPEC.md §12's Anchor, the half about the block itself: <b>the bay column stays loaded with
     * nobody around.</b>
     *
     * <p>Mirroring alone cannot do it. It holds the Backshop column exactly while <em>this</em>
     * block ticks, and this block stops ticking the moment its own chunk unloads - so an anchored
     * room full of barrels sat there with nothing pushing into it, because the thing that runs the
     * links is the Workbay and its chunk was forced by nothing. One ticket on this block's own
     * chunk answers all of it: the block ticks, mirroring runs, the column and the rooms follow.
     *
     * <p>Gated on {@code maxAnchoredWorkbaysPerPlayer}, which is the switch this asked for and was
     * until now read by nothing. Zero means a host who bought the Anchor for rooms does not also
     * get a permanently ticking overworld chunk with it.
     *
     * <p>And gated on the owner being <b>online</b> ({@link com.neryos.workbay.world.AnchorPresence},
     * OPEN_ISSUES #55). This is the release half only: once the ticket drops this block stops
     * ticking, so the arming half has to come from the login hook.
     */
    private void anchor(ServerLevel server) {
        boolean wanted = record()
            .map(record -> RoomAnchors.anchorsOwnChunk(server.getServer(), record)).orElse(false);
        if (wanted == holdingOwnChunk) {
            return;
        }
        net.minecraft.world.level.ChunkPos here =
            new net.minecraft.world.level.ChunkPos(worldPosition);
        if (wanted) {
            WorkbayTickets.force(server, ticketOwner(server, worldPosition), here);
        } else {
            WorkbayTickets.release(server, ticketOwner(server, worldPosition), here);
        }
        holdingOwnChunk = wanted;
    }

    private void mirror(ServerLevel server) {
        ServerLevel backshop = server.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        WorkbayRecord record = record().orElse(null);
        if (backshop == null || record == null) {
            return;
        }
        // <b>The bay column, and the rooms in its bays only if the server says so.</b> SPEC.md
        // §0: `roomsLoadWithWorkbay` off is a room that runs while somebody stands in it and
        // otherwise sleeps; on is every room in a bay held exactly while this block's chunk is,
        // one chunk each, which is what makes a barrel in a room a stage in a chain. The old
        // per-room anchor is gone (OPEN_ISSUES #59).
        java.util.Set<net.minecraft.world.level.ChunkPos> wanted =
            new java.util.HashSet<>(java.util.Set.of(record.bayColumn()));
        if (com.neryos.workbay.config.WorkbayConfig.SERVER.roomsLoadWithWorkbay.get()) {
            for (com.neryos.workbay.world.RoomRecord room
                : RoomRegistry.get(server.getServer()).roomsOf(record).values()) {
                wanted.addAll(com.neryos.workbay.world.RoomGeometry.chunks(room.region(),
                    room.builtTier()));
            }
        }
        if (wanted.equals(mirrored)) {
            return;
        }
        for (net.minecraft.world.level.ChunkPos chunk : wanted) {
            if (mirrored.add(chunk)) {
                WorkbayTickets.force(backshop, ticketOwner(server, worldPosition), chunk);
            }
        }
        mirrored.removeIf(chunk -> {
            if (wanted.contains(chunk)) {
                return false;
            }
            WorkbayTickets.release(backshop, ticketOwner(server, worldPosition), chunk);
            return true;
        });
    }

    /**
     * {@code setRemoved} is called both when the block is broken and when its chunk unloads, which
     * is exactly the pair of events mirroring is defined by. Keeping the column loaded past either
     * is the Anchor's job, and the Anchor is not built.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            ServerLevel backshop = server.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            if (backshop != null) {
                mirrored.forEach(chunk ->
                    WorkbayTickets.release(backshop, ticketOwner(server, worldPosition), chunk));
            }
            mirrored.clear();
            // The Anchor's hold on this block's own chunk. Released on break, which is the only
            // way this runs now: a chunk holding its own ticket does not unload.
            if (holdingOwnChunk) {
                WorkbayTickets.release(server, ticketOwner(server, worldPosition),
                    new net.minecraft.world.level.ChunkPos(worldPosition));
                holdingOwnChunk = false;
            }
        }
        // Every endpoint cache holds a ServerLevel reference. Dropping them here is what stops a
        // removed Workbay keeping another dimension's level object alive.
        runner.invalidate();
    }

    // ------------------------------------------------------------ persistence

    /**
     * Puts a stored figure back into the buffer, and the <b>only</b> way anything but a cable does.
     *
     * <p>Never {@code receiveEnergy}: {@link EnergyStorage}'s ceiling is per call, so a Workbay
     * holding a full hundred thousand came back with ten and the screen printed the loss as the
     * truth. Measured: stored 100,000, saved 100,000, reloaded 10,000. Both restores go through
     * here - off the disk and off the item somebody broke it into - because it is the same
     * mistake either way and one of them was made twice. The provider argument NeoForge's
     * {@code deserializeNBT} takes is unused by it, and there is nothing here to look up.
     */
    private void restoreEnergy(int stored) {
        energy.deserializeNBT(null, net.minecraft.nbt.IntTag.valueOf(stored));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(RoomRegistry.VERSION_KEY, RoomRegistry.DATA_VERSION);
        if (workbayId != null) {
            tag.put(ID_KEY, UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, workbayId)
                .getOrThrow(e -> new IllegalStateException("could not write a Workbay id: " + e)));
        }
        tag.putInt(ENERGY_KEY, energy.getEnergyStored());
    }

    /**
     * The whole saved tag plus the pips. The pips are the only thing the client needs and the only
     * thing not on disk; everything else rides along because it is already written and leaving it
     * out would make the client's copy differ from the server's for no gain.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        tag.putByteArray(PIPS_KEY, pips);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
        getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // SPEC.md §14: DataVersion is the first key of every structure this mod persists, and
        // branching on it here is the entire migration mechanism. A tag with no version is one
        // written before the block entity ever saved, which is simply an unbound Workbay.
        int version = tag.getInt(RoomRegistry.VERSION_KEY);
        if (version != 0 && version != RoomRegistry.DATA_VERSION) {
            throw new IllegalStateException("a Workbay block entity is version " + version
                + ", this build reads version " + RoomRegistry.DATA_VERSION);
        }
        workbayId = tag.contains(ID_KEY)
            ? UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get(ID_KEY)).result().orElse(null)
            : null;
        if (tag.contains(ENERGY_KEY)) {
            restoreEnergy(tag.getInt(ENERGY_KEY));
        }
        // Absent on disk, present on the update tag. getByteArray answers with an empty array for
        // a key that is not there, which is exactly "no links to show".
        pips = tag.getByteArray(PIPS_KEY);
    }

    // ------------------------------------------------------- item round trip

    /**
     * What the dropped item carries. Read by the block's loot table through
     * {@code copy_components}, so breaking a Workbay and placing it again lands on the same bays.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        record().ifPresent(r -> components.set(WBDataComponents.BINDING.get(),
            WorkbayBinding.of(r, occupiedBays(), r.buses().size(), energy.getEnergyStored())));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        WorkbayBinding binding = input.get(WBDataComponents.BINDING.get());
        if (binding != null) {
            workbayId = binding.id();
            restoreEnergy(binding.energy());
        }
    }

    /**
     * Without this the id is in {@code block_entity_data} as well as in the component, and a
     * creative ctrl-pick would clone a bound Workbay. SPEC.md §14: pick-block must never hand out a
     * second block pointing at somebody's factory.
     */
    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(ID_KEY);
    }
}
