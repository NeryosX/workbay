package com.neryos.workbay.gametests;

import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.UUID;

/**
 * SPEC.md §0 and §14's network model. <b>One Workbay block is one network</b>: its own bays,
 * machines, Connectors and energy, sharing nothing with any other. A Workbay belongs to the player
 * who placed it, not to whatever NBT the specific dropped item happened to carry - so losing the
 * block is never the end of a base - and <b>placing one is never refused</b>: a block placed while
 * every network you own already has one stands there holding nothing until a network is moved into
 * it.
 */
@ForEachTest(groups = "network")
public class NetworkTests {

    private static WorkbayBlockEntity place(ServerLevel level, BlockPos pos, GameTestPlayer player,
        ItemStack stack) {
        Block workbay = WBBlocks.WORKBAY.get();
        level.setBlock(pos, workbay.defaultBlockState(), Block.UPDATE_ALL);
        workbay.setPlacedBy(level, pos, level.getBlockState(pos), player, stack);
        return (WorkbayBlockEntity) level.getBlockEntity(pos);
    }

    /**
     * A menu on one Workbay, driven the way a click drives it. {@code moveTo} first because
     * {@code act} opens with {@code stillValid}, which is eight blocks, and a mock player is
     * created wherever the structure landed.
     */
    private static com.neryos.workbay.menu.WorkbayMenu menuFor(WorkbayBlockEntity workbay,
        GameTestPlayer player) {
        BlockPos pos = workbay.getBlockPos();
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return new com.neryos.workbay.menu.WorkbayMenu(1, player.getInventory(), workbay,
            com.neryos.workbay.menu.WorkbayMenu.build(workbay, player, 0));
    }

    /** Raises the network limit for one test and puts it back however that test ends. */
    private static void withNetworkLimit(int limit, Runnable body) {
        int was = com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.get();
        com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.set(limit);
        try {
            body.run();
        } finally {
            com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.set(was);
        }
    }

