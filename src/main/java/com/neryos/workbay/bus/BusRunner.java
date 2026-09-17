package com.neryos.workbay.bus;

import com.neryos.workbay.config.WorkbayConfig;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.RedstoneMode;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Runs one Workbay's buses. SPEC.md §9.
 *
 * <p>Holds the endpoint caches, which is why it is one object per Workbay rather than a pile of
 * statics: a cache is registered with the level it points at and has to be dropped when the Workbay
 * that owns it goes away.
 */
public class BusRunner {

    /**
     * SPEC.md §9's wheel. One countdown from 1200, acting every fifth tick, giving 240 steps. Every
     * legal speed divides it, which is why speeds come from a fixed list rather than free entry.
     */
    public static final int WHEEL = 1200;
    public static final int STEP_TICKS = 5;

    /**
     * What one step of the rate dial is worth to a fluid link. The dial is one number shared by all
     * three resources, and a rate of 8 meaning eight millibuckets would make every fluid link look
     * broken - a bucket is a thousand. A hundred keeps the dial's own range (1..64 by default)
     * spanning a tenth of a bucket to just over six, which is the granularity a player actually
     * wants when metering a machine.
     */
    public static final int MB_PER_RATE = 100;

    /**
     * The same argument for energy, and it was missing.
     *
     * <p>A rate of 8 meaning eight FE per move is not a slow link, it is a broken one: at the
     * default speed that is 0.4 FE a tick, and a Mekanism machine wants hundreds. Measured in a
     * live world -- a Metallurgic Infuser with its infusion tank full and its input slot loaded sat
     * on "Running" with a hazard-striped energy bar, because the link feeding it was two orders of
     * magnitude short and nothing in the mod said so. Fluids were given a multiplier for exactly
     * this reason and energy was not.
     *
     * <p>A thousand puts the dial's range (1..64) at 1k..64k FE a move, which is a tenth of a Basic
     * Energy Cube's buffer at the bottom and several cubes' worth at the top.
     */
    public static final int FE_PER_RATE = 1000;

    /**
     * What one move of this link is worth, Impellers included.
     *
     * <p>The dial on the link says how much; the Workbay's Impellers say how much that is worth.
     * Applied here rather than baked into the stored rate so that fitting one lifts every link at
     * once, and losing one lowers them again -- a number written into each row would have to be
     * migrated, and would disagree with the row the moment a plate moved.
     *
     * <p><b>The server's ceiling is applied here too</b>, for the same reason and one more: this
     * is the only place every move passes through, so lowering {@code linkMaxRate} slows links
     * that are already running rather than only the next one somebody sets. A ceiling enforced
     * where a number is stored is one that a saved world walks straight past.
     */
    private static int rate(WorkbayRecord record, BusConfig bus) {
        int ceiling = com.neryos.workbay.config.WorkbayConfig.SERVER.linkMaxRate.get();
        return Math.clamp(bus.rate(), 1, ceiling) * record.upgrades().impellerFactor();
    }

    private final BooleanSupplier alive;
    private final Map<UUID, BusEndpoint<IItemHandler>> targetItems = new HashMap<>();
    private final Map<UUID, BusEndpoint<IEnergyStorage>> targetEnergy = new HashMap<>();
    private final Map<Integer, BusEndpoint<IItemHandler>> machineItems = new HashMap<>();
    private final Map<Integer, BusEndpoint<IEnergyStorage>> machineEnergy = new HashMap<>();
    private final Map<UUID, BusEndpoint<IFluidHandler>> targetFluids = new HashMap<>();
    private final Map<Integer, BusEndpoint<IFluidHandler>> machineFluids = new HashMap<>();
    /**
     * What each link last reported. <b>Borrowed, not owned</b> - {@link #shareStatuses} points
     * every Workbay on one network at one map, so the blocks that lose the per-network bus turn
     * report what the block that took it found rather than Idle. OPEN_ISSUES #39.
     */
    private Map<UUID, BusStatus> statuses = new HashMap<>();

    private static final java.util.Set<Direction> EVERY_FACE =
        java.util.EnumSet.allOf(Direction.class);

