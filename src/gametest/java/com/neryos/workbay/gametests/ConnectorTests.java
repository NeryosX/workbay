package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/**
 * The Connector is the only way a link comes into existence, so these cover the whole life of one:
 * pairing, placing, the many channels one Connector may carry, and what breaking it takes away.
 *
 * <p>SPEC.md §0 rejected a configuration-only link precisely so this life cycle exists - a link the
 * player cannot see, find or break in the world is a link they cannot debug either.
 */
@ForEachTest(groups = "connector")
public class ConnectorTests {

    private static WorkbayBlockEntity placeWorkbay(ExtendedGameTestHelper helper, BlockPos pos,
        GameTestPlayer player) {
        ServerLevel level = helper.getLevel();
        level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
        WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), player,
            new ItemStack(WBBlocks.WORKBAY.get()));
        WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
        // Powered, because a real one has to be: SPEC.md §9 charges the buffer for every
        // link that is switched on and again for every move, so an unfed Workbay runs
        // nothing and every link on it reads NO_POWER. OPEN_ISSUES #72. A test that is not
        // about the bill pays it up front and says so here.
        workbay.energy().deserializeNBT(null,
            net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
        return workbay;
    }

    private static ItemStack paired(ServerLevel level, WorkbayBlockEntity workbay) {
        ItemStack stack = new ItemStack(WBBlocks.CONNECTOR.get());
        WorkbayBlock.pair(stack, workbay.record().orElseThrow(),
            GlobalPos.of(level.dimension(), workbay.getBlockPos()));
        return stack;
    }

    private static void place(ServerLevel level, BlockPos at, Direction facing, ItemStack stack,
        GameTestPlayer player) {
        BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
            .setValue(ConnectorBlock.FACING, facing);
        level.setBlock(at, state, Block.UPDATE_ALL);
        WBBlocks.CONNECTOR.get().setPlacedBy(level, at, state, player, stack);
    }

    /** Right-clicking the placed Connector with an empty hand, exactly as a player would. */
    private static void poke(ServerLevel level, BlockPos at, GameTestPlayer player) {
        level.getBlockState(at).useWithoutItem(level, player,
            new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(at), Direction.UP, at, false));
    }

    /**
     * The player's own sequence, in order: right-click the Workbay with a Connector, place the
     * Connector on a chest, and the LINKS list has a row pointing at that chest.
     */
    /**
     * <b>A Connector placed the way a player places one: the item, against a container's face.</b>
     *
     * <p>Every other test in this file reaches for {@code setPlacedBy} directly, which skips
     * {@code BlockItem#useOn} - so it skips {@code getStateForPlacement}, {@code canSurvive}, and
     * the whole question of whether the container swallows the right-click first. Driving a client
     * by hand, a sneak-right-click that put a Connector on a room's floor would not put one on a
     * barrel beside it, and nothing in the suite could say whether that was the mod or the driving.
     *
     * <p>All six faces, and a chest as well as a barrel, because the two differ in exactly the way
     * that would matter: a chest has a lid and refuses to open with a block above it.
     */
    @GameTest
    @TestHolder(description = "A Connector item places on every face of a barrel and a chest, the way a player places one.")
    public static void aConnectorItemPlacesOnEveryFaceOfAContainer(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 9));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            net.minecraft.world.level.block.Block[] containers = {
                net.minecraft.world.level.block.Blocks.BARREL,
                net.minecraft.world.level.block.Blocks.CHEST,
            };
            int at = 0;
            for (net.minecraft.world.level.block.Block container : containers) {
                for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
                    BlockPos box = helper.absolutePos(new BlockPos(1 + (at % 4) * 2, 1,
                        1 + (at / 4) * 2));
                    at++;
                    level.setBlock(box, container.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
                    BlockPos plate = box.relative(face);
                    if (!level.getBlockState(plate).isAir()) {
                        continue;
                    }

                    ItemStack held = new ItemStack(WBBlocks.CONNECTOR.get());
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
                    // Sneaking, which is what a player does to place against a container -- and
                    // the hit is on the face the plate is going onto, exactly as a crosshair
                    // reports it.
                    player.setShiftKeyDown(true);
                    held.useOn(new net.minecraft.world.item.context.UseOnContext(player,
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(box)
                                .add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                                    face.getStepZ() * 0.5),
                            face, box, false)));
                    player.setShiftKeyDown(false);

                    helper.assertTrue(level.getBlockState(plate).is(WBBlocks.CONNECTOR.get()),
                        "a Connector would not go on the " + face.getName() + " face of a "
                            + container.getName().getString() + ": " + plate + " holds "
                            + level.getBlockState(plate));
                    helper.assertValueEqual(level.getBlockState(plate)
                            .getValue(com.neryos.workbay.content.connector.ConnectorBlock.FACING),
                        face.getOpposite(),
                        "the plate on the " + face.getName() + " face pointing back at the block");
                }
            }
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "Pairing a Connector and placing it creates a link on the Workbay.")
    public static void placingAPairedConnectorMakesNoChannelAndOffersIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);

            // Pairing is what the right-click on the Workbay does, and it has to reach the item.
            ItemStack stack = paired(level, workbay);
            if (stack.get(WBDataComponents.PAIRING.get()) == null) {
                helper.fail("pairing a Connector left no pairing component on the item");
                return;
            }
            helper.assertValueEqual(workbay.connectors().size(), 0,
                "Connectors the network holds before this one is placed");

            place(level, chestPos.above(), Direction.DOWN, stack, player);

            helper.assertValueEqual(workbay.buses().size(), 0,
                "channels after placing a Connector - placing one never mints a channel");
            WorkbayRecord.Connector known = workbay
                .connectorAt(GlobalPos.of(level.dimension(), chestPos.above()))
                .orElseThrow(() -> new net.minecraft.gametest.framework.GameTestAssertException(
                    "placing a paired Connector did not put it on the network, so no bay's Add "
                        + "list can offer it and the block does nothing forever"));
            if (!known.target().pos().equals(chestPos)) {
                helper.fail("the Connector points at " + known.target().pos() + ", not at the chest "
                    + "it was placed against (" + chestPos + ")");
                return;
            }
            // What it points at, remembered on the Connector. The server can only read the far
            // block while its chunk is loaded, which for a real base is almost never -- so without
            // this stamp the Add list has nothing to call it but two coordinates and the flow map
            // draws a box with no icon. This is the one moment the block is guaranteed to be
            // there, so this is where it has to be taken.
            helper.assertValueEqual(known.targetBlock().orElse(null),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.CHEST),
                "the block the Connector remembers being placed against");
            helper.succeed();
        });
    }

    /**
     * <b>Nothing is ever created automatically, and the right-click is a rename.</b> OPEN_ISSUES
     * #77. A Connector may carry as many rows as the player pulls in -- that is
     * {@code aRecordKeepsFourChannelsOnOneConnector} and
     * {@code pullingAConnectorIntoItsOwnBayAgainIsASecondRow} -- but every one of them is a thing
     * the player asked for on a bay screen. The world gesture makes none: after right-clicking,
     * the player has the Connector's rename panel open and the link is the one that was there
     * before, untouched, on the bay it was paired to. Poked six times over a two-bay network.
     */
    @GameTest
    @TestHolder(description = "A placed Connector is one row however often it is right-clicked.")
    public static void oneConnectorIsOneRowHoweverOftenItIsPoked(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);

            int bays = workbay.record().orElseThrow().bayCapacity();
            helper.assertTrue(bays >= 2, "a base Workbay is supposed to have two bays, not " + bays);
            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            com.neryos.workbay.menu.WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
                workbay.connectorAt(here).orElseThrow().id(), 0);
            for (int attempt = 0; attempt < bays + 4; attempt++) {
                poke(level, connectorPos, player);
            }

            helper.assertTrue(
                player.containerMenu instanceof com.neryos.workbay.menu.ConnectorMenu,
                "right-clicking a placed Connector left the player with "
                    + player.containerMenu.getClass().getSimpleName()
                    + " open, not its rename panel");
            helper.assertValueEqual(workbay.linksAt(here).size(), 1,
                "channels one Connector holds after being right-clicked " + (bays + 4) + " times");
            helper.assertValueEqual(workbay.buses().size(), 1,
                "channels on the whole Workbay after one Connector was poked past every bay");
            helper.assertValueEqual(workbay.buses().getFirst().bay(), 0,
                "the bay the one channel sits on after every one of those right-clicks");
            helper.succeed();
        });
    }

    /**
     * <b>Four channels on one Connector, off a disk, all four kept.</b> This is the product
     * (SPEC.md 0): the same Connector on the same machine carries items in on one row and energy
     * out on the next, and each row has its own resource, direction, filter, rate and name.
     *
     * <p>Written against {@link WorkbayRecord} directly because the thing that used to break this
     * lived there -- a compact constructor that folded every non-internal link sharing a
     * {@code GlobalPos} down to one, which is #77 read too literally. Two internal links are in
     * the list because they were the fold's exemption, and an exemption that outlives its rule is
     * the next quiet bug.
     */
    @GameTest
    @TestHolder(description = "A record holding four links on one Connector keeps all four.")
    public static void aRecordKeepsFourChannelsOnOneConnector(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GlobalPos connector = GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(2, 1, 2)));
            GlobalPos target = GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(2, 1, 1)));
            GlobalPos anchor = GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(0, 1, 0)));

            java.util.List<BusConfig> saved = java.util.List.of(
                BusConfig.create(java.util.UUID.randomUUID(), 0, BusConfig.Resource.ITEM,
                    BusConfig.Mode.EXTRACT, connector, target),
                BusConfig.create(java.util.UUID.randomUUID(), 0, BusConfig.Resource.FLUID,
                    BusConfig.Mode.EXTRACT, connector, target),
                BusConfig.create(java.util.UUID.randomUUID(), 0, BusConfig.Resource.ENERGY,
                    BusConfig.Mode.INSERT, connector, target),
                BusConfig.create(java.util.UUID.randomUUID(), 1, BusConfig.Resource.ITEM,
                    BusConfig.Mode.INSERT, connector, target),
                BusConfig.createInternal(java.util.UUID.randomUUID(), 0, anchor, target),
                BusConfig.createInternal(java.util.UUID.randomUUID(), 1, anchor, target));

            WorkbayRecord record = new WorkbayRecord(java.util.UUID.randomUUID(), "TEST-CODE-0000", "Workbay 1",
                java.util.UUID.randomUUID(), "tester", false,
                new net.minecraft.world.level.ChunkPos(0, 0),
                com.neryos.workbay.world.WorkbayRecord.Upgrades.NONE,
                java.util.Optional.empty(), java.util.List.of(), saved, 1,
                java.util.List.of());

            helper.assertValueEqual(record.buses().stream()
                .filter(bus -> !bus.internal()).count(), 4L,
                "links left on one Connector after a saved record was read back");
            helper.assertValueEqual(record.buses().stream()
                .filter(bus -> !bus.internal()).map(BusConfig::resource)
                .distinct().count(), 3L,
                "distinct resources those four rows carry");
            helper.assertValueEqual(record.buses().stream().filter(BusConfig::internal).count(), 2L,
                "bay-to-bay links, which anchor on the Workbay and must not fold into each other");
            helper.succeed();
        });
    }

    /**
     * <b>How a channel is made, and how a second one on the same bay is.</b> Placing a Connector
     * mints nothing at all; the Add list on a bay screen offers every Connector the network holds,
     * and ticking one gives that bay a channel - again and again, as many as the player wants.
     *
     * <p>Fresh is the point: each new row starts on ITEM, INSERT, off and unfiltered, so it is a
     * channel of its own rather than a copy of the row beside it.
     */
    @GameTest
    @TestHolder(description = "Placing a Connector makes no channel, and Add makes one every time.")
    public static void addingAConnectorToOneBayTwiceIsTwoChannels(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);

            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            helper.assertValueEqual(workbay.buses().size(), 0,
                "channels after placing a Connector - nothing is ever created by itself");
            java.util.UUID connectorId = workbay.connectorAt(here).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException(
                    "placing a paired Connector did not put it on the network, so no bay can "
                        + "offer it")).id();

            int bay = 0;
            // The menu's own reach check is eight blocks, and a mock player is created wherever
            // the test structure happens to land. MenuTests#menuFor does the same.
            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            com.neryos.workbay.menu.WorkbayMenu menu = new com.neryos.workbay.menu.WorkbayMenu(
                1, player.getInventory(), workbay,
                com.neryos.workbay.menu.WorkbayMenu.build(workbay, player, bay));
            menu.act(com.neryos.workbay.menu.WorkbayAction.ADD_CHANNEL, bay,
                java.util.Optional.of(connectorId));
            menu.act(com.neryos.workbay.menu.WorkbayAction.ADD_CHANNEL, bay,
                java.util.Optional.of(connectorId));

            helper.assertValueEqual(workbay.linksAt(here).size(), 2,
                "channels on one Connector after adding it to one bay twice");
            helper.assertTrue(workbay.buses().stream().allMatch(bus -> bus.bay() == bay),
                "every row landed on the bay it was added to");
            helper.assertValueEqual(workbay.buses().stream().map(BusConfig::id).distinct().count(),
                2L, "distinct link ids, so the rows are their own channels and not one row twice");
            helper.succeed();
        });
    }

    /**
     * The block <em>is</em> the link. Breaking it has to take the link with it, or a player who
     * tidies up their base is left with rows pointing at nothing and no way to clear them.
     */
    @GameTest
    @TestHolder(description = "Breaking a Connector removes the links it anchored.")
    public static void breakingAConnectorRemovesItsLinks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);
            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            com.neryos.workbay.menu.WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
                workbay.connectorAt(here).orElseThrow().id(), 0);
            helper.assertValueEqual(workbay.buses().size(), 1, "channels after adding it to bay 1");

            level.setBlock(connectorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertValueEqual(workbay.buses().size(), 0,
                "channels after breaking the Connector");
            helper.assertValueEqual(workbay.connectors().size(), 0,
                "Connectors the network still offers after this one was broken");
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #112, the root: a Connector reached its Workbay by the <em>position</em> on its
     * pairing, so once the Workbay had been broken and re-placed somewhere else, breaking the
     * Connector found nobody and the record kept it - and kept offering it on every Add list.
     * Proven off {@code run/saves/Correction}: "Ore feed" listed with air where its block was.
     */
    @GameTest
    @TestHolder(description = "Breaking a Connector after its Workbay moved still takes it off the record.")
    public static void aConnectorBrokenAfterItsWorkbayMovedLeavesNoRecord(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos first = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos second = helper.absolutePos(new BlockPos(6, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 6));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, first, player);
            java.util.UUID network = workbay.workbayId().orElseThrow();
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);

            // The Workbay moves: broken, the network sleeps; placed again by its owner, it wakes.
            level.setBlock(first, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity moved = placeWorkbay(helper, second, player);
            helper.assertValueEqual(moved.workbayId().orElseThrow(), network,
                "the re-placed Workbay's network");
            helper.assertValueEqual(moved.connectors().size(), 1, "Connectors before the break");

            level.setBlock(connectorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertValueEqual(moved.connectors().size(), 0,
                "Connectors the record still offers after the block was broken");
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #112, the loss: a Connector whose block is gone but whose record survived (a
     * world edit, or the break above before it was fixed) still carries the channels the player
     * took off a bay with the X. Add attached one, the runner read CONNECTOR_GONE, the sweep
     * deleted it - the header went 12 to 11 and a named, filtered channel was gone for good.
     * Add refuses instead, with the reason, and the waiting channel is still there afterwards.
     */
    @GameTest
    @TestHolder(description = "Add on a Connector whose block is gone refuses, and the waiting channel survives.")
    public static void addOnAGoneConnectorRefusesAndKeepsTheWaitingChannel(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);
            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            java.util.UUID connectorId = workbay.connectorAt(here).orElseThrow().id();
            com.neryos.workbay.menu.WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
                connectorId, 0);
            BusConfig made = workbay.buses().get(0);
            // Taken off the bay with the X: waiting, and still the player's.
            workbay.addBus(made.withBay(BusConfig.NO_BAY).withName("T"));
            WorkbayRecord stale = workbay.record().orElseThrow();

            // The block goes, the record does not: put back what the break took off.
            level.setBlock(connectorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            RoomRegistry.get(level.getServer()).put(stale);
            helper.assertValueEqual(workbay.connectors().size(), 1, "the stale Connector, listed");
            helper.assertValueEqual(workbay.buses().size(), 1, "the waiting channel, before Add");

            net.minecraft.network.chat.Component refusal = com.neryos.workbay.menu.WorkbayMenu
                .addChannel(workbay, workbay.record().orElseThrow(), connectorId, 0);
            helper.assertTrue(refusal != null, "Add on a gone Connector must refuse with a reason");
            helper.runAfterDelay(40, () -> {
                BusConfig kept = workbay.bus(made.id()).orElseThrow(() ->
                    new net.minecraft.gametest.framework.GameTestAssertException(
                        "the waiting channel was eaten"));
                helper.assertTrue(kept.detached(), "the waiting channel is still waiting");
                helper.assertValueEqual(kept.name(), "T", "and still the player's");
                helper.assertValueEqual(workbay.buses().size(), 1, "channels after the refused Add");
                helper.succeed();
            });
        });
    }

    /**
     * An unpaired Connector is an ordinary block that does nothing. It must not attach itself to
     * whichever Workbay happens to be nearby - a link nobody asked for is worse than no link.
     */
    @GameTest
    @TestHolder(description = "An unpaired Connector makes no link.")
    public static void anUnpairedConnectorMakesNoLink(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, chestPos.above(), Direction.DOWN, new ItemStack(WBBlocks.CONNECTOR.get()), player);

            helper.assertValueEqual(workbay.buses().size(), 0, "links from an unpaired Connector");
            helper.succeed();
        });
    }

    /**
     * The Connector's half of {@link WorkbayBlockTests#workbayDropsWhenMinedByAPlayer}: the same
     * missing {@code minecraft:mineable/pickaxe} tag would silently swallow a Connector too, and a
     * player who mines one back up expects it back in hand like any other block.
     */
    @GameTest
    @TestHolder(description = "A player mining a Connector with a pickaxe gets the item back.")
    public static void connectorDropsWhenMinedByAPlayer(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos chestPos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos connectorPos = chestPos.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(connectorPos, state, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, connectorPos, state, player,
                new ItemStack(WBBlocks.CONNECTOR.get()));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));

            player.gameMode.destroyBlock(connectorPos);

            if (!level.getBlockState(connectorPos).isAir()) {
                helper.fail("the Connector was still there after destroyBlock");
                return;
            }
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(connectorPos).inflate(1.5));
            if (drops.stream().noneMatch(e -> e.getItem().is(WBBlocks.CONNECTOR.get().asItem()))) {
                helper.fail("mining a Connector with a diamond pickaxe dropped nothing. Check the "
                    + "minecraft:mineable/pickaxe block tag.");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>Rule 1 of SPEC.md §0: a Connector has one name.</b> Renaming it from the panel its
     * right-click opens changes it everywhere that Connector appears - every channel, on every bay.
     *
     * <p>OPEN_ISSUES #97: the name used to be a field on a channel, so the panel wrote to whichever
     * one it found first. With four channels, three kept the coordinates and one got the name, on a
     * screen titled "Name this Connector". The name is a field of the Connector now and no channel
     * has one of its own to disagree with.
     *
     * <p>Asserted off the snapshot the screens actually draw, not off the record, because the
     * stamping that carries the Connector's name onto each row is the half that could be missed -
     * and a second Connector standing beside it proves the rename reached one object, not all of
     * them.
     */
    @GameTest
    @TestHolder(description = "Renaming a Connector in the world renames every channel it carries, on every bay.")
    public static void renamingAConnectorRenamesEveryChannelOnEveryBay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();
            BlockPos otherChest = helper.absolutePos(new BlockPos(2, 1, 4));
            BlockPos otherConnector = otherChest.above();

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(otherChest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);
            place(level, otherConnector, Direction.DOWN, paired(level, workbay), player);

            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);
            GlobalPos there = GlobalPos.of(level.dimension(), otherConnector);
            java.util.UUID id = workbay.connectorAt(here).orElseThrow().id();

            // Four channels through one Connector, spread over both bays of a base Workbay: this is
            // the shape the old panel got wrong.
            player.moveTo(workbayPos.getX() + 0.5, workbayPos.getY(), workbayPos.getZ() + 0.5);
            com.neryos.workbay.menu.WorkbayMenu menu = new com.neryos.workbay.menu.WorkbayMenu(
                1, player.getInventory(), workbay,
                com.neryos.workbay.menu.WorkbayMenu.build(workbay, player, 0));
            for (int bay : new int[] { 0, 0, 1, 1 }) {
                menu.act(com.neryos.workbay.menu.WorkbayAction.ADD_CHANNEL, bay,
                    java.util.Optional.of(id));
            }
            menu.act(com.neryos.workbay.menu.WorkbayAction.ADD_CHANNEL, 1,
                java.util.Optional.of(workbay.connectorAt(there).orElseThrow().id()));
            helper.assertValueEqual(workbay.linksAt(here).size(), 4,
                "channels on the Connector being renamed");

            // The player's own gesture: right-click the placed Connector, type, press Save.
            com.neryos.workbay.menu.ConnectorMenu.open(player, connectorPos);
            if (!(player.containerMenu instanceof com.neryos.workbay.menu.ConnectorMenu panel)) {
                helper.fail("right-clicking a placed Connector did not open its rename panel");
                return;
            }
            panel.act(com.neryos.workbay.menu.WorkbayAction.SET_CONNECTOR_NAME, "Ore feed", player);

            // Off the snapshot, because that is what every screen in the mod reads.
            com.neryos.workbay.menu.WorkbaySnapshot snap =
                com.neryos.workbay.menu.WorkbayMenu.build(workbay, player, 0);
            java.util.List<com.neryos.workbay.menu.WorkbaySnapshot.Link> renamed = snap.links()
                .stream().filter(link -> link.config().connector().equals(here)).toList();
            helper.assertValueEqual(renamed.size(), 4, "rows of the renamed Connector on the screen");
            for (com.neryos.workbay.menu.WorkbaySnapshot.Link link : renamed) {
                helper.assertValueEqual(link.label().orElse(""), "Ore feed",
                    "the name on the bay " + (link.config().bay() + 1) + " row of the renamed "
                        + "Connector");
            }
            helper.assertTrue(snap.links().stream()
                .filter(link -> link.config().connector().equals(there))
                .allMatch(link -> link.label().isEmpty()),
                "the Connector next to it kept its own name, so the rename named one object");
            helper.assertValueEqual(workbay.connectorAt(here).orElseThrow().name(), "Ore feed",
                "the name the network stored for the Connector");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 8. Right-clicking a Workbay with a Connector paired it to that network
     * whoever held it, so a stranger's anvil-named Connector landed in the owner's Add list. The
     * world gesture now honours the lock the way the menu does.
     */
    @GameTest
    @TestHolder(description = "A stranger right-clicking a locked Workbay with a Connector pairs nothing; the owner pairs.")
    public static void aStrangerCannotPairAConnectorOnALockedWorkbay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, owner);
            if (!workbay.record().orElseThrow().locked()) {
                helper.fail("a fresh Workbay is not locked");
                return;
            }
            var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(workbayPos), Direction.UP, workbayPos, false);

            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ItemStack theirs = new ItemStack(WBBlocks.CONNECTOR.get());
            stranger.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, theirs);
            level.getBlockState(workbayPos).useItemOn(theirs, level, stranger,
                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(theirs.get(WBDataComponents.PAIRING.get()) == null,
                "a stranger paired a Connector on somebody else's locked Workbay");

            ItemStack own = new ItemStack(WBBlocks.CONNECTOR.get());
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, own);
            level.getBlockState(workbayPos).useItemOn(own, level, owner,
                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(own.get(WBDataComponents.PAIRING.get()) != null,
                "the owner could not pair a Connector on their own locked Workbay");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A, question 6. Nothing asked whether the placer may reach the block the
     * Connector was going onto -- the hopper-across-a-claim-border class. Placement now posts a
     * {@code RightClickBlock} for the target, which is what claim mods cancel, and refuses on a
     * cancel or a {@code useBlock} of FALSE.
     */
    @GameTest
    @TestHolder(description = "A Connector cannot be placed against a block the placer may not right-click.")
    public static void aConnectorRefusesATargetTheClaimForbids(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(0, 1, 0)), player);
            BlockPos chest = helper.absolutePos(new BlockPos(2, 1, 2));
            BlockPos plate = chest.above();
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            // A claim mod, in one line: nobody may right-click the chest.
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock> claim =
                event -> {
                    if (event.getPos().equals(chest)) {
                        event.setCanceled(true);
                    }
                };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(claim);
            try {
                placeByHand(level, player, paired(level, workbay), chest, Direction.UP);
                helper.assertTrue(level.getBlockState(plate).isAir(),
                    "a Connector went onto a block the placer may not right-click");
                helper.assertTrue(workbay.connectors().isEmpty(),
                    "a refused Connector was still registered on the network");
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(claim);
            }

            // Positive control: with the claim gone, the same gesture places and registers.
            placeByHand(level, player, paired(level, workbay), chest, Direction.UP);
            helper.assertTrue(level.getBlockState(plate).is(WBBlocks.CONNECTOR.get()),
                "the Connector would not go on the chest once nothing forbade it");
            helper.assertValueEqual(workbay.connectors().size(), 1, "Connectors on the network");
            helper.succeed();
        });
    }

    /** The item, used on a face of a block, exactly as a sneaking player's crosshair reports it. */
    private static void placeByHand(ServerLevel level, GameTestPlayer player, ItemStack held,
        BlockPos box, Direction face) {
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        player.setShiftKeyDown(true);
        held.useOn(new net.minecraft.world.item.context.UseOnContext(player,
            net.minecraft.world.InteractionHand.MAIN_HAND,
            new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(box)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5),
                face, box, false)));
        player.setShiftKeyDown(false);
    }
    /**
     * Night 2026-09-11, 1A #14. The rename used to go through the Workbay's block entity, which
     * meant loading the Workbay's chunk synchronously on the server thread for every Save. The
     * Connector's pairing already names the network, and the registry knows the Connector; so the
     * rename works with no Workbay block standing at all -- which is also the proof it loads no
     * chunk.
     */
    @GameTest
    @TestHolder(description = "Renaming a Connector goes through the registry, not the Workbay's block.")
    public static void renamingAConnectorNeedsNoWorkbayBlock(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));
            BlockPos connectorPos = chestPos.above();
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            java.util.UUID network = workbay.workbayId().orElseThrow();
            place(level, connectorPos, Direction.DOWN, paired(level, workbay), player);
            GlobalPos here = GlobalPos.of(level.dimension(), connectorPos);

            // The Workbay is gone (the network sleeps, the record and its Connectors stay): the
            // block-entity path has nothing to find, the registry path does.
            level.removeBlock(workbayPos, false);
            helper.assertFalse(level.getBlockEntity(workbayPos) instanceof WorkbayBlockEntity,
                "the Workbay block is still standing, so this proves nothing about the chunk");

            new com.neryos.workbay.menu.ConnectorMenu(1,
                new com.neryos.workbay.menu.ConnectorMenu.View(connectorPos, "", "", 0, true))
                .act(com.neryos.workbay.menu.WorkbayAction.SET_CONNECTOR_NAME, "Ore feed", player);

            helper.assertValueEqual(RoomRegistry.get(level.getServer()).byId(network).orElseThrow()
                    .connectorAt(here).map(WorkbayRecord.Connector::name).orElse("<gone>"),
                "Ore feed", "the Connector's name on the record after a rename with no Workbay block");
            helper.succeed();
        });
    }
}