    /**
     * The whole point: break the block for real, place a completely uncrafted one, and the same
     * bays, upgrades and links come back - nothing written down, nothing typed in.
     */
    @GameTest
    @TestHolder(description = "A fresh, unbound Workbay reuses the placer's existing network.")
    public static void unboundWorkbayReusesThePlayersExistingNetwork(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));

            WorkbayBlockEntity workbay = place(level, first, player, new ItemStack(WBBlocks.WORKBAY.get()));
            var id = workbay.workbayId().orElseThrow();
            String code = workbay.record().orElseThrow().code();
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            registry.put(workbay.record().orElseThrow()
                .withBay(workbay.record().orElseThrow().bay(0)
                    .withHosted(java.util.Optional.of(net.minecraft.core.registries.BuiltInRegistries
                        .BLOCK.getKey(Blocks.CHEST)))));

            // Genuinely broken, not merely unloaded: destroyBlock is the real path a finished dig
            // takes, the same one workbayDropsWhenMinedByAPlayer exercises.
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            player.gameMode.destroyBlock(first);
            helper.assertValueEqual(registry.byId(id).orElseThrow().deployedCount(), 0,
                "deployed count after the only Workbay was broken");

            BlockPos second = helper.absolutePos(new BlockPos(3, 1, 3));
            WorkbayBlockEntity again = place(level, second, player,
                new ItemStack(WBBlocks.WORKBAY.get()));

            helper.assertValueEqual(again.workbayId().orElse(null), id,
                "id after placing an unbound Workbay for the same player");
            helper.assertValueEqual(again.record().orElseThrow().code(), code,
                "code after reusing the network");
            helper.assertValueEqual(again.record().orElseThrow().bay(0).hosted().isPresent(), true,
                "the bay's remembered contents after reusing the network");
            helper.succeed();
        });
    }

    /**
     * <b>Placing a Workbay is never refused.</b> OPEN_ISSUES' old model had two knobs that said no
     * - a cap on networks per player and a cap on blocks per network - and both of them ate a
     * right-click and left the player holding an expensive block that would not go down.
     *
     * <p>What replaces them is a block that stands there holding <em>nothing</em>: a real state
     * with a screen, listing the player's networks with a Transfer beside each. So the assertion is
     * that the block exists, and that it is bound to no network at all - not to somebody else's,
     * and not silently sharing the first one.
     */
    @GameTest
    @TestHolder(description = "A Workbay placed at the network limit goes down, holding nothing.")
    public static void placingAtTheLimitIsNeverRefused(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            withNetworkLimit(1, () -> {
                BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
                WorkbayBlockEntity one =
                    place(level, first, player, new ItemStack(WBBlocks.WORKBAY.get()));
                var owned = one.workbayId().orElseThrow();

                BlockPos second = helper.absolutePos(new BlockPos(4, 1, 1));
                WorkbayBlockEntity two =
                    place(level, second, player, new ItemStack(WBBlocks.WORKBAY.get()));

                helper.assertBlockPresent(WBBlocks.WORKBAY.get(), new BlockPos(4, 1, 1));
                helper.assertValueEqual(two.workbayId().isPresent(), false,
                    "whether a Workbay placed at the limit took a network");
                helper.assertValueEqual(
                    RoomRegistry.get(level.getServer()).ownedBy(player.getUUID()).size(), 1,
                    "networks owned after placing a second Workbay at a limit of one");
                helper.assertValueEqual(
                    RoomRegistry.get(level.getServer()).byId(owned).orElseThrow().deployedCount(), 1,
                    "blocks on the first network after a second Workbay was placed");
            });
            helper.succeed();
        });
    }

    /**
     * Transfer moves a network <b>whole</b> - its bays and what is racked in them, its Connectors,
     * its channels - and leaves the block it came from standing and empty.
     *
     * <p>The empty half is the half that goes wrong: a first cut bound the new block and never
     * unbound the old, so two blocks answered for one network and every link on it ticked twice,
     * which is exactly the model this session deleted.
     */
    @GameTest
    @TestHolder(description = "Transfer moves a network whole and empties the block it left.")
    public static void transferMovesANetworkWholeAndEmptiesTheOldBlock(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            RoomRegistry registry = RoomRegistry.get(level.getServer());

            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity from = place(level, home, player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            java.util.UUID id = from.workbayId().orElseThrow();

            // Something in it, so "whole" is a claim with evidence: a machine in bay 0 and a
            // Connector on the network's list.
            registry.put(registry.byId(id).orElseThrow()
                .withBay(registry.byId(id).orElseThrow().bay(0).withHosted(
                    java.util.Optional.of(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(Blocks.FURNACE)))));
            net.minecraft.core.GlobalPos where = net.minecraft.core.GlobalPos.of(level.dimension(),
                helper.absolutePos(new BlockPos(3, 1, 3)));
            from.addConnector(new WorkbayRecord.Connector(java.util.UUID.randomUUID(), where,
                "Ore feed", where, java.util.Optional.empty()));

            // A second block, holding nothing, the way one placed at the limit does.
            BlockPos away = helper.absolutePos(new BlockPos(6, 1, 1));
            level.setBlock(away, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity to = (WorkbayBlockEntity) level.getBlockEntity(away);

            menuFor(to, player).act(com.neryos.workbay.menu.WorkbayAction.TRANSFER_NETWORK, 0,
                java.util.Optional.of(id));

            helper.assertValueEqual(to.workbayId().orElse(null), id,
                "the network the Transfer moved in");
            helper.assertValueEqual(from.workbayId().isPresent(), false,
                "whether the block the network left is still holding it");
            helper.assertValueEqual(registry.byId(id).orElseThrow().deployedCount(), 1,
                "blocks on the network after a Transfer");
            helper.assertValueEqual(to.record().orElseThrow().bay(0).hosted().isPresent(), true,
                "the machine in bay 0 after a Transfer");
            helper.assertValueEqual(to.connectors().size(), 1,
                "Connectors after a Transfer");
            helper.assertValueEqual(registry.byId(id).orElseThrow().lastKnownPos().orElseThrow()
                .pos(), away, "where the network says its block stands after a Transfer");
            helper.succeed();
        });
    }

    /**
     * The new-player QA of 2026-09-14 (fixed the same night): Smeltery moved A -> B, then the network
     * B was born with (asleep, its last block <em>B</em>) moved into A, and B opened on "This
     * Workbay holds no network" while its own list said Smeltery was placed at B. Transfer unbound
     * whatever block stood at the incoming network's last position without asking whether that
     * block still held <em>that</em> network.
     */
    @GameTest
    @TestHolder(description = "Transferring a sleeping network into A does not unbind B, the block it last stood in, when B now holds another network.")
    public static void transferringASleepingNetworkDoesNotUnbindTheBlockItLastStoodIn(
        final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            // Two placed blocks, two networks: A holds "Smeltery", B the one it was born with.
            BlockPos aPos = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos bPos = helper.absolutePos(new BlockPos(6, 1, 1));
            WorkbayBlockEntity a = place(level, aPos, player, new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity b = place(level, bPos, player, new ItemStack(WBBlocks.WORKBAY.get()));
            UUID smeltery = a.workbayId().orElseThrow();
            UUID second = b.workbayId().orElseThrow();
            helper.assertFalse(smeltery.equals(second), "the second block minted no network of its own");

            // Smeltery to B: B holds Smeltery, A stands empty, the second network sleeps and
            // remembers B as the block it last stood in.
            menuFor(b, player).act(com.neryos.workbay.menu.WorkbayAction.TRANSFER_NETWORK, 0,
                java.util.Optional.of(smeltery));
            helper.assertTrue(smeltery.equals(b.workbayId().orElse(null)), "B does not hold Smeltery after the first Transfer");
            helper.assertTrue(a.workbayId().isEmpty(), "A still holds Smeltery");
            // #129: a live network leaves a block standing empty, and the line says so.
            helper.assertValueEqual(LockTests.lastRefusal(player),
                com.neryos.workbay.WorkbayLang.messageKey("network_transferred"),
                "the line after moving a placed network");

            // The sleeping network into A.
            menuFor(a, player).act(com.neryos.workbay.menu.WorkbayAction.TRANSFER_NETWORK, 0,
                java.util.Optional.of(second));
            helper.assertTrue(second.equals(a.workbayId().orElse(null)), "A does not hold the second network after the second Transfer");
            helper.assertTrue(smeltery.equals(b.workbayId().orElse(null)),
                "B lost Smeltery when a network that last stood in it was moved elsewhere");
            // #129: a sleeping one had no block, and the line said "the block it came from".
            helper.assertValueEqual(LockTests.lastRefusal(player),
                com.neryos.workbay.WorkbayLang.messageKey("network_woken"),
                "the line after moving a sleeping network");
            helper.succeed();
        });
    }

    /**
     * <b>A network with no block sleeps, and wakes with everything intact.</b> Nothing in it may
     * run: the whole promise of "place as many Workbays as you like" is that the blocks holding
     * nothing, and the networks with no block, cost the server nothing at all.
     *
     * <p>Measured off the <b>status map</b>, which is the registry's own record of what each
     * network's links reported on the tick they were last run. Counting items would have been the
     * obvious instrument and is the wrong one: a link runs between a <em>bay</em> and its target,
     * so two chests with nothing racked between them move nothing whether the network is awake or
     * asleep, and the assertion would pass on a mod that ticked everything. The status map fills on
     * every tick a network's links are run, moved or not.
     *
     * <p>Which is why the live network beside it is checked first. "Nothing ran" is worth something
     * only once something did.
     */
    @GameTest
    @TestHolder(description = "A sleeping network runs nothing, and wakes with everything intact.")
    public static void aSleepingNetworkDoesNotTickAndWakesIntact(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(7, 5, 7));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            // Remembered, not assumed. This put the knob back as a literal 1, and the moment the
            // default became 2 every test that ran after it read 1 -- serverConfigIsLoaded caught
            // it. Writing the fact down is not the same as not doing it.
            int wasLimit = com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.get();
            com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.set(2);

            // Two networks, one block each, both switched on and both with a link: the shape
            // The owner asked to watch, and the only shape this can be measured in.
            WorkbayBlockEntity neighbour = place(level, helper.absolutePos(new BlockPos(5, 1, 5)),
                player, new ItemStack(WBBlocks.WORKBAY.get()));
            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            WorkbayBlockEntity workbay = place(level, home, player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            com.neryos.workbay.config.WorkbayConfig.SERVER.maxNetworksPerPlayer.set(wasLimit);
            java.util.UUID id = workbay.workbayId().orElseThrow();
            java.util.UUID neighbourId = neighbour.workbayId().orElseThrow();
            for (WorkbayBlockEntity each : java.util.List.of(neighbour, workbay)) {
                each.energy().deserializeNBT(null,
                    net.minecraft.nbt.IntTag.valueOf(WorkbayBlockEntity.BUFFER_FE));
            }

            // <b>Bay to bay, so there is no Connector to be missing.</b> A link pointed at a
            // world position with no Connector block behind it is swept as orphaned within a tick
            // or two -- so the live network would have had no links left to run, and this test
            // would have measured a network that had quietly emptied itself. An internal link
            // anchors on the Workbay and is never swept.
            for (WorkbayBlockEntity each : java.util.List.of(neighbour, workbay)) {
                net.minecraft.core.GlobalPos anchor =
                    net.minecraft.core.GlobalPos.of(level.dimension(), each.getBlockPos());
                each.addBus(com.neryos.workbay.bus.BusConfig.createInternal(
                    java.util.UUID.randomUUID(), 0, anchor, anchor).withEnabled(true));
            }

            // Asleep: the block is broken for real, and the record keeps everything.
            player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            player.gameMode.destroyBlock(home);
            helper.assertValueEqual(registry.byId(id).orElseThrow().live(), false,
                "whether a network with no block reads as live");

            registry.busStatuses(id).clear();
            registry.busStatuses(neighbourId).clear();
            helper.runAfterDelay(60, () -> {
                if (registry.busStatuses(neighbourId).isEmpty()) {
                    helper.fail("the live network beside it ran no link in sixty ticks, so this "
                        + "test cannot tell a sleeping network from a dead measurement");
                    return;
                }
                if (!registry.busStatuses(id).isEmpty()) {
                    helper.fail("a sleeping network's links were run "
                        + registry.busStatuses(id).size() + " time(s) in sixty ticks; a network "
                        + "with no block must cost the server nothing");
                    return;
                }
                // And everything it had is still there when a block is put back.
                WorkbayBlockEntity again = place(level, home, player,
                    new ItemStack(WBBlocks.WORKBAY.get()));
                helper.assertValueEqual(again.workbayId().orElse(null), id,
                    "the network a fresh Workbay woke");
                helper.assertValueEqual(again.buses().size(), 1,
                    "channels kept while the network slept");
                helper.assertValueEqual(registry.byId(id).orElseThrow().live(), true,
                    "whether the network reads as live once a block is back on it");
                helper.succeed();
            });
        });
    }

    /**
     * <b>A Workbay left empty by a Transfer is not "at the quota", and is not a dead end.</b>
     *
     * <p>Found in a photograph: the screen said "Workbay quota reached" over a footer reading
     * "1 of 2", because every blockless Workbay drew the same banner. A Transfer leaves the block
     * it moved a network out of standing empty while its owner is <em>under</em> the limit, and
     * that block had no way to become anything again except by being broken and placed back.
     */
    @GameTest
    @TestHolder(description = "An empty Workbay under the limit starts a new network in place.")
    public static void anEmptyWorkbayUnderTheLimitStartsANewNetwork(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            // A limit of one, so both halves are reachable in one test: the player owns nothing, so
            // the first New network is allowed, and the second is exactly at the limit.
            withNetworkLimit(1, () -> {
                BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
                level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
                WorkbayBlockEntity empty = (WorkbayBlockEntity) level.getBlockEntity(pos);
                helper.assertValueEqual(empty.workbayId().isPresent(), false,
                    "whether a block placed without setPlacedBy is holding anything");

                menuFor(empty, player)
                    .act(com.neryos.workbay.menu.WorkbayAction.NEW_NETWORK, 0,
                        java.util.Optional.empty());

                java.util.UUID made = empty.workbayId().orElse(null);
                helper.assertNotNull(made, "the network New network made in an empty Workbay");
                helper.assertValueEqual(registry.byId(made).orElseThrow().live(), true,
                    "whether the network it made reads as live");
                helper.assertValueEqual(registry.ownedBy(player.getUUID()).size(), 1,
                    "networks owned after starting one in an empty Workbay");

                // And once at the limit it is refused, whatever a packet claims -- the screen not
                // drawing the button is a decision about pixels, not about the save.
                BlockPos second = helper.absolutePos(new BlockPos(3, 1, 1));
                level.setBlock(second, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
                WorkbayBlockEntity other = (WorkbayBlockEntity) level.getBlockEntity(second);
                menuFor(other, player).act(com.neryos.workbay.menu.WorkbayAction.NEW_NETWORK, 0,
                    java.util.Optional.empty());
                helper.assertValueEqual(registry.ownedBy(player.getUUID()).size(), 1,
                    "networks owned after a second New network at the limit -- it must be refused");
                helper.assertValueEqual(other.workbayId().isPresent(), false,
                    "whether the refused block took a network anyway");
            });
            helper.succeed();
        });
    }

    /**
     * <b>Two networks of one player see nothing of each other.</b> Separate bays, separate
     * Connectors, separate channels - the whole of what "one Workbay is one network" means, and the
     * thing a shared registry keyed by owner would quietly get wrong.
     */
    @GameTest
    @TestHolder(description = "Two networks of one player share no bay, Connector or channel.")
    public static void twoNetworksOfOnePlayerShareNothing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            withNetworkLimit(2, () -> {
                WorkbayBlockEntity one = place(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                    player, new ItemStack(WBBlocks.WORKBAY.get()));
                WorkbayBlockEntity two = place(level, helper.absolutePos(new BlockPos(6, 1, 1)),
                    player, new ItemStack(WBBlocks.WORKBAY.get()));

                if (one.workbayId().orElseThrow().equals(two.workbayId().orElseThrow())) {
                    helper.fail("the second Workbay joined the first one's network instead of "
                        + "minting its own");
                    return;
                }
                RoomRegistry registry = RoomRegistry.get(level.getServer());
                if (one.record().orElseThrow().bayColumn()
                    .equals(two.record().orElseThrow().bayColumn())) {
                    helper.fail("two networks were handed the same bay column, so their bays are "
                        + "the same blocks in the Backshop");
                    return;
                }

                // A machine in one and a Connector on the other, and neither may appear on both.
                registry.put(one.record().orElseThrow().withBay(one.record().orElseThrow().bay(0)
                    .withHosted(java.util.Optional.of(net.minecraft.core.registries
                        .BuiltInRegistries.BLOCK.getKey(Blocks.FURNACE)))));
                net.minecraft.core.GlobalPos where = net.minecraft.core.GlobalPos.of(
                    level.dimension(), helper.absolutePos(new BlockPos(3, 1, 3)));
                two.addConnector(new WorkbayRecord.Connector(java.util.UUID.randomUUID(), where,
                    "Ore feed", where, java.util.Optional.empty()));
                two.addBus(com.neryos.workbay.bus.BusConfig.create(java.util.UUID.randomUUID(), 0,
                    com.neryos.workbay.bus.BusConfig.Resource.ITEM,
                    com.neryos.workbay.bus.BusConfig.Mode.INSERT, where, where));

                helper.assertValueEqual(two.record().orElseThrow().bay(0).hosted().isPresent(),
                    false, "whether the second network can see the first network's machine");
                helper.assertValueEqual(one.connectors().size(), 0,
                    "Connectors the first network can see of the second's");
                helper.assertValueEqual(one.buses().size(), 0,
                    "channels the first network can see of the second's");
                helper.assertValueEqual(two.buses().size(), 1,
                    "the second network's own channels");
                // And the list the screen draws is both of them, from either block.
                helper.assertValueEqual(
                    com.neryos.workbay.menu.WorkbayMenu.build(one, player, 0).networks().size(), 2,
                    "networks the NETWORKS page lists for a player who owns two");
            });
            helper.succeed();
        });
    }

    /**
     * <b>A world saved before networks had names opens without losing anything.</b> The registry
     * round-trips through its own codec with the {@code Name} key absent, which is exactly what
     * every existing save has, and every record comes back named - numbered per owner, in code
     * order, so the same world names the same networks the same way every time.
     */
    @GameTest
    @TestHolder(description = "A registry written before networks had names loads with names.")
    public static void anOlderRegistryLoadsWithEveryNetworkNamed(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            var registries = level.registryAccess();
            RoomRegistry registry = new RoomRegistry();
            java.util.UUID owner = java.util.UUID.randomUUID();
            WorkbayRecord first = registry.create(owner, "tester", level.getRandom());
            WorkbayRecord second = registry.create(owner, "tester", level.getRandom());
            // With one bay filled, so "without losing anything" is checked and not assumed.
            registry.put(first.withName("").withBay(first.bay(0).withHosted(java.util.Optional.of(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.FURNACE)))));
            registry.put(second.withName(""));

            net.minecraft.nbt.CompoundTag tag =
                registry.save(new net.minecraft.nbt.CompoundTag(), registries);
            // What a world written before this build actually holds: no Name key at all.
            tag.getList("Workbays", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .forEach(entry -> ((net.minecraft.nbt.CompoundTag) entry).remove("Name"));

            RoomRegistry back = RoomRegistry.load(tag, registries);
            java.util.List<String> names = back.ownedBy(owner).stream()
                .map(WorkbayRecord::name).sorted().toList();
            helper.assertValueEqual(names, java.util.List.of("tester's Workbay", "tester's Workbay 2"),
                "the names an older registry loads with");
            helper.assertValueEqual(back.byId(first.id()).orElseThrow().bay(0).hosted().isPresent(),
                true, "the machine in bay 0 after loading an older registry");
            helper.assertValueEqual(back.byId(second.id()).orElseThrow().code(), second.code(),
                "the code of the second network after loading an older registry");
            helper.succeed();
        });
    }
    /**
     * Night 2026-09-11, 1B #3c / 1A #10. {@code WorkbayMenu#transfer} unbinds the block a network
     * is leaving only if that block is loaded; an unloaded one keeps the id in its NBT and, when
     * its chunk comes back, ticks and opens the same record as the block that took it. A gametest
     * cannot unload a chunk, so the stale block is made directly: a second Workbay bound by hand
     * to a network whose {@code lastKnownPos} is the first. The record already says where its
     * block is, so the stale one lets go on its own tick; the live one keeps the id.
     */
    @GameTest(timeoutTicks = 100)
    @TestHolder(description = "A Workbay whose network says its block stands elsewhere lets go of it on its own tick.")
    public static void aBlockWhoseNetworkMovedLetsGoOnTick(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlock(home, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(level, home, level.getBlockState(home), player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity real = (WorkbayBlockEntity) level.getBlockEntity(home);
            UUID id = real.workbayId().orElseThrow();

            BlockPos away = helper.absolutePos(new BlockPos(6, 1, 1));
            level.setBlock(away, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WorkbayBlockEntity stale = (WorkbayBlockEntity) level.getBlockEntity(away);
            stale.bindTo(id);

            helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(real.workbayId().orElse(null), id,
                        "the network on the block the record points at");
                    helper.assertValueEqual(stale.workbayId().isPresent(), false,
                        "whether a block the record does not point at is still holding the network");
                    helper.assertValueEqual(
                        RoomRegistry.get(level.getServer()).byId(id).orElseThrow().deployedCount(),
                        1, "blocks the record counts on the network");
                })
                .thenSucceed();
        });
    }
    /**
     * OPEN_ISSUES #108, the owner's call. Numbered per owner, every player's first network was
     * "Workbay 1", so a chat line or a bay title naming one on a server named nobody's. A network
     * is born carrying its owner's name; the second gets a number; a name ending in s -- Neryos
     * -- still takes 's, because the bare apostrophe reads as a cut.
     */
    @GameTest
    @TestHolder(description = "A network is born named after its owner.")
    public static void aNetworkIsBornCarryingItsOwnersName(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            RoomRegistry registry = new RoomRegistry();
            UUID neryos = UUID.randomUUID();
            UUID silas = UUID.randomUUID();
            helper.assertValueEqual(registry.create(neryos, "Neryos", level.getRandom()).label(),
                "Neryos's Workbay", "the first network's name");
            helper.assertValueEqual(registry.create(neryos, "Neryos", level.getRandom()).label(),
                "Neryos's Workbay 2", "the second network's name");
            helper.assertValueEqual(registry.create(silas, "Silas", level.getRandom()).label(),
                "Silas's Workbay", "a network of another owner");
            helper.succeed();
        });
    }

    /**
     * OPEN_ISSUES #111. SPEC.md §14's audit command, cited by {@code WorkbayRecord} and never
     * registered. A bay keeps the id of the block last racked in it when that block's mod is gone;
     * the command counts those bays and nothing else -- a furnace's bay is not an orphan.
     */
    @GameTest
    @TestHolder(description = "/workbay orphans counts every bay whose machine's mod is gone.")
    public static void orphansCountsEveryBayWhoseMachineIsGone(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            var commands = level.getServer().getCommands();
            java.util.function.IntSupplier count = () -> {
                int[] got = {-1};
                commands.performPrefixedCommand(level.getServer().createCommandSourceStack()
                    .withSuppressedOutput().withPermission(4)
                    .withCallback((success, result) -> got[0] = result), "workbay orphans");
                return got[0];
            };
            int before = count.getAsInt();
            helper.assertTrue(before >= 0, "/workbay orphans did not run at all");

            RoomRegistry registry = RoomRegistry.get(level.getServer());
            WorkbayRecord record = registry.create(UUID.randomUUID(), "auditor", level.getRandom());
            registry.put(record
                .withBay(record.bay(0).withHosted(java.util.Optional.of(
                    net.minecraft.resources.ResourceLocation.parse("gone_mod:crusher"))))
                .withBay(record.bay(1).withHosted(java.util.Optional.of(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.FURNACE)))));

            helper.assertValueEqual(count.getAsInt(), before + 1,
                "orphan bays after one network gained a bay whose mod is gone and one with a furnace");
            helper.succeed();
        });
    }
}