    private int delay = WHEEL;

    /** SPEC.md §4's redstone gate. Edge detection lives here so the block entity stays a handle. */
    private boolean powered;
    private boolean pulseArmed;

    public BusRunner(BooleanSupplier alive) {
        this.alive = alive;
    }

    /**
     * @param offset derived from the Workbay's BlockPos so that Workbays stagger instead of every
     *               one in a base firing on the same tick
     */
    /**
     * The redstone signal at the Workbay, and the rising edge {@link RedstoneMode#PULSE} spends.
     * Called every tick whether or not any bus runs, or an edge that lands between two wheel steps
     * is never seen.
     */
    public void power(boolean nowPowered) {
        if (nowPowered && !powered) {
            pulseArmed = true;
        }
        powered = nowPowered;
    }

    /**
     * <b>What running costs, and the only place it is charged.</b> SPEC.md §9: one FE per tick for
     * each link that is switched on, and one draw per move on top. Takes an amount and answers
     * whether the whole of it was there.
     *
     * <p>Handed in rather than reached for, because the buffer belongs to the block and this class
     * knows nothing about blocks -- and because every gametest that drives a runner without one
     * then says exactly what it means: this test is not about the bill.
     */
    @FunctionalInterface
    public interface Purse {
        boolean spend(int fe);
    }

    /** A purse that pays for anything. What a test uses when the bill is not what it is testing. */
    public static final Purse FREE = fe -> true;

    public void tick(ServerLevel level, WorkbayRecord record, Iterable<BusConfig> buses,
        int offset) {
        tick(level, record, buses, offset, FREE);
    }

    public void tick(ServerLevel level, WorkbayRecord record, Iterable<BusConfig> buses, int offset,
        Purse purse) {
        if (--delay < 0) {
            delay = WHEEL - 1;
        }
        int phase = (delay + offset) % WHEEL;
        if (phase % STEP_TICKS != 0) {
            return;
        }
        int step = phase / STEP_TICKS;
        boolean spentPulse = false;

        // <b>The standing cost first, for every link that is switched on.</b> Charged here rather
        // than in the per-bus loop because it is owed whether or not a link's turn came up on this
        // step -- it is the price of having the automation at all, not of using it. STEP_TICKS
        // ticks have passed since the last time this ran, so that is what is billed for.
        //
        // A Workbay that cannot pay it runs nothing at all this step and every link says why. The
        // buffer was drawn, saved and spent by nothing before this: links moved goods for ever on
        // an empty buffer, which made the bar on the screen a decoration. OPEN_ISSUES #72.
        int switchedOn = 0;
        for (BusConfig bus : buses) {
            if (bus.enabled() && !bus.detached()) {
                switchedOn++;
            }
        }
        int standing = switchedOn * WorkbayConfig.SERVER.powerPerLinkPerTick.get() * STEP_TICKS;
        if (!purse.spend(standing)) {
            for (BusConfig bus : buses) {
                statuses.put(bus.id(), bus.enabled() && !bus.detached()
                    ? BusStatus.NO_POWER : BusStatus.DISABLED);
            }
            return;
        }

        ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        if (backshop == null) {
            return;
        }
        for (BusConfig bus : buses) {
            // Attached to no bay, so there is no machine end to move anything to or from. Checked
            // before anything reads `record.bay(bus.bay())`, which is what a negative index would
            // walk straight into. OPEN_ISSUES #70.
            if (bus.detached()) {
                statuses.put(bus.id(), BusStatus.DETACHED);
                continue;
            }
            if (!bus.enabled()) {
                statuses.put(bus.id(), BusStatus.DISABLED);
                continue;
            }
            // The other half of an Impeller: the same factor taken off the wait, floored at one
            // step so the fastest a link can ever be is the wheel itself.
            int wait = Math.max(STEP_TICKS, bus.speed() / record.upgrades().impellerFactor());
            if (step % Math.max(1, wait / STEP_TICKS) != 0) {
                continue;
            }
            RedstoneMode gate = record.bay(bus.bay()).redstone();
            if (!gate.allows(powered, pulseArmed)) {
                statuses.put(bus.id(), BusStatus.HELD_BY_REDSTONE);
                continue;
            }
            spentPulse |= gate == RedstoneMode.PULSE;
            if (needsResonator(level, record, bus)) {
                statuses.put(bus.id(), BusStatus.NEEDS_RESONATOR);
                continue;
            }
            // And the per-move draw, before the move. Taken first so nothing is ever half-moved:
            // a link that cannot pay does not touch either end.
            if (!purse.spend(WorkbayConfig.SERVER.powerPerMove.get())) {
                statuses.put(bus.id(), BusStatus.NO_POWER);
                continue;
            }
            statuses.put(bus.id(), run(level, backshop, record, bus));
        }
        // One operation per rising edge, spent only once something on PULSE actually got its turn.
        if (spentPulse) {
            pulseArmed = false;
        }
    }

