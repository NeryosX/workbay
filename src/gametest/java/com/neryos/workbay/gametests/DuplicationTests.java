package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.Optional;
import java.util.UUID;

/**
 * The duplication audit. ROADMAP.md §2.
 *
 * <p>One rule, and every test here is a way of trying to break it: <b>the item exists in exactly
 * one place at the end of every tick.</b> Not one place plus a copy, and not nought places - a mod
 * whose pitch is "put your machine inside this block and take it back out" has to be right about
 * both, and the audience that finds either is the one that publishes it.
 *
 * <p>Each test therefore <em>counts</em> rather than asserts a state. A census over every owner an
 * item could be sitting in - a player's inventory, a cursor, a container in the world, a container
 * in the Backshop, the contents saved onto an ejected machine's item, and loose item entities -
 * has to come back with the same number it started with. That shape is deliberate: an assertion
 * that "the bay is empty" passes just as happily when the machine was deleted.
 */
@ForEachTest(groups = "duplication")
public class DuplicationTests {

    // ------------------------------------------------------------- the census

    /** Everything one item could be in, on one player. Cursor included: that is where a dupe hides. */
    private static int onPlayer(GameTestPlayer player, Item item) {
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += count(inventory.getItem(slot), item);
        }
        return total;
    }

    /** A stack, plus whatever that stack is carrying - an ejected machine keeps its contents. */
    private static int count(ItemStack stack, Item item) {
        if (stack.isEmpty()) {
            return 0;
        }
        int total = stack.is(item) ? stack.getCount() : 0;
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents != null) {
            for (ItemStack inside : contents.nonEmptyItems()) {
                total += count(inside, item);
            }
        }
        var blockEntity = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntity != null) {
            total += inSavedBlockEntity(blockEntity.copyTag(), item);
        }
        return total;
    }

    /**
     * A machine ejected with a part-finished recipe in it keeps its contents in
     * {@code minecraft:block_entity_data}, not in {@code minecraft:container} - SPEC.md §10 step 2
     * uses {@code saveToItem}, which writes the block entity's own NBT. Reading it back is the only
     * way a census can tell "the contents came with it" from "the contents are gone".
     */
    private static int inSavedBlockEntity(net.minecraft.nbt.CompoundTag tag, Item item) {
        if (!tag.contains("Items", net.minecraft.nbt.Tag.TAG_LIST)) {
            return 0;
        }
        int total = 0;
        var list = tag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        for (int i = 0; i < list.size(); i++) {
            var entry = list.getCompound(i);
            if (id.equals(entry.getString("id"))) {
                total += entry.getInt("count");
            }
        }
        return total;
    }

    /**
     * Through the capability rather than {@code Container}, because a Mekanism machine is not one.
     * The null side is what Bay View itself reads, so this counts what Bay View can see.
     */
    private static int inHandler(ServerLevel level, BlockPos pos, Item item) {
        var handler = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            total += count(handler.getStackInSlot(slot), item);
        }
        return total;
    }

    /**
     * Millibuckets of one fluid standing in a block's tanks, through the same capability Bay View
     * reads. A fluid census has to count these <em>and</em> the buckets carrying it, because a
     * bucket that empties into a tank without emptying is exactly the item-dupe bug wearing a
     * different hat.
     */
    private static int inTanks(ServerLevel level, BlockPos pos, net.minecraft.world.level.material.Fluid fluid) {
        var handler = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, null);
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            var contents = handler.getFluidInTank(tank);
            if (contents.getFluid() == fluid) {
                total += contents.getAmount();
            }
        }
        return total;
    }

    /** Energy stored in a block, on whichever side answers. Never the null side twice over. */
    private static int inEnergy(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            var store = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
            if (store != null) {
                return store.getEnergyStored();
            }
        }
        var store = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, null);
        return store == null ? 0 : store.getEnergyStored();
    }

    private static int inContainer(ServerLevel level, BlockPos pos, Item item) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += count(container.getItem(slot), item);
        }
        return total;
    }

    /**
     * Loose on the floor, inside this test's own structure and a little around it.
     *
     * <p><b>Bounded by {@code helper.getBounds()}, not by a radius around a position.</b> Two
     * things go wrong with a radius. Two calls around two positions in the same structure count
     * every dropped stack twice; and a box wide enough to be safe reaches into the <em>next</em>
     * gametest's structure, so a neighbour's spilled chest lands in this census. Both read exactly
     * like a duplication bug in the mod, and this file "found" each of them once.
     */
    private static int loose(ExtendedGameTestHelper helper, Item item) {
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            helper.getBounds().inflate(2))) {
            total += count(entity.getItem(), item);
        }
        return total;
    }

    private static int carried(net.minecraft.world.inventory.AbstractContainerMenu menu, Item item) {
        return count(menu.getCarried(), item);
    }

    /**
     * A real mod's fluid handler is the point of these tests, so a missing partner mod is a
     * failure and never a skip.
     */
    private static Block mekanismTank(ExtendedGameTestHelper helper) {
        Block tank = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_fluid_tank"));
        if (tank == Blocks.AIR) {
            helper.fail("mekanism:basic_fluid_tank is not registered. These tests are about a real "
                + "mod's fluid handler, so a missing partner mod is a failure, never a skip. "
                + "Check the gametestRuntimeOnly Mekanism dependency in build.gradle.");
        }
        return tank;
    }

    // ---------------------------------------------------------------- fixture

    private static WorkbayBlockEntity placeWorkbay(ExtendedGameTestHelper helper, BlockPos pos,
        GameTestPlayer player) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), player,
            new ItemStack(WBBlocks.WORKBAY.get()));
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
        // Powered, because a real one has to be: SPEC.md §9 charges the buffer for every
        // link that is switched on and again for every move, so an unfed Workbay runs
        // nothing and every link on it reads NO_POWER. OPEN_ISSUES #72. A test that is not
        // about the bill pays it up front and says so here.
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        return workbay;
    }

    private static WorkbayMenu menuFor(WorkbayBlockEntity workbay, GameTestPlayer player) {
        return new WorkbayMenu(1, player.getInventory(), workbay,
            WorkbayMenu.build(workbay, player, 0));
    }

    private static void rack(WorkbayMenu menu, GameTestPlayer player, int bay, ItemStack machine) {
        menu.act(WorkbayAction.SELECT_BAY, bay, Optional.empty());
        player.setItemInHand(InteractionHand.MAIN_HAND, machine);
        menu.act(WorkbayAction.RACK, 0, Optional.empty());
    }

    private static ServerLevel backshop(ExtendedGameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
    }

    private static void put(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            container.setItem(slot, stack);
        }
    }

    // ------------------------------------------------------- racking the block

    /**
     * The base case, and the reason every other test here is a census. Rack a machine, eject it,
     * and there is one of it the whole way through - never two, never nought.
     */
    @GameTest
    @TestHolder(description = "Racking then ejecting a machine leaves exactly one of it in the world.")
    public static void aRackedMachineIsNeverInTwoPlacesOrNone(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            WorkbayMenu menu = menuFor(workbay, player);
            player.getInventory().clearContent();
            rack(menu, player, 0, new ItemStack(Blocks.FURNACE, 1));

            int afterRack = onPlayer(player, Items.FURNACE) + loose(helper, Items.FURNACE)
                + (backshop.getBlockState(machinePos).is(Blocks.FURNACE) ? 1 : 0);
            helper.assertValueEqual(afterRack, 1, "furnaces in existence after racking one");

            menu.act(WorkbayAction.EJECT, 0, Optional.empty());
            int afterEject = onPlayer(player, Items.FURNACE) + loose(helper, Items.FURNACE)
                + (backshop.getBlockState(machinePos).is(Blocks.FURNACE) ? 1 : 0);
            helper.assertValueEqual(afterEject, 1, "furnaces in existence after ejecting it again");
            helper.succeed();
        });
    }

    /**
     * Two players, one bay, both clicking eject in the same tick. ROADMAP §2 names this one
     * explicitly, and it is the classic: the second eject must find the bay already empty rather
     * than hand out a second copy of somebody's machine.
     */
    @GameTest
    @TestHolder(description = "Two players ejecting the same bay in one tick get one machine between them.")
    public static void twoPlayersEjectingOneBayGetOneMachine(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer first = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer second = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, first);
            second.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            first.getInventory().clearContent();
            second.getInventory().clearContent();
            WorkbayMenu mine = menuFor(workbay, first);
            rack(mine, first, 0, new ItemStack(Blocks.FURNACE, 1));
            // Unlocked, and asserted: a network is locked at mint, and a locked Workbay refused the
            // second player every click, so until 09-14 this test never ran the race it names.
            mine.act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.assertTrue(workbay.record().orElseThrow().admits(second.getUUID()),
                "the second player is refused, so the second eject is never tried");

            // Both screens were opened before either clicked, which is the whole point: the second
            // menu's snapshot still says the bay is full when the first eject lands.
            WorkbayMenu theirs = menuFor(workbay, second);
            mine.act(WorkbayAction.EJECT, 0, Optional.empty());
            theirs.act(WorkbayAction.EJECT, 0, Optional.empty());

            int total = onPlayer(first, Items.FURNACE) + onPlayer(second, Items.FURNACE)
                + loose(helper, Items.FURNACE)
                + (backshop.getBlockState(machinePos).isAir() ? 0 : 1);
            helper.assertValueEqual(total, 1, "furnaces after two players both clicked eject");
            helper.succeed();
        });
    }

    /**
     * A machine ejected with a part-finished recipe inside it. SPEC.md §10's extract order removes
     * the block entity <em>before</em> the setBlock precisely so this does not spill into a sealed
     * room, and the census reads the saved NBT rather than the block, so "the contents came with
     * it" cannot be confused with "the contents are gone".
     */
    @GameTest
    @TestHolder(description = "Ejecting a machine that is mid-recipe brings its contents out with it.")
    public static void ejectingAMidRecipeMachineKeepsWhatIsInside(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            WorkbayMenu menu = menuFor(workbay, player);
            rack(menu, player, 0, new ItemStack(Blocks.FURNACE, 1));
            // Smelting in progress: ore in the input, fuel in the burner, a finished ingot waiting.
            put(backshop, machinePos, 0, new ItemStack(Items.RAW_IRON, 5));
            put(backshop, machinePos, 1, new ItemStack(Items.COAL, 3));
            put(backshop, machinePos, 2, new ItemStack(Items.IRON_INGOT, 2));

            menu.act(WorkbayAction.EJECT, 0, Optional.empty());

            helper.assertValueEqual(onPlayer(player, Items.RAW_IRON)
                + loose(helper, Items.RAW_IRON), 5, "raw iron after the eject");
            helper.assertValueEqual(onPlayer(player, Items.COAL)
                + loose(helper, Items.COAL), 3, "coal after the eject");
            helper.assertValueEqual(onPlayer(player, Items.IRON_INGOT)
                + loose(helper, Items.IRON_INGOT), 2, "iron ingots after the eject");
            helper.assertValueEqual(inContainer(backshop, machinePos, Items.RAW_IRON), 0,
                "raw iron left standing in an emptied bay");
            helper.succeed();
        });
    }

    /**
     * Breaking the Workbay while a machine is racked. SPEC.md §14 keeps the machines running, so
     * the machine must still be exactly where it was - the failure this guards against is a
     * teardown path that helpfully "returns" it as well.
     */
    @GameTest
    @TestHolder(description = "Breaking a Workbay with a machine racked neither drops nor deletes it.")
    public static void breakingTheWorkbayLeavesTheMachineWhereItIs(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(Blocks.FURNACE, 1));
            put(backshop, machinePos, 0, new ItemStack(Items.RAW_IRON, 7));

            level.destroyBlock(workbayPos, true, player);

            int furnaces = onPlayer(player, Items.FURNACE) + loose(helper, Items.FURNACE)
                + (backshop.getBlockState(machinePos).is(Blocks.FURNACE) ? 1 : 0);
            helper.assertValueEqual(furnaces, 1, "furnaces after breaking the Workbay around one");
            helper.assertValueEqual(inContainer(backshop, machinePos, Items.RAW_IRON), 7,
                "raw iron still inside the hosted furnace");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ links

    /**
     * A link whose target is destroyed while goods are moving. ROADMAP §2 names it; the answer is
     * that {@link com.neryos.workbay.bus.BusEndpoint} resolves through a capability cache that goes
     * null rather than stale, so the move never half-happens.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "Destroying a link's target mid-transfer loses nothing from the source.")
    public static void destroyingALinkTargetMidTransferLosesNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(Blocks.CHEST, 1));
            put(backshop, machinePos, 0, new ItemStack(Items.IRON_INGOT, 64));
            connect(helper, workbay, 0, targetPos.above(), player);

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inContainer(level, targetPos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing has moved through the link yet");
                    }
                })
                .thenExecute(() -> {
                    // Mid-flight: some ingots are here, some are there. Take the target away.
                    int arrived = inContainer(level, targetPos, Items.IRON_INGOT);
                    level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    test.framework().logger().info("{} ingots had arrived when the chest went",
                        arrived);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    // One loose() call: the chest spilled what had arrived, and the census box
                    // covers the whole structure.
                    int total = inContainer(backshop, machinePos, Items.IRON_INGOT)
                        + loose(helper, Items.IRON_INGOT);
                    helper.assertValueEqual(total, 64,
                        "iron ingots after the link's target was destroyed mid-transfer");
                })
                .thenSucceed();
        });
    }

    /**
     * Breaking the Connector while goods are in flight through it. The link goes with the block
     * (SPEC.md §2), so the question is whether the tick that removed it can also have been halfway
     * through a move.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "Breaking a Connector while its link is running loses nothing.")
    public static void breakingAConnectorMidFlightLosesNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = targetPos.above();
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(Blocks.CHEST, 1));
            put(backshop, machinePos, 0, new ItemStack(Items.IRON_INGOT, 64));
            connect(helper, workbay, 0, connectorPos, player);

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inContainer(level, targetPos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing has moved through the link yet");
                    }
                })
                .thenExecute(() -> level.setBlock(connectorPos, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL))
                .thenIdle(40)
                .thenExecute(() -> {
                    int total = inContainer(backshop, machinePos, Items.IRON_INGOT)
                        + inContainer(level, targetPos, Items.IRON_INGOT)
                        + loose(helper, Items.IRON_INGOT);
                    helper.assertValueEqual(total, 64,
                        "iron ingots after the Connector was broken mid-transfer");
                })
                .thenSucceed();
        });
    }

    /**
     * A Workbay broken and put back while a pair of opposed links circulates through one chest.
     * Night 2026-09-11, 2C's RED-candidate: a re-placed network "ate gold" -- the probe had read
     * another network's bay chest, and the loop was conserving all along -- but nothing had ever
     * proven that a re-placed block's fresh runner and caches keep a loop both moving and honest.
     *
     * <p>Counted twice, on purpose. A stack written straight into the bay chest's slot 0 has to
     * turn up in the world chest -- the extract link does the moving, and that is the positive
     * control that the loop is alive -- and the census of cobblestone and gold together has to be
     * what it was, after the break as before it.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "Breaking and re-placing a Workbay keeps a circulating pair of links "
        + "moving and loses nothing.")
    public static void aReplacedWorkbayKeepsACirculatingLoopHonest(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos targetPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(Blocks.CHEST, 1));
            put(level, targetPos, 0, new ItemStack(Items.COBBLESTONE, 48));
            put(backshop, machinePos, 0, new ItemStack(Items.COBBLESTONE, 8));
            // One Connector, two channels: the world chest's cobblestone into the bay, and the
            // bay's back out. What Alpha's Workbay 2 carried.
            BusConfig insert = connect(helper, workbay, 0, targetPos.above(), player);
            WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
                workbay.connectorAt(insert.connector()).orElseThrow().id(), 0);
            BusConfig extract = workbay.buses().stream()
                .filter(bus -> !bus.id().equals(insert.id())).findFirst().orElseThrow()
                .withMode(BusConfig.Mode.EXTRACT).withEnabled(true).withRate(16).withSpeed(10);
            workbay.addBus(extract);

            java.util.function.IntSupplier census = () ->
                inContainer(level, targetPos, Items.COBBLESTONE)
                    + inContainer(backshop, machinePos, Items.COBBLESTONE)
                    + inContainer(level, targetPos, Items.GOLD_INGOT)
                    + inContainer(backshop, machinePos, Items.GOLD_INGOT)
                    + loose(helper, Items.COBBLESTONE) + loose(helper, Items.GOLD_INGOT);
            // What the census should read. Writing slot 0 destroys whatever the loop had put
            // there, so the seed books that out before it books the gold in.
            int[] expected = { 56 };
            Runnable seedGold = () -> {
                expected[0] += 8 - ((Container) backshop.getBlockEntity(machinePos)).getItem(0).getCount();
                put(backshop, machinePos, 0, new ItemStack(Items.GOLD_INGOT, 8));
            };
            Runnable goldSurfaced = () -> {
                if (inContainer(level, targetPos, Items.GOLD_INGOT) < 8) {
                    throw new GameTestAssertException("the gold written into the bay chest's slot 0"
                        + " has not reached the world chest");
                }
            };

            helper.startSequence()
                .thenExecute(seedGold)
                .thenWaitUntil(goldSurfaced)
                .thenExecute(() -> helper.assertValueEqual(census.getAsInt(), expected[0],
                    "cobblestone and gold across the loop before the break"))
                .thenExecute(() -> {
                    // Mined, as a player mines it, and put back from a fresh item: the sleeping
                    // network of this player's is what setPlacedBy wakes.
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                    player.gameMode.destroyBlock(workbayPos);
                    helper.assertTrue(level.getBlockState(workbayPos).isAir(),
                        "the Workbay is still standing after destroyBlock");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    WorkbayBlockEntity again = placeWorkbay(helper, workbayPos, player);
                    helper.assertValueEqual(again.record().map(WorkbayRecord::id).orElse(null),
                        record.id(), "the network the re-placed Workbay woke");
                    seedGold.run();
                })
                .thenWaitUntil(goldSurfaced)
                .thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(census.getAsInt(), expected[0],
                    "cobblestone and gold across the loop after the break and re-place"))
                .thenSucceed();
        });
    }

    /**
     * The energy half, end to end, with a real Mekanism machine at both ends: a charged Basic
     * Energy Cube standing in the world, a link pulling from it, and an empty Basic Energy Cube
     * racked in a bay. <b>Nothing had ever pushed FE through a link into a hosted machine</b> - the
     * energy path was proven only at the read end, where the bays screen showed a cube's stored
     * figure - so this is the half that was taken on trust.
     *
     * <p>Counted rather than asserted, like every other test here: energy is a resource, and a
     * transfer that hands the destination more than the source lost is the same bug as a duplicated
     * item. The tolerance is not slack - Mekanism stores joules and converts on every FE call, so a
     * few units round away - but it only ever forgives a <em>loss</em>. A gain of one FE fails.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "FE pushed through a link into a racked Mekanism Energy Cube arrives, and none is created.")
    public static void energyPushedIntoARackedEnergyCubeIsNeverCreated(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos sourcePos = helper.absolutePos(new BlockPos(4, 1, 4));

            Block cube = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_energy_cube"));
            if (cube == Blocks.AIR) {
                helper.fail("mekanism:basic_energy_cube is not registered. This test is about a real "
                    + "mod's energy handler, so a missing partner mod is a failure, never a skip.");
            }

            // The source, in the world, placed the way a player would and then charged by hand.
            BlockState cubeState = cube.defaultBlockState();
            level.setBlock(sourcePos, cubeState, Block.UPDATE_ALL);
            net.minecraft.world.item.BlockItem.updateCustomBlockEntityTag(level, player, sourcePos,
                new ItemStack(cube));
            if (level.getBlockEntity(sourcePos) != null) {
                level.getBlockEntity(sourcePos).applyComponentsFromItemStack(new ItemStack(cube));
            }
            cube.setPlacedBy(level, sourcePos, cubeState, player, new ItemStack(cube));
            level.invalidateCapabilities(sourcePos);

            // Through the same fixture the command uses, and asserting on what it *kept*. The
            // hand-rolled loop this replaces asserted on receiveEnergy's return, which is the
            // number Mekanism's creative cube lies with -- so this test could have been written
            // against an empty source and still passed. OPEN_ISSUES #81.
            int charged = com.neryos.workbay.command.WorkbayCommands
                .chargeBlock(level, sourcePos).stored();
            if (charged <= 0) {
                helper.fail("could not charge the source Energy Cube through any face, so there is "
                    + "nothing for the link to carry");
            }
            helper.assertValueEqual(inEnergy(level, sourcePos), charged,
                "FE the source reports holding, against what charging it stored");

            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(cube, 1));

            // EXTRACT: the target is the source and the hosted machine is the destination, which is
            // the direction a player uses to charge something they have put away.
            BusConfig link = connect(helper, workbay, 0, sourcePos.above(), player);
            workbay.addBus(link.withResource(BusConfig.Resource.ENERGY)
                // 64 because that is the shipped linkMaxRate, which BusRunner#rate now clamps
                // every move against; a link asking for 20,000 got 64 and said nothing.
                .withMode(BusConfig.Mode.EXTRACT).withRate(64).withSpeed(10));

            int before = inEnergy(level, sourcePos) + inEnergy(backshop, machinePos);
            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inEnergy(backshop, machinePos) <= 0) {
                        throw new GameTestAssertException("no FE has reached the racked Energy Cube "
                            + "yet; the source holds " + inEnergy(level, sourcePos)
                            + " and the link reads " + workbay.busStatus(link.id()));
                    }
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    int arrived = inEnergy(backshop, machinePos);
                    int left = inEnergy(level, sourcePos);
                    test.framework().logger().info("{} FE arrived, {} left at the source, {} before",
                        arrived, left, before);
                    if (arrived + left > before) {
                        helper.fail("the link created energy: " + before + " FE existed, "
                            + (arrived + left) + " FE exists now");
                    }
                    // A generous floor, because the conversion is Mekanism's and rounds per call.
                    // It is here to catch energy vanishing wholesale, not to police the last unit.
                    if (arrived + left < before - before / 100) {
                        helper.fail("the link lost energy: " + before + " FE existed, "
                            + (arrived + left) + " FE exists now");
                    }
                })
                .thenSucceed();
        });
    }

    /**
     * The instrument, not the mod: <b>charging a block reports what the block kept</b>.
     *
     * <p>Mekanism's creative Energy Cube combines every insert with {@code SIMULATE}, so it answers
     * {@code receiveEnergy} with {@code Integer.MAX_VALUE} and stores nothing - and a creative cube
     * taken from JEI or {@code /give}, rather than from the creative tab's second, charged entry,
     * arrives empty and stays empty. Trusting that answer printed <em>"Pushed 2147483646 FE"</em>
     * over an empty cube, and the empty cube then made a healthy energy link read Idle for a
     * session: that is the whole of OPEN_ISSUES #81, and no test could see it because the fixture
     * was the thing that was wrong.
     *
     * <p>Both halves on purpose. A source that keeps nothing must report zero, and a source that
     * really keeps something must report what it kept - or the fix is just a zero.
     */
    @GameTest
    @TestHolder(description = "Charging a block reports the FE it kept, not the FE its handler "
        + "claimed to accept.")
    public static void chargingReportsWhatTheBlockKeptAndNotWhatItClaimed(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 3, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            Block creative = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("mekanism:creative_energy_cube"));
            Block basic = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_energy_cube"));
            if (creative == Blocks.AIR || basic == Blocks.AIR) {
                helper.fail("Mekanism's Energy Cubes are not registered. This test is about a real "
                    + "mod's energy handler, so a missing partner mod is a failure, never a skip.");
            }

            BlockPos liar = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos honest = helper.absolutePos(new BlockPos(3, 1, 3));
            place(level, player, liar, creative);
            place(level, player, honest, basic);

            var lied = com.neryos.workbay.command.WorkbayCommands.chargeBlock(level, liar);
            // If this ever fails, Mekanism stopped lying and the guard below stopped guarding.
            helper.assertTrue(lied.claimed() > 0,
                "the creative cube no longer claims to accept FE, so this test guards nothing");
            helper.assertValueEqual(lied.stored(), 0,
                "FE reported as stored in a creative Energy Cube, which keeps none");
            helper.assertValueEqual(inEnergy(level, liar), 0,
                "FE actually held by a creative Energy Cube after charging it");

            var kept = com.neryos.workbay.command.WorkbayCommands.chargeBlock(level, honest);
            helper.assertTrue(kept.stored() > 0,
                "charging a Basic Energy Cube stored nothing, so the fixture is now useless");
            helper.assertValueEqual(kept.stored(), inEnergy(level, honest),
                "FE reported as stored in a Basic Energy Cube, against what it holds");
            helper.succeed();
        });
    }


    // ------------------------------------------------- rooms and two hands (phase 5, 09-14)

    /** Iron ingots in every container standing inside a room, plus any lying loose on its floor. */
    private static int inRoom(ServerLevel backshop, com.neryos.workbay.world.RoomRecord room,
        Item item) {
        AABB box = com.neryos.workbay.world.RoomGeometry.interiorBox(room.region(), room.tier());
        int total = 0;
        for (BlockPos pos : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ,
            (int) box.maxX, (int) box.maxY, (int) box.maxZ)) {
            total += inContainer(backshop, pos, item);
        }
        for (ItemEntity entity : backshop.getEntitiesOfClass(ItemEntity.class, box.inflate(1))) {
            total += count(entity.getItem(), item);
        }
        return total;
    }

    private static ItemStack roomInHand(GameTestPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (com.neryos.workbay.world.RoomHolding.isRoom(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The one place an ejected room can be: exactly one stamped room item, on one player. */
    private static int roomItems(GameTestPlayer... players) {
        int total = 0;
        for (GameTestPlayer player : players) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (com.neryos.workbay.world.RoomHolding.isRoom(player.getInventory().getItem(slot))) {
                    total += player.getInventory().getItem(slot).getCount();
                }
            }
        }
        return total;
    }

    /** A chest of {@code n} iron in the room in {@code bay}, entered as {@code who} so the chunk is built. */
    private static BlockPos chestOfIron(ExtendedGameTestHelper helper, GameTestPlayer who,
        WorkbayRecord record, int bay, int n) {
        helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(who, record, bay),
            "entering the room to build in it was refused");
        var room = com.neryos.workbay.world.RoomRegistry.get(helper.getLevel().getServer())
            .roomInBay(record, bay).orElseThrow();
        ServerLevel backshop = backshop(helper);
        BlockPos chest = com.neryos.workbay.world.RoomGeometry.origin(room.region()).offset(1, 1, 1);
        backshop.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        put(backshop, chest, 0, new ItemStack(Items.IRON_INGOT, 64));
        put(backshop, chest, 1, new ItemStack(Items.IRON_INGOT, 64));
        put(backshop, chest, 2, new ItemStack(Items.IRON_INGOT, n - 128));
        helper.assertValueEqual(inRoom(backshop, room, Items.IRON_INGOT), n, "iron built into the room");
        com.neryos.workbay.world.RoomVisit.leave(who);
        return chest;
    }

    /**
     * A room with a counted chest goes from one owner's Workbay to another's and back, and the
     * count never moves. Phase 5 case 1: {@code RoomItemTests#aRoomTravelsWithEverythingInIt}
     * proves the move for one owner; this one counts across two networks that share nothing,
     * and counts the old bay and the room's whole interior rather than one slot, so a copy left
     * in the bay or a second chest minted by the move would show. The copy of the item is then
     * refused by name and cannot open the room anywhere (SPEC §0's ticket rule).
     */
    @GameTest
    @TestHolder(description = "A room with 165 counted iron moves between two owners' Workbays and back; 165 stays 165 and a copied item opens nothing.")
    public static void aRoomMovedBetweenTwoOwnersKeepsItsCount(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer alice = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer bob = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ServerLevel backshop = backshop(helper);
            var registry = com.neryos.workbay.world.RoomRegistry.get(helper.getLevel().getServer());
            BlockPos aPos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos bPos = helper.absolutePos(new BlockPos(5, 1, 5));
            WorkbayBlockEntity a = placeWorkbay(helper, aPos, alice);
            WorkbayBlockEntity b = placeWorkbay(helper, bPos, bob);
            helper.assertFalse(a.record().orElseThrow().owner().equals(b.record().orElseThrow().owner()),
                "the two Workbays share an owner, so this proves nothing about two networks");
            WorkbayTickets.force(backshop, a.record().orElseThrow().id(), a.record().orElseThrow().bayColumn());
            WorkbayTickets.force(backshop, b.record().orElseThrow().id(), b.record().orElseThrow().bayColumn());
            BlockPos aBay0 = BayGeometry.machinePos(a.record().orElseThrow().bayColumn(), 0);
            BlockPos bBay0 = BayGeometry.machinePos(b.record().orElseThrow().bayColumn(), 0);
            alice.getInventory().clearContent();
            bob.getInventory().clearContent();

            final int n = 165;
            RoomTests.loadRoom(helper, a.record().orElseThrow(), 0, RoomTests.roomItem(1), alice);
            var room = registry.roomInBay(a.record().orElseThrow(), 0).orElseThrow();
            chestOfIron(helper, alice, a.record().orElseThrow(), 0, n);

            java.util.function.IntSupplier iron = () -> inRoom(backshop, room, Items.IRON_INGOT)
                + inContainer(backshop, aBay0, Items.IRON_INGOT) + inContainer(backshop, bBay0, Items.IRON_INGOT)
                + onPlayer(alice, Items.IRON_INGOT) + onPlayer(bob, Items.IRON_INGOT)
                + loose(helper, Items.IRON_INGOT);

            // A ejects, hands the item to B, B racks it.
            alice.moveTo(aPos.getX() + 0.5, aPos.getY(), aPos.getZ() + 0.5);
            menuFor(a, alice).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack item = roomInHand(alice);
            helper.assertFalse(item.isEmpty(), "A's eject handed A no room item");
            helper.assertValueEqual(roomItems(alice, bob), 1, "room items after A's eject");
            helper.assertTrue(backshop.getBlockState(aBay0).isAir(), "A's bay 0 still holds a block after the eject");
            helper.assertTrue(a.record().orElseThrow().bay(0).room().isEmpty(), "A's bay 0 still names the room");
            helper.assertValueEqual(iron.getAsInt(), n, "iron after A's eject");
            ItemStack copy = item.copy();
            alice.getInventory().clearContent();
            bob.setItemInHand(InteractionHand.MAIN_HAND, item);
            bob.moveTo(bPos.getX() + 0.5, bPos.getY(), bPos.getZ() + 0.5);
            menuFor(b, bob).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(b.record().orElseThrow().bay(0).room().map(room.id()::equals).orElse(false),
                "B's bay 0 does not hold the room");
            helper.assertValueEqual(roomItems(alice, bob), 0, "room items after B racked it");
            helper.assertValueEqual(registry.holderOf(room).map(h -> h.network().id()).orElse(null),
                b.record().orElseThrow().id(), "the room's holder after B racked it");
            helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(bob, b.record().orElseThrow(), 0),
                "B cannot enter the room B holds");
            com.neryos.workbay.world.RoomVisit.leave(bob);
            helper.assertValueEqual(iron.getAsInt(), n, "iron after B racked the room");

            // The copy, same ticket: refused by name, and racking it opens nothing anywhere.
            helper.assertTrue(com.neryos.workbay.world.RoomHolding.refusal(registry, a.record().orElseThrow(),
                Optional.of(GlobalPos.of(helper.getLevel().dimension(), aPos)), copy).isPresent(),
                "a copy of a held room item was not refused");
            alice.setItemInHand(InteractionHand.MAIN_HAND, copy.copy());
            alice.moveTo(aPos.getX() + 0.5, aPos.getY(), aPos.getZ() + 0.5);
            menuFor(a, alice).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(a.record().orElseThrow().bay(0).room().isEmpty(), "the copy opened the room in A");
            helper.assertTrue(backshop.getBlockState(aBay0).isAir(), "the copy put a block in A's bay");
            helper.assertValueEqual(roomItems(alice, bob), 1, "the copy was taken from A's hand");
            alice.getInventory().clearContent();

            // Back to A: B ejects, A racks, still n.
            bob.moveTo(bPos.getX() + 0.5, bPos.getY(), bPos.getZ() + 0.5);
            menuFor(b, bob).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack back = roomInHand(bob);
            helper.assertFalse(back.isEmpty(), "B's eject handed B no room item");
            helper.assertTrue(backshop.getBlockState(bBay0).isAir(), "B's bay 0 still holds a block");
            bob.getInventory().clearContent();
            alice.setItemInHand(InteractionHand.MAIN_HAND, back);
            menuFor(a, alice).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(a.record().orElseThrow().bay(0).room().map(room.id()::equals).orElse(false),
                "A's bay 0 does not hold the room again");
            helper.assertValueEqual(roomItems(alice, bob), 0, "room items after A racked it back");
            helper.assertTrue(b.record().orElseThrow().bay(0).room().isEmpty(), "B's bay 0 still names the room");
            helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(alice, a.record().orElseThrow(), 0),
                "A cannot enter the room A holds again");
            com.neryos.workbay.world.RoomVisit.leave(alice);
            helper.assertValueEqual(iron.getAsInt(), n, "iron after the room came back to A");
            helper.succeed();
        });
    }

    /**
     * A room inside a room. Workbay A holds Room 1; a Workbay standing in Room 1 holds Room 2; a
     * chest in Room 2 holds 190 iron. Room 1 is pulled out of A and racked into B's Workbay; Room
     * 2 is entered from the inner Workbay and the chest still holds 190. Phase 5 case 2. The
     * cycle (Room 1 into the inner Workbay) is {@code RoomItemTests#aCycleIsRefused}; re-asserted
     * here in one line because the item is in hand anyway.
     */
    @GameTest
    @TestHolder(description = "A room nested in a room travels with its 190 counted iron when the outer room changes Workbays.")
    public static void aNestedRoomKeepsItsCountWhenTheOuterRoomMoves(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer alice = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer bob = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ServerLevel backshop = backshop(helper);
            var registry = com.neryos.workbay.world.RoomRegistry.get(helper.getLevel().getServer());
            BlockPos aPos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos bPos = helper.absolutePos(new BlockPos(5, 1, 5));
            WorkbayBlockEntity a = placeWorkbay(helper, aPos, alice);
            WorkbayBlockEntity b = placeWorkbay(helper, bPos, bob);
            WorkbayTickets.force(backshop, a.record().orElseThrow().id(), a.record().orElseThrow().bayColumn());
            WorkbayTickets.force(backshop, b.record().orElseThrow().id(), b.record().orElseThrow().bayColumn());
            alice.getInventory().clearContent();
            bob.getInventory().clearContent();

            RoomTests.loadRoom(helper, a.record().orElseThrow(), 0, RoomTests.roomItem(1), alice);
            var room1 = registry.roomInBay(a.record().orElseThrow(), 0).orElseThrow();
            helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(alice, a.record().orElseThrow(), 0),
                "entering Room 1 was refused");
            BlockPos innerPos = com.neryos.workbay.world.RoomGeometry.origin(room1.region()).offset(2, 1, 2);
            backshop.setBlock(innerPos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(backshop, innerPos, backshop.getBlockState(innerPos), alice,
                new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity inner = (WorkbayBlockEntity) backshop.getBlockEntity(innerPos);
            helper.assertTrue(inner.record().isPresent(), "the Workbay inside Room 1 got no network");
            WorkbayTickets.force(backshop, inner.record().orElseThrow().id(), inner.record().orElseThrow().bayColumn());
            com.neryos.workbay.world.RoomVisit.leave(alice);

            final int n = 190;
            RoomTests.loadRoom(helper, inner.record().orElseThrow(), 0, RoomTests.roomItem(1), alice);
            var room2 = registry.roomInBay(inner.record().orElseThrow(), 0).orElseThrow();
            chestOfIron(helper, alice, inner.record().orElseThrow(), 0, n);
            java.util.function.IntSupplier iron = () -> inRoom(backshop, room2, Items.IRON_INGOT)
                + inRoom(backshop, room1, Items.IRON_INGOT)
                + onPlayer(alice, Items.IRON_INGOT) + onPlayer(bob, Items.IRON_INGOT)
                + loose(helper, Items.IRON_INGOT);
            helper.assertValueEqual(iron.getAsInt(), n, "iron before the outer room moves");

            // Pull Room 1 out of A. The cycle: the inner Workbay stands in it, so it is refused there.
            alice.moveTo(aPos.getX() + 0.5, aPos.getY(), aPos.getZ() + 0.5);
            menuFor(a, alice).act(WorkbayAction.EJECT, 0, Optional.empty());
            ItemStack item = roomInHand(alice);
            helper.assertFalse(item.isEmpty(), "Room 1 did not come out as an item");
            helper.assertTrue(com.neryos.workbay.world.RoomHolding.refusal(registry, inner.record().orElseThrow(),
                Optional.of(GlobalPos.of(WorkbayDimensions.BACKSHOP, innerPos)), item).isPresent(),
                "Room 1 was allowed into the Workbay standing inside it");
            alice.getInventory().clearContent();

            // Into B, then in through both doors.
            bob.setItemInHand(InteractionHand.MAIN_HAND, item);
            bob.moveTo(bPos.getX() + 0.5, bPos.getY(), bPos.getZ() + 0.5);
            menuFor(b, bob).act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(b.record().orElseThrow().bay(0).room().map(room1.id()::equals).orElse(false),
                "B's bay 0 does not hold Room 1");
            helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(bob, b.record().orElseThrow(), 0),
                "B cannot enter Room 1");
            helper.assertTrue(backshop.getBlockState(innerPos).is(WBBlocks.WORKBAY.get()),
                "the inner Workbay is gone from Room 1");
            helper.assertTrue(registry.roomInBay(inner.record().orElseThrow(), 0)
                .map(r -> r.id().equals(room2.id())).orElse(false), "the inner Workbay lost Room 2");
            helper.assertTrue(com.neryos.workbay.world.RoomVisit.enter(alice, inner.record().orElseThrow(), 0),
                "Room 2 cannot be entered from the inner Workbay after the move");
            helper.assertTrue(com.neryos.workbay.world.RoomGeometry.interiorBox(room2.region(), room2.tier())
                .inflate(1).contains(alice.position()), "A did not land inside Room 2");
            helper.assertValueEqual(iron.getAsInt(), n, "iron in the nested room after the outer room moved");
            com.neryos.workbay.world.RoomVisit.leave(alice);
            com.neryos.workbay.world.RoomVisit.leave(bob);
            helper.succeed();
        });
    }

    /**
     * Two players at one screen, each holding a furnace, both Rack into the same empty bay; then
     * both Add on the same Connector; then both Eject the bay with goods inside. Phase 5 case 3.
     * The racking half of {@code twoPlayersEjectingOneBayGetOneMachine}, and the goods count that
     * one did not have: the census is furnaces, raw iron and the chest's iron, and each is what
     * it was.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "Two players racking one bay, adding one Connector and ejecting one bay at once: two furnaces stay two, 7 diamonds stay 7, 64 raw iron stay 64.")
    public static void twoPlayersOnOneScreenNeverDoubleAThing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer first = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer second = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            // Raw iron, because a furnace is only ever offered what it cooks (#120).
            put(level, chestPos, 0, new ItemStack(Items.RAW_IRON, 64));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, first);
            second.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            first.getInventory().clearContent();
            second.getInventory().clearContent();

            java.util.function.IntSupplier furnaces = () -> onPlayer(first, Items.FURNACE)
                + onPlayer(second, Items.FURNACE) + loose(helper, Items.FURNACE)
                + (backshop.getBlockState(machinePos).is(Blocks.FURNACE) ? 1 : 0);
            java.util.function.IntSupplier diamonds = () -> onPlayer(first, Items.DIAMOND)
                + onPlayer(second, Items.DIAMOND) + loose(helper, Items.DIAMOND)
                + inContainer(backshop, machinePos, Items.DIAMOND);
            java.util.function.IntSupplier rawIron = () -> inContainer(level, chestPos, Items.RAW_IRON)
                + inContainer(backshop, machinePos, Items.RAW_IRON) + loose(helper, Items.RAW_IRON)
                + onPlayer(first, Items.RAW_IRON) + onPlayer(second, Items.RAW_IRON);

            // A network is locked at mint and a locked Workbay refuses a stranger every action, so
            // the owner unlocks first -- or the second player's every click is a refusal and the
            // race is never run. Asserted, so this cannot pass vacuously.
            WorkbayMenu mine = menuFor(workbay, first);
            mine.act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.assertTrue(workbay.record().orElseThrow().admits(second.getUUID()),
                "the second player is still refused, so two hands never touch one bay");
            WorkbayMenu theirs = menuFor(workbay, second);

            // Both rack into bay 0, both screens opened before either clicked.
            mine.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            theirs.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.FURNACE, 1));
            second.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.FURNACE, 1));
            mine.act(WorkbayAction.RACK, 0, Optional.empty());
            theirs.act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(backshop.getBlockState(machinePos).is(Blocks.FURNACE), "nobody's furnace was racked");
            helper.assertValueEqual(onPlayer(first, Items.FURNACE) + onPlayer(second, Items.FURNACE), 1,
                "furnaces still in hand after both racked into one bay");
            helper.assertValueEqual(furnaces.getAsInt(), 2, "furnaces after both racked into one bay");

            // Both Add on the same Connector into bay 0; every channel is switched on and pulls.
            BusConfig link = connect(helper, workbay, 0, chestPos.above(), first);
            UUID connectorId = workbay.connectorAt(link.connector()).orElseThrow().id();
            int before = workbay.buses().size();
            mine.act(WorkbayAction.ADD_CHANNEL, 0, Optional.of(connectorId));
            theirs.act(WorkbayAction.ADD_CHANNEL, 0, Optional.of(connectorId));
            helper.assertValueEqual(workbay.buses().size(), before + 2,
                "channels on the Connector after both pressed Add (each Add is one channel)");
            for (BusConfig bus : java.util.List.copyOf(workbay.buses())) {
                workbay.addBus(bus.withEnabled(true).withMode(BusConfig.Mode.EXTRACT).withRate(16).withSpeed(10));
            }
            helper.assertValueEqual(rawIron.getAsInt(), 64, "raw iron after both pressed Add");
            // Goods that came with the machine and that nothing cooks: parked in the result slot.
            put(backshop, machinePos, 2, new ItemStack(Items.DIAMOND, 7));

            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inContainer(backshop, machinePos, Items.RAW_IRON) <= 0) {
                        throw new GameTestAssertException("no raw iron has reached the furnace through any channel");
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(rawIron.getAsInt(), 64, "raw iron with three channels on one Connector"))
                .thenExecute(() -> {
                    // Both eject the bay, with the diamonds and whatever raw iron arrived inside.
                    int arrived = inContainer(backshop, machinePos, Items.RAW_IRON);
                    mine.act(WorkbayAction.EJECT, 0, Optional.empty());
                    theirs.act(WorkbayAction.EJECT, 0, Optional.empty());
                    helper.assertTrue(backshop.getBlockState(machinePos).isAir(), "the bay is not empty after two ejects");
                    helper.assertValueEqual(furnaces.getAsInt(), 2, "furnaces after both pressed Eject");
                    helper.assertValueEqual(diamonds.getAsInt(), 7, "diamonds after both pressed Eject");
                    helper.assertValueEqual(rawIron.getAsInt(), 64, "raw iron after both pressed Eject");
                    test.framework().logger().info("{} raw iron had arrived in the furnace when it was ejected", arrived);
                })
                .thenSucceed();
        });
    }

    /**
     * Transfer-here while a link runs: the network moves from one block to another mid-flow, the
     * link keeps moving on the new block, and nothing is duplicated at the old one. Phase 5 case 5.
     */
    @GameTest(timeoutTicks = 400)
    @TestHolder(description = "Transferring a network to another block while its link runs keeps the link moving and 64 iron stay 64.")
    public static void transferringANetworkMidFlowLosesAndDoublesNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 3, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos fromPos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos toPos = helper.absolutePos(new BlockPos(5, 1, 5));
            BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 5));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            put(level, chestPos, 0, new ItemStack(Items.IRON_INGOT, 64));
            WorkbayBlockEntity from = placeWorkbay(helper, fromPos, player);
            WorkbayRecord record = from.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            player.getInventory().clearContent();
            rack(menuFor(from, player), player, 0, new ItemStack(Blocks.CHEST, 1));
            BusConfig link = connect(helper, from, 0, chestPos.above(), player);
            from.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(4).withSpeed(10));

            // A second block, holding nothing, the way one placed at the limit does.
            level.setBlock(toPos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity to = (WorkbayBlockEntity) level.getBlockEntity(toPos);
            to.energy().deserializeNBT(null, net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));

            java.util.function.IntSupplier iron = () -> inContainer(level, chestPos, Items.IRON_INGOT)
                + inContainer(backshop, machinePos, Items.IRON_INGOT) + loose(helper, Items.IRON_INGOT)
                + onPlayer(player, Items.IRON_INGOT);
            int[] arrivedAtTransfer = { 0 };
            helper.startSequence()
                .thenWaitUntil(() -> {
                    if (inContainer(backshop, machinePos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing has moved through the link yet");
                    }
                })
                .thenExecute(() -> {
                    arrivedAtTransfer[0] = inContainer(backshop, machinePos, Items.IRON_INGOT);
                    player.moveTo(toPos.getX() + 0.5, toPos.getY(), toPos.getZ() + 0.5);
                    menuFor(to, player).act(WorkbayAction.TRANSFER_NETWORK, 0, Optional.of(record.id()));
                    helper.assertValueEqual(to.workbayId().orElse(null), record.id(), "the network after Transfer");
                    helper.assertTrue(from.workbayId().isEmpty(), "the old block still holds the network");
                    helper.assertValueEqual(iron.getAsInt(), 64, "iron the tick the network moved");
                })
                .thenWaitUntil(() -> {
                    if (inContainer(backshop, machinePos, Items.IRON_INGOT) <= arrivedAtTransfer[0]) {
                        throw new GameTestAssertException("the link stopped moving after the Transfer: "
                            + to.busStatus(link.id()));
                    }
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(iron.getAsInt(), 64, "iron after the network moved blocks mid-flow");
                    helper.assertTrue(from.record().isEmpty(), "the old block grew a record back");
                })
                .thenSucceed();
        });
    }

    /**
     * Not a test of the mod: the rig for phase 5 case 4, which a gametest cannot run (a server
     * has to be stopped and killed). Builds a chest of 1728 iron in the overworld feeding a
     * racked chest at 64 a move, force-loads its chunks so the link runs with nobody online, and
     * logs the positions. {@code run-gametest/world} is then copied under a dedicated server and
     * counted with {@code night/n4/5-count.py} after every stop and kill.
     */
    @GameTest
    @TestHolder(description = "Rig for the dedicated-server stop/kill count (phase 5 case 4). Passes when built.")
    public static void rigAChestFeedingABayForTheServerKillCount(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 3, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            for (int slot = 0; slot < 27; slot++) {
                put(level, chestPos, slot, new ItemStack(Items.IRON_INGOT, 64));
            }
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = backshop(helper);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            player.getInventory().clearContent();
            rack(menuFor(workbay, player), player, 0, new ItemStack(Blocks.CHEST, 1));
            BusConfig link = connect(helper, workbay, 0, chestPos.above(), player);
            // Switched on but slow enough that the whole stock is still moving when the server
            // is stopped: 64 a move, one move a second.
            workbay.addBus(link.withMode(BusConfig.Mode.EXTRACT).withRate(64).withSpeed(20));
            level.setChunkForced(workbayPos.getX() >> 4, workbayPos.getZ() >> 4, true);
            level.setChunkForced(chestPos.getX() >> 4, chestPos.getZ() >> 4, true);
            test.framework().logger().info("PHASE5 RIG workbay {} chest {} bay0 {} network {} column {}",
                workbayPos.toShortString(), chestPos.toShortString(), machinePos.toShortString(),
                record.id(), record.bayColumn());
            helper.assertValueEqual(inContainer(level, chestPos, Items.IRON_INGOT)
                + inContainer(backshop, machinePos, Items.IRON_INGOT), 1728, "iron in the rig");
            helper.succeed();
        });
    }

    /** A block put down the way a player puts it down, from a bare item - components and all. */
    private static void place(ServerLevel level, GameTestPlayer player, BlockPos pos, Block block) {
        BlockState state = block.defaultBlockState();
        ItemStack stack = new ItemStack(block);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        net.minecraft.world.item.BlockItem.updateCustomBlockEntityTag(level, player, pos, stack);
        if (level.getBlockEntity(pos) != null) {
            level.getBlockEntity(pos).applyComponentsFromItemStack(stack);
        }
        block.setPlacedBy(level, pos, state, player, stack);
        level.invalidateCapabilities(pos);
    }

    private static BusConfig connect(ExtendedGameTestHelper helper, WorkbayBlockEntity workbay,
        int bay, BlockPos at, GameTestPlayer player) {
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
            workbay.connectorAt(here).orElseThrow().id(), bay);

        var links = workbay.buses();
        if (links.isEmpty()) {
            throw new GameTestAssertException("placing a paired Connector did not create a link");
        }
        // Bay to target, as every test here was written before a channel was born EXTRACT (#56).
        BusConfig link = links.get(links.size() - 1).withEnabled(true).withRate(16).withSpeed(10)
            .withMode(BusConfig.Mode.INSERT);
        workbay.addBus(link);
        return link;
    }
}
