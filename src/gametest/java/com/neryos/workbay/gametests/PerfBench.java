package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.remote.RemoteScreens;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * What the mod costs a server, measured with the game's own profiler.
 *
 * <p><b>Off unless {@code -Dworkbay.bench=true}</b>, which {@code ./gradlew runBenchmark} sets. It
 * is not a test - nothing here asserts anything about behaviour - and it runs for minutes, so
 * leaving it in the ordinary gametest batch would make every test run pay for it.
 *
 * <p><b>Every scenario is a difference, not a reading.</b> The first measurement attempt reported
 * five milliseconds a tick for one Workbay, because a hundred finished gametests leave their
 * Workbays, chests and furnaces standing in the same world, still ticking, still moving items. So
 * each scenario silences those first (every link in the registry that is not ours is switched off),
 * then profiles five windows with <b>nothing of ours built</b>, then builds, then profiles five
 * more. What is reported is on minus off: whatever else is in that world is in both halves.
 *
 * <p>The numbers come from {@code MinecraftServer#startRecordingMetrics}, which is what
 * {@code /perf start} calls. The section tree is written verbatim to
 * {@code run-gametest/perf/<scenario>.<on|off>.<window>.txt} with the tick and time spans and how
 * many items the window actually moved, so cost can be divided by work. The medians are taken
 * from those files afterwards; nothing is summarised here.
 *
 * <p><b>GameTestServer ticks unthrottled</b> ({@code waitUntilNextTick} is {@code runAllTasks}), so
 * a window is a tick count rather than a wall-clock one and no idle time dilutes the percentages.
 */
@ForEachTest(groups = "perf")
public class PerfBench {

    private static final boolean ON = Boolean.getBoolean("workbay.bench");

    /** Windows per half. The median of five is what gets reported; one window decides nothing. */
    private static final int REPEATS = 5;

    /** Ticks per window. Long enough that a link at speed 10 takes forty turns inside one. */
    private static final int WINDOW = 400;

    /** Links per Workbay, which is also {@link WorkbayBlockEntity#PIPS} - the most a block shows. */
    private static final int LINKS = 8;

    private static final Path OUT = Path.of("perf");

    /** Where a finished window's results wait for the tick that writes them. */
    private static final Map<String, ProfileResults> DONE = new HashMap<>();

    /**
     * Menus the bench is holding open. A real player's menu is polled from
     * {@code ServerPlayer#doTick}; a mock one is not reliably ticked at all, so the bench drives
     * the same {@code broadcastChanges} itself and gives it a name the profiler can report. Empty
     * in every scenario that is not about screens, and then this listener costs one isEmpty check.
     */
    private static final List<WorkbayMenu> OPEN_MENUS = new ArrayList<>();

    static {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (OPEN_MENUS.isEmpty()) {
                return;
            }
            event.getServer().getProfiler().push("workbay:menus");
            OPEN_MENUS.forEach(WorkbayMenu::broadcastChanges);
            event.getServer().getProfiler().pop();
        });
    }

    // ------------------------------------------------------------------ the scenarios

    @GameTest(timeoutTicks = 60_000, batch = "perfOneWorkbay")
    @TestHolder(description = "PERF: one Workbay, eight links, all of them moving items.")
    public static void oneWorkbayEightBusyLinks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 4));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> workbays(helper, "one-workbay", 1));
    }

    /** Sixteen separate networks, a hundred and twenty-eight links, which nobody has tried. */
    @GameTest(timeoutTicks = 80_000, batch = "perfScale")
    @TestHolder(description = "PERF: sixteen networks with eight busy links each.")
    public static void sixteenNetworksAllBusy(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 34));
        test.onGameTest(ExtendedGameTestHelper.class,
            helper -> workbays(helper, "sixteen-networks", 16));
    }

    /**
     * Every bay full, which is what a base looks like the moment a machine stops consuming. The
     * link cannot bind its destination, so it tries all six faces and then tries all six again to
     * tell "full" from "no port" - the dearest thing a link can do, and the ordinary state of a
     * base nobody is watching.
     */
    @GameTest(timeoutTicks = 60_000, batch = "perfStalled")
    @TestHolder(description = "PERF: one Workbay whose eight links all have a full destination.")
    public static void eightLinksWithNowhereToPut(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 4));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            List<WorkbayBlockEntity> units = new ArrayList<>();
            profile(helper, "stalled", () -> {
                GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                units.add(unit(helper, player, helper.absolutePos(new BlockPos(1, 1, 1))));
            }, () -> {
                refillUnits(helper, units);
                ServerLevel backshop =
                    helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
                WorkbayRecord record = units.get(0).record().orElseThrow();
                for (int bay = 0; bay < LINKS; bay++) {
                    fill(backshop, BayGeometry.machinePos(record.bayColumn(), bay), 27);
                }
            }, () -> 0)
                .thenSucceed();
        });
    }

    /** The same one Workbay, with four players holding its screen open. */
    @GameTest(timeoutTicks = 60_000, batch = "perfScreens")
    @TestHolder(description = "PERF: one busy Workbay with four Workbay screens open.")
    public static void fourWorkbayScreensOpen(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 4));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            List<WorkbayBlockEntity> units = new ArrayList<>();
            profile(helper, "four-screens", () -> {
                GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                units.add(unit(helper, owner, helper.absolutePos(new BlockPos(1, 1, 1))));
                OPEN_MENUS.clear();
                for (int i = 0; i < 4; i++) {
                    GameTestPlayer viewer =
                        helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                    OPEN_MENUS.add(new WorkbayMenu(1 + i, viewer.getInventory(), units.get(0),
                        WorkbayMenu.build(units.get(0), viewer, 0)));
                }
            }, () -> refillUnits(helper, units), () -> delivered(helper, units))
                .thenExecute(OPEN_MENUS::clear)
                .thenSucceed();
        });
    }

    /**
     * Four remote machine screens. Two costs live here and only one of them is obvious: the
     * {@code getUpdateTag} re-send every fifth tick, and {@code Level#getBlockEntity}'s patched
     * return, which from here on scans the open list on every lookup in the game that misses.
     */
    @GameTest(timeoutTicks = 60_000, batch = "perfRemote")
    @TestHolder(description = "PERF: one busy Workbay with four remote machine screens open.")
    public static void fourRemoteScreensOpen(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 4));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            List<WorkbayBlockEntity> units = new ArrayList<>();
            List<GameTestPlayer> viewers = new ArrayList<>();
            profile(helper, "four-remote-screens", () -> {
                GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                units.add(unit(helper, owner, helper.absolutePos(new BlockPos(1, 1, 1))));
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                WorkbayRecord record = units.get(0).record().orElseThrow();
                for (int i = 0; i < 4; i++) {
                    GameTestPlayer viewer =
                        helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                    viewers.add(viewer);
                    RemoteScreens.opened(viewer, backshop,
                        BayGeometry.machinePos(record.bayColumn(), i));
                }
                // Written into the report rather than asserted: the re-send skips a viewer the
                // player list does not know, and a zero here means only the mixin was measured.
                note("four-remote-screens.on", "viewersInPlayerList " + viewers.stream()
                    .filter(v -> level.getServer().getPlayerList().getPlayer(v.getUUID()) != null)
                    .count());
            }, () -> refillUnits(helper, units), () -> delivered(helper, units))
                .thenExecute(() -> viewers.forEach(RemoteScreens::close))
                .thenSucceed();
        });
    }

    /**
     * The same work on the floor. A hopper is what a player uses with no mod at all, and 64 of them
     * move about what eight links move - the report divides both by items delivered, so the counts
     * do not have to match exactly for the comparison to hold.
     */
    @GameTest(timeoutTicks = 60_000, batch = "perfHoppers")
    @TestHolder(description = "PERF: sixty-four hoppers moving items on the floor, for comparison.")
    public static void sixtyFourHoppersOnTheFloor(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(10, 6, 10));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            List<BlockPos> sinks = new ArrayList<>();
            profile(helper, "hoppers", () -> {
                for (int i = 0; i < 64; i++) {
                    BlockPos sink = helper.absolutePos(new BlockPos(i % 8, 1, i / 8));
                    level.setBlock(sink, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(sink.above(), Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(sink.above(2), Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                    sinks.add(sink);
                }
            }, () -> sinks.forEach(sink -> {
                empty(level, sink);
                empty(level, sink.above());
                fill(level, sink.above(2), 2);
            }), () -> countAll(level, sinks))
                .thenSucceed();
        });
    }

    /**
     * The owner's question, and the reason it is one recording rather than two: the profiler keys its
     * tree by level, so sixteen furnaces in a bay and sixteen on the floor are two numbers taken
     * under identical conditions, on the same hardware, in the same second.
     */
    @GameTest(timeoutTicks = 60_000, batch = "perfDimension")
    @TestHolder(description = "PERF: sixteen furnaces racked in the Backshop and sixteen in the "
        + "overworld, smelting at the same time.")
    public static void sameFurnacesInBothWorlds(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(12, 4, 6));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            List<BlockPos> hosted = new ArrayList<>();
            List<BlockPos> floor = new ArrayList<>();
            profile(helper, "dimension", () -> {
                GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                for (int i = 0; i < 2; i++) {
                    WorkbayBlockEntity workbay = bareWorkbay(helper, player,
                        helper.absolutePos(new BlockPos(9 + i, 1, 1)));
                    WorkbayRecord record = workbay.record().orElseThrow();
                    for (int bay = 0; bay < LINKS; bay++) {
                        BayHosting.rack(backshop, record.bayColumn(), bay,
                            new ItemStack(Blocks.FURNACE), player, Direction.NORTH);
                        hosted.add(BayGeometry.machinePos(record.bayColumn(), bay));
                    }
                }
                for (int i = 0; i < 16; i++) {
                    BlockPos at = helper.absolutePos(new BlockPos(i % 4, 1, i / 4));
                    level.setBlock(at, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
                    floor.add(at);
                }
            }, () -> {
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                hosted.forEach(at -> light(backshop, at));
                floor.forEach(at -> light(level, at));
            }, () -> smelted(level.getServer().getLevel(WorkbayDimensions.BACKSHOP), hosted))
                .thenSucceed();
        });
    }

    /**
     * The claim SPEC.md §0 makes and nothing measured: a network with no block costs the server
     * nothing. Thirty-two networks, each a racked furnace and two switched-on links, each ticked
     * once so it has mirrored its column, then every block broken. On minus off should be noise,
     * and the ticket count written beside it should come back to where it started.
     */
    @GameTest(timeoutTicks = 80_000, batch = "perfSleeping")
    @TestHolder(description = "PERF: thirty-two networks whose Workbay block was broken.")
    public static void thirtyTwoSleepingNetworks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(18, 4, 18));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            profile(helper, "sleeping", () -> {
                GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                String before = tickets(level);
                List<WorkbayBlockEntity> units = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    BlockPos at = helper.absolutePos(new BlockPos(1 + (i / 8) * 4, 1, 1 + (i % 8) * 2));
                    units.add(unit(helper, player, at, 2, Blocks.FURNACE));
                }
                // One tick each by hand, so every one has mirrored its column and holds a ticket
                // the break then has to let go of. Built and broken on the same tick they would
                // never have held one, and "released" would be measuring nothing.
                units.forEach(w -> WorkbayBlockEntity.serverTick(level, w.getBlockPos(),
                    level.getBlockState(w.getBlockPos()), w));
                String awake = tickets(level);
                units.forEach(w -> level.destroyBlock(w.getBlockPos(), false));
                note("sleeping.on", "tickets before " + before + "\n# tickets awake " + awake
                    + "\n# tickets asleep " + tickets(level));
            }, () -> { }, () -> 0)
                .thenSucceed();
        });
    }

    /**
     * A placed Workbay with nothing to do: two cold furnaces racked, no links. What a block costs
     * for standing there -- its serverTick, the mirroring check, and a column chunk kept ticking
     * with two furnaces in it.
     */
    @GameTest(timeoutTicks = 80_000, batch = "perfIdle")
    @TestHolder(description = "PERF: sixteen placed Workbays with two cold furnaces and no links.")
    public static void sixteenIdleWorkbays(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(4, 4, 34));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            profile(helper, "idle", () -> {
                GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                String before = tickets(level);
                for (int i = 0; i < 16; i++) {
                    WorkbayBlockEntity workbay = bareWorkbay(helper, player,
                        helper.absolutePos(new BlockPos(1, 1, 1 + i * 2)));
                    WorkbayRecord record = workbay.record().orElseThrow();
                    for (int bay = 0; bay < 2; bay++) {
                        BayHosting.rack(backshop, record.bayColumn(), bay,
                            new ItemStack(Blocks.FURNACE), player, Direction.NORTH);
                    }
                }
                note("idle.on", "tickets before " + before);
            }, () -> { }, () -> 0)
                .thenExecute(() -> note("idle.on", "tickets after " + tickets(level)))
                .thenSucceed();
        });
    }

    /**
     * One network at {@link WorkbayBlockEntity#MAX_LINKS}: eight sources into each of eight bays.
     * Rate 4 rather than 8 so a bay's barrel is not full before the window ends, at the same
     * speed as the eight-link scenario -- so the slope against it is per link turn, and the
     * per-item figure divides out the halved rate.
     */
    @GameTest(timeoutTicks = 80_000, batch = "perfCap")
    @TestHolder(description = "PERF: one network with sixty-four busy links, the cap.")
    public static void sixtyFourLinksOneNetwork(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(13, 4, 10));
        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            if (skip(helper)) {
                return;
            }
            ServerLevel level = helper.getLevel();
            List<WorkbayBlockEntity> units = new ArrayList<>();
            List<BlockPos> sources = new ArrayList<>();
            profile(helper, "sixty-four-links", () -> {
                GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
                BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
                WorkbayBlockEntity workbay = bareWorkbay(helper, player, origin);
                WorkbayRecord record = workbay.record().orElseThrow();
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                for (int bay = 0; bay < LINKS; bay++) {
                    BayHosting.rack(backshop, record.bayColumn(), bay, new ItemStack(Blocks.BARREL),
                        player, Direction.NORTH);
                    for (int k = 0; k < WorkbayBlockEntity.MAX_LINKS / LINKS; k++) {
                        BlockPos source = origin.offset(2 + bay, 0, k);
                        level.setBlock(source, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                        workbay.addBus(connect(helper, workbay, source.above(), player)
                            .withEnabled(true).withBay(bay).withMode(BusConfig.Mode.EXTRACT)
                            .withRate(4).withSpeed(10));
                        sources.add(source);
                    }
                }
                units.add(workbay);
                note("sixty-four-links.on", "links " + workbay.buses().size());
            }, () -> {
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                WorkbayRecord record = units.get(0).record().orElseThrow();
                for (int bay = 0; bay < LINKS; bay++) {
                    empty(backshop, BayGeometry.machinePos(record.bayColumn(), bay));
                }
                sources.forEach(source -> fill(level, source, 8));
            }, () -> delivered(helper, units))
                .thenSucceed();
        });
    }

    /** One or many identical Workbay units, each with eight bays and eight busy links. */
    private static void workbays(ExtendedGameTestHelper helper, String label, int count) {
        if (skip(helper)) {
            return;
        }
        List<WorkbayBlockEntity> units = new ArrayList<>();
        profile(helper, label, () -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            for (int i = 0; i < count; i++) {
                units.add(unit(helper, player, helper.absolutePos(new BlockPos(1, 1, 1 + i * 2))));
            }
        }, () -> refillUnits(helper, units), () -> delivered(helper, units))
            .thenSucceed();
    }

    // ------------------------------------------------------------------ the rig

    /**
     * Five windows with nothing of ours in the world, then {@code build}, then five with it. Each
     * window starts by putting the fixture back the way it began, so every one of them measures a
     * scenario that is working rather than one that has run out of things to move.
     */
    private static GameTestSequence profile(ExtendedGameTestHelper helper, String label,
        Runnable build, Runnable refill, IntSupplier moved) {
        MinecraftServer server = helper.getLevel().getServer();
        GameTestSequence sequence = helper.startSequence()
            .thenExecute(() -> quiesce(server))
            .thenIdle(40);
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            sequence = window(sequence, server, label + ".off." + repeat, () -> { }, () -> 0);
        }
        sequence = sequence.thenExecute(build).thenIdle(40);
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            sequence = window(sequence, server, label + ".on." + repeat, refill, moved);
        }
        return sequence;
    }

    private static GameTestSequence window(GameTestSequence sequence, MinecraftServer server,
        String name, Runnable refill, IntSupplier moved) {
        int[] before = new int[1];
        return sequence
            .thenExecute(() -> {
                refill.run();
                before[0] = moved.getAsInt();
                server.startRecordingMetrics(results -> DONE.put(name, results), path -> { });
            })
            .thenIdle(WINDOW)
            .thenExecute(server::finishRecordingMetrics)
            .thenWaitUntil(() -> {
                if (!DONE.containsKey(name)) {
                    throw new GameTestAssertException("still recording " + name);
                }
            })
            .thenExecute(() -> write(name, DONE.remove(name), moved.getAsInt() - before[0]))
            // The recorder writes its own report on the io pool and then dumps level state back
            // onto the server thread. Left inside the next window that would be measured as this
            // mod's cost, which it is not.
            .thenIdle(60);
    }

    /**
     * Switches off every link this bench did not make. A hundred finished gametests leave their
     * Workbays standing and still pulling items, which is five milliseconds a tick of somebody
     * else's work sitting on top of the reading.
     */
    private static void quiesce(MinecraftServer server) {
        RoomRegistry registry = RoomRegistry.get(server);
        for (WorkbayRecord record : registry.all()) {
            if (!record.buses().isEmpty()) {
                registry.put(record.withBuses(List.of()));
            }
        }
    }

    private static void write(String label, ProfileResults results, int moved) {
        note(label, "ticks " + results.getTickDuration()
            + "\n# nanos " + results.getNanoDuration()
            + "\n# moved " + moved
            + "\n" + results.getProfilerResults());
    }

    private static void note(String label, String body) {
        try {
            Files.createDirectories(OUT);
            Path file = OUT.resolve(label + ".txt");
            Files.writeString(file, "# " + label + "\n# " + body + "\n",
                Files.exists(file) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when the bench is off, in which case the scenario passes without building anything. */
    private static boolean skip(ExtendedGameTestHelper helper) {
        if (!ON) {
            helper.succeed();
        }
        return !ON;
    }

    // ------------------------------------------------------------------ the fixtures

    /**
     * One Workbay with eight bays holding barrels, eight barrels outside it, and one link from each
     * outside barrel into its bay. Left at the speed a link is born with; the rate is turned up so
     * the link is genuinely busy rather than mostly asleep.
     */
    private static WorkbayBlockEntity unit(ExtendedGameTestHelper helper, GameTestPlayer player,
        BlockPos origin) {
        return unit(helper, player, origin, LINKS, Blocks.BARREL);
    }

    /** The same, {@code bays} wide, with {@code first} racked in bay 0 and barrels in the rest. */
    private static WorkbayBlockEntity unit(ExtendedGameTestHelper helper, GameTestPlayer player,
        BlockPos origin, int bays, Block first) {
        ServerLevel level = helper.getLevel();
        WorkbayBlockEntity workbay = bareWorkbay(helper, player, origin);
        WorkbayRecord record = workbay.record().orElseThrow();
        ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);

        for (int bay = 0; bay < bays; bay++) {
            BayHosting.rack(backshop, record.bayColumn(), bay,
                new ItemStack(bay == 0 ? first : Blocks.BARREL), player, Direction.NORTH);
            BlockPos source = origin.offset(2 + bay, 0, 0);
            level.setBlock(source, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            workbay.addBus(connect(helper, workbay, source.above(), player)
                .withEnabled(true).withBay(bay).withMode(BusConfig.Mode.EXTRACT)
                .withRate(8).withSpeed(10));
        }
        return workbay;
    }

    /**
     * A Workbay on its own network. <b>A second Workbay placed by the same player joins the first
     * one's network</b> - SPEC.md §0, and the reason the first scale run reported sixteen Workbays
     * pouring a hundred and twenty-eight links into one set of eight bays. That is a real shape and
     * it gets its own scenario; it is not what "sixteen Workbays" was supposed to mean.
     */
    private static WorkbayBlockEntity bareWorkbay(ExtendedGameTestHelper helper,
        GameTestPlayer player, BlockPos at) {
        ServerLevel level = helper.getLevel();
        level.setBlock(at, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, at, level.getBlockState(at), player,
            new ItemStack(WBBlocks.WORKBAY.get()));
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(at);
        // Powered, because a real one has to be: SPEC.md §9 charges the buffer for every
        // link that is switched on and again for every move, so an unfed Workbay runs
        // nothing and every link on it reads NO_POWER. OPEN_ISSUES #72. A test that is not
        // about the bill pays it up front and says so here.
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        RoomRegistry registry = RoomRegistry.get(level.getServer());
        workbay.bindTo(registry.create(java.util.UUID.randomUUID(), "bench",
            level.getRandom()).id());
        return workbay;
    }

    /** The player's three: pair a Connector, stick it on the target, add it to bay 1. */
    private static BusConfig connect(ExtendedGameTestHelper helper, WorkbayBlockEntity workbay,
        BlockPos at, GameTestPlayer player) {
        ServerLevel level = helper.getLevel();
        ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
        WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
            GlobalPos.of(level.dimension(), workbay.getBlockPos()));
        BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
            .setValue(ConnectorBlock.FACING, Direction.DOWN);
        level.setBlock(at, state, Block.UPDATE_ALL);
        WBBlocks.CONNECTOR.get().setPlacedBy(level, at, state, player, connector);
        GlobalPos here = GlobalPos.of(level.dimension(), at);
        com.neryos.workbay.menu.WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
            workbay.connectorAt(here).orElseThrow(() ->
                new GameTestAssertException("placing a paired Connector registered none")).id(), 0);
        List<BusConfig> links = workbay.buses();
        if (links.isEmpty()) {
            throw new GameTestAssertException("adding the Connector to a bay made no channel");
        }
        return links.get(links.size() - 1);
    }

    /** Sources full, bays empty: the state each window starts from, so all five are the same run. */
    private static void refillUnits(ExtendedGameTestHelper helper, List<WorkbayBlockEntity> units) {
        ServerLevel level = helper.getLevel();
        ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        for (WorkbayBlockEntity workbay : units) {
            WorkbayRecord record = workbay.record().orElseThrow();
            for (int bay = 0; bay < LINKS; bay++) {
                empty(backshop, BayGeometry.machinePos(record.bayColumn(), bay));
                fill(level, workbay.getBlockPos().offset(2 + bay, 0, 0), 8);
            }
        }
    }

    /** Tickets this mod holds, overworld and Backshop, for the report to read beside a delta. */
    private static String tickets(ServerLevel level) {
        ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        return "overworld " + WorkbayTickets.held(level) + " backshop "
            + (backshop == null ? 0 : WorkbayTickets.held(backshop));
    }

    private static void fill(ServerLevel level, BlockPos pos, int stacks) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < stacks && slot < container.getContainerSize(); slot++) {
                container.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
        }
    }

    private static void empty(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.clearContent();
        }
    }

    /** Fuel and ore, so the furnace is doing a furnace's work rather than sitting cold. */
    private static void light(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container furnace) {
            furnace.clearContent();
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
            furnace.setItem(1, new ItemStack(Items.COAL, 64));
        }
    }

    /** Everything the links have delivered into the bays so far. */
    private static int delivered(ExtendedGameTestHelper helper, List<WorkbayBlockEntity> units) {
        ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
        int total = 0;
        for (WorkbayBlockEntity workbay : units) {
            WorkbayRecord record = workbay.record().orElseThrow();
            for (int bay = 0; bay < LINKS; bay++) {
                total += count(backshop, BayGeometry.machinePos(record.bayColumn(), bay));
            }
        }
        return total;
    }

    private static int countAll(ServerLevel level, List<BlockPos> positions) {
        int total = 0;
        for (BlockPos pos : positions) {
            total += count(level, pos);
        }
        return total;
    }

    /** Iron the racked furnaces have made, which is the work that scenario is comparing. */
    private static int smelted(ServerLevel backshop, List<BlockPos> furnaces) {
        int total = 0;
        for (BlockPos pos : furnaces) {
            if (backshop.getBlockEntity(pos) instanceof Container furnace) {
                total += furnace.getItem(2).getCount();
            }
        }
        return total;
    }

    private static int count(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }
}