    /**
     * SPEC.md §1's Resonator, enforced. <b>It was priced, drawn, saved and read by nothing</b> -
     * a player spent an ender eye on "Links may target other dimensions" for a
     * capability they already had, which is the worst kind of rung on a ladder.
     *
     * <p>The comparison is the Connector's dimension against the <b>Workbay's own</b>, because
     * that is what "another dimension" means to the player standing at the block. §1 exempts the
     * Backshop outright - a bay-to-bay link and a Connector inside one of your own rooms are
     * <em>inside</em> the machine, not a reach across the world - and an internal link has no
     * Connector at all.
     */
    private static boolean needsResonator(ServerLevel level, WorkbayRecord record, BusConfig bus) {
        if (bus.internal() || record.upgrades().resonators() > 0) {
            return false;
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> where =
            bus.connector().dimension();
        return !where.equals(level.dimension()) && !where.equals(WorkbayDimensions.BACKSHOP);
    }

    /**
     * Links whose Connector has gone, discovered while ticking. Reported rather than acted on here,
     * because a runner must not mutate the list it is iterating; the block entity sweeps them.
     */
    public java.util.Set<UUID> orphaned() {
        return statuses.entrySet().stream()
            .filter(e -> e.getValue() == BusStatus.CONNECTOR_GONE)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** Points this runner at its network's shared status map. Called before the bus election. */
    public void shareStatuses(Map<UUID, BusStatus> shared) {
        statuses = shared;
    }

    public BusStatus status(UUID busId) {
        return statuses.getOrDefault(busId, BusStatus.IDLE);
    }

    /** True while any link needs the player to do something about it. Drives the block lit state. */
    public boolean anyProblem() {
        return statuses.values().stream().anyMatch(BusStatus::isProblem);
    }

    /** True on the ticks a link reported a move. The block entity is what turns that into "recently". */
    public boolean anyRunning() {
        return statuses.containsValue(BusStatus.RUNNING);
    }


    /** Forgets one link's caches and status, so a removed link stops holding a level reference. */
    public void forget(UUID busId) {
        targetItems.remove(busId);
        targetEnergy.remove(busId);
        targetFluids.remove(busId);
        statuses.remove(busId);
    }

    /** Drops one bay's machine-end caches, so a changed face config is re-resolved rather than kept. */
    public void forgetBay(int bay) {
        machineItems.remove(bay);
        machineEnergy.remove(bay);
        machineFluids.remove(bay);
    }

    /** Drops every cache. Called when the Workbay is removed, so nothing keeps a level alive. */
    public void invalidate() {
        targetItems.clear();
        targetEnergy.clear();
        targetFluids.clear();
        machineItems.clear();
        machineEnergy.clear();
        machineFluids.clear();
    }

    private BusStatus run(ServerLevel level, ServerLevel backshop, WorkbayRecord record, BusConfig bus) {
        GlobalPos target = bus.target();
        ServerLevel targetLevel = level.getServer().getLevel(target.dimension());
        if (targetLevel == null) {
            return BusStatus.TARGET_MISSING;
        }
        // A link is its Connector. If that chunk is loaded and the block is not there any more, the
        // Connector was broken while this Workbay was unloaded and could not be told. Only ever
        // asked of a chunk already loaded: getBlockState on an unloaded one loads it synchronously.
        // An internal (bay-to-bay) link has no Connector at all - its "connector" field is the
        // Workbay's own position, which is never going to hold a Connector block, so this check
        // would misfire as CONNECTOR_GONE forever if it ran for one.
        if (!bus.internal() && com.neryos.workbay.content.connector.ConnectorBlock.gone(
            level.getServer(), bus.connector())) {
            return BusStatus.CONNECTOR_GONE;
        }
        BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), bus.bay());

        // Which of the hosted machine's faces this link may use, from the bay's own face config.
        // Unconfigured means every face, so a bay nobody has opened the screen for behaves exactly
        // as it did before the config existed.
        java.util.Set<Direction> faces = record.bay(bus.bay()).faces()
            .usable(bus.resource(), bus.mode() == BusConfig.Mode.INSERT);
        // Configured, but not for this direction of travel. Reported here rather than left to fall
        // through as IDLE: a link that can never move anything is not resting, and telling the
        // player nothing is wrong while their face config silently kills the link is the worst
        // outcome the cube can have. Found in play, then reproduced by
        // aFaceConfigThatBlocksALinkIsReported.
        if (faces.isEmpty()) {
            return BusStatus.MACHINE_NO_FACE;
        }

        return switch (bus.resource()) {
            case ITEM -> runItems(record, bus, targetLevel, target.pos(), backshop, machinePos, faces);
            case ENERGY -> runEnergy(record, bus, targetLevel, target.pos(), backshop, machinePos, faces);
            case FLUID -> runFluid(record, bus, targetLevel, target.pos(), backshop, machinePos, faces);
            case CHEMICAL -> runChemical(record, bus, targetLevel, target.pos(), backshop, machinePos);
        };
    }

