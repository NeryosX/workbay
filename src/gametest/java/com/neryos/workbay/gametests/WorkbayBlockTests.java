package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.workbay.WorkbayBinding;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
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

import java.util.List;
import java.util.UUID;

/**
 * SPEC.md §14: a Workbay can be broken while its machines keep running, so the dropped item is the
 * only thread back to them. If that thread breaks, a player loses a factory and there is no way to
 * tell them apart from someone who never had one.
 */
@ForEachTest(groups = "workbay_block")
public class WorkbayBlockTests {

    private static BlockPos place(ExtendedGameTestHelper helper, ServerLevel level, BlockPos pos,
        GameTestPlayer player, ItemStack stack) {
        Block workbay = WBBlocks.WORKBAY.get();
        BlockState state = workbay.defaultBlockState();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        // What BlockItem#place does before setPlacedBy, and what this helper used to skip. Without
        // it nothing here ever ran applyImplicitComponents, so anything the dropped item carries
        // that setPlacedBy does not read by hand -- the buffer -- was tested against a placement
        // no player can make.
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            be.applyComponentsFromItemStack(stack);
        }
        workbay.setPlacedBy(level, pos, state, player, stack);
        return pos;
    }

    /**
     * The two things the energy figures on three screens promise, both of which were false.
     *
     * <p><b>"Up to 10,000 FE/t" is a rate</b>, and {@link net.neoforged.neoforge.energy.EnergyStorage}
     * caps per <em>call</em>: three pushes in one tick put in thirty thousand. Six cables on six
     * faces are six pushes, so the number was out by however many things were pushing.
     *
     * <p><b>"%s / %s FE" has to survive a save.</b> The load path put the saved figure back through
     * {@code receiveEnergy}, which the same cap clamps, so a full buffer came off disk as a tenth
     * of one and the screen printed the loss as the truth: measured, 100,000 stored, 100,000
     * saved, 10,000 reloaded. Neither fault is reachable from a screenshot, and both are what a
     * printed number is for.
     *
     * <p><b>And it has to survive the pickaxe.</b> Everything else a Workbay holds does - the bays,
     * the links, the upgrades - because they live on the record and the record outlives
     * the block. The buffer is the one thing that lives in the block entity, and the loot table
     * copied {@code workbay:binding} and nothing else, so a player who fed a generator into a
     * Workbay and then moved it lost every FE of it without being told. The same loss as the load
     * path's, through the door nothing was watching, which is why it is the same test.
     */
    @GameTest
    @TestHolder(description = "The Workbay's buffer takes its printed rate once a tick and comes "
        + "back with everything it had, off disk and off the item it was broken into.")
    public static void theBufferKeepsItsPrintedRateAndItsContents(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = place(helper, level, helper.absolutePos(new BlockPos(0, 1, 0)), player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);

            int first = workbay.energy().receiveEnergy(Integer.MAX_VALUE, false);
            int second = workbay.energy().receiveEnergy(Integer.MAX_VALUE, false);
            helper.assertValueEqual(first, WorkbayBlockEntity.MAX_FE_PER_TICK,
                "what one push takes");
            helper.assertValueEqual(second, 0,
                "what a second push in the same tick takes, over a screen promising "
                    + WorkbayBlockEntity.MAX_FE_PER_TICK + " FE/t");

            // Filled the honest way -- a tick each -- so the round trip is over a real full buffer
            // rather than one written straight into the field.
            helper.startSequence()
                .thenExecuteFor(WorkbayBlockEntity.BUFFER_FE / WorkbayBlockEntity.MAX_FE_PER_TICK,
                    () -> workbay.energy().receiveEnergy(Integer.MAX_VALUE, false))
                .thenExecute(() -> {
                    int stored = workbay.energy().getEnergyStored();
                    helper.assertValueEqual(stored, WorkbayBlockEntity.BUFFER_FE,
                        "a buffer filled one tick at a time");
                    net.minecraft.nbt.CompoundTag tag =
                        workbay.saveCustomOnly(level.registryAccess());
                    WorkbayBlockEntity reloaded =
                        new WorkbayBlockEntity(pos, level.getBlockState(pos));
                    reloaded.loadWithComponents(tag, level.registryAccess());
                    helper.assertValueEqual(reloaded.energy().getEnergyStored(), stored,
                        "the buffer after a save and load");

                    // Broken through the real loot table, because that is what decides which
                    // components the dropped item carries -- writing the component by hand here
                    // would test the codec and leave the table saying whatever it liked.
                    ItemStack dropped = dropOf(helper, level, pos, player);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    WorkbayBinding binding = dropped.get(WBDataComponents.BINDING.get());
                    helper.assertNotNull(binding, "a binding on the dropped Workbay");
                    helper.assertValueEqual(binding.energy(), stored,
                        "FE on the item a full Workbay was broken into");

                    place(helper, level, pos, player, dropped);
                    WorkbayBlockEntity back = (WorkbayBlockEntity) level.getBlockEntity(pos);
                    helper.assertValueEqual(back.energy().getEnergyStored(), stored,
                        "the buffer after the Workbay was broken and placed again");
                })
                .thenSucceed();
        });
    }

    /**
     * SPEC.md §0 and §14: <b>a Workbay block is a handle, not a container.</b> The bays, the
     * machines in them, the links, the upgrades and the lock all live on the
     * {@code WorkbayRecord}, so breaking the block puts the network to sleep and losing nothing,
     * and putting one back wakes it whole.
     *
     * <p>It used to place <b>two</b> blocks on one network and ask which one to put back. That
     * model is gone - one Workbay block is one network - so the second block here is a second
     * <em>network</em>, which is what makes the assertion about {@code registry.size()} worth
     * making: re-placing must wake the sleeping one rather than mint a third.
     */
    @GameTest
    @TestHolder(description = "A broken Workbay's network sleeps, and placing one back wakes it whole.")
    public static void aBrokenWorkbaysNetworkSleepsAndWakesWhole(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(5, 3, 5));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            int before = registry.size();
            BlockPos left = place(helper, level, helper.absolutePos(new BlockPos(0, 1, 0)), player,
                new ItemStack(WBBlocks.WORKBAY.get()));

            if (!(level.getBlockEntity(left) instanceof WorkbayBlockEntity one)) {
                helper.fail("placing a Workbay produced no block entity");
                return;
            }
            UUID id = one.workbayId().orElse(null);
            helper.assertNotNull(id, "a placed Workbay was never bound to a registry record");
            helper.assertValueEqual(registry.size(), before + 1,
                "records after placing one Workbay");
            helper.assertValueEqual(registry.byId(id).orElseThrow().deployedCount(), 1,
                "blocks on the network");

            // Something on the record worth losing, so "it came back" means more than "an id
            // matched". A bay-to-bay link needs no Connector, so nothing here depends on a block
            // that a break could take with it.
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            var record = registry.byId(id).orElseThrow();
            BayHosting.rack(backshop, record.bayColumn(), 0, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH);
            one.addBus(BusConfig.createInternal(UUID.randomUUID(), 0,
                GlobalPos.of(level.dimension(), left),
                GlobalPos.of(WorkbayDimensions.BACKSHOP,
                    BayGeometry.machinePos(record.bayColumn(), 1))));
            helper.assertValueEqual(registry.byId(id).orElseThrow().buses().size(), 1,
                "links on the network before the block is broken");

            // Broken the way the world breaks it, through the real loot table.
            ItemStack dropped = dropOf(helper, level, left, player);
            level.setBlock(left, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertValueEqual(registry.byId(id).orElseThrow().live(), false,
                "whether the network is still live after its only block was broken");

            WorkbayBinding binding = dropped.get(WBDataComponents.BINDING.get());
            if (binding == null) {
                helper.fail("a broken Workbay dropped an item with no binding, so its bays are "
                    + "unreachable forever");
                return;
            }
            helper.assertValueEqual(binding.id(), id,
                "the network the dropped Workbay remembers");

            // Placed back somewhere else entirely, and the whole network wakes: same record, same
            // bay, same link.
            BlockPos again = place(helper, level, helper.absolutePos(new BlockPos(2, 1, 2)), player,
                dropped);
            if (!(level.getBlockEntity(again) instanceof WorkbayBlockEntity back)) {
                helper.fail("re-placing the broken Workbay produced no block entity");
                return;
            }
            helper.assertValueEqual(back.workbayId().orElse(null), id, "id after re-placing it");
            helper.assertValueEqual(registry.size(), before + 1,
                "records after re-placing it -- a second record here means the block minted a "
                    + "network of its own instead of waking the one it remembered");
            helper.assertValueEqual(registry.byId(id).orElseThrow().deployedCount(), 1,
                "blocks on the network after re-placing it");
            helper.assertValueEqual(back.buses().size(), 1, "links after the block was broken");
            // Read from the Backshop, not from the record: BayHosting.rack is what puts the block
            // in the bay, and the block standing there is SPEC.md §14's actual claim -- a hosted
            // machine keeps running while its Workbay is in somebody's pocket.
            helper.assertTrue(backshop.getBlockState(BayGeometry.machinePos(
                    registry.byId(id).orElseThrow().bayColumn(), 0)).is(Blocks.CHEST),
                "the machine in bay 1 is still in its bay while the network slept");

            BayHosting.eject(backshop, registry.byId(id).orElseThrow().bayColumn(), 0, null);
            level.setBlock(again, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /** What breaking this block would actually put on the floor, through the real loot table. */
    private static ItemStack dropOf(ExtendedGameTestHelper helper, ServerLevel level, BlockPos pos,
        GameTestPlayer player) {
        List<ItemStack> drops = Block.getDrops(level.getBlockState(pos), level, pos,
            level.getBlockEntity(pos), player, ItemStack.EMPTY);
        return drops.size() == 1 ? drops.get(0) : ItemStack.EMPTY;
    }

    @GameTest
    @TestHolder(description = "Placing a Workbay mints one registry record, and breaking it hands that record back on the item.")
    public static void bindingSurvivesBreakAndPlace(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            RoomRegistry registry = RoomRegistry.get(level.getServer());
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            int before = registry.size();
            BlockPos first = helper.absolutePos(new BlockPos(0, 1, 0));
            place(helper, level, first, player, new ItemStack(WBBlocks.WORKBAY.get()));

            if (!(level.getBlockEntity(first) instanceof WorkbayBlockEntity placed)) {
                helper.fail("placing a Workbay produced no WorkbayBlockEntity");
                return;
            }
            UUID id = placed.workbayId().orElse(null);
            helper.assertNotNull(id, "a placed Workbay was never bound to a registry record");
            helper.assertValueEqual(registry.size(), before + 1, "records after placing one Workbay");

            String code = registry.byId(id).orElseThrow().code();

            // Break it the way the world does: through the real loot table, so copy_components is
            // what has to carry the binding rather than anything this test does by hand.
            List<ItemStack> drops = Block.getDrops(level.getBlockState(first), level, first, placed,
                player, ItemStack.EMPTY);
            if (drops.size() != 1) {
                helper.fail("breaking a Workbay dropped " + drops.size() + " stacks, expected 1: " + drops);
                return;
            }
            ItemStack dropped = drops.get(0);
            WorkbayBinding binding = dropped.get(WBDataComponents.BINDING.get());
            if (binding == null) {
                helper.fail("the dropped Workbay carries no binding, so its bays are unreachable "
                    + "forever. Check copy_components in the loot table and collectImplicitComponents.");
                return;
            }
            helper.assertValueEqual(binding.id(), id, "id on the dropped item");
            helper.assertValueEqual(binding.code(), code, "code on the dropped item");

            level.setBlock(first, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            // Place it somewhere else. It must rebind, not mint a second Workbay.
            BlockPos second = helper.absolutePos(new BlockPos(2, 1, 2));
            place(helper, level, second, player, dropped);
            if (!(level.getBlockEntity(second) instanceof WorkbayBlockEntity replaced)) {
                helper.fail("re-placing a Workbay produced no WorkbayBlockEntity");
                return;
            }
            helper.assertValueEqual(replaced.workbayId().orElse(null), id, "id after re-placing");
            helper.assertValueEqual(registry.size(), before + 1,
                "records after re-placing the same Workbay");

            level.setBlock(second, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * SPEC.md §14. Middle-click must not duplicate a factory, so pick-block hands back a plain
     * unbound Workbay, and the id is stripped from {@code block_entity_data} so a creative
     * ctrl-pick cannot clone it either.
     */
    @GameTest
    @TestHolder(description = "Pick-block on a bound Workbay returns an unbound one.")
    public static void pickBlockReturnsAnUnboundWorkbay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.CREATIVE);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            place(helper, level, pos, player, new ItemStack(WBBlocks.WORKBAY.get()));

            if (!(level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay)) {
                helper.fail("placing a Workbay produced no WorkbayBlockEntity");
                return;
            }
            helper.assertNotNull(workbay.workbayId().orElse(null), "the Workbay was never bound");

            ItemStack picked = WBBlocks.WORKBAY.get().getCloneItemStack(level.getBlockState(pos),
                null, level, pos, player);
            if (picked.get(WBDataComponents.BINDING.get()) != null) {
                helper.fail("pick-block returned a Workbay still bound to a live one, so middle-click "
                    + "duplicates a factory");
                return;
            }

            // Ctrl-pick in creative copies the block entity through saveToItem. The id must not
            // survive that either, which is what removeComponentsFromTag is for.
            ItemStack cloned = new ItemStack(WBBlocks.WORKBAY.get());
            workbay.saveToItem(cloned, level.registryAccess());
            var beData = cloned.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            if (beData != null && beData.copyTag().contains("WorkbayId")) {
                helper.fail("a creative ctrl-pick would clone the Workbay id through "
                    + "block_entity_data: " + beData.copyTag());
            }

            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "A comparator on a Workbay reads how many of its bays are occupied.")
    public static void comparatorReadsBayOccupancy(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            place(helper, level, pos, player, new ItemStack(WBBlocks.WORKBAY.get()));

            BlockState state = level.getBlockState(pos);
            if (!state.hasAnalogOutputSignal()) {
                helper.fail("a Workbay reports no comparator output at all");
                return;
            }
            helper.assertValueEqual(state.getAnalogOutputSignal(level, pos), 0,
                "comparator output with no bays occupied");

            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.succeed();
        });
    }

    /**
     * Reported from play: mining a Workbay with a diamond pickaxe took a long time and then it
     * just vanished, no item. {@link #bindingSurvivesBreakAndPlace} did not catch this because it
     * breaks the block through {@code Block.getDrops} directly, which is the loot table's own
     * query path and skips the step that actually failed.
     *
     * <p>The real path is {@code ServerPlayerGameMode#destroyBlock}, and it only calls
     * {@code Block#playerDestroy} - which is what queries the loot table - when
     * {@code player.hasCorrectToolForDrops(state)} is true. That in turn is
     * {@code !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)}, checked
     * against the block's own {@code minecraft:mineable/*} tag membership. Both Workbay and
     * Connector copy their properties from {@code Blocks.IRON_BLOCK}, which sets
     * {@code requiresCorrectToolForDrops()} - but copying properties does not copy tag
     * membership, and neither block was ever added to {@code minecraft:mineable/pickaxe}. So no
     * tool was ever "correct", the correct-tool branch never ran, and the block was simply removed
     * with nothing dropped - silently, because {@code destroyBlock} returns {@code true} either
     * way and nothing here throws.
     */
    @GameTest
    @TestHolder(description = "A player mining a Workbay with a pickaxe gets the item back.")
    public static void workbayDropsWhenMinedByAPlayer(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            place(helper, level, pos, player, new ItemStack(WBBlocks.WORKBAY.get()));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));

            // The exact call ServerPlayerGameMode.destroyBlock makes when a player finishes
            // mining - not helper.destroyBlock, which is the creative-style instant removal and
            // would not have caught this either.
            player.gameMode.destroyBlock(pos);

            if (!level.getBlockState(pos).isAir()) {
                helper.fail("the Workbay was still there after destroyBlock");
                return;
            }
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(1.5));
            if (drops.stream().noneMatch(e -> e.getItem().is(WBBlocks.WORKBAY.get().asItem()))) {
                helper.fail("mining a Workbay with a diamond pickaxe dropped nothing. Check the "
                    + "minecraft:mineable/pickaxe block tag.");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * Night audit 1A finding 8. Right-clicking a Workbay with a blank Workbay stamped the item with
     * that network for whoever held it -- a pre-staged capture of a sleeping network. The stamp now
     * honours the lock the way the menu does.
     */
    @GameTest
    @TestHolder(description = "A stranger cannot stamp a blank Workbay on a locked one; the owner can.")
    public static void aStrangerCannotStampABlankWorkbayOnALockedOne(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer owner = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            place(helper, level, pos, owner, new ItemStack(WBBlocks.WORKBAY.get()));
            var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.UP, pos, false);

            GameTestPlayer stranger = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ItemStack theirs = new ItemStack(WBBlocks.WORKBAY.get());
            stranger.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, theirs);
            level.getBlockState(pos).useItemOn(theirs, level, stranger,
                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(theirs.get(WBDataComponents.BINDING.get()) == null,
                "a stranger stamped a blank Workbay with somebody else's locked network");

            ItemStack own = new ItemStack(WBBlocks.WORKBAY.get());
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, own);
            level.getBlockState(pos).useItemOn(own, level, owner,
                net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(own.get(WBDataComponents.BINDING.get()) != null,
                "the owner could not stamp a blank Workbay on their own locked one");
            helper.succeed();
        });
    }

    /**
     * 1.0.1, from the play test of the published jar. A Workbay holding no network (one placed by
     * {@code /setblock}, or by a player at the network quota) answered a held Workbay with FAIL,
     * and vanilla reads FAIL as "the item goes next": a second Workbay went down on top of the
     * first, minted a network, and the screen never opened. The screen wins over placement.
     */
    @GameTest
    @TestHolder(description = "Right-clicking a Workbay holding no network with a Workbay in hand opens it; nothing is placed.")
    public static void aWorkbayHoldingNoNetworkOpensRatherThanPlacingASecondOne(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 4, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            // What /setblock does: the block and its entity, no setPlacedBy, so no record.
            level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
            ItemStack held = new ItemStack(WBBlocks.WORKBAY.get(), 2);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);

            // The whole server-side gesture, item use included, not the block's hook alone.
            player.gameMode.useItemOn(player, level, held, net.minecraft.world.InteractionHand.MAIN_HAND, hit);

            helper.assertTrue(level.getBlockState(pos.above()).isAir(),
                "a second Workbay was placed on top instead of the screen opening");
            helper.assertTrue(player.containerMenu instanceof com.neryos.workbay.menu.WorkbayMenu,
                "the Workbay screen did not open with a Workbay in hand");
            helper.succeed();
        });
    }
}
