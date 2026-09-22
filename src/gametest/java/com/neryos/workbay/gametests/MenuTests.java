package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.content.workbay.WorkbayUpgrade;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBItems;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.menu.WorkbaySnapshot;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.FaceConfig;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

import java.util.Optional;

/**
 * What the screens actually do, tested where the work happens. SPEC.md §4.
 *
 * <p>None of this touches a client. Every button on the bays and upgrades screens turns into one
 * {@link WorkbayAction} on {@link WorkbayMenu}, so driving the menu directly tests the same code
 * path a click takes, minus the pixels.
 */
@ForEachTest(groups = "menu")
public class MenuTests {

    /**
     * "All ores into this chest" as one row rather than nine. SPEC.md §5, OPEN_ISSUES #33.
     *
     * <p>Three things have to hold and each has caught a different mistake in this feature. The
     * ring has to <b>start at the item</b>, so a filter behaves exactly as it always did until
     * somebody asks for a tag. Stepping has to <b>keep the item</b>, so the row still has a sprite
     * and a way back. And the tag has to be what the filter actually <b>matches on</b> - the
     * interesting half, and the one a UI-only change would have left undone.
     */
    @GameTest
    @TestHolder(description = "A filter row can be stepped onto a tag, and matches everything in it.")
    public static void aFilterRowSteppedOntoATagMatchesTheWholeTag(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(0, 1, 0)), player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());

            BlockPos chest = helper.absolutePos(new BlockPos(2, 1, 2));
            helper.getLevel().setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            GlobalPos where = GlobalPos.of(helper.getLevel().dimension(), chest);
            BusConfig link = BusConfig.create(java.util.UUID.randomUUID(), 0,
                BusConfig.Resource.ITEM, BusConfig.Mode.INSERT, where, where);
            workbay.addBus(link);
            WorkbayMenu menu = menuFor(workbay, player);

            // One iron ingot in slot 0, the ordinary way.
            menu.act(WorkbayAction.SET_FILTER,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.IRON_INGOT) + 1L,
                Optional.of(link.id()));
            helper.assertTrue(workbay.bus(link.id()).orElseThrow().filter()
                    .at(0).orElseThrow().tag().isEmpty(),
                "a row starts on the item itself, not on one of its tags");
            helper.assertFalse(workbay.bus(link.id()).orElseThrow().filter()
                    .allows(new ItemStack(Items.GOLD_INGOT)),
                "a row on the item must still match only that item");

            // Step it until it lands on an ingot tag, which is the one a player is reaching for.
            // Bounded: the ring is the item plus its tags, so it cannot be longer than that.
            com.neryos.workbay.bus.BusFilter reached = null;
            for (int step = 0; step < 24; step++) {
                menu.act(WorkbayAction.CYCLE_FILTER_TAG, 0, Optional.of(link.id()));
                var filter = workbay.bus(link.id()).orElseThrow().filter();
                var tag = filter.at(0).orElseThrow().tag();
                helper.assertValueEqual(filter.at(0).orElseThrow().id(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT),
                    "the item a stepped row still remembers");
                if (tag.isPresent() && tag.get().toString().equals("c:ingots")) {
                    reached = filter;
                    break;
                }
            }
            if (reached == null) {
                helper.fail("stepping the row never reached #c:ingots: it reads "
                    + workbay.bus(link.id()).orElseThrow().filter().at(0));
                return;
            }
            helper.assertTrue(reached.allows(new ItemStack(Items.GOLD_INGOT)),
                "a row on #c:ingots has to carry a gold ingot, which is the whole point of it");
            helper.assertFalse(reached.allows(new ItemStack(Items.COBBLESTONE)),
                "a row on #c:ingots must not carry cobblestone");
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #15, which rode along unverified for the whole project.
     *
     * <p>SPEC.md §10's step 2 is {@code BlockItem.updateCustomBlockEntityTag}, and that method
     * loads nothing at all when the block entity says {@code onlyOpCanSetNbt} and the placer is not
     * a game master. A spawner carrying its settings would have been racked as a fresh one, with no
     * message and no way to notice until the mob that came out was the wrong mob.
     *
     * <p>Both halves are asserted, because a refusal that also refuses a plain spawner would be a
     * different bug wearing this one's fix: an ordinary spawner item carries no data and still
     * racks.
     */
    @GameTest
    @TestHolder(description = "A block whose settings only an operator may place is refused, not stripped.")
    public static void aStackWhoseDataWouldBeStrippedIsRefused(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(0, 1, 0)), player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            WorkbayMenu menu = menuFor(workbay, player);
            BlockPos hosted = BayGeometry.machinePos(record.bayColumn(), 0);

            // A spawner out of the creative menu carries nothing, so nothing can be lost.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.SPAWNER));
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(backshop.getBlockState(hosted).is(Blocks.SPAWNER),
                "a plain spawner carries no settings and must rack as it always did");
            menu.act(WorkbayAction.EJECT, 0, Optional.empty());
            player.getInventory().clearContent();

            // The same block carrying settings. Written the way `saveToItem` writes them, which is
            // the way an ejected machine comes back to the player.
            ItemStack configured = new ItemStack(Blocks.SPAWNER);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("id", "minecraft:mob_spawner");
            tag.putShort("RequiredPlayerRange", (short) 3);
            configured.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
            player.setItemInHand(InteractionHand.MAIN_HAND, configured);
            menu.act(WorkbayAction.RACK, 0, Optional.empty());
            helper.assertTrue(backshop.getBlockState(hosted).isAir(),
                "a spawner carrying settings a survival player may not place was racked anyway, "
                    + "which racks an empty one and says nothing");
            helper.assertValueEqual(record.bay(0).hosted(), Optional.empty(),
                "the bay after a refused rack");
            player.getInventory().clearContent();
            helper.succeed();
        });
    }

    /**
     * What the screens draw is a {@link WorkbaySnapshot}, and every box on the flow map takes its
     * name and its icon from one of two fields in it: a bay's {@code hosted}, and a link's
     * {@code targetBlock}. When either is empty the box falls back to coordinates - and a map of
     * number-boxes is a map of nowhere, which is exactly what a world full of links made before
     * {@link BusConfig#targetBlock} existed looks like.
     *
     * <p>So this drives the whole player path on a real Mekanism machine - rack it, pair a
     * Connector, stick it on a chest - then <b>erases</b> the stamp to make the link look like one
     * saved by an older version, and asserts the snapshot both answers with the chest and writes
     * the answer back, so it is answered once and not re-derived on every poll.
     */
    @GameTest
    @TestHolder(description = "Every box the screens draw has a name: a bay knows its machine, and "
        + "a link that has forgotten what it points at learns it again the first time it is drawn.")
    public static void theScreenNamesEveryBoxItDraws(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));

            net.minecraft.resources.ResourceLocation machineId =
                net.minecraft.resources.ResourceLocation.parse("mekanism:enrichment_chamber");
            Block machine = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(machineId);
            if (machine == Blocks.AIR) {
                helper.fail(machineId + " is not registered. This test is about naming another "
                    + "mod's machine on the map, so a missing partner mod is a failure, never a "
                    + "skip: check the gametestRuntimeOnly Mekanism dependency in build.gradle.");
                return;
            }

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(machine));
            WorkbayMenu menu = menuFor(workbay, player);
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.RACK, 0, Optional.empty());

            // The link, made the only way a link is ever made.
            ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
                GlobalPos.of(level.dimension(), workbayPos));
            BlockPos connectorPos = chestPos.above();
            BlockState placed = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(connectorPos, placed, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, connectorPos, placed, player, connector);
            helper.assertValueEqual(workbay.buses().size(), 0,
                "channels after placing a Connector - placing one mints none");
            addChannel(workbay, GlobalPos.of(level.dimension(), connectorPos), 0);
            helper.assertValueEqual(workbay.buses().size(), 1, "channels after adding it to bay 1");

            // Older than the stamp: this is what every link saved before it looks like on disk.
            BusConfig link = workbay.buses().get(0);
            workbay.addBus(link.withTargetBlock(Optional.empty()));
            helper.assertTrue(workbay.bus(link.id()).orElseThrow().targetBlock().isEmpty(),
                "the stamp should have been erased, or this test proves nothing");

            WorkbaySnapshot snapshot = WorkbayMenu.build(workbay, player, 0);

            helper.assertValueEqual(snapshot.bays().get(0).hosted().orElse(null), machineId,
                "the machine the bay box on the flow map draws");
            helper.assertValueEqual(snapshot.links().get(0).targetBlock().orElse(null),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.CHEST),
                "the block the link box on the flow map draws");
            helper.assertValueEqual(
                workbay.bus(link.id()).orElseThrow().targetBlock().orElse(null),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.CHEST),
                "the stamp written back onto the link, so the next poll has nothing to re-derive");

            level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * The player's third action: open a bay, press Add, tick the Connector. Placing one mints no
     * channel (SPEC.md §0), so a fixture that wants one asks the way the button asks.
     */
    private static void addChannel(WorkbayBlockEntity workbay, GlobalPos connector, int bay) {
        WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(),
            workbay.connectorAt(connector).orElseThrow(() -> new GameTestAssertException(
                "placing a paired Connector did not register it on the network")).id(), bay);
        // Bay to target, as every test here was written before a channel was born EXTRACT (#56);
        // BusTests#aNewChannelIsBornPullingIntoTheBay pins the born default.
        BusConfig made = workbay.buses().get(workbay.buses().size() - 1);
        workbay.addBus(made.withMode(BusConfig.Mode.INSERT));
    }

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
        // stillValid is a distance check, and a mock player standing across the structure fails it.
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return workbay;
    }

    private static WorkbayMenu menuFor(WorkbayBlockEntity workbay, GameTestPlayer player) {
        return new WorkbayMenu(1, player.getInventory(), workbay,
            WorkbayMenu.build(workbay, player, 0));
    }

    /**
     * <b>Rate and speed, which every link has had since the first one and no screen ever showed.</b>
     * SPEC.md 5 draws them on the panel the row's gear opens; OPEN_ISSUES #32 was that they were
     * never built, so the Impeller -- which moves both, for every link at once -- was the only way
     * a player could change either.
     *
     * <p>The clamps are the half worth testing. A rate is clamped against a <b>server</b> config a
     * client cannot see, and a speed is an index into a fixed list precisely because a free number
     * would not divide the tick wheel; both are asked for out of range on purpose.
     */
    /**
     * OPEN_ISSUES #70. The X used to call {@code removeBus}, and the Add list can only offer links
     * that exist -- so the Connector standing in the world became unreachable from the screen and
     * the filter, rate, speed and name it carried were gone for good. Detaching keeps all of it.
     *
     * <p>Asserts the two halves that make the row usable again: the link is still on the network
     * with everything it carried, and it is no longer on the bay -- which is what puts it in the
     * Add list, because that list is "every link not on this bay".
     */
    @GameTest
    @TestHolder(description = "Taking a link off a bay keeps the link, its filter and its rate.")
    public static void takingALinkOffABayKeepsIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            GlobalPos connectorPos = GlobalPos.of(level.dimension(), workbayPos.above());
            // The block as well as the record: Add refuses a Connector whose block is gone (#112).
            level.setBlock(connectorPos.pos(), WBBlocks.CONNECTOR.get().defaultBlockState(),
                Block.UPDATE_ALL);
            workbay.addConnector(new com.neryos.workbay.world.WorkbayRecord.Connector(
                java.util.UUID.randomUUID(), connectorPos, "Furnace feed",
                GlobalPos.of(level.dimension(), workbayPos.above(2)), Optional.empty()));
            BusConfig link = BusConfig.create(java.util.UUID.randomUUID(), 0,
                BusConfig.Resource.ITEM, BusConfig.Mode.INSERT, connectorPos,
                GlobalPos.of(level.dimension(), workbayPos.above(2)))
                .withRate(24);
            workbay.addBus(link);
            WorkbayMenu menu = menuFor(workbay, player);

            menu.act(WorkbayAction.LINK_REMOVE, 0, Optional.of(link.id()));

            BusConfig after = workbay.buses().stream()
                .filter(bus -> bus.id().equals(link.id()))
                .findFirst()
                .orElseThrow(() -> new GameTestAssertException(
                    "the X deleted the channel outright, so its filter and rate are gone"));
            helper.assertTrue(after.detached(), "the link is still on a bay after the X");
            helper.assertValueEqual(after.rate(), 24, "the rate it was set to");

            // And back on, which is the whole point: this is what the Add list sends. Adding the
            // Connector to a bay gives back the channel that is waiting rather than a blank one.
            menu.act(WorkbayAction.ADD_CHANNEL, 1,
                Optional.of(workbay.connectorAt(connectorPos).orElseThrow().id()));
            helper.assertValueEqual(workbay.buses().size(), 1,
                "channels after putting the Connector back - the waiting one, not a second");
            BusConfig reattached = workbay.buses().stream()
                .filter(bus -> bus.id().equals(link.id())).findFirst().orElseThrow();
            helper.assertValueEqual(reattached.bay(), 1, "the bay it was put back on");
            helper.assertValueEqual(reattached.rate(), 24, "the rate it kept across the round trip");
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #71. A snapshot is the server's answer and is rebuilt every five ticks, so
     * between a click on a bay and the packet landing it still names the bay before. Add reads the
     * selection to decide which bay the picked links go to, so for that window it assigned them to
     * the wrong one -- and the screen's own optimistic value was not enough on its own, because
     * the next snapshot quietly overwrote it with the stale number.
     */
    @GameTest
    @TestHolder(description = "A bay clicked on the client survives a snapshot built before the click.")
    public static void aClickedBaySurvivesAStaleSnapshot(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayMenu menu = menuFor(workbay, player);
            WorkbaySnapshot stale = WorkbayMenu.build(workbay, player, 0);

            menu.setSelectedBayClientSide(1);
            menu.applySnapshot(stale);
            helper.assertValueEqual(menu.selectedBay(), 1,
                "the bay the player clicked, after a snapshot built before the click arrived");

            // And the server's own answer wins again the moment it agrees, so the two cannot drift.
            menu.applySnapshot(WorkbayMenu.build(workbay, player, 1));
            helper.assertValueEqual(menu.selectedBay(), 1, "the bay both ends now agree on");
            menu.applySnapshot(WorkbayMenu.build(workbay, player, 0));
            helper.assertValueEqual(menu.selectedBay(), 0,
                "a selection changed by anything but this client");
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "A link's rate and speed can be set from the menu, and both are clamped to what is legal.")
    public static void aLinksRateAndSpeedAreSetAndClamped(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            com.neryos.workbay.bus.BusConfig link = com.neryos.workbay.bus.BusConfig.create(
                java.util.UUID.randomUUID(), 0, com.neryos.workbay.bus.BusConfig.Resource.ITEM,
                com.neryos.workbay.bus.BusConfig.Mode.INSERT,
                net.minecraft.core.GlobalPos.of(level.dimension(), workbayPos.above()),
                net.minecraft.core.GlobalPos.of(level.dimension(), workbayPos.above(2)));
            workbay.addBus(link);
            WorkbayMenu menu = menuFor(workbay, player);

            menu.act(WorkbayAction.SET_LINK_RATE, 32, Optional.of(link.id()));
            helper.assertValueEqual(current(workbay, link).rate(), 32, "the rate the menu set");

            int cap = com.neryos.workbay.config.WorkbayConfig.SERVER.linkMaxRate.get();
            menu.act(WorkbayAction.SET_LINK_RATE, 100_000, Optional.of(link.id()));
            helper.assertValueEqual(current(workbay, link).rate(), cap,
                "a rate asked for past the server's ceiling");
            menu.act(WorkbayAction.SET_LINK_RATE, -5, Optional.of(link.id()));
            helper.assertValueEqual(current(workbay, link).rate(), 1, "a rate asked for below one");

            menu.act(WorkbayAction.SET_LINK_SPEED, 0, Optional.of(link.id()));
            helper.assertValueEqual(current(workbay, link).speed(),
                com.neryos.workbay.bus.BusConfig.SPEEDS[0], "the fastest speed on the list");
            menu.act(WorkbayAction.SET_LINK_SPEED, 99, Optional.of(link.id()));
            helper.assertValueEqual(current(workbay, link).speed(),
                com.neryos.workbay.bus.BusConfig.SPEEDS[
                    com.neryos.workbay.bus.BusConfig.SPEEDS.length - 1],
                "a speed index past the end of the list");
            // Every legal speed has to divide the wheel, which is the entire reason the list is
            // fixed rather than a number the player types.
            for (int s : com.neryos.workbay.bus.BusConfig.SPEEDS) {
                helper.assertValueEqual(1200 % s, 0, "speed " + s + " dividing the 1200-tick wheel");
            }
            helper.succeed();
        });
    }

    private static com.neryos.workbay.bus.BusConfig current(WorkbayBlockEntity workbay,
        com.neryos.workbay.bus.BusConfig link) {
        return workbay.buses().stream().filter(b -> b.id().equals(link.id())).findFirst()
            .orElseThrow();
    }

    @GameTest
    @TestHolder(description = "The Pair button stamps a Connector held in the offhand with the selected bay.")
    public static void pairReachesTheOffhandAndTheSelectedBay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            com.neryos.workbay.world.RoomRegistry.get(level.getServer())
                .put(record.withUpgrades(new WorkbayRecord.Upgrades(2, 0, 0, 0)));

            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            com.neryos.workbay.world.WorkbayTickets.force(backshop,
                workbay.record().orElseThrow().id(), workbay.record().orElseThrow().bayColumn());
            com.neryos.workbay.world.BayHosting.rack(backshop,
                workbay.record().orElseThrow().bayColumn(), 0, new ItemStack(Blocks.CHEST), player,
                net.minecraft.core.Direction.NORTH);

            WorkbayMenu menu = menuFor(workbay, player);
            ItemStack connector = new ItemStack(
                com.neryos.workbay.init.WBBlocks.CONNECTOR.get());
            player.setItemInHand(InteractionHand.OFF_HAND, connector);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            menu.act(WorkbayAction.SELECT_BAY, 2, Optional.empty());
            menu.act(WorkbayAction.PAIR, 0, Optional.empty());

            var pairing = player.getOffhandItem()
                .get(com.neryos.workbay.init.WBDataComponents.PAIRING.get());
            if (pairing == null) {
                helper.fail("the Pair button left the offhand Connector unpaired, so the button is "
                    + "still unreachable with anything in it");
                return;
            }
            helper.assertValueEqual(pairing.network(), workbay.record().orElseThrow().label(),
                "the network name the Pair button stamped on it");
            helper.assertValueEqual(pairing.workbayId(), workbay.record().orElseThrow().id(),
                "the network the Connector was paired to");
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "Racking and ejecting through the menu moves a real machine.")
    public static void rackAndEjectThroughTheMenu(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();

            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            WorkbayMenu menu = menuFor(workbay, player);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.FURNACE, 3));

            menu.act(WorkbayAction.RACK, 0, Optional.empty());

            if (!backshop.getBlockState(machinePos).is(Blocks.FURNACE)) {
                helper.fail("clicking the bay slot with a furnace in hand did not rack it; the bay "
                    + "holds " + backshop.getBlockState(machinePos));
                return;
            }
            // Exactly one leaves the hand, and the registry has to agree with the Backshop or the
            // rack icon shows an empty bay holding a machine.
            helper.assertValueEqual(player.getMainHandItem().getCount(), 2, "furnaces left in hand");
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).hosted().isPresent(), true,
                "the registry knowing bay 0 is occupied");

            // Something inside it, so the eject has something to lose.
            if (backshop.getBlockEntity(machinePos) instanceof Container hosted) {
                hosted.setItem(1, new ItemStack(Items.COAL, 7));
            }

            menu.act(WorkbayAction.EJECT, 0, Optional.empty());

            helper.assertValueEqual(backshop.getBlockState(machinePos).isAir(), true,
                "the bay being empty after ejecting");
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).hosted().isEmpty(), true,
                "the registry knowing bay 0 is empty");

            // Exactly three furnaces: the two still in hand plus the one that came back. Racking
            // takes the machine and ejecting returns it - neither may quietly hand out a copy.
            helper.assertValueEqual(player.getInventory().countItem(Blocks.FURNACE.asItem()), 3,
                "furnaces the player holds after the round trip");

            // And it comes back carrying what was inside it, not as a fresh block. A furnace keeps
            // its contents in the `container` data component rather than in block_entity_data -
            // this test asserted the wrong one first and went red, which is how that was found.
            int coal = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                var contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
                if (!stack.is(Blocks.FURNACE.asItem()) || contents == null) {
                    continue;
                }
                for (ItemStack inside : contents.nonEmptyItems()) {
                    if (inside.is(Items.COAL)) {
                        coal += inside.getCount();
                    }
                }
            }
            helper.assertValueEqual(coal, 7, "coal carried out of the bay on the ejected furnace");
            WorkbayTickets.release(backshop, record.id(), record.bayColumn());
            level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * <b>Setting one face for one direction of travel must not close the other one.</b>
     * OPEN_ISSUES #83, found in a live world: a furnace fed through its top face would not give
     * its cooked output back, and the row said "Idle. Nothing to move."
     *
     * <p>{@code usable} returned the empty set the moment <em>anything</em> was configured and
     * nothing carried the role being asked for -- so marking the top face "in", the first and most
     * obvious thing anybody does, silently closed all six faces to extraction. A face the player
     * marked for the opposite role is still refused; a face nobody has said anything about is not.
     */
    @GameTest
    @TestHolder(description = "Marking one face in leaves the others open to taking out.")
    public static void markingAFaceInLeavesTheOthersOpenToTakingOut(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            var item = BusConfig.Resource.ITEM;
            helper.assertValueEqual(FaceConfig.NONE.usable(item, true).size(), 6,
                "faces an unconfigured bay offers to a link taking out");

            // The top face marked "in", and nothing else said at all.
            FaceConfig topIn = FaceConfig.NONE.cycled(item, Direction.UP, false);
            helper.assertValueEqual(topIn.role(item, Direction.UP), FaceConfig.Role.INPUT,
                "the role one click puts on the top face");

            var out = topIn.usable(item, true);
            if (!out.contains(Direction.DOWN)) {
                helper.fail("a bay fed through its top face offers no face at all to a link taking "
                    + "out, so a furnace's cooked output can never leave -- the whole of #83");
                return;
            }
            if (out.contains(Direction.UP)) {
                helper.fail("the face marked in is offered to a link taking out; a role the player "
                    + "set must still be obeyed");
                return;
            }
            helper.assertValueEqual(topIn.usable(item, false), java.util.Set.of(Direction.UP),
                "faces offered to a link putting in, once one is marked in");

            // And an explicit out still wins over the fallback.
            FaceConfig alsoOut = topIn.cycled(item, Direction.NORTH, false)
                .cycled(item, Direction.NORTH, false);
            helper.assertValueEqual(alsoOut.role(item, Direction.NORTH), FaceConfig.Role.OUTPUT,
                "the role two clicks put on the north face");
            helper.assertValueEqual(alsoOut.usable(item, true), java.util.Set.of(Direction.NORTH),
                "faces offered to a link taking out, once one is marked out");
            helper.succeed();
        });
    }

    /**
     * The isometric cube is not decoration. A bay configured with no output face for items has
     * nothing an insert link can pull from, and opening one has to be what makes it move again.
     *
     * <p>Runs both halves: the negative would pass on its own if links never worked at all, so the
     * positive control after it is what makes the first half mean anything.
     *
     * <p>"No output face" means <b>every</b> face marked in, not one. Marking one face for one
     * direction of travel no longer closes the other direction - OPEN_ISSUES #83, and
     * {@link #markingAFaceInLeavesTheOthersOpenToTakingOut} is the rule on its own.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A bay's face config decides which faces a link may use.")
    public static void faceConfigDecidesWhichFacesALinkUses(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos chestPos = helper.absolutePos(new BlockPos(4, 1, 4));

            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());

            WorkbayMenu menu = menuFor(workbay, player);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.BARREL));
            menu.act(WorkbayAction.RACK, 0, Optional.empty());

            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);
            if (!(backshop.getBlockEntity(machinePos) instanceof Container hosted)) {
                helper.fail("the bay does not hold a container after racking a barrel");
                return;
            }
            hosted.setItem(0, new ItemStack(Items.IRON_INGOT, 64));

            // Every face marked in, so an insert link -- which pulls OUT of the machine -- has no
            // face at all to use. One face was enough before #83; it is not any more.
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord shutRecord = registry.byId(record.id()).orElseThrow();
            FaceConfig shut = FaceConfig.NONE;
            for (Direction face : Direction.values()) {
                shut = shut.cycled(BusConfig.Resource.ITEM, face, false);
            }
            registry.put(shutRecord.withBay(shutRecord.bay(0).withFaces(shut)));
            workbay.forgetBay(0);

            ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(connector, record, GlobalPos.of(level.dimension(), workbayPos));
            BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(chestPos.above(), state, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, chestPos.above(), state, player, connector);
            addChannel(workbay, GlobalPos.of(level.dimension(), chestPos.above()), 0);
            BusConfig link = workbay.buses().get(0);
            // A placed Connector's link starts disabled (SPEC.md §7); this test is about the face
            // config, so it turns the link on itself rather than testing that too.
            workbay.addBus(link.withRate(64).withSpeed(10).withEnabled(true));

            helper.startSequence()
                .thenIdle(200)
                .thenExecute(() -> {
                    int moved = countIn(level, chestPos, Items.IRON_INGOT);
                    if (moved > 0) {
                        helper.fail("the link moved " + moved + " iron out of a bay whose only "
                            + "configured item face is an input");
                    }
                })
                // The positive control: turn north into an output and the same link runs.
                .thenExecute(() -> {
                    WorkbayRecord open = registry.byId(record.id()).orElseThrow();
                    registry.put(open.withBay(open.bay(0).withFaces(
                        open.bay(0).faces().cycled(BusConfig.Resource.ITEM, Direction.NORTH, false))));
                    workbay.forgetBay(0);
                })
                .thenWaitUntil(() -> {
                    if (countIn(level, chestPos, Items.IRON_INGOT) <= 0) {
                        throw new GameTestAssertException("nothing moved after north became an "
                            + "output, so the face config is not what stopped it");
                    }
                })
                .thenExecute(() -> {
                    WorkbayTickets.release(backshop, record.id(), record.bayColumn());
                    level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                })
                .thenSucceed();
        });
    }

    /**
     * The upgrades screen's Add button. Upgrades are consumed on install with no removal path, so
     * the item has to leave the inventory exactly once and the counter has to stop at the maximum.
     */
    @GameTest
    @TestHolder(description = "Installing an upgrade consumes the item and stops at the maximum.")
    public static void installingAnUpgradeConsumesIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayMenu menu = menuFor(workbay, player);

            player.getInventory().add(new ItemStack(WBItems.RESONATOR.get(), 2));
            helper.assertValueEqual(workbay.record().orElseThrow().upgrades().resonators(), 0,
                "Resonator installed before anything is clicked");

            // One gate per rung: holding the item is the whole of it now. The Levy that used to be
            // the second gate is gone -- neither its author nor a player could say in a sentence
            // what it was for -- so an install succeeds the moment the item is in the inventory.
            menu.act(WorkbayAction.INSTALL_UPGRADE, WorkbayUpgrade.RESONATOR.ordinal(), Optional.empty());
            helper.assertValueEqual(workbay.record().orElseThrow().upgrades().resonators(), 1,
                "Resonator installed off the item alone");

            // Max is one. A second click must refuse rather than eat the item.
            menu.act(WorkbayAction.INSTALL_UPGRADE, WorkbayUpgrade.RESONATOR.ordinal(), Optional.empty());
            helper.assertValueEqual(workbay.record().orElseThrow().upgrades().resonators(), 1,
                "Resonator installed after clicking past the maximum");
            helper.assertValueEqual(player.getInventory().countItem(WBItems.RESONATOR.get()), 1,
                "Resonators left after a refused install");

            // And what the screen would draw has to agree with what the registry holds.
            WorkbaySnapshot snapshot = WorkbayMenu.build(workbay, player, 0);
            helper.assertValueEqual(snapshot.upgrades().resonators(), 1, "the snapshot's count");
            helper.assertValueEqual(snapshot.bayCapacity(), WorkbayRecord.BASE_BAYS,
                "the snapshot's bay capacity");

            level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
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
     * Copy and paste, SPEC.md §7's second QOL item. Eight bays running the same machine means
     * setting the same faces eight times, which is the complaint this answers.
     *
     * <p>The interesting part is the packing: a whole {@link FaceConfig} rides one action as 36
     * bits, so a paste that loses a resource or a face would be silent. Two of the three resources
     * are set here, to different faces, so a collapsed field cannot pass.
     */
    @GameTest
    @TestHolder(description = "A bay's face config copies onto another bay through one action.")
    public static void pastingABayCarriesEveryFace(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, player);
            WorkbayMenu menu = menuFor(workbay, player);

            // Bay 0: items out of the west face, energy into the top one.
            menu.act(WorkbayAction.CYCLE_FACE,
                BusConfig.Resource.ITEM.ordinal() | (Direction.WEST.ordinal() << 4), Optional.empty());
            menu.act(WorkbayAction.CYCLE_FACE,
                BusConfig.Resource.ITEM.ordinal() | (Direction.WEST.ordinal() << 4), Optional.empty());
            menu.act(WorkbayAction.CYCLE_FACE,
                BusConfig.Resource.ENERGY.ordinal() | (Direction.UP.ordinal() << 4), Optional.empty());

            FaceConfig source = workbay.record().orElseThrow().bay(0).faces();
            helper.assertValueEqual(source.role(BusConfig.Resource.ITEM, Direction.WEST),
                FaceConfig.Role.OUTPUT, "the copied bay's west item face");

            menu.act(WorkbayAction.SELECT_BAY, 1, Optional.empty());
            menu.act(WorkbayAction.PASTE_BAY, source.bits(), Optional.empty());

            FaceConfig pasted = workbay.record().orElseThrow().bay(1).faces();
            helper.assertValueEqual(pasted, source, "the pasted bay's whole face config");
            helper.assertValueEqual(pasted.role(BusConfig.Resource.ENERGY, Direction.UP),
                FaceConfig.Role.INPUT, "the pasted bay's up energy face");
            helper.assertValueEqual(pasted.role(BusConfig.Resource.FLUID, Direction.WEST),
                FaceConfig.Role.NONE, "a resource the source never set");
            helper.succeed();
        });
    }

    /**
     * The lock is the mod's only permission, and it was leaking. {@code rack} and {@code eject}
     * each carried their own copy of the owner check and {@code cycleFace} carried none, so anyone
     * could rewrite a locked Workbay's faces and quietly stop its links.
     */
    @GameTest
    @TestHolder(description = "A locked Workbay refuses face edits from anyone but its owner.")
    public static void aLockedWorkbayRefusesFaceEditsFromStrangers(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            // Locked at mint (night audit 1A finding 1); the toggle is the owner's way out of it.
            if (!workbay.record().orElseThrow().locked()) {
                helper.fail("a fresh Workbay is not locked");
                return;
            }

            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            stranger.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            WorkbayMenu theirs = new WorkbayMenu(2, stranger.getInventory(), workbay,
                WorkbayMenu.build(workbay, stranger, 0));
            theirs.act(WorkbayAction.CYCLE_FACE,
                BusConfig.Resource.ITEM.ordinal() | (Direction.WEST.ordinal() << 4), Optional.empty());
            theirs.act(WorkbayAction.PASTE_BAY, FaceConfig.NONE
                .cycled(BusConfig.Resource.ITEM, Direction.EAST, false).bits(), Optional.empty());

            FaceConfig after = workbay.record().orElseThrow().bay(0).faces();
            helper.assertValueEqual(after, FaceConfig.NONE,
                "a locked Workbay's faces after a stranger tried to change them");
            helper.succeed();
        });
    }

    /**
     * A drag out of JEI or EMI arrives as one number: the item's registry id, the way every vanilla
     * packet carries one. Asserted end to end because an id that survives the trip as the wrong
     * item would be a filter that silently blocks everything.
     */
    @GameTest
    @TestHolder(description = "The filter slot round-trips an item id, and -1 clears it.")
    public static void theFilterSlotRoundTripsAnItemId(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos targetPos = helper.absolutePos(new BlockPos(3, 1, 3));
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);

            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, player);
            ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
            WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
                GlobalPos.of(level.dimension(), pos));
            BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                .setValue(ConnectorBlock.FACING, Direction.DOWN);
            level.setBlock(targetPos.above(), state, Block.UPDATE_ALL);
            WBBlocks.CONNECTOR.get().setPlacedBy(level, targetPos.above(), state, player, connector);
            addChannel(workbay, GlobalPos.of(level.dimension(), targetPos.above()), 0);

            BusConfig link = workbay.buses().get(0);
            WorkbayMenu menu = menuFor(workbay, player);

            // Slot 0, and the id plus one, which is how the packet says "clear" with a zero.
            menu.act(WorkbayAction.SET_FILTER,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.REDSTONE) + 1L,
                Optional.of(link.id()));
            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().filter().ids(),
                java.util.List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(Items.REDSTONE)),
                "the filter after dropping redstone on the slot");

            // A second entry goes in beside the first rather than replacing it. That is the whole
            // difference between this filter and the one item the row used to hold.
            menu.act(WorkbayAction.SET_FILTER, (1L << 32)
                | (net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(Items.COAL) + 1L),
                Optional.of(link.id()));
            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().filter().entries().size(),
                2, "entries after listing a second item");

            menu.act(WorkbayAction.TOGGLE_FILTER_DENY, 0, Optional.of(link.id()));
            helper.assertTrue(workbay.bus(link.id()).orElseThrow().filter().deny(),
                "the filter did not turn into a deny list");

            menu.act(WorkbayAction.SET_FILTER, 0, Optional.of(link.id()));
            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().filter().ids(),
                java.util.List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(Items.COAL)),
                "the filter after clicking slot 0 to clear it");

            // Changing what the link carries drops the list: those ids name items, and an item id
            // read as a fluid id is a filter that quietly refuses everything.
            workbay.addBus(workbay.bus(link.id()).orElseThrow()
                .withResource(BusConfig.Resource.FLUID));
            helper.assertTrue(workbay.bus(link.id()).orElseThrow().filter().isEmpty(),
                "the filter survived a change of resource");
            helper.succeed();
        });
    }

    /**
     * Reported from play: toggling a link's checkbox visibly jumped it to the bottom of the list.
     * The screen's default sort has no comparator at all and relies on the snapshot's own list
     * order - {@link com.neryos.workbay.content.workbay.WorkbayBlockEntity#addBus} used to remove
     * the edited link and append it, which silently reordered the list on every single edit.
     */
    @GameTest
    @TestHolder(description = "Editing a link does not change its position in the list.")
    public static void editingALinkDoesNotReorderTheList(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, player);

            // Three links, three different targets, so their order is unambiguous.
            java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                BlockPos targetPos = helper.absolutePos(new BlockPos(3 + i, 1, 3));
                level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                ItemStack connector = new ItemStack(WBBlocks.CONNECTOR.get());
                WorkbayBlock.pair(connector, workbay.record().orElseThrow(),
                    GlobalPos.of(level.dimension(), pos));
                BlockState state = WBBlocks.CONNECTOR.get().defaultBlockState()
                    .setValue(ConnectorBlock.FACING, Direction.DOWN);
                level.setBlock(targetPos.above(), state, Block.UPDATE_ALL);
                WBBlocks.CONNECTOR.get().setPlacedBy(level, targetPos.above(), state, player, connector);
                addChannel(workbay, GlobalPos.of(level.dimension(), targetPos.above()), 0);
                ids.add(workbay.buses().get(workbay.buses().size() - 1).id());
            }

            WorkbayMenu menu = menuFor(workbay, player);
            // Edit the FIRST link every way the row offers: enable it, flip its mode, rename its
            // filter. Any one of these used to send it to the back of the list.
            menu.act(WorkbayAction.LINK_TOGGLE_ENABLED, 0, Optional.of(ids.get(0)));
            menu.act(WorkbayAction.LINK_FLIP_MODE, 0, Optional.of(ids.get(0)));
            menu.act(WorkbayAction.LINK_CYCLE_RESOURCE, 0, Optional.of(ids.get(0)));

            java.util.List<java.util.UUID> after = workbay.buses().stream()
                .map(BusConfig::id).toList();
            helper.assertValueEqual(after, ids, "link order after editing the first link three times");
            helper.succeed();
        });
    }

    /**
     * Right-click steps a cycling control to the <em>previous</em> value, everywhere one cycles.
     *
     * <p>Worth a test rather than eyeballing because the interesting cases are the wraps, and each
     * of the four rings wraps differently: the redstone modes and the face roles are plain enums,
     * the target face is a ring of seven whose first slot is "any" rather than a Direction, and
     * the target bay is a ring with a hole in it - the link's own bay is skipped, in whichever
     * direction it is being skipped from.
     */
    @GameTest
    @TestHolder(description = "Right-clicking a cycling control steps it back, wraps included.")
    public static void rightClickStepsACycleBackwards(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, player);
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            // Three bays, so the target-bay ring has a value on both sides of the hole. One plate,
            // because the base Workbay already grants two (WorkbayRecord.BASE_BAYS).
            registry.put(workbay.record().orElseThrow()
                .withUpgrades(new WorkbayRecord.Upgrades(1, 0, 0, 0)));
            WorkbayMenu menu = menuFor(workbay, player);

            // The redstone ring: forward one step off ALWAYS, then back past it to the far end.
            menu.act(WorkbayAction.CYCLE_REDSTONE, 0, Optional.empty(), Optional.empty(), false);
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).redstone(),
                com.neryos.workbay.world.RedstoneMode.WITH_SIGNAL, "redstone after a left-click");
            menu.act(WorkbayAction.CYCLE_REDSTONE, 0, Optional.empty(), Optional.empty(), true);
            menu.act(WorkbayAction.CYCLE_REDSTONE, 0, Optional.empty(), Optional.empty(), true);
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).redstone(),
                com.neryos.workbay.world.RedstoneMode.PULSE, "redstone wrapped backwards past ALWAYS");

            // A face role, off the cube: NONE backwards is OUTPUT, not INPUT.
            menu.act(WorkbayAction.CYCLE_FACE,
                BusConfig.Resource.ITEM.ordinal() | (Direction.NORTH.ordinal() << 4),
                Optional.empty(), Optional.empty(), true);
            helper.assertValueEqual(
                workbay.record().orElseThrow().bay(0).faces()
                    .role(BusConfig.Resource.ITEM, Direction.NORTH),
                FaceConfig.Role.OUTPUT, "a face role stepped backwards off NONE");

            // An internal link, for the two rings that live on a row.
            menu.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            menu.act(WorkbayAction.CREATE_INTERNAL_LINK, 1, Optional.empty());
            BusConfig link = workbay.buses().stream().filter(BusConfig::internal).findFirst()
                .orElseThrow(() -> new GameTestAssertException("no internal link was made"));

            // The face ring starts on "any", so one step back is the LAST direction, not the first.
            menu.act(WorkbayAction.LINK_CYCLE_TARGET_FACE, 0, Optional.of(link.id()),
                Optional.empty(), true);
            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().targetFace(),
                Optional.of(Direction.values()[Direction.values().length - 1]),
                "the target face stepped backwards off any");

            // Bay 1 holds the link and bay 2 is its target, so stepping back from bay 2 has to skip
            // bay 1 -- the link's own -- and land on bay 3.
            menu.act(WorkbayAction.LINK_CYCLE_TARGET_BAY, 0, Optional.of(link.id()),
                Optional.empty(), true);
            WorkbayRecord record = workbay.record().orElseThrow();
            helper.assertValueEqual(workbay.bus(link.id()).orElseThrow().target().pos(),
                BayGeometry.machinePos(record.bayColumn(), 2),
                "the target bay stepped backwards, skipping the link's own bay");

            WorkbayTickets.release(level.getServer().getLevel(WorkbayDimensions.BACKSHOP),
                record.id(), record.bayColumn());
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 1. A Workbay was minted <b>unlocked</b>, and an unlocked Workbay let
     * anyone who right-clicked it eject the owner's machines into their own inventory. A fresh
     * Workbay is now locked; the owner opts into sharing with the Lock button.
     */
    @GameTest
    @TestHolder(description = "A fresh Workbay is locked: a stranger cannot eject the owner's machine, the owner can.")
    public static void aFreshWorkbayRefusesAStrangersEject(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, pos, owner);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            BlockPos machinePos = BayGeometry.machinePos(record.bayColumn(), 0);

            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.FURNACE, 1));
            menuFor(workbay, owner).act(WorkbayAction.RACK, 0, Optional.empty());
            if (!backshop.getBlockState(machinePos).is(Blocks.FURNACE)) {
                helper.fail("the owner could not rack a furnace into their own fresh Workbay");
                return;
            }

            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            stranger.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            WorkbayMenu theirs = new WorkbayMenu(2, stranger.getInventory(), workbay,
                WorkbayMenu.build(workbay, stranger, 0));
            theirs.act(WorkbayAction.SELECT_BAY, 0, Optional.empty());
            theirs.act(WorkbayAction.EJECT, 0, Optional.empty());

            helper.assertTrue(backshop.getBlockState(machinePos).is(Blocks.FURNACE),
                "a stranger ejected the owner's machine out of a fresh Workbay");
            helper.assertValueEqual(stranger.getInventory().countItem(Blocks.FURNACE.asItem()), 0,
                "furnaces in the stranger's inventory");

            // Positive control: the same eject from the owner lands.
            menuFor(workbay, owner).act(WorkbayAction.EJECT, 0, Optional.empty());
            helper.assertTrue(backshop.getBlockState(machinePos).isAir(),
                "the owner could not eject from their own fresh Workbay");
            helper.assertValueEqual(owner.getInventory().countItem(Blocks.FURNACE.asItem()), 1,
                "furnaces back in the owner's inventory");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 4. Nothing in {@code act} limited how many actions one menu ran per
     * tick, and a room repaint is up to 13.4k {@code setBlock}s per packet. One budget at the
     * single entry: past {@link WorkbayMenu#MAX_ACTIONS_PER_WINDOW} in one refresh window an action
     * is dropped, and the window is the snapshot's own five-tick cadence.
     *
     * <p>Renames rather than repaints, because the assertion is on which packet landed and a bay
     * name is the cheapest thing that keeps a number.
     */
    @GameTest
    @TestHolder(description = "A menu drops actions past its per-window budget, and takes them again after the window turns.")
    public static void aMenuBudgetsActionsPerRefreshWindow(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(1, 1, 1)), player);
            WorkbayMenu menu = menuFor(workbay, player);

            for (int i = 1; i <= 200; i++) {
                menu.act(WorkbayAction.SET_BAY_NAME, 0, Optional.empty(), Optional.of("n" + i), false);
            }
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).name(),
                "n" + WorkbayMenu.MAX_ACTIONS_PER_WINDOW,
                "the bay's name after two hundred renames in one tick");

            // The window turns with the snapshot cadence; one more rename lands.
            for (int tick = 0; tick < 10; tick++) {
                menu.broadcastChanges();
            }
            menu.act(WorkbayAction.SET_BAY_NAME, 0, Optional.empty(), Optional.of("after"), false);
            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).name(), "after",
                "the bay's name after the window turned");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 5. Every Add minted a link and nothing capped the list, so a record
     * grew without bound, every viewer's snapshot grew with it, and past a megabyte the snapshot
     * packet disconnected whoever right-clicked the block. {@code addBus} now refuses a
     * <em>new</em> link once the record holds {@link WorkbayBlockEntity#MAX_LINKS}; edits of an
     * existing one are never refused.
     */
    @GameTest
    @TestHolder(description = "A network holds at most 64 links; Add past that mints nothing and says so.")
    public static void aNetworkHoldsAtMostSixtyFourLinks(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            GlobalPos connectorPos = GlobalPos.of(level.dimension(), workbayPos.above());
            // The block as well as the record: Add refuses a Connector whose block is gone (#112).
            level.setBlock(connectorPos.pos(), WBBlocks.CONNECTOR.get().defaultBlockState(),
                Block.UPDATE_ALL);
            workbay.addConnector(new WorkbayRecord.Connector(java.util.UUID.randomUUID(),
                connectorPos, "feed", GlobalPos.of(level.dimension(), workbayPos.above(2)),
                Optional.empty()));
            java.util.UUID connector = workbay.connectorAt(connectorPos).orElseThrow().id();

            // The button's own code, straight, so the menu's action budget is not what stops it.
            WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(), connector, 0);
            helper.assertValueEqual(workbay.buses().size(), 1, "links after the first Add");
            for (int i = 1; i < 100; i++) {
                WorkbayMenu.addChannel(workbay, workbay.record().orElseThrow(), connector, 0);
            }
            helper.assertValueEqual(workbay.buses().size(), WorkbayBlockEntity.MAX_LINKS,
                "links after a hundred Adds");

            // The button itself, once more: the 65th Add used to fail in silence.
            menuFor(workbay, player).act(WorkbayAction.ADD_CHANNEL, 0, Optional.of(connector));
            helper.assertValueEqual(LockTests.lastRefusal(player),
                com.neryos.workbay.WorkbayLang.messageKey("link_cap"),
                "what the player was told on the Add past the cap");

            // An edit of a link that exists still lands on a full record.
            BusConfig first = workbay.buses().getFirst();
            workbay.addBus(first.withName("renamed"));
            helper.assertValueEqual(workbay.bus(first.id()).orElseThrow().name(), "renamed",
                "the first link's name after an edit on a full record");
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 9. A name arrived capped but not filtered: a formatting code
     * obfuscated every viewer's screen, control characters and zero-width joiners passed, and a
     * zero-width-only name passed {@code isBlank()}. One filter on the wire codec, vanilla's own
     * {@code StringUtil#filterText} plus the format-category characters it keeps.
     */
    @GameTest
    @TestHolder(description = "A name is filtered on the wire: formatting codes, control and zero-width characters are dropped.")
    public static void aNameIsFilteredOnTheWire(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayBlockEntity workbay = placeWorkbay(helper, helper.absolutePos(new BlockPos(1, 1, 1)), player);
            WorkbayMenu menu = menuFor(workbay, player);

            // Exactly the round trip a client's packet takes: encode, decode, handle. The name is
            // a section sign + k, "foo", a zero-width space and a bell, padded with spaces.
            String dirty = " " + (char) 0xa7 + "kfoo" + (char) 0x200b + (char) 0x07 + " ";
            var buf = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
            com.neryos.workbay.network.ActionPacket.STREAM_CODEC.encode(buf,
                new com.neryos.workbay.network.ActionPacket(1, WorkbayAction.SET_BAY_NAME, 0,
                    Optional.empty(), Optional.of(dirty), false));
            com.neryos.workbay.network.ActionPacket decoded =
                com.neryos.workbay.network.ActionPacket.STREAM_CODEC.decode(buf);
            menu.act(decoded.action(), decoded.arg(), decoded.link(), decoded.text(), decoded.back());

            helper.assertValueEqual(workbay.record().orElseThrow().bay(0).name(), "foo",
                "the bay's name after a packet carrying a formatting code, a zero-width space and a bell");
            helper.succeed();
        });
    }
    /**
     * Night 2026-09-11, 1A #7. A locked Workbay opens for a stranger (they may look, not act), and
     * the snapshot on the menu-open buffer carried every Connector's and target's coordinates in
     * every dimension, plus room names and guest lists. A stranger's snapshot now carries none of
     * them; the owner's is unchanged.
     */
    @GameTest
    @TestHolder(description = "A locked Workbay's snapshot carries no coordinates or room names for a stranger.")
    public static void aLockedSnapshotCarriesNoCoordinatesForAStranger(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(0, 1, 0));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, owner);
            helper.assertTrue(workbay.record().orElseThrow().locked(), "a fresh Workbay is not locked");
            GlobalPos connectorPos = GlobalPos.of(level.dimension(), workbayPos.above());
            GlobalPos targetPos = GlobalPos.of(level.dimension(), workbayPos.above(2));
            workbay.addConnector(new WorkbayRecord.Connector(java.util.UUID.randomUUID(),
                connectorPos, "Secret feed", targetPos, Optional.empty()));
            workbay.addBus(BusConfig.create(java.util.UUID.randomUUID(), 0,
                BusConfig.Resource.ITEM, BusConfig.Mode.INSERT, connectorPos, targetPos));

            var forStranger = WorkbayMenu.build(workbay, stranger, 0);
            helper.assertTrue(forStranger.connectors().isEmpty(),
                "a stranger's snapshot of a locked Workbay lists its Connectors");
            for (var link : forStranger.links()) {
                helper.assertFalse(link.config().target().equals(targetPos)
                        || link.config().connector().equals(connectorPos),
                    "a stranger's snapshot of a locked Workbay carries a link's real coordinates");
            }
            var forOwner = WorkbayMenu.build(workbay, owner, 0);
            helper.assertValueEqual(forOwner.connectors().size(), 1, "Connectors on the owner's snapshot");
            helper.assertTrue(forOwner.links().stream()
                    .anyMatch(link -> link.config().target().equals(targetPos)),
                "the owner's own snapshot lost the link's coordinates too");
            helper.succeed();
        });
    }

    /**
     * <b>OPEN_ISSUES #130: two buttons that could be pressed and say nothing.</b> Eject on an
     * empty bay returned in silence, and the lock changed only its own tooltip. Each now puts a
     * line on the action bar. The positive control is a real eject in the same window: a bay that
     * held a barrel must not say it was empty.
     */
    @GameTest
    @TestHolder(description = "Eject on an empty bay and the lock toggle each say what happened.")
    public static void anEmptyEjectAndTheLockEachSayWhatHappened(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayRecord record = workbay.record().orElseThrow();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            WorkbayTickets.force(backshop, record.id(), record.bayColumn());
            WorkbayMenu menu = menuFor(workbay, player);
            String empty = com.neryos.workbay.WorkbayLang.messageKey("eject_empty");

            menu.act(WorkbayAction.EJECT, 0, Optional.empty());
            helper.assertValueEqual(LockTests.lastRefusal(player), empty,
                "the line after Eject on an empty bay");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.BARREL));
            menu.act(WorkbayAction.RACK, 0, Optional.empty());
            menu.act(WorkbayAction.EJECT, 0, Optional.empty());
            helper.assertValueEqual(LockTests.actionBar(player).filter(empty::equals).count(), 1L,
                "empty-bay lines after ejecting a racked barrel");
            helper.assertValueEqual(player.getInventory().countItem(Items.BARREL), 1,
                "barrels back in the inventory after the eject");

            boolean wasLocked = workbay.record().orElseThrow().locked();
            menu.act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.assertValueEqual(LockTests.lastRefusal(player),
                com.neryos.workbay.WorkbayLang.messageKey(wasLocked ? "unlocked_now" : "locked_now"),
                "the line after the first click on the lock");
            menu.act(WorkbayAction.TOGGLE_LOCK, 0, Optional.empty());
            helper.assertValueEqual(LockTests.lastRefusal(player),
                com.neryos.workbay.WorkbayLang.messageKey(wasLocked ? "locked_now" : "unlocked_now"),
                "the line after the second click on the lock");
            helper.assertValueEqual(workbay.record().orElseThrow().locked(), wasLocked,
                "the lock after two clicks");

            level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * <b>OPEN_ISSUES #126: an upgrade fitted from the inventory stayed drawn there.</b> The server
     * took it; the client kept a ghost until the slot was clicked. The menu has no slots, so its
     * sync went through the player's inventory menu as container 0 -- and a client with another
     * menu open applies container 0 to the hotbar only. This reads the packets the way that client
     * does: for a slot off the hotbar, only container -2 reaches it.
     *
     * <p>The plate is put in slot 20, off the hotbar, because the hotbar is the half that always
     * worked and a test there could not fail.
     */
    @GameTest
    @TestHolder(description = "An upgrade fitted from the main inventory leaves no ghost on the client.")
    public static void anUpgradeFittedFromTheInventoryLeavesNoGhost(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos workbayPos = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = placeWorkbay(helper, workbayPos, player);
            WorkbayMenu menu = menuFor(workbay, player);
            player.containerMenu = menu;
            int slot = 20;
            helper.assertFalse(net.minecraft.world.inventory.InventoryMenu.isHotbarSlot(slot)
                || slot < 9, "slot 20 has to be off the hotbar, or this test cannot fail");
            player.getInventory().setItem(slot, new ItemStack(WBItems.EXPANSION_PLATE.get()));

            menu.act(WorkbayAction.INSTALL_UPGRADE, WorkbayUpgrade.EXPANSION_PLATE.ordinal(),
                Optional.empty());
            helper.assertValueEqual(workbay.record().orElseThrow().upgrades()
                .installed(WorkbayUpgrade.EXPANSION_PLATE), 1, "plates fitted");
            helper.assertTrue(player.getInventory().getItem(slot).isEmpty(),
                "the server still has the plate in slot 20");

            // What a client with the Workbay screen open would apply to inventory slot 20.
            java.util.List<ItemStack> seen = player.getOutboundPackets(
                    net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket.class)
                .filter(packet -> packet.getContainerId() == -2 && packet.getSlot() == slot)
                .map(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket::getItem)
                .toList();
            helper.assertFalse(seen.isEmpty(), "the client was never told slot 20 changed");
            helper.assertTrue(seen.getLast().isEmpty(),
                "the client was told slot 20 holds " + seen.getLast());

            level.setBlock(workbayPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }
}