    /**
     * Chemicals, which are Mekanism's and nobody else's (OPEN_ISSUES #31).
     *
     * <p>Thin on purpose. <b>Every line that names a Mekanism type is behind
     * {@link com.neryos.workbay.compat.MekanismChemicals}</b>, because this class is loaded in every
     * install and a class is what resolves the types in it. The guard and the work must not live
     * together: that is the exact shape of the crash that shipped in 0.1.0.
     *
     * <p>No face config, still: which face a Mekanism machine offers gas on is its own side
     * config's answer, and a Connector stuck to the wrong one would only ever be a preference.
     * <b>A filter, now</b> - carried in the same id list the item and fluid filters use, because
     * a chemical id is a {@code ResourceLocation} like any other. What is different is how an entry
     * gets in: there is no chemical item to drag, so the panel names what is standing in the tank.
     * OPEN_ISSUES #41.
     */
    private BusStatus runChemical(WorkbayRecord record, BusConfig bus, ServerLevel targetLevel,
        BlockPos targetPos, ServerLevel backshop, BlockPos machinePos) {
        boolean insert = bus.mode() == BusConfig.Mode.INSERT;
        ServerLevel sourceLevel = insert ? backshop : targetLevel;
        BlockPos sourcePos = insert ? machinePos : targetPos;
        ServerLevel sinkLevel = insert ? targetLevel : backshop;
        BlockPos sinkPos = insert ? targetPos : machinePos;
        Direction targetFace = bus.targetFace().orElse(null);

        // <b>The bay's cube decides which of the machine's faces this link may use</b>, exactly as
        // it does for items, fluids and energy. It used to read the link's pinned machine face and
        // nothing else -- a field the screen never set -- so a chemical link took whichever face
        // Mekanism answered on first, which on a machine with a gas input and a gas output is the
        // wrong one about half the time. FaceConfig#usable answers "every face" for a bay nobody
        // has configured, so nothing changes for one.
        java.util.Set<Direction> machineFaces = record.bay(bus.bay()).faces()
            .usable(BusConfig.Resource.CHEMICAL, insert);
        if (machineFaces.isEmpty()) {
            return BusStatus.MACHINE_NO_FACE;
        }

        // Chemicals are measured in mB like fluids, so they take the fluid scale. A separate
        // constant would be a second number meaning the same thing.
        long budget = Math.max(1, (long) rate(record, bus) * MB_PER_RATE);
        // Each allowed face in turn, keeping the most informative answer. A face a machine does
        // not answer on is not a fault while another one might; only "none of them answered" is.
        BusStatus best = null;
        for (Direction machineFace : machineFaces) {
            BusStatus status = switch (com.neryos.workbay.compat.MekanismChemicals.move(
                sourceLevel, sourcePos, insert ? machineFace : targetFace,
                sinkLevel, sinkPos, insert ? targetFace : machineFace,
                budget, bus.filter()::allowsId)) {
                case MOVED -> BusStatus.RUNNING;
                case NOTHING_TO_MOVE -> BusStatus.IDLE;
                case NOT_LOADED -> BusStatus.TARGET_NOT_LOADED;
                case NO_SOURCE_PORT -> insert ? BusStatus.MACHINE_NO_PORT : BusStatus.TARGET_NO_PORT;
                case NO_SINK_PORT -> insert ? BusStatus.TARGET_NO_PORT : BusStatus.MACHINE_NO_PORT;
            };
            if (status == BusStatus.RUNNING) {
                return status;
            }
            if (best == null || best.isProblem()) {
                best = status;
            }
        }
        return best;
    }

