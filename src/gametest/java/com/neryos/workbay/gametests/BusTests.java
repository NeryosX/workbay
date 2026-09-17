package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.content.workbay.WorkbayState;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.FaceConfig;
import com.neryos.workbay.world.RedstoneMode;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.Optional;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/**
 * The mod's central promise, end to end: a machine standing in another dimension, reached from a
 * block in this one, with no cable in between. SPEC.md §9.
 *
 * <p>Every link here is made the way a player makes one - a Connector paired to the Workbay and
 * stuck on the target block. There is no other way to make one, which is the point: if the
 * Connector path breaks, none of these pass.
 */
@ForEachTest(groups = "bus")
public class BusTests {

    /** Places a Workbay, gives it a bay with a chest in it, and returns the block entity. */
    // A Mekanism machine measures its work in hundreds of ticks, where a furnace measures it in
    // dozens, so the default hundred-tick budget expires while the line is running correctly.
    @GameTest(timeoutTicks = 1200)
    @TestHolder(description = "Two chests outside feed a Mekanism machine in a bay through links "
        + "alone, a racked energy cube powers it through a third, and a fourth banks the alloy in "
        + "a chest outside. Nothing is placed into a slot by hand.")
    public static void fourLinksRunAMekanismLineFromOutsideTheBay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 9));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos dustChest = helper.absolutePos(new BlockPos(5, 1, 0));
            BlockPos ingotChest = helper.absolutePos(new BlockPos(5, 1, 2));
            BlockPos outChest = helper.absolutePos(new BlockPos(5, 1, 4));
            BlockPos cube = helper.absolutePos(new BlockPos(5, 1, 6));

            Block infuser = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("mekanism:metallurgic_infuser"));
            Block energyCube = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("mekanism:basic_energy_cube"));
            net.minecraft.world.item.Item alloy = BuiltInRegistries.ITEM.get(
                ResourceLocation.parse("mekanism:alloy_infused"));
            helper.assertFalse(infuser == Blocks.AIR, "mekanism:metallurgic_infuser is not "
                + "registered - check the gametestRuntimeOnly Mekanism dependency in build.gradle");

            for (BlockPos at : new BlockPos[] {dustChest, ingotChest, outChest}) {
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            }
            if (level.getBlockEntity(dustChest) instanceof Container dustIn) {
                dustIn.setItem(0, new ItemStack(Items.REDSTONE, 32));
            }
            if (level.getBlockEntity(ingotChest) instanceof Container ingotsIn) {
                ingotsIn.setItem(0, new ItemStack(Items.COPPER_INGOT, 8));
            }

            // The power is the one thing this test is allowed to conjure, and it still has to
            // travel: the cube stands outside with the chests and reaches the machine only through
            // a link. Placed the long way on purpose -- a setBlock alone leaves a Mekanism cube
            // exposing energy on no face at all, which cost a run to find out.
            BlockState cubeState = energyCube.defaultBlockState();
            level.setBlock(cube, cubeState, Block.UPDATE_ALL);
            net.minecraft.world.item.BlockItem.updateCustomBlockEntityTag(level, player, cube,
                new ItemStack(energyCube));
            if (level.getBlockEntity(cube) != null) {
                level.getBlockEntity(cube).applyComponentsFromItemStack(new ItemStack(energyCube));
            }
            energyCube.setPlacedBy(level, cube, cubeState, player, new ItemStack(energyCube));
            level.invalidateCapabilities(cube);
            int charged = 0;
            for (Direction side : Direction.values()) {
                var store = level.getCapability(Capabilities.EnergyStorage.BLOCK, cube, side);
                if (store != null) {
                    charged = store.receiveEnergy(200_000, false);
                    if (charged > 0) {
                        break;
                    }
                }
            }
            helper.assertFalse(charged <= 0, "could not charge the Energy Cube through any face, "
                + "so there is nothing for the link to carry");

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(infuser));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            BusConfig dust = connect(helper, workbay, dustChest.above(), Direction.DOWN, player);
            workbay.addBus(dust.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("minecraft:redstone"))));
            BusConfig copper = connect(helper, workbay, ingotChest.above(), Direction.DOWN, player);
            workbay.addBus(copper.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("minecraft:copper_ingot"))));
            BusConfig out = connect(helper, workbay, outChest.above(), Direction.DOWN, player);
            workbay.addBus(out.withMode(BusConfig.Mode.INSERT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("mekanism:alloy_infused"))));

            // Deliberately left at the rate and speed a link is born with, because that is what a
            // player gets: a hand-tuned 64-per-tick link proved the plumbing and hid that the
            // default was two orders of magnitude short of running anything.
            BusConfig power = connect(helper, workbay, cube.above(), Direction.DOWN, player);
            workbay.addBus(power.withResource(BusConfig.Resource.ENERGY)
                .withMode(BusConfig.Mode.EXTRACT));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    int made = countIn(level, outChest, alloy);
                    if (made < 2) {
                        throw new GameTestAssertException("the line has banked " + made
                            + " of 2 alloy. links read " + workbay.busStatus(dust.id()) + "/"
                            + workbay.busStatus(copper.id()) + "/" + workbay.busStatus(out.id())
                            + "/" + workbay.busStatus(power.id())
                            + "; inside=" + inside(backshop, machinePos)
                            + "; chests hold " + countIn(level, dustChest, Items.REDSTONE)
                            + " redstone and " + countIn(level, ingotChest, Items.COPPER_INGOT)
                            + " copper; the cube offers"
                            + energyFaces(level, cube));
                    }
                })
                .thenExecute(() -> {
                    // Every ingot that left the chest is accounted for by the line rather than by
                    // a link shovelling it straight through to the output.
                    helper.assertValueEqual(countIn(level, outChest, Items.COPPER_INGOT), 0,
                        "copper ingots that reached the output chest");
                    helper.assertValueEqual(countIn(level, outChest, Items.REDSTONE), 0,
                        "redstone that reached the output chest");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /** Which faces of a block will hand energy out, read the way a link reads them. */
    private static String energyFaces(ServerLevel level, BlockPos pos) {
        StringBuilder out = new StringBuilder();
        for (Direction face : Direction.values()) {
            var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, face);
            out.append(' ').append(face.getName()).append('=')
                .append(energy == null ? "-" : energy.canExtract() + "/" + energy.getEnergyStored());
        }
        return out.toString();
    }

    @GameTest
    @TestHolder(description = "An Impeller doubles what every link on the Workbay moves in a step "
        + "and halves the wait between steps, so the same link carries four times as much.")
    public static void anImpellerRaisesBothHalvesOfWhatALinkCarries(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos source = helper.absolutePos(new BlockPos(5, 1, 0));

            level.setBlock(source, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(source) instanceof Container chest) {
                chest.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            RoomRegistry.get(level.getServer()).put(record.withUpgrades(
                new WorkbayRecord.Upgrades(0, 0, 0, 1)));
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            // Rate one, so what arrives is the multiplier and nothing else.
            BusConfig link = connect(helper, workbay, source.above(), Direction.DOWN, player);
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(1).withSpeed(10));

            helper.startSequence()
                // Long enough for several steps, because half of what an Impeller buys is how
                // often a step comes round -- a window, not a single move.
                .thenIdle(21)
                .thenExecute(() -> {
                    // Unfitted this link moves one a step every ten ticks: three in this window.
                    // Fitted it moves two every five: ten. Eight is clear of both.
                    int moved = countIn(backshop, machinePos, Items.IRON_INGOT);
                    helper.assertTrue(moved >= 8,
                        "the link carried " + moved + " iron with an Impeller fitted, which is "
                            + "what it would have carried with none: the upgrade is not reaching "
                            + "the link");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /** What a hosted machine is actually holding, read the way a link reads it. */
    private static String inside(ServerLevel backshop, BlockPos machinePos) {
        IItemHandler handler = backshop.getCapability(Capabilities.ItemHandler.BLOCK, machinePos, null);
        var energy = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, machinePos, null);
        StringBuilder out = new StringBuilder();
        if (handler == null) {
            out.append("no handler");
        } else {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).isEmpty()) {
                    out.append(' ').append(slot).append(':').append(handler.getStackInSlot(slot));
                }
            }
            if (out.isEmpty()) {
                out.append("empty");
            }
        }
        return out + ", energy=" + (energy == null ? "none" : energy.getEnergyStored());
    }

    /**
     * SPEC.md §7. The block is the only thing in the mod that answers "is something wrong?" without
     * being opened, and until this test the {@code state} property existed, generated twenty-four
     * blockstate variants, and was <b>never written by anything</b>: every Workbay in every world
     * was {@code idle} forever.
     *
     * <p><b>One Workbay, read twice.</b> This used to place two and assert that they differed, and
     * that is a distinction the mod does not make: links live on the {@link WorkbayRecord}, a second
     * Workbay placed by the same player joins the same record (§14), and every Workbay on a record
     * ticks <em>every</em> bus on it. Both blocks therefore always read the same thing. The old test
     * passed only on the single tick the shared bay ran dry - one block's runner had already
     * re-read the dead link as IDLE while the other still held a stale problem - which is why it
     * went red two runs in four. OPEN_ISSUES #39 is the behaviour it was accidentally documenting.
     *
     * <p>The "one constant" guard survives the change: a {@code refreshLitState} that always writes
     * RUNNING hangs on the second wait, one that always writes STUCK or IDLE hangs on the first.
     *
     * <p>Nothing here waits on a quantity running out. The source holds three stacks, which is more
     * iron than this test's whole timeout can move, and the dead link is an <b>EXTRACT</b> - it
     * reads its unreachable target first and so reports the same thing whatever the bay holds.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A Workbay reads running while a link moves, and stuck once one cannot reach.")
    public static void theBlockSaysRunningOrStuckWithoutBeingOpened(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);

            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(0, 1, 4));
            // A block with no item capability on any face. The Connector still attaches to it, so
            // the link exists and reports TARGET_NO_PORT -- the live case a player hits by pairing
            // a Connector onto the wrong block, which is what stuck is for.
            BlockPos deadPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(deadPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));

            BlockPos sourcePos = BayGeometry.machinePos(workbay.record().orElseThrow().bayColumn(), 0);
            if (!(backshop.getBlockEntity(sourcePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a chest");
                return;
            }
            // Three stacks at one item per five ticks is 960 ticks of iron against a 900-tick
            // timeout, so the link cannot finish inside this test and RUNNING cannot lapse into
            // IDLE underneath an assertion. The old single stack ran out after 320.
            hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            hosted.setItem(1, new ItemStack(Items.IRON_INGOT, 64));
            hosted.setItem(2, new ItemStack(Items.IRON_INGOT, 64));

            workbay.addBus(connect(helper, workbay, chestPos.above(), Direction.DOWN, player)
                .withRate(1).withSpeed(5));

            boolean[] sawRunning = {false};

            helper.startSequence()
                .thenWaitUntil(() -> {
                    WorkbayState lit = level.getBlockState(workbayPos).getValue(WorkbayBlock.STATE);
                    if (lit != WorkbayState.RUNNING) {
                        throw new GameTestAssertException("a Workbay whose link is moving iron "
                            + "still reads " + lit.getSerializedName() + ". Nothing writes "
                            + "WorkbayBlock.STATE, so the block cannot say anything across a room.");
                    }
                    sawRunning[0] = true;
                })
                // Only now, so the two readings cannot overlap: while this link exists the block
                // has a problem to report and RUNNING can never be seen again.
                .thenExecute(() -> workbay.addBus(
                    connect(helper, workbay, deadPos.above(), Direction.DOWN, player)
                        .withMode(BusConfig.Mode.EXTRACT).withRate(1).withSpeed(5)))
                .thenWaitUntil(() -> {
                    WorkbayState lit = level.getBlockState(workbayPos).getValue(WorkbayBlock.STATE);
                    if (lit != WorkbayState.STUCK) {
                        throw new GameTestAssertException("a Workbay whose link cannot reach its "
                            + "target reads " + lit.getSerializedName() + ", not stuck");
                    }
                })
                .thenExecute(() -> {
                    // The pair, together: a driver that wrote one constant would pass one of the
                    // two waits above and hang on the other, and this says which reading is which.
                    helper.assertTrue(sawRunning[0],
                        "the Workbay read running while its link was moving iron");
                    helper.assertValueEqual(
                        level.getBlockState(workbayPos).getValue(WorkbayBlock.STATE),
                        WorkbayState.STUCK, "the lit state of the Workbay that cannot reach");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * SPEC.md §7, the half the frame cannot carry. The lit state answers "is anything wrong?" for
     * the whole bay; these answer "which link", which is the question a player asks second. Eight
     * independent lamps is 65,536 combinations, so it can never be a blockstate - it is a byte
     * array on the update tag and {@code WorkbayPips} draws it.
     *
     * <p>Two links, and the assertion is that they differ. A {@code pipFor} rewritten to return one
     * constant passes a length check and every "is it OK" check written on its own, and fails this.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "The front of a Workbay carries one status pip per link, in the list's order.")
    public static void theFrontOfTheBlockShowsOneStatusPerLink(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(0, 1, 4));
            // Stone: the Connector attaches, so the link exists, and the target has no item
            // capability on any face. The live case a player hits by pairing onto the wrong block.
            BlockPos deadPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(deadPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            // Both links share bay 0, and the bay is the source. With nothing in it every link
            // reports IDLE without ever resolving its target, so both pips would read OK and the
            // test would be measuring an empty chest rather than a status.
            BlockPos sourcePos = BayGeometry.machinePos(workbay.record().orElseThrow().bayColumn(), 0);
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            if (!(backshop.getBlockEntity(sourcePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a chest");
                return;
            }
            hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));

            // Speed 5, like theBlockSaysRunningOrStuckWithoutBeingOpened: a link at the default
            // speed does not come round on the wheel inside a gametest's patience, so its status
            // stays at the never-run default and both pips read OK for the wrong reason.
            workbay.addBus(connect(helper, workbay, chestPos.above(), Direction.DOWN, player)
                .withRate(1).withSpeed(5));
            BusConfig unreachable = connect(helper, workbay, deadPos.above(), Direction.DOWN, player)
                .withRate(1).withSpeed(5);
            workbay.addBus(unreachable);

            helper.startSequence()
                .thenWaitUntil(() -> {
                    byte[] pips = workbay.pips();
                    if (pips.length != 2) {
                        throw new GameTestAssertException("a Workbay with two links shows "
                            + pips.length + " pips, not 2");
                    }
                    if (pips[0] == pips[1]) {
                        throw new GameTestAssertException("both pips read "
                            + WorkbayBlockEntity.Pip.VALUES[pips[0]]
                            + ", so the front of the block cannot tell a link that works from one "
                            + "that cannot reach its target");
                    }
                })
                .thenExecute(() -> {
                    byte[] pips = workbay.pips();
                    helper.assertValueEqual(WorkbayBlockEntity.Pip.VALUES[pips[0]],
                        WorkbayBlockEntity.Pip.OK, "the pip of the link pointed at a chest");
                    helper.assertValueEqual(WorkbayBlockEntity.Pip.VALUES[pips[1]],
                        WorkbayBlockEntity.Pip.ATTENTION,
                        "the pip of the link pointed at a block with no port");
                    // The pips exist to be looked at, and nothing else this block entity holds is
                    // sent to a client. If they are not on the update tag they are invisible.
                    byte[] sent = workbay.getUpdateTag(level.registryAccess()).getByteArray("Pips");
                    helper.assertValueEqual(sent.length, 2, "the pips on the update tag");
                })
                .thenExecute(() -> workbay.addBus(unreachable.withEnabled(false)))
                .thenWaitUntil(() -> {
                    byte[] pips = workbay.pips();
                    if (pips.length != 2 || WorkbayBlockEntity.Pip.VALUES[pips[1]]
                        != WorkbayBlockEntity.Pip.NONE) {
                        throw new GameTestAssertException("a link the player switched off still "
                            + "lights its pip, so a bay held on purpose reads as a bay with a fault");
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    private static WorkbayBlockEntity setUp(ExtendedGameTestHelper helper, BlockPos workbayPos,
        GameTestPlayer player, ItemStack inTheBay) {
        ServerLevel level = helper.getLevel();
        level.setBlock(workbayPos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, workbayPos,
            level.getBlockState(workbayPos), player, new ItemStack(WBBlocks.WORKBAY.get()));

        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(workbayPos);
        // Powered, because a real one has to be: SPEC.md §9 charges the buffer for every
        // link that is switched on and again for every move, so an unfed Workbay runs
        // nothing and every link on it reads NO_POWER. OPEN_ISSUES #72. A test that is not
        // about the bill pays it up front and says so here.
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        WorkbayRecord record = workbay.record().orElseThrow();

        ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
        // Deliberately NOT force-loaded here. SPEC.md §12's mirroring is the mod's job, and while
        // this line was in the harness it did that job on the mod's behalf - so every test passed
        // against a bay column no real world ever loads.
        BayHosting.rack(backshop, record.bayColumn(), 0, inTheBay, player, Direction.NORTH);
        return workbay;
    }

    /**
     * Pairs a Connector to the Workbay, sticks it on the block below {@code at}, adds it to bay 1
     * and returns the channel that made. Mirrors the player's three actions exactly: right-click
     * the Workbay with the Connector, place it against the thing you want reached, then press Add
     * on the bay and tick it.
     */
    private static BusConfig connect(ExtendedGameTestHelper helper, WorkbayBlockEntity workbay,
        BlockPos at, Direction facing, GameTestPlayer player) {
        ServerLevel level = helper.getLevel();
        ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
        WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
            GlobalPos.of(level.dimension(), workbay.getBlockPos()));

        BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
            .setValue(ConnectorBlock.FACING, facing);
        level.setBlock(at, state, Block.UPDATE_ALL);
        WBBlocks.CONNECTOR.get().setPlacedBy(level, at, state, player, connector);

        // A new channel is switched off, so nothing moves before the player has looked at the row.
        // These tests are about what moves once it is on, so they turn it on the way a player does.
        // aNewLinkStartsSwitchedOff asserts the default itself. And <b>bay to target</b>, which
        // is how every test here was written before a channel was born EXTRACT (OPEN_ISSUES #56);
        // aNewChannelIsBornPullingIntoTheBay asserts that default itself.
        return addChannel(helper, workbay, GlobalPos.of(level.dimension(), at), 0)
            .withEnabled(true).withMode(BusConfig.Mode.INSERT);
    }

    /**
     * The player's third action, and the one that makes a channel: open the bay, press Add,
     * tick the Connector. Placing one no longer mints anything (SPEC.md §0), so a fixture that
     * wants a channel has to ask for one -- through the very method the button calls.
     */
    private static com.neryos.workbay.bus.BusConfig addChannel(ExtendedGameTestHelper helper,
        WorkbayBlockEntity workbay, net.minecraft.core.GlobalPos at, int bay) {
        var known = workbay.connectorAt(at).orElseThrow(() ->
            new IllegalStateException("placing a paired Connector did not register it"));
        int before = workbay.buses().size();
        com.neryos.workbay.menu.WorkbayMenu.addChannel(workbay,
            workbay.record().orElseThrow(), known.id(), bay);
        helper.assertValueEqual(workbay.buses().size(), before + 1,
            "channels after adding the Connector at " + at.pos() + " to bay " + (bay + 1));
        return workbay.buses().getLast();
    }

    private static void tearDown(ExtendedGameTestHelper helper, BlockPos workbayPos) {
        ServerLevel level = helper.getLevel();
        if (level.getBlockEntity(workbayPos) instanceof WorkbayBlockEntity workbay) {
            workbay.record().ifPresent(record -> {
                ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
                BayHosting.eject(backshop, record.bayColumn(), 0, null);
            });
        }
        level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static int countIn(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            return -1;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    /**
     * The guard on {@link com.neryos.workbay.bus.BusTransfer}'s refusal memo. A destination that
     * says no to one item must still be offered the next one, and the memo is what could break
     * that: it exists because a full destination was being asked to take the same cobblestone once
     * per source slot, which measured four times the cost of a link that was delivering.
     *
     * <p>A brewing stand is the cheapest destination there is that refuses one thing and takes
     * another - its ingredient slot accepts nether wart and no face of it accepts cobblestone - so
     * the source offers the refused item first and the wanted one second, in that order.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A destination that refuses one item is still offered the next one.")
    public static void aDestinationThatRefusesOneItemIsStillOfferedTheNext(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(targetPos) instanceof Container source) {
                // Slot order is the whole test: the refused item has to be asked about first.
                source.setItem(0, new ItemStack(Blocks.COBBLESTONE, 64));
                source.setItem(1, new ItemStack(Items.NETHER_WART, 16));
            }
            WorkbayBlockEntity workbay =
                setUp(helper, workbayPos, player, new ItemStack(Blocks.BREWING_STAND));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(backshop, machinePos, Items.NETHER_WART) <= 0) {
                        throw new GameTestAssertException("no nether wart has reached the brewing "
                            + "stand; the cobblestone in the slot before it is being taken as an "
                            + "answer for the whole source");
                    }
                })
                .thenExecute(() -> {
                    // The other half: the refused item must still be refused, or the memo would be
                    // hiding a destination that quietly accepts anything.
                    helper.assertValueEqual(countIn(backshop, machinePos, Items.COBBLESTONE), 0,
                        "cobblestone in a brewing stand");
                    tearDown(helper, workbayPos);
                })
                .thenSucceed();
        });
    }

    /**
     * <b>The Resonator, which for the whole project bought nothing.</b> SPEC.md §1 sells it as
     * "links may target other dimensions" and it was priced, crafted, installed, saved, drawn on
     * the upgrades page - and read by no code anywhere, so a player spent an ender eye
     * on a capability they already had. Found by reading the ladder for what each rung is worth.
     *
     * <p>Both halves, because a gate that refuses everything is not a gate. The same link is
     * asked twice with nothing changed but the upgrade counter, so the counter is the only thing
     * the answer can have come from. The Backshop is exempt by §1 - a bay-to-bay link and a
     * Connector inside your own room are inside the machine, not a reach across the world - which
     * is what {@code aBayToBayLinkMovesItemsWithNoConnector} keeps honest next door.
     */
    @GameTest
    @TestHolder(description = "A link into another dimension reports Needs a Resonator until one is installed.")
    public static void aCrossDimensionLinkWaitsForAResonator(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord record = workbay.record().orElseThrow();
            helper.assertValueEqual(record.upgrades().resonators(), 0,
                "resonators on a fresh network");

            // A Connector that claims to be standing in the nether. The block need not be there:
            // the gate runs before anything is resolved, which is the point of it -- a link that
            // cannot legally reach says so instead of quietly reporting the target unloaded.
            BusConfig link = BusConfig.create(java.util.UUID.randomUUID(), 0,
                BusConfig.Resource.ITEM, BusConfig.Mode.INSERT,
                GlobalPos.of(net.minecraft.world.level.Level.NETHER, new BlockPos(0, 64, 0)),
                GlobalPos.of(net.minecraft.world.level.Level.NETHER, new BlockPos(0, 64, 1)))
                .withEnabled(true).withSpeed(10);
            workbay.addBus(link);

            helper.startSequence()
                .thenIdle(12)
                .thenExecute(() -> helper.assertValueEqual(workbay.busStatus(link.id()),
                    BusRunner.BusStatus.NEEDS_RESONATOR,
                    "the status of an off-world link with no Resonator"))
                .thenExecute(() -> {
                    WorkbayRecord now = workbay.record().orElseThrow();
                    WorkbayRecord.Upgrades up = now.upgrades();
                    registry.put(now.withUpgrades(new WorkbayRecord.Upgrades(up.expansionPlates(),
                        1, up.anchors(), up.impellers())));
                })
                .thenIdle(12)
                .thenExecute(() -> helper.assertFalse(
                    workbay.busStatus(link.id()) == BusRunner.BusStatus.NEEDS_RESONATOR,
                    "the link still wants a Resonator with one installed, so the upgrade buys "
                        + "nothing after all"))
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The whole thing in one test. A chest in a bay in the Backshop, a chest on the floor in the
     * overworld, and a bus that moves iron from one to the other across a dimension boundary with
     * nothing physical connecting them.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "An insert bus moves items out of a hosted machine into a chest in another dimension.")
    public static void insertBusMovesItemsAcrossDimensions(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));

            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (!(backshop.getBlockEntity(machinePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a chest");
                return;
            }
            hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) < 64) {
                        throw new GameTestAssertException("the bus has moved "
                            + countIn(level, targetPos, Items.IRON_INGOT) + " of 64 iron so far");
                    }
                })
                .thenExecute(() -> {
                    // Everything has to arrive, and nothing may be created on the way.
                    helper.assertValueEqual(countIn(level, targetPos, Items.IRON_INGOT), 64,
                        "iron in the target chest");
                    int left = 0;
                    for (int slot = 0; slot < hosted.getContainerSize(); slot++) {
                        left += hosted.getItem(slot).getCount();
                    }
                    helper.assertValueEqual(left, 0, "items left in the hosted chest");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "An extract bus pulls items out of a chest into a hosted machine.")
    public static void extractBusPullsItemsIntoAHostedMachine(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(targetPos) instanceof Container source) {
                source.setItem(0, new ItemStack(Items.GOLD_INGOT, 32));
            }
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.BARREL));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(16).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(backshop, machinePos, Items.GOLD_INGOT) < 32) {
                        throw new GameTestAssertException("the bus has pulled "
                            + countIn(backshop, machinePos, Items.GOLD_INGOT) + " of 32 gold so far");
                    }
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(backshop, machinePos, Items.GOLD_INGOT), 32,
                        "gold in the hosted barrel");
                    helper.assertValueEqual(countIn(level, targetPos, Items.GOLD_INGOT), 0,
                        "gold left in the source chest");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * Rate and speed are the two numbers a player sets, so they have to mean exactly what the screen
     * says. A bus that quietly moves more than its rate is a bus nobody can plan around.
     */
    /**
     * OPEN_ISSUES #72. The Workbay's buffer was drawn on three screens, saved to disk, restored off
     * the item it was broken into -- and asked for by nothing. Links moved goods for ever on an
     * empty one, which made the bar a decoration and the whole of SPEC.md §9 a paragraph.
     *
     * <p>Both halves, because only one of them is a regression that can be seen: an empty buffer
     * moves <b>nothing</b> and every link says why, and the same rig with the buffer full moves
     * what it always did. Without the second half this test passes just as well against a mod
     * where links never work at all.
     *
     * <p><b>Both power draws ship at zero, so this test turns them on and says so.</b> A mod that needs FE
     * before it does anything is inert in an install with no energy mod, and only a pack author
     * knows whether there is one -- so the cost is opt-in and the default is free. It asserts the
     * shipped zeros before writing XNet's own numbers over them, which is what stops it going
     * vacuous in either direction: a mod that started charging by default would fail here, and so
     * would one that stopped charging when asked to.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A link with an empty buffer moves nothing, and moves again once it is fed.")
    public static void anEmptyBufferMovesNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }
            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(4).withSpeed(20));

            var config = com.neryos.workbay.config.WorkbayConfig.SERVER;
            int wasStanding = config.powerPerLinkPerTick.get();
            int wasMove = config.powerPerMove.get();
            helper.assertValueEqual(wasStanding, 0, "the shipped standing draw per link per tick");
            helper.assertValueEqual(wasMove, 0, "the shipped draw per move");
            config.powerPerLinkPerTick.set(1);
            config.powerPerMove.set(2);

            // setUp pays the bill for every other test here. This one is the bill.
            workbay.energy().deserializeNBT(null, net.minecraft.nbt.IntTag.valueOf(0));

            helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    int moved = countIn(level, targetPos, Items.IRON_INGOT);
                    if (moved > 0) {
                        helper.fail("a link on a Workbay with an empty buffer moved " + moved
                            + " iron: the buffer is drawn, saved and spent by nothing");
                    }
                    helper.assertValueEqual(workbay.busStatus(link.id()),
                        BusRunner.BusStatus.NO_POWER,
                        "the status of a link that cannot pay for its move");
                })
                .thenExecute(() -> workbay.energy().deserializeNBT(null,
                    net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE)))
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("the link never started again after the "
                            + "buffer was filled, so the draw is not a price but a wall");
                    }
                })
                // Put the shipped free-to-run defaults back, or every test that follows this one
                // in the same server pays a bill it never asked for.
                .thenExecute(() -> {
                    config.powerPerLinkPerTick.set(wasStanding);
                    config.powerPerMove.set(wasMove);
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A bus never moves more than its rate in one operation.")
    public static void busObeysItsRate(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            // The slowest legal speed, so the test can look between operations.
            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(4).withSpeed(200));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("the bus has not moved anything yet");
                    }
                })
                .thenExecute(() -> {
                    int moved = countIn(level, targetPos, Items.IRON_INGOT);
                    if (moved > 4) {
                        helper.fail("the first operation moved " + moved + " items with a rate of 4");
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The other half of {@link #busObeysItsRate}, and the half nobody wrote: <b>how often</b> a
     * step comes round. A rate is a promise per step, a speed is a promise about the gap, and a
     * link kept to its rate but stepped twice as often lies by exactly as much as one that keeps
     * its interval and moves double.
     *
     * <p>OPEN_ISSUES #54 was measured in a live world: 126 items in 61 s from a link the panel
     * printed as one item every twenty ticks. The wheel was right and the rate was right --
     * <b>two Workbay blocks were standing on one network, and each of them ran every link on it</b>
     * (#40). That is now impossible by construction: one Workbay block is one network (SPEC.md §0),
     * and the second block a player places is a second network with its own links. So this places
     * one and asserts the number the panel prints is the number that arrives -- the
     * {@code takeBusTurn} election behind it is kept as the guard that made #54 unrepeatable.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A link delivers the rate its panel prints, over two hundred ticks.")
    public static void aLinkDeliversThePrintedRateOverTime(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos secondPos = helper.absolutePos(new BlockPos(0, 1, 4));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(targetPos) instanceof Container source) {
                source.setItem(0, new ItemStack(Items.GOLD_INGOT, 64));
            }
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.BARREL));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(1).withSpeed(20));

            int[] atStart = new int[1];
            helper.startSequence()
                // Start the window on a delivery rather than on an arbitrary tick, so the count is
                // not off by whatever fraction of an interval the setup landed in.
                .thenWaitUntil(() -> {
                    if (countIn(backshop, machinePos, Items.GOLD_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing has moved yet");
                    }
                })
                .thenExecute(() -> atStart[0] = countIn(backshop, machinePos, Items.GOLD_INGOT))
                .thenIdle(200)
                .thenExecute(() -> {
                    int moved = countIn(backshop, machinePos, Items.GOLD_INGOT) - atStart[0];
                    if (moved != 10) {
                        helper.fail("a link at rate 1, speed 20 moved " + moved
                            + " items in 200 ticks; the panel promises 10");
                    }
                })
                .thenExecute(() -> {
                    level.setBlock(secondPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    tearDown(helper, workbayPos);
                })
                .thenSucceed();
        });
    }

    /**
     * Reported from play, not from reading: a chest hosted in a bay with one face set to <b>in</b>
     * refused to send anything and the row said nothing was wrong, while another row said
     * <code>No port</code> without saying which end had none.
     *
     * <p>Both halves are asserted here. A link whose direction of travel the face config forbids
     * must not report {@code IDLE} - idle means "working, nothing to do right now", and a link that
     * can never work is not idle. And a link blocked at the machine end must not report a status
     * whose name blames the target.
     *
     * <p><b>What "forbids" means changed, and this test moved with it.</b> It used to mark
     * <em>one</em> face in and expect the out direction to be closed, which is the rule
     * OPEN_ISSUES #83 was about: The owner fed a furnace through its top face in a live world and
     * could not get the cooked output back out, because marking one face in had closed all six to
     * extraction and the row said only "Idle. Nothing to move." Marking a face for one direction of
     * travel now says nothing about the other. So the way to forbid a direction is to leave it no
     * face at all - every face marked for the opposite role - and that is what this sets up.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "A face config that forbids a link's direction is reported, not silently idle.")
    public static void aFaceConfigThatBlocksALinkIsReported(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            // Every face marked in, so there is genuinely nowhere to take anything out of. One
            // face marked in used to be enough to close the other five; it is not any more (#83).
            FaceConfig shut = FaceConfig.NONE;
            for (Direction face : Direction.values()) {
                shut = shut.cycled(BusConfig.Resource.ITEM, face, false);
            }
            RoomRegistry.get(level.getServer()).put(record.withBay(record.bay(0).withFaces(shut)));
            workbay.forgetBay(0);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10));
            java.util.UUID id = link.id();

            helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    BusRunner.BusStatus status = workbay.busStatus(id);
                    if (status == BusRunner.BusStatus.IDLE) {
                        helper.fail("a link the face config forbids reported IDLE, so the player is "
                            + "told nothing is wrong while it can never move anything");
                    }
                    if (!status.isProblem()) {
                        helper.fail("a link that can never work reported " + status
                            + ", which the screen does not count as a problem");
                    }
                    helper.assertValueEqual(countIn(level, targetPos, Items.IRON_INGOT), 0,
                        "items that reached the target");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The other half of the same report: with one face set to <b>in</b>, pulling into the hosted
     * chest is exactly what the player configured, so it has to work.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "An in face lets an extract link fill the hosted machine through it.")
    public static void anInputFaceLetsAnExtractLinkThrough(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(targetPos) instanceof Container source) {
                source.setItem(0, new ItemStack(Items.GOLD_INGOT, 32));
            }
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            RoomRegistry.get(level.getServer()).put(record.withBay(
                record.bay(0).withFaces(FaceConfig.NONE.cycled(BusConfig.Resource.ITEM, Direction.WEST, false))));
            workbay.forgetBay(0);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(16).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(backshop, machinePos, Items.GOLD_INGOT) < 32) {
                        throw new GameTestAssertException("the bus has pulled "
                            + countIn(backshop, machinePos, Items.GOLD_INGOT) + " of 32 gold in "
                            + "through the one face marked in; status is "
                            + workbay.busStatus(link.id()));
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The case actually reported: <b>both</b> directions marked on the cube - one face in, the
     * opposite face out - two links on the same bay, and nothing moved. Both links are live at
     * once here, which is the part a single-link test cannot see: the machine end is one
     * {@link com.neryos.workbay.bus.BusEndpoint} shared per bay, with one bound face, and the two
     * links want different ones.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "Two links on one bay, one in face and one out face, both move.")
    public static void anInAndAnOutFaceOnOneBayBothWork(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos sendTo = helper.absolutePos(new BlockPos(6, 1, 6));
            BlockPos pullFrom = helper.absolutePos(new BlockPos(6, 1, 0));

            level.setBlock(sendTo, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pullFrom, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(pullFrom) instanceof Container source) {
                source.setItem(0, new ItemStack(Items.GOLD_INGOT, 32));
            }

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            // West in, east out. Exactly what was set in play.
            RoomRegistry.get(level.getServer()).put(record.withBay(record.bay(0).withFaces(
                FaceConfig.NONE
                    .cycled(BusConfig.Resource.ITEM, Direction.WEST, false)
                    .cycled(BusConfig.Resource.ITEM, Direction.EAST, false)
                    .cycled(BusConfig.Resource.ITEM, Direction.EAST, false))));
            workbay.forgetBay(0);

            BusConfig send = connect(helper, workbay, sendTo.above(), Direction.DOWN, player);
            workbay.addBus(send.withRate(8).withSpeed(10));
            BusConfig pull = connect(helper, workbay, pullFrom.above(), Direction.DOWN, player);
            workbay.addBus(pull.withMode(BusConfig.Mode.EXTRACT).withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    int sent = countIn(level, sendTo, Items.IRON_INGOT);
                    int pulled = countIn(backshop, machinePos, Items.GOLD_INGOT);
                    if (sent < 64 || pulled < 32) {
                        throw new GameTestAssertException("sent " + sent + "/64 iron ("
                            + workbay.busStatus(send.id()) + "), pulled " + pulled + "/32 gold ("
                            + workbay.busStatus(pull.id()) + ")");
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The filter doing its job, in the sense a player means: <b>only iron goes here</b>. SPEC.md §5.
     *
     * <p>The hosted chest holds two items and the link lists one, so a filter that is read but not
     * applied - or applied to the destination rather than the source - cannot pass.
     * {@link #aDenyFilterCarriesEverythingElse} is the same fixture with the list inverted.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A filtered link moves its item and leaves everything else behind.")
    public static void aFilteredLinkMovesOnlyItsItem(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.GOLD_INGOT, 16));
                hosted.setItem(1, new ItemStack(Items.IRON_INGOT, 16));
            }

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10).withFilter(
                com.neryos.workbay.bus.BusFilter.only(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT))));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) < 16) {
                        throw new GameTestAssertException("the filtered bus has moved "
                            + countIn(level, targetPos, Items.IRON_INGOT) + " of 16 iron so far");
                    }
                })
                // Long enough after the iron arrived that unfiltered gold would have followed it.
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(level, targetPos, Items.GOLD_INGOT), 0,
                        "gold that reached the target past an iron-only filter");
                    helper.assertValueEqual(countIn(backshop, machinePos, Items.GOLD_INGOT), 16,
                        "gold left in the hosted chest");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The mod's whole promise, with nothing staged: place a Workbay, rack a machine, point a link
     * at a chest, and it works - <b>without the test loading the Backshop chunk for itself.</b>
     *
     * <p>Reported from play. Every link on a freshly placed Workbay said the machine was
     * unreachable, because SPEC.md §12's mirroring was specified, marked built, and never written:
     * nothing in the mod ever registered a ticket, so the bay column was simply never loaded. The
     * gametests could not see it, because their own setup force-loaded the column first.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A hosted machine is reachable without anyone force-loading its chunk.")
    public static void aFreshlyPlacedWorkbayLoadsItsOwnBayColumn(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 8));
            }

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) < 8) {
                        throw new GameTestAssertException("nothing reached the chest; the link says "
                            + workbay.busStatus(link.id()) + " and the bay column is "
                            + (backshop.isLoaded(machinePos) ? "loaded" : "NOT loaded"));
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The four standard redstone modes, SPEC.md §4. Asserted as a pair per mode - held while the
     * signal is wrong, moving when it is right - because a gate that never opens and a gate that
     * never closes both pass a one-sided test.
     */
    @GameTest(timeoutTicks = 1200)
    @TestHolder(description = "A bay set to run with a signal waits for one, then runs.")
    public static void withASignalHoldsUntilThereIsOne(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos leverPos = workbayPos.above();

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
            }

            RoomRegistry.get(level.getServer()).put(record.withBay(
                record.bay(0).withRedstone(RedstoneMode.WITH_SIGNAL)));

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10));

            helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    // Half of the test: with no signal it must not have moved anything at all.
                    helper.assertValueEqual(countIn(level, targetPos, Items.IRON_INGOT), 0,
                        "items moved by a bay waiting for a signal it has not got");
                    helper.assertValueEqual(workbay.busStatus(link.id()),
                        BusRunner.BusStatus.HELD_BY_REDSTONE, "the held link's status");
                })
                // A redstone block on top is the shortest way to a real neighbour signal.
                .thenExecute(() -> level.setBlock(leverPos,
                    Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) < 16) {
                        throw new GameTestAssertException("powered, the bus has moved "
                            + countIn(level, targetPos, Items.IRON_INGOT) + " of 16; status is "
                            + workbay.busStatus(link.id()));
                    }
                })
                .thenExecute(() -> level.setBlock(leverPos, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL))
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * Pulse is the one mode with state, so it is the one that can leak: an edge that is never spent
     * turns pulse into always, and an edge spent twice turns one click into two loads.
     */
    @GameTest(timeoutTicks = 1200)
    @TestHolder(description = "Pulse moves one load per rising edge and then stops again.")
    public static void pulseMovesOncePerRisingEdge(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos leverPos = workbayPos.above();

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            RoomRegistry.get(level.getServer()).put(record.withBay(
                record.bay(0).withRedstone(RedstoneMode.PULSE)));

            // Rate 4, so one operation is unmistakably four items and not "some".
            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(4).withSpeed(10));

            helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> helper.assertValueEqual(
                    countIn(level, targetPos, Items.IRON_INGOT), 0,
                    "items moved before any edge"))
                .thenExecute(() -> level.setBlock(leverPos,
                    Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(60)
                .thenExecute(() -> helper.assertValueEqual(
                    countIn(level, targetPos, Items.IRON_INGOT), 4,
                    "items moved by one rising edge at a rate of 4"))
                // Still held high. A level, not an edge, so nothing more may move.
                .thenIdle(80)
                .thenExecute(() -> helper.assertValueEqual(
                    countIn(level, targetPos, Items.IRON_INGOT), 4,
                    "items moved while the signal simply stayed on"))
                .thenExecute(() -> level.setBlock(leverPos, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL))
                .thenIdle(20)
                .thenExecute(() -> level.setBlock(leverPos,
                    Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(60)
                .thenExecute(() -> helper.assertValueEqual(
                    countIn(level, targetPos, Items.IRON_INGOT), 8,
                    "items moved after a second rising edge"))
                .thenExecute(() -> level.setBlock(leverPos, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL))
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * Placing a Connector used to start a link running immediately, which emptied a chest into the
     * wrong machine before the row had been read. A link is made switched off now.
     */
    @GameTest
    @TestHolder(description = "A link made by placing a Connector starts switched off.")
    public static void aNewLinkStartsSwitchedOff(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
            }

            connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            BusConfig asCreated = workbay.buses().get(0);
            if (asCreated.enabled()) {
                helper.fail("a link made by placing a Connector was already running");
                return;
            }

            helper.startSequence()
                .thenIdle(80)
                .thenExecute(() -> helper.assertValueEqual(
                    countIn(level, targetPos, Items.IRON_INGOT), 0,
                    "items moved by a link nobody switched on"))
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * OPEN_ISSUES #56, the owner's call. The commonest first channel anybody makes is a chest of ore
     * into a furnace; born INSERT it moved the furnace's output the other way, which on the first
     * thing a new player builds reads as nothing happening. So a channel is born pulling from its
     * target into the bay. The flip is still one click; this pins which way the click starts.
     */
    @GameTest
    @TestHolder(description = "A channel made with Add is born pulling from its target into the bay.")
    public static void aNewChannelIsBornPullingIntoTheBay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.FURNACE));

            ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
                GlobalPos.of(level.dimension(), workbayPos));
            BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(targetPos.above(), state, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, targetPos.above(), state, player, connector);

            BusConfig born = addChannel(helper, workbay,
                GlobalPos.of(level.dimension(), targetPos.above()), 0);
            helper.assertValueEqual(born.mode(), BusConfig.Mode.EXTRACT,
                "the direction a channel is born with");
            tearDown(helper, workbayPos);
            helper.succeed();
        });
    }

    /**
     * Bay to bay, no Connector at all - SPEC.md §0's "no physical anchor" rejection was about links
     * that leave the Workbay, and this one never does. Two bays in the same column, an internal
     * link from one to the other, and it has to move items exactly like an external one - including
     * never reporting CONNECTOR_GONE, which is the trap an internal link falls into if the "am I
     * still anchored" check ever runs for it: its connector field is the Workbay's own position, and
     * that position is never going to hold a Connector block.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A bay-to-bay link moves items with no Connector anywhere.")
    public static void aBayToBayLinkMovesItemsWithNoConnector(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord record = workbay.record().orElseThrow();
            // A second bay to link to. Racking directly, the way setUp racks bay 0, rather than
            // going through an Expansion Plate item this test does not need to own.
            registry.put(record.withUpgrades(new WorkbayRecord.Upgrades(1, 0, 0, 0)));
            record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BayHosting.rack(backshop, record.bayColumn(), 1, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH);

            BlockPos bay0 = BayGeometry.machinePos(record.bayColumn(), 0);
            BlockPos bay1 = BayGeometry.machinePos(record.bayColumn(), 1);
            if (backshop.getBlockEntity(bay0) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            // stillValid is a distance check; setUp never moves the mock player near the
            // Workbay because nothing in this file went through the menu before this test.
            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay,
                WorkbayMenu.build(workbay, player, 0));
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.CREATE_INTERNAL_LINK, 0, Optional.empty());

            BusConfig created = workbay.buses().stream().filter(BusConfig::internal).findFirst()
                .orElse(null);
            if (created == null) {
                helper.fail("CREATE_INTERNAL_LINK made no internal link");
                return;
            }
            helper.assertValueEqual(created.target().pos(), bay1, "the internal link's target bay");
            menu.act(WorkbayAction.LINK_TOGGLE_ENABLED, 0, Optional.of(created.id()));
            workbay.addBus(workbay.bus(created.id()).orElseThrow().withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(backshop, bay1, Items.IRON_INGOT) < 64) {
                        throw new GameTestAssertException("the internal link has moved "
                            + countIn(backshop, bay1, Items.IRON_INGOT) + " of 64 iron so far; "
                            + "status is " + workbay.busStatus(created.id()));
                    }
                })
                .thenExecute(() -> {
                    if (workbay.busStatus(created.id()) == BusRunner.BusStatus.CONNECTOR_GONE) {
                        helper.fail("an internal link reported CONNECTOR_GONE - it has no "
                            + "Connector to be gone");
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * The other end of the same cache, and the one that shipped without a guard. OPEN_ISSUES #25.
     *
     * <p>{@code addBus} drops a link's cached endpoint on every replacement because an edit can
     * move either end. {@code assigningALinkToAnotherBayMovesWhereItPullsFrom} covers the source
     * end; this is the target end, which is a different map keyed the same way: an internal link
     * pointed at bay 2, cycled to bay 3, has to <b>deliver</b> to bay 3. Without the drop it keeps
     * pushing into the bay it used to point at while every screen names the new one.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "Retargeting an internal link moves where it actually delivers.")
    public static void retargetingAnInternalLinkMovesWhereItDelivers(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord record = workbay.record().orElseThrow();
            // Two Expansion Plates: bay 0 is the source, bays 1 and 2 are the two targets.
            registry.put(record.withUpgrades(new WorkbayRecord.Upgrades(2, 0, 0, 0)));
            record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BayHosting.rack(backshop, record.bayColumn(), 1, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH);
            BayHosting.rack(backshop, record.bayColumn(), 2, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH);

            BlockPos bay0 = BayGeometry.machinePos(record.bayColumn(), 0);
            BlockPos bay1 = BayGeometry.machinePos(record.bayColumn(), 1);
            BlockPos bay2 = BayGeometry.machinePos(record.bayColumn(), 2);
            if (backshop.getBlockEntity(bay0) instanceof Container hosted) {
                hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            }

            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay,
                WorkbayMenu.build(workbay, player, 0));
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.CREATE_INTERNAL_LINK, 0, Optional.empty());
            BusConfig created = workbay.buses().stream().filter(BusConfig::internal).findFirst()
                .orElse(null);
            if (created == null) {
                helper.fail("CREATE_INTERNAL_LINK made no internal link");
                return;
            }
            helper.assertValueEqual(created.target().pos(), bay1, "the link's first target bay");
            menu.act(WorkbayAction.LINK_TOGGLE_ENABLED, 0, Optional.of(created.id()));
            workbay.addBus(workbay.bus(created.id()).orElseThrow().withRate(8).withSpeed(10));

            helper.startSequence()
                // Let it resolve and cache bay 1 before the retarget, or the drop has nothing to
                // drop and the test would pass against a cache that was never populated.
                .thenWaitUntil(() -> {
                    if (countIn(backshop, bay1, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing has reached bay 2 yet; status is "
                            + workbay.busStatus(created.id()));
                    }
                })
                .thenExecute(() -> {
                    menu.act(WorkbayAction.LINK_CYCLE_TARGET_BAY, 0, Optional.of(created.id()));
                    helper.assertValueEqual(
                        workbay.bus(created.id()).orElseThrow().target().pos(), bay2,
                        "the link's target bay after one cycle");
                })
                .thenWaitUntil(() -> {
                    if (countIn(backshop, bay2, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("the retargeted link has delivered "
                            + countIn(backshop, bay2, Items.IRON_INGOT) + " to bay 3 and "
                            + countIn(backshop, bay1, Items.IRON_INGOT) + " sits in bay 2; status "
                            + "is " + workbay.busStatus(created.id()));
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * <b>Rule 2 of SPEC.md §0: adding a Connector to a bay never takes anything away from another.</b>
     *
     * <p>Add used to <em>move</em> a row: ticking a Connector on bay 2 pulled it off bay 1, so a
     * player who wanted power in one bay and cobble out of another kept losing the first every
     * time they set up the second. One Connector is meant to serve every bay at once.
     *
     * <p>Both halves are watched, not just counted. The bay-1 channel keeps moving iron after the
     * bay-2 channel exists, and the bay-2 channel really pulls out of bay 2 - the runner caches a
     * resolved capability per link and that cache is built from the bay the link had at the time,
     * so a count on its own would pass against a channel talking to the wrong bay.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "Adding a Connector to a second bay leaves the first bay's channel alone.")
    public static void addingAConnectorToASecondBayLeavesTheFirstAlone(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 0));
            helper.setBlock(new BlockPos(3, 1, 0), Blocks.CHEST);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord record = workbay.record().orElseThrow();
            registry.put(record.withUpgrades(new WorkbayRecord.Upgrades(1, 0, 0, 0)));
            record = workbay.record().orElseThrow();

            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BayHosting.rack(backshop, record.bayColumn(), 1, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH);
            BlockPos bay0 = BayGeometry.machinePos(record.bayColumn(), 0);
            BlockPos bay1 = BayGeometry.machinePos(record.bayColumn(), 1);
            // Each bay holds a different metal, so the chest says which bay a channel pulled from.
            if (backshop.getBlockEntity(bay0) instanceof Container first) {
                first.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            }
            if (backshop.getBlockEntity(bay1) instanceof Container second) {
                second.setItem(0, new ItemStack(Items.GOLD_INGOT, 32));
            }

            // The first channel: bay 1, pulling iron into the chest.
            BusConfig link = connect(helper, workbay, chestPos.above(), Direction.DOWN, player);
            workbay.addBus(workbay.bus(link.id()).orElseThrow()
                .withMode(BusConfig.Mode.INSERT).withEnabled(true).withRate(8).withSpeed(10));
            GlobalPos here = GlobalPos.of(level.dimension(), chestPos.above());

            // The same Connector, added on bay 2. This is the tick in the Add list.
            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay,
                WorkbayMenu.build(workbay, player, 1));
            menu.act(WorkbayAction.ADD_CHANNEL, 1,
                Optional.of(workbay.connectorAt(here).orElseThrow().id()));

            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().bay(), 0,
                "the first channel's bay after adding the same Connector to bay 2");
            helper.assertValueEqual(workbay.linksAt(here).size(), 2,
                "channels on one Connector after adding it to a second bay");
            BusConfig second = workbay.linksAt(here).stream()
                .filter(bus -> !bus.id().equals(link.id())).findFirst().orElseThrow();
            helper.assertValueEqual(second.bay(), 1, "the new channel's bay");
            workbay.addBus(workbay.bus(second.id()).orElseThrow()
                .withMode(BusConfig.Mode.INSERT).withEnabled(true).withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, chestPos, Items.IRON_INGOT) < 32
                        || countIn(level, chestPos, Items.GOLD_INGOT) < 32) {
                        throw new GameTestAssertException("one Connector carrying two channels has "
                            + "delivered " + countIn(level, chestPos, Items.IRON_INGOT)
                            + " of 32 iron out of bay 1 and "
                            + countIn(level, chestPos, Items.GOLD_INGOT)
                            + " of 32 gold out of bay 2; statuses are "
                            + workbay.busStatus(link.id()) + " and "
                            + workbay.busStatus(second.id()));
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * A row is named after what its link points at, and stays named after it when the link is
     * retargeted. SPEC.md §4's list is unreadable otherwise: every internal link used to carry the
     * stored name "Bay link", so four rows on one bay were four identical rows.
     *
     * <p>The name is <b>derived, not stored</b> - which is the half worth guarding. Baking the
     * target into the name at creation would read correctly on the day it was made and lie the
     * moment somebody pointed the link at a different bay, which is one click on the row.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "A link with no name of its own is named after what it points at.")
    public static void aLinkIsNamedAfterWhatItPointsAt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 0));
            helper.setBlock(new BlockPos(3, 1, 0), Blocks.CHEST);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            // Three bays, so retargeting has somewhere else to go than straight back.
            RoomRegistry.get(level.getServer()).put(workbay.record().orElseThrow()
                .withUpgrades(new WorkbayRecord.Upgrades(2, 0, 0, 0)));

            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayMenu menu = new WorkbayMenu(1, player.getInventory(), workbay,
                WorkbayMenu.build(workbay, player, 0));
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.CREATE_INTERNAL_LINK, 1, Optional.empty());

            BusConfig internal = workbay.buses().stream().filter(BusConfig::internal).findFirst()
                .orElseThrow(() -> new GameTestAssertException("no internal link was made"));
            helper.assertValueEqual(labelOf(workbay, player, internal.id()), Optional.of("Bay 2"),
                "the name of a link pointing at bay 2");

            // One click on the row's target. The name has to follow it.
            menu.act(WorkbayAction.LINK_CYCLE_TARGET_BAY, 0, Optional.of(internal.id()));
            helper.assertValueEqual(labelOf(workbay, player, internal.id()), Optional.of("Bay 3"),
                "the name of the same link after it was pointed at bay 3");

            // And a name the player typed outranks both.
            workbay.addBus(workbay.bus(internal.id()).orElseThrow().withName("Furnace feed"));
            helper.assertValueEqual(labelOf(workbay, player, internal.id()),
                Optional.of("Furnace feed"), "a link the player named");

            // An external link carries no stored name either; the row reads the target block's own
            // name, which only the client can resolve, so the snapshot hands back nothing.
            BusConfig external = connect(helper, workbay, chestPos.above(), Direction.DOWN, player);
            helper.assertValueEqual(external.name(), "", "a new external link's stored name");
            helper.assertValueEqual(labelOf(workbay, player, external.id()), Optional.empty(),
                "an external link's derived name, which the client resolves from the block");

            tearDown(helper, workbayPos);
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #30, and the same fault as the energy bus: <b>a face that reports slots and
     * refuses every insert wins the bind</b>, and the link then moves nothing forever while reading
     * IDLE.
     *
     * <p>The target is a plain furnace, which has exactly that face. {@code getSlotsForFace(DOWN)}
     * is the output slot, so the down face's handler has one slot and {@code canPlaceItem} says no
     * to all of it - and {@code DOWN} is the first entry in {@code Direction#values}, so a bind on
     * {@code getSlots() &gt; 0} takes it and stops looking. Every other item test in this file
     * points at a chest or a barrel, whose six faces all accept, which is exactly the blind spot
     * that let the energy bug live a whole phase.
     *
     * <p>No Mekanism needed to say it: an output-only face is an output-only face. The two
     * assertions before the sequence are the positive control - if a future furnace accepts through
     * its bottom, this test says so instead of passing vacuously.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A link skips a face that reports slots and refuses every insert.")
    public static void aLinkSkipsAnOutputOnlyFaceInsteadOfBindingToIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);

            // The positive control. Both of these have to hold or the test is not testing anything:
            // the down face must offer a handler with slots (so the old predicate binds it) and it
            // must refuse the very item the link is about to carry.
            IItemHandler down = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos,
                Direction.DOWN);
            if (down == null || down.getSlots() <= 0) {
                helper.fail("the furnace's down face has no item handler with slots, so it is no "
                    + "longer the decoy this test is about");
            }
            for (int slot = 0; slot < down.getSlots(); slot++) {
                // Raw iron, not an ingot: a furnace is only ever offered what it can cook (#120).
                if (down.insertItem(slot, new ItemStack(Items.RAW_IRON, 1), true).isEmpty()) {
                    helper.fail("the furnace's down face accepted iron in slot " + slot
                        + ", so it is not an output-only face and this test proves nothing");
                }
            }

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (!(backshop.getBlockEntity(machinePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a chest");
                return;
            }
            hosted.setItem(0, new ItemStack(Items.RAW_IRON, 16));

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.RAW_IRON) < 16) {
                        throw new GameTestAssertException("the link has moved "
                            + countIn(level, targetPos, Items.RAW_IRON) + " of 16 iron into the "
                            + "furnace and reads " + workbay.busStatus(link.id())
                            + "; it bound the output-only down face and is moving nothing");
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * Fluids, end to end, through a real mod's handler at both ends. SPEC.md §9.
     *
     * <p><b>This does not guard the bind, and it was checked rather than assumed.</b> Rewriting
     * {@code runFluid} to bind on {@code getTanks() > 0} at both ends leaves this test green. The
     * reason is structural: {@code basic_fluid_tank} answers identically on all six faces, so
     * there is no wrong face for a count to pick. OPEN_ISSUES has the measured table and what a
     * real guard would need.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A fluid link moves water out of a hosted tank into one in the world.")
    public static void aFluidLinkMovesWaterOutOfAHostedTank(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            Block tank = BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse("mekanism:basic_fluid_tank"));
            if (tank == Blocks.AIR) {
                helper.fail("mekanism:basic_fluid_tank is not registered. This test is about a real "
                    + "mod's fluid handler, so a missing partner mod is a failure, never a skip.");
            }

            // The destination, placed the way a player places it so Mekanism records its sides.
            BlockState state = tank.defaultBlockState();
            level.setBlock(targetPos, state, Block.UPDATE_ALL);
            tank.setPlacedBy(level, targetPos, state, player, new ItemStack(tank));
            level.invalidateCapabilities(targetPos);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(tank));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            var water = net.minecraft.world.level.material.Fluids.WATER;
            int filled = fill(backshop, machinePos, new FluidStack(water, 8_000));
            if (filled <= 0) {
                helper.fail("could not fill the hosted tank through any face, so the link has "
                    + "nothing to carry");
            }
            int before = inTanks(backshop, machinePos, water) + inTanks(level, targetPos, water);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withResource(BusConfig.Resource.FLUID).withRate(20).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inTanks(level, targetPos, water) < filled) {
                        throw new GameTestAssertException("the link has moved "
                            + inTanks(level, targetPos, water) + " of " + filled
                            + " mB and reads " + workbay.busStatus(link.id())
                            + "; a bind on a tank count rather than on the move itself picks a "
                            + "face that answers and refuses, and then carries nothing");
                    }
                })
                .thenExecute(() -> {
                    int now = inTanks(backshop, machinePos, water) + inTanks(level, targetPos, water);
                    helper.assertValueEqual(now, before, "millibuckets of water in existence");
                    helper.assertValueEqual(inTanks(backshop, machinePos, water), 0,
                        "water left in the hosted tank");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * <b>The mod's whole promise in one test.</b> Three chests on the floor and a furnace in a bay:
     * one link feeds it coal, another feeds it raw chicken, a third takes the cooked chicken away.
     * Nobody configures a face - the routing has to fall out of the links themselves.
     *
     * <p>It does, and only because a bind is a simulated move. A furnace's faces each expose a
     * different slot ({@code SLOTS_FOR_DOWN} is the output and the fuel, {@code SLOTS_FOR_SIDES}
     * the fuel, {@code SLOTS_FOR_UP} the input), so <em>trying</em> the insert is what sorts coal
     * from chicken: the down face takes coal into the fuel slot and refuses chicken, the up face
     * takes the chicken. A bind on {@code getSlots() > 0} sends both to the down face and the
     * chicken link then reads IDLE forever.
     *
     * <p>It is also the only test here that proves a <b>hosted machine ticks</b>. Nothing else
     * would turn raw chicken into cooked. OPEN_ISSUES #19.
     */
    @GameTest(timeoutTicks = 2400)
    @TestHolder(description = "Two links feed a hosted furnace fuel and food; a third takes the meal away.")
    public static void twoLinksFeedAFurnaceAndAThirdTakesTheMealAway(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos fuelChest = helper.absolutePos(new BlockPos(5, 1, 0));
            BlockPos foodChest = helper.absolutePos(new BlockPos(5, 1, 2));
            BlockPos outChest = helper.absolutePos(new BlockPos(5, 1, 4));

            for (BlockPos at : new BlockPos[] {fuelChest, foodChest, outChest}) {
                level.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            }
            if (level.getBlockEntity(fuelChest) instanceof Container fuel) {
                fuel.setItem(0, new ItemStack(Items.COAL, 8));
            }
            if (level.getBlockEntity(foodChest) instanceof Container food) {
                food.setItem(0, new ItemStack(Items.CHICKEN, 4));
            }

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.FURNACE));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            // Three links, made the way a player makes them, and told apart only by their filters.
            BusConfig coal = connect(helper, workbay, fuelChest.above(), Direction.DOWN, player);
            workbay.addBus(coal.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("minecraft:coal"))));
            BusConfig raw = connect(helper, workbay, foodChest.above(), Direction.DOWN, player);
            workbay.addBus(raw.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("minecraft:chicken"))));
            BusConfig cooked = connect(helper, workbay, outChest.above(), Direction.DOWN, player);
            workbay.addBus(cooked.withMode(BusConfig.Mode.INSERT).withRate(4).withSpeed(10)
                .withFilter(com.neryos.workbay.bus.BusFilter.only(ResourceLocation.parse("minecraft:cooked_chicken"))));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    int done = countIn(level, outChest, Items.COOKED_CHICKEN);
                    if (done < 4) {
                        throw new GameTestAssertException("the line has delivered " + done
                            + " of 4 cooked chicken. fuel slot=" + inSlot(backshop, machinePos, 1)
                            + " input slot=" + inSlot(backshop, machinePos, 0)
                            + " output slot=" + inSlot(backshop, machinePos, 2)
                            + "; links read " + workbay.busStatus(coal.id()) + "/"
                            + workbay.busStatus(raw.id()) + "/" + workbay.busStatus(cooked.id()));
                    }
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(level, foodChest, Items.CHICKEN), 0,
                        "raw chicken left in the food chest");
                    // The coal link must not have posted chicken into the fuel slot, or the other
                    // way round: each face takes only what belongs in it.
                    helper.assertValueEqual(countIn(level, outChest, Items.CHICKEN), 0,
                        "raw chicken that reached the output chest");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * OPEN_ISSUES #120, verified rather than guessed: one chest of sand and coal, a filterless
     * chest-to-furnace channel and a second one turned round. The glass the furnace makes goes
     * back to the chest, where the first channel finds it - and a furnace's top face takes
     * anything into its input slot ({@code canPlaceItem(0, ..)} is true for every item), so the
     * glass would ride back in and sit in the input, jamming the sand behind it. This asserts
     * the chest ends up with every pane and the furnace's input never holds glass.
     */
    @GameTest(timeoutTicks = 2400)
    @TestHolder(description = "A filterless chest-to-furnace channel with a second one turned round never feeds the glass back in.")
    public static void aFilterlessLoopDoesNotFeedTheFurnaceItsOwnGlass(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 2));
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(chest) instanceof Container box) {
                box.setItem(0, new ItemStack(Items.SAND, 8));
                box.setItem(1, new ItemStack(Items.COAL, 2));
            }
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.FURNACE));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            BusConfig in = connect(helper, workbay, chest.above(), Direction.DOWN, player);
            workbay.addBus(in.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10));
            BusConfig out = connect(helper, workbay, chest.above(), Direction.DOWN, player);
            workbay.addBus(out.withMode(BusConfig.Mode.INSERT).withRate(4).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    String input = inSlot(backshop, machinePos, 0);
                    if (input.contains("glass")) {
                        throw new GameTestAssertException("glass rode back into the furnace's "
                            + "input slot: " + input);
                    }
                    int glass = countIn(level, chest, Items.GLASS);
                    if (glass < 8) {
                        throw new GameTestAssertException("the chest holds " + glass
                            + " of 8 glass and " + countIn(level, chest, Items.SAND)
                            + " sand; input=" + input + " fuel=" + inSlot(backshop, machinePos, 1)
                            + " output=" + inSlot(backshop, machinePos, 2) + "; links read "
                            + workbay.busStatus(in.id()) + "/" + workbay.busStatus(out.id())
                            + " of " + workbay.buses().size());
                    }
                })
                .thenExecuteFor(200, () -> {
                    String input = inSlot(backshop, machinePos, 0);
                    if (input.contains("glass")) {
                        throw new GameTestAssertException("glass rode back into the furnace's "
                            + "input slot after the batch: " + input);
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /** What is in one slot of a hosted machine, by name, for a failure message worth reading. */
    private static String inSlot(ServerLevel level, BlockPos pos, int slot) {
        if (!(level.getBlockEntity(pos) instanceof Container container)
            || slot >= container.getContainerSize()) {
            return "none";
        }
        ItemStack held = container.getItem(slot);
        return held.isEmpty() ? "empty" : held.getCount() + "x" + held.getItem();
    }

    /** Fills a block's tank through whichever face accepts, never the null side alone. */
    /**
     * The same filter read the other way round: <b>everything except gold</b>.
     *
     * <p>EnderIO's {@code EnderItemFilter} is one list and one {@code isDenyList} flag, and this is
     * the second half of that flag. Its own test because "the list is inverted" is exactly the kind
     * of thing that stays green while being applied to the wrong side of the comparison: with one
     * entry listed, {@link #aFilteredLinkMovesOnlyItsItem} passes either way round.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A link denying gold carries everything but the gold.")
    public static void aDenyFilterCarriesEverythingElse(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(Blocks.CHEST));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (!(backshop.getBlockEntity(machinePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a chest");
                return;
            }
            hosted.setItem(0, new ItemStack(Items.GOLD_INGOT, 16));
            hosted.setItem(1, new ItemStack(Items.IRON_INGOT, 16));

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withRate(8).withSpeed(10).withFilter(
                com.neryos.workbay.bus.BusFilter.ofIds(java.util.List.of(
                    BuiltInRegistries.ITEM.getKey(Items.GOLD_INGOT)), true)));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (countIn(level, targetPos, Items.IRON_INGOT) < 16) {
                        throw new GameTestAssertException("the link has moved "
                            + countIn(level, targetPos, Items.IRON_INGOT) + " of 16 iron and reads "
                            + workbay.busStatus(link.id())
                            + "; a deny list must carry everything it does not name");
                    }
                })
                // The iron is through and the link is still running, so it has had every step
                // since to move the gold too.
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(countIn(level, targetPos, Items.GOLD_INGOT), 0,
                        "gold carried by a link that denies it");
                    helper.assertValueEqual(hosted.getItem(0).getCount(), 16, "gold left in the bay");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /**
     * Fluids have a filter too, and it is the same filter. OPEN_ISSUES had the fluid link carrying
     * everything with no way to say otherwise.
     *
     * <p><b>The test names the wrong fluid first.</b> A hosted tank full of water, a link told
     * lava: nothing may move. Then the same link is told water, and it must all move -- which is
     * what makes the first half mean something. A filter that is never consulted passes the first
     * assertion by moving the water immediately, and one that refuses everything passes it by
     * refusing forever; only the pair can be satisfied by a filter that actually reads its list.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A fluid link carries only the fluid its filter lists.")
    public static void aFluidLinkCarriesOnlyTheFluidItsFilterLists(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            Block tank = BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse("mekanism:basic_fluid_tank"));
            if (tank == Blocks.AIR) {
                helper.fail("mekanism:basic_fluid_tank is not registered. This test is about a real "
                    + "mod's fluid handler, so a missing partner mod is a failure, never a skip.");
            }
            BlockState state = tank.defaultBlockState();
            level.setBlock(targetPos, state, Block.UPDATE_ALL);
            tank.setPlacedBy(level, targetPos, state, player, new ItemStack(tank));
            level.invalidateCapabilities(targetPos);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(tank));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            var water = net.minecraft.world.level.material.Fluids.WATER;
            int filled = fill(backshop, machinePos, new FluidStack(water, 8_000));
            if (filled <= 0) {
                helper.fail("could not fill the hosted tank, so the link has nothing to carry");
            }

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player)
                .withResource(BusConfig.Resource.FLUID).withRate(20).withSpeed(10);
            // Lava, into a tank holding only water. The entries are fluid ids on a fluid link.
            workbay.addBus(link.withFilter(com.neryos.workbay.bus.BusFilter.ofIds(
                java.util.List.of(BuiltInRegistries.FLUID.getKey(
                    net.minecraft.world.level.material.Fluids.LAVA)), false)));

            helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> helper.assertValueEqual(inTanks(level, targetPos, water), 0,
                    "water carried by a link whose filter lists only lava"))
                // Same link, same tank, water listed instead. Everything else is unchanged, so the
                // only thing that can move it now is the filter.
                .thenExecute(() -> workbay.addBus(link.withFilter(
                    com.neryos.workbay.bus.BusFilter.ofIds(java.util.List.of(
                        BuiltInRegistries.FLUID.getKey(water)), false))))
                .thenWaitUntil(() -> {
                    if (inTanks(level, targetPos, water) < filled) {
                        throw new GameTestAssertException("the link has moved "
                            + inTanks(level, targetPos, water) + " of " + filled
                            + " mB with water listed and reads " + workbay.busStatus(link.id()));
                    }
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    private static int fill(ServerLevel level, BlockPos pos, FluidStack what) {
        for (Direction side : Direction.values()) {
            var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler != null) {
                int moved = handler.fill(what, IFluidHandler.FluidAction.EXECUTE);
                if (moved > 0) {
                    return moved;
                }
            }
        }
        return 0;
    }

    private static int inTanks(ServerLevel level, BlockPos pos,
        net.minecraft.world.level.material.Fluid fluid) {
        var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            if (handler.getFluidInTank(tank).getFluid() == fluid) {
                total += handler.getFluidInTank(tank).getAmount();
            }
        }
        return total;
    }

    /** The row's name, straight out of the snapshot the screen actually draws. */
    private static Optional<String> labelOf(WorkbayBlockEntity workbay, GameTestPlayer player,
        java.util.UUID id) {
        return WorkbayMenu.build(workbay, player, 0).links().stream()
            .filter(link -> link.config().id().equals(id))
            .findFirst()
            .orElseThrow(() -> new GameTestAssertException("the snapshot has no row for " + id))
            .label();
    }

    // ------------------------------------------------------- chemicals (#31)

    /**
     * OPEN_ISSUES #98, the row three drivers could not light: an item link feeding a full
     * hydrogen bucket into a racked Rotary Condensentrator's bucket slot. Measured here instead
     * of guessed: if the link moves it, the drivers' rig was wrong; if not, the failure message
     * says whether Mekanism's side handler refuses the bucket or the bus never offers it.
     */
    @GameTest(timeoutTicks = 200)
    @TestHolder(description = "An item link feeds a hydrogen bucket into a racked Rotary Condensentrator (#98).")
    public static void anItemLinkFeedsABucketIntoARackedRotaryCondensentrator(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 0));
            Block rc = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mekanism:rotary_condensentrator"));
            net.minecraft.world.item.Item bucket = BuiltInRegistries.ITEM.get(
                ResourceLocation.parse("mekanism:hydrogen_bucket"));
            helper.assertFalse(rc == Blocks.AIR || bucket == Items.AIR,
                "mekanism:rotary_condensentrator or mekanism:hydrogen_bucket is not registered");

            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(chest) instanceof Container in) {
                in.setItem(0, new ItemStack(bucket, 1));
            }
            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(rc));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            // Decondensentrating (mode true): the bucket slot takes a FULL bucket and drains it.
            var machine = backshop.getBlockEntity(machinePos);
            var tag = machine.saveWithoutMetadata(level.registryAccess());
            tag.putBoolean("mode", true);
            machine.loadWithComponents(tag, level.registryAccess());

            BusConfig feed = connect(helper, workbay, chest.above(), Direction.DOWN, player);
            workbay.addBus(feed.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10));

            helper.startSequence()
                .thenIdle(100)
                .thenExecute(() -> {
                    int left = countIn(level, chest, bucket);
                    if (left != 0) {
                        // Say which end refused. Ask the machine directly through every face.
                        StringBuilder why = new StringBuilder("the bucket never left the chest; link reads ")
                            .append(workbay.busStatus(feed.id())).append(". Machine faces: ");
                        for (Direction side : Direction.values()) {
                            IItemHandler h = backshop.getCapability(Capabilities.ItemHandler.BLOCK, machinePos, side);
                            if (h == null) { why.append(side).append("=none "); continue; }
                            boolean valid = false, takes = false;
                            for (int slot = 0; slot < h.getSlots(); slot++) {
                                ItemStack probe = new ItemStack(bucket, 1);
                                valid |= h.isItemValid(slot, probe);
                                takes |= h.insertItem(slot, probe, true).getCount() < 1;
                            }
                            why.append(side).append("=slots").append(h.getSlots()).append(valid ? "/valid" : "/invalid")
                                .append(takes ? "/takes " : "/refuses ");
                        }
                        throw new GameTestAssertException(why.toString());
                    }
                })
                .thenSucceed();
        });
    }

    /**
     * Gas moves the way items, fluids and energy already move. OPEN_ISSUES #31.
     *
     * <p>A chemical is the one resource with no NeoForge capability behind it, so this is the only
     * link that can be <em>absent</em> - and every line of it that names a Mekanism type lives
     * behind {@code MekanismChemicals}, because a guard in the same class as a Mekanism-typed field
     * is checked after the JVM has already failed to load it. That half is proved in a plain
     * instance with no Mekanism, which no dev run can do; this half proves it carries gas.
     */
    @GameTest(timeoutTicks = 300)
    @TestHolder(description = "A chemical link moves gas out of a hosted Mekanism tank without losing any.")
    public static void aChemicalLinkMovesGasOutOfAHostedTank(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));

            Block tank = BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse("mekanism:basic_chemical_tank"));
            if (tank == Blocks.AIR) {
                helper.fail("mekanism:basic_chemical_tank is not registered. This test is about a "
                    + "real mod's chemical handler, so a missing partner mod is a failure, not a skip.");
            }
            mekanism.api.chemical.Chemical oxygen = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY
                .get(ResourceLocation.parse("mekanism:oxygen"));

            // The whole placement, not the short one. OPEN_ISSUES: a Mekanism block placed by
            // setBlock alone exposes no capability on any face, because its containers live in
            // data components -- and a chemical tank is the block that proves it.
            BlockState state = tank.defaultBlockState();
            ItemStack tankStack = new ItemStack(tank);
            level.setBlock(targetPos, state, Block.UPDATE_ALL);
            net.minecraft.world.item.BlockItem.updateCustomBlockEntityTag(level, player, targetPos, tankStack);
            if (level.getBlockEntity(targetPos) instanceof net.minecraft.world.level.block.entity.BlockEntity placed) {
                placed.applyComponentsFromItemStack(tankStack);
            }
            tank.setPlacedBy(level, targetPos, state, player, tankStack);
            level.invalidateCapabilities(targetPos);

            WorkbayBlockEntity workbay = setUp(helper, workbayPos, player, new ItemStack(tank));
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            helper.assertFalse(level.getCapability(CHEMICAL_CAP, targetPos, Direction.NORTH) == null
                && level.getCapability(CHEMICAL_CAP, targetPos, Direction.UP) == null,
                "the target chemical tank exposes no chemical handler on any face, so this test "
                    + "would be measuring its placement rather than the link");

            long seeded = fillChemical(backshop, machinePos,
                new mekanism.api.chemical.ChemicalStack(oxygen, 8_000L));
            if (seeded <= 0) {
                helper.fail("could not put oxygen into the hosted tank through any face, so the "
                    + "link has nothing to carry");
            }
            long before = inChemicalTanks(backshop, machinePos) + inChemicalTanks(level, targetPos);

            BusConfig link = connect(helper, workbay, targetPos.above(), Direction.DOWN, player);
            workbay.addBus(link.withResource(BusConfig.Resource.CHEMICAL).withRate(20).withSpeed(10));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inChemicalTanks(level, targetPos) < seeded) {
                        throw new GameTestAssertException("the link has moved "
                            + inChemicalTanks(level, targetPos) + " of " + seeded
                            + " mB of oxygen and reads " + workbay.busStatus(link.id())
                            + "; a bind that trusts a handler's own answer instead of simulating "
                            + "the move picks a face that accepts nothing");
                    }
                })
                .thenExecute(() -> {
                    long now = inChemicalTanks(backshop, machinePos) + inChemicalTanks(level, targetPos);
                    helper.assertValueEqual(now, before, "millibuckets of oxygen in existence");
                    helper.assertValueEqual(inChemicalTanks(backshop, machinePos), 0L,
                        "oxygen left in the hosted tank");
                })
                .thenExecute(() -> tearDown(helper, workbayPos))
                .thenSucceed();
        });
    }

    /** Fills through whichever face will take it, never the null side (SPEC.md §9). */
    private static long fillChemical(ServerLevel level, BlockPos pos,
        mekanism.api.chemical.ChemicalStack stack) {
        for (Direction face : Direction.values()) {
            mekanism.api.chemical.IChemicalHandler handler = level.getCapability(CHEMICAL_CAP, pos, face);
            if (handler == null) {
                continue;
            }
            long leftover = handler.insertChemical(stack, mekanism.api.Action.EXECUTE).getAmount();
            if (leftover < stack.getAmount()) {
                return stack.getAmount() - leftover;
            }
        }
        return 0L;
    }

    /** Everything a block is holding, read on the null side because this is a reading. */
    private static long inChemicalTanks(ServerLevel level, BlockPos pos) {
        mekanism.api.chemical.IChemicalHandler handler = level.getCapability(CHEMICAL_CAP, pos, null);
        if (handler == null) {
            return 0L;
        }
        long total = 0L;
        for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
            total += handler.getChemicalInTank(tank).getAmount();
        }
        return total;
    }

    /** The same capability Mekanism registers, by name - the way the mod's own boundary gets it. */
    private static final net.neoforged.neoforge.capabilities.BlockCapability<
        mekanism.api.chemical.IChemicalHandler, Direction> CHEMICAL_CAP =
        net.neoforged.neoforge.capabilities.BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"),
            mekanism.api.chemical.IChemicalHandler.class);
}