    private BusStatus runItems(WorkbayRecord record, BusConfig bus, ServerLevel targetLevel,
        BlockPos targetPos, ServerLevel backshop, BlockPos machinePos,
        java.util.Set<Direction> faces) {
        BusEndpoint<IItemHandler> targetEnd = targetItems.computeIfAbsent(bus.id(), id ->
            new BusEndpoint<>(Capabilities.ItemHandler.BLOCK, targetLevel, targetPos, alive,
                bus.targetFace().orElse(null)));
        BusEndpoint<IItemHandler> machineEnd = machineItems.computeIfAbsent(bus.bay(), b ->
            new BusEndpoint<>(Capabilities.ItemHandler.BLOCK, backshop, machinePos, alive,
                bus.machineFace().orElse(null)));

        if (!targetEnd.targetLoaded()) {
            return BusStatus.TARGET_NOT_LOADED;
        }
        boolean insert = bus.mode() == BusConfig.Mode.INSERT;
        BusEndpoint<IItemHandler> source = insert ? machineEnd : targetEnd;
        BusEndpoint<IItemHandler> sink = insert ? targetEnd : machineEnd;
        java.util.Set<Direction> sourceFaces = insert ? faces : EVERY_FACE;
        java.util.Set<Direction> sinkFaces = insert ? EVERY_FACE : faces;

        // The source first, and the destination bound on inserting what the source actually
        // offered, simulated. Not the other way round, and not on `getSlots() > 0`: a face that
        // reports slots and refuses every insert - a furnace's bottom, a Mekanism machine's
        // output-only side - wins that bind, and BusEndpoint then keeps it, because the same
        // predicate is what re-confirms the bound face on every later step. The link moves nothing
        // forever and reads IDLE while doing it. Measured, at rate 8 into a furnace: zero of
        // sixteen iron.
        //
        // Ordering is the whole fix. There is nothing to simulate an insert *of* until the source
        // has been asked what it is offering, which is why the energy bus's one-line predicate swap
        // did not port over. SPEC.md §9, and the third time this fault has been found -
        // aLinkSkipsAnOutputOnlyFaceInsteadOfBindingToIt is the guard.
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> allowed = allowed(bus);
        // What a source gives up: its outputs, when it is a machine that has any (#120). And what
        // a vanilla furnace is offered: only what it can burn or cook, because its input slot
        // says yes to anything and a filterless chest-to-furnace channel would otherwise post the
        // furnace its own glass back, one stack at a time, until the input was full of it.
        ServerLevel sourceLevel = insert ? backshop : targetLevel;
        BlockPos sourcePos = insert ? machinePos : targetPos;
        ServerLevel sinkLevel = insert ? targetLevel : backshop;
        BlockPos sinkPos = insert ? targetPos : machinePos;
        // Six capability lookups, and only when a stack is actually up for judgement.
        java.util.function.Supplier<java.util.List<IItemHandler>> sourceHandlers =
            com.google.common.base.Suppliers.memoize(() -> {
                java.util.List<IItemHandler> handlers = new java.util.ArrayList<>(6);
                for (Direction face : Direction.values()) {
                    IItemHandler handler = sourceLevel.getCapability(Capabilities.ItemHandler.BLOCK,
                        sourcePos, face);
                    if (handler != null) {
                        handlers.add(handler);
                    }
                }
                return handlers;
            });
        if (sinkLevel.getBlockEntity(sinkPos)
                instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
            allowed = allowed.and(stack -> furnaceTakes(sinkLevel, furnace, stack));
        }
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> wanted = allowed;
        java.util.Map<net.minecraft.world.item.Item, Boolean> roles = new java.util.HashMap<>(4);
        IItemHandler bound = source.resolve(h -> hasAnything(BusTransfer.outputsOnly(h,
            sourceHandlers, roles), wanted), sourceFaces);
        IItemHandler from = bound == null ? null
            : BusTransfer.outputsOnly(bound, sourceHandlers, roles);
        if (from == null) {
            // Nothing came out. Two very different reasons, and one message for both is how a
            // dead link spends a session looking like a resting one.
            boolean anyHandler = source.resolve(h -> h.getSlots() > 0, sourceFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.MACHINE_NO_PORT : BusStatus.TARGET_NO_PORT;
        }
        int budget = rate(record, bus);
        IItemHandler to = sink.resolve(h -> BusTransfer.moveItems(from, h, budget, wanted, true) > 0,
            sinkFaces);
        if (to == null) {
            // A destination that is merely full is resting, not unreachable - the same distinction
            // the energy path draws, drawn here for the same reason.
            boolean anyHandler = sink.resolve(h -> h.getSlots() > 0, sinkFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.TARGET_NO_PORT : BusStatus.MACHINE_NO_PORT;
        }
        int moved = BusTransfer.moveItems(from, to, budget, wanted);
        return moved > 0 ? BusStatus.RUNNING : BusStatus.IDLE;
    }


    /**
     * The link's filter, as a predicate over items. Applied to what the <em>source</em> is
     * offering, in the simulation the bind is made on, so a filtered link never has to put anything
     * back - the same rule the census below runs by. {@link BusFilter} says what it matches on.
     */
    private static java.util.function.Predicate<net.minecraft.world.item.ItemStack> allowed(
        BusConfig bus) {
        BusFilter filter = bus.filter();
        return filter.isEmpty() ? stack -> true : filter::allows;
    }

    /** The same filter over fluids. Its entries are fluid ids on a fluid link. */
    private static java.util.function.Predicate<net.neoforged.neoforge.fluids.FluidStack> allowedFluid(
        BusConfig bus) {
        BusFilter filter = bus.filter();
        return filter.isEmpty() ? stack -> true : filter::allows;
    }

    private BusStatus runEnergy(WorkbayRecord record, BusConfig bus, ServerLevel targetLevel, BlockPos targetPos,
        ServerLevel backshop, BlockPos machinePos, java.util.Set<Direction> faces) {
        BusEndpoint<IEnergyStorage> targetEnd = targetEnergy.computeIfAbsent(bus.id(), id ->
            new BusEndpoint<>(Capabilities.EnergyStorage.BLOCK, targetLevel, targetPos, alive,
                bus.targetFace().orElse(null)));
        BusEndpoint<IEnergyStorage> machineEnd = machineEnergy.computeIfAbsent(bus.bay(), b ->
            new BusEndpoint<>(Capabilities.EnergyStorage.BLOCK, backshop, machinePos, alive,
                bus.machineFace().orElse(null)));

        if (!targetEnd.targetLoaded()) {
            return BusStatus.TARGET_NOT_LOADED;
        }
        boolean insert = bus.mode() == BusConfig.Mode.INSERT;
        BusEndpoint<IEnergyStorage> source = insert ? machineEnd : targetEnd;
        BusEndpoint<IEnergyStorage> sink = insert ? targetEnd : machineEnd;
        java.util.Set<Direction> sourceFaces = insert ? faces : EVERY_FACE;
        java.util.Set<Direction> sinkFaces = insert ? EVERY_FACE : faces;

        // Simulated, not asked. `canExtract` and `canReceive` are what a handler *says*, and
        // Mekanism's FE wrapper returns a hardcoded `true` from both
        // (`ForgeEnergyIntegration#canExtract`), on every face, whatever that face's side config
        // says. So a bus that binds on them takes the first face it meets - an input-only one about
        // five times in six - and then moves nothing, forever, reading IDLE the whole time.
        //
        // This is the same fault as trusting `isItemValid` in Bay View, at the other end of the
        // mod, and it is why SPEC.md §9 says to simulate on bind. Measured: pushing FE through a
        // link into a racked Basic Energy Cube moved zero until this line changed.
        int budget = Math.max(1, rate(record, bus) * FE_PER_RATE);
        IEnergyStorage to = sink.resolve(store -> store.receiveEnergy(budget, true) > 0, sinkFaces);
        if (to == null) {
            // A destination that is merely full is resting, not unreachable, so the two are still
            // told apart the way the item path tells them apart.
            boolean anyHandler = sink.resolve(store -> true, sinkFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.TARGET_NO_PORT : BusStatus.MACHINE_NO_PORT;
        }
        IEnergyStorage from = source.resolve(store -> store.extractEnergy(budget, true) > 0,
            sourceFaces);
        if (from == null) {
            boolean anyHandler = source.resolve(store -> true, sourceFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.MACHINE_NO_PORT : BusStatus.TARGET_NO_PORT;
        }
        return BusTransfer.moveEnergy(from, to, budget) > 0 ? BusStatus.RUNNING : BusStatus.IDLE;
    }

    /**
     * Fluids. SPEC.md §9, and deliberately the item path's shape rather than the energy path's:
     * <b>the source is resolved first, and the destination is bound on the same call the commit
     * will make</b>, given what the source actually offered. A fluid handler's own answers are no
     * more trustworthy than an item handler's - a Mekanism machine's null side reports its tanks
     * perfectly and then swallows the fill - and there is nothing to simulate a fill <em>of</em>
     * until the source has been asked.
     *
     * <p>The filter is honoured, and
     * for the same reason the item path honours it - on what the source offers, before the commit.
     */
    private BusStatus runFluid(WorkbayRecord record, BusConfig bus, ServerLevel targetLevel, BlockPos targetPos,
        ServerLevel backshop, BlockPos machinePos, java.util.Set<Direction> faces) {
        BusEndpoint<IFluidHandler> targetEnd = targetFluids.computeIfAbsent(bus.id(), id ->
            new BusEndpoint<>(Capabilities.FluidHandler.BLOCK, targetLevel, targetPos, alive,
                bus.targetFace().orElse(null)));
        BusEndpoint<IFluidHandler> machineEnd = machineFluids.computeIfAbsent(bus.bay(), b ->
            new BusEndpoint<>(Capabilities.FluidHandler.BLOCK, backshop, machinePos, alive,
                bus.machineFace().orElse(null)));

        if (!targetEnd.targetLoaded()) {
            return BusStatus.TARGET_NOT_LOADED;
        }
        boolean insert = bus.mode() == BusConfig.Mode.INSERT;
        BusEndpoint<IFluidHandler> source = insert ? machineEnd : targetEnd;
        BusEndpoint<IFluidHandler> sink = insert ? targetEnd : machineEnd;
        java.util.Set<Direction> sourceFaces = insert ? faces : EVERY_FACE;
        java.util.Set<Direction> sinkFaces = insert ? EVERY_FACE : faces;

        int budget = Math.max(1, rate(record, bus) * MB_PER_RATE);
        java.util.function.Predicate<net.neoforged.neoforge.fluids.FluidStack> allowed =
            allowedFluid(bus);
        IFluidHandler from = source.resolve(
            h -> !BusTransfer.offer(h, budget, allowed).isEmpty(), sourceFaces);
        if (from == null) {
            // Empty and unreachable are different things, and one message for both is how a dead
            // link spends a session looking like a resting one.
            boolean anyHandler = source.resolve(h -> h.getTanks() > 0, sourceFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.MACHINE_NO_PORT : BusStatus.TARGET_NO_PORT;
        }
        IFluidHandler to = sink.resolve(
            h -> BusTransfer.moveFluid(from, h, budget, allowed, true) > 0, sinkFaces);
        if (to == null) {
            boolean anyHandler = sink.resolve(h -> h.getTanks() > 0, sinkFaces) != null;
            return anyHandler ? BusStatus.IDLE
                : insert ? BusStatus.TARGET_NO_PORT : BusStatus.MACHINE_NO_PORT;
        }
        return BusTransfer.moveFluid(from, to, budget, allowed, false) > 0
            ? BusStatus.RUNNING : BusStatus.IDLE;
    }

    /**
     * A source only counts if something could actually come out of it. Binding to the first handler
     * that merely exists is how a bus ends up wired to a read-only face and moves nothing forever.
     */
    /** Fuel, or something this furnace's own recipe type turns into something else. */
    private static boolean furnaceTakes(ServerLevel level,
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace,
        net.minecraft.world.item.ItemStack stack) {
        if (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.isFuel(stack)) {
            return true;
        }
        net.minecraft.world.item.crafting.RecipeType<?
            extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type =
            furnace instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity
                ? net.minecraft.world.item.crafting.RecipeType.BLASTING
            : furnace instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity
                ? net.minecraft.world.item.crafting.RecipeType.SMOKING
            : net.minecraft.world.item.crafting.RecipeType.SMELTING;
        return level.getRecipeManager().getRecipeFor(type,
            new net.minecraft.world.item.crafting.SingleRecipeInput(stack), level).isPresent();
    }

    private static boolean hasAnything(IItemHandler handler,
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> allowed) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            net.minecraft.world.item.ItemStack sample = handler.extractItem(slot, 1, true);
            if (!sample.isEmpty() && allowed.test(sample)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kept strictly separate. Collapsing any two turns ordinary behaviour into a bug report, and
     * SPEC.md §4 requires the three failing ones to be visually distinct on the LINKS row.
     */
    public enum BusStatus {
        RUNNING, IDLE, DISABLED,
        /** Waiting on the bay's redstone mode. The player's own instruction, not a fault. */
        HELD_BY_REDSTONE,
        CONNECTOR_GONE, TARGET_MISSING, TARGET_NOT_LOADED, TARGET_NO_PORT,
        /** The hosted machine answers on none of the faces this link may use. */
        MACHINE_NO_PORT,
        /** The bay's cube has faces set, but none for this link's direction of travel. */
        MACHINE_NO_FACE,
        /**
         * The link crosses a dimension boundary and this network has no Resonator. SPEC.md §1.
         *
         * <p>Appended at the end because a status travels on the snapshot as its ordinal.
         */
        NEEDS_RESONATOR,
        /**
         * Attached to no bay. The player took it off one with the X and has not put it on another;
         * it is waiting in the Add list. <b>Not a problem</b> - it is a thing the player did on
         * purpose, the same way DISABLED is, and counting it as one would put a red number in the
         * header for every link anybody ever detached.
         *
         * <p>Appended at the end because a status travels on the snapshot as its ordinal.
         */
        DETACHED,
        /**
         * The Workbay's own buffer is empty, so it cannot pay for the move. SPEC.md §9: the buses
         * stop and the block reads stuck.
         *
         * <p><b>A problem</b>, unlike DISABLED and DETACHED: nobody chose it and it is fixed by
         * feeding the block, which is the sort of thing the header's problem count exists to point
         * at. Appended at the end because a status travels on the snapshot as its ordinal.
         */
        NO_POWER;

        /** True for a status the player has to do something about. Drives the problem count. */
        public boolean isProblem() {
            return this != RUNNING && this != IDLE && this != DISABLED && this != HELD_BY_REDSTONE
                && this != DETACHED;
        }
    }
}
