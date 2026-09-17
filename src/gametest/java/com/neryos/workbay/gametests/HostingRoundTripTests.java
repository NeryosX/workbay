package com.neryos.workbay.gametests;

import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.world.BayBuilder;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.List;
import java.util.UUID;

/**
 * SPEC.md §10, and OPEN_ISSUES #15. This is where silent item loss lives: a machine racked into a
 * sealed room in a dimension nobody can reach, and everything inside it spilled onto a floor no
 * player will ever stand on.
 */
@ForEachTest(groups = "hosting")
public class HostingRoundTripTests {

    private static final UUID CHEST_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000bb01");
    private static final UUID NAMED_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000bb02");
    private static final UUID FOREIGN_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000bb03");

    private static final ChunkPos CHEST_COLUMN = new ChunkPos(4096, 0);
    private static final ChunkPos NAMED_COLUMN = new ChunkPos(4096, 64);
    private static final ChunkPos FOREIGN_COLUMN = new ChunkPos(4096, 128);

    private static ServerLevel backshop(ExtendedGameTestHelper helper) {
        ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
        helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
        return backshop;
    }

    /** Anything loose in the bay means the extract order spilled it. */
    private static int looseItemsAround(ServerLevel backshop, BlockPos machine) {
        AABB box = new AABB(machine).inflate(4.0);
        return backshop.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box).size();
    }

    /**
     * The test OPEN_ISSUES #15 asks for. Rack a container with something in it, take it back out,
     * and account for every item.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A container racked and ejected keeps every item, and spills none into the bay.")
    public static void roundTripPreservesContents(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayTickets.force(backshop, CHEST_OWNER, CHEST_COLUMN);

            BlockPos machine = BayGeometry.machinePos(CHEST_COLUMN, 0);
            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            if (!BayHosting.rack(backshop, CHEST_COLUMN, 0, new ItemStack(Blocks.CHEST), player,
                Direction.NORTH)) {
                helper.fail("racking a chest was refused");
                return;
            }
            if (!(backshop.getBlockEntity(machine) instanceof Container chest)) {
                helper.fail("the racked chest has no container block entity");
                return;
            }
            chest.setItem(0, new ItemStack(Items.DIAMOND, 17));
            chest.setItem(5, new ItemStack(Items.REDSTONE, 64));
            ((BlockEntity) chest).setChanged();

            ItemStack ejected = BayHosting.eject(backshop, CHEST_COLUMN, 0, player);

            if (!ejected.is(Blocks.CHEST.asItem())) {
                helper.fail("ejecting gave back " + ejected + ", not a chest");
                return;
            }
            if (!backshop.getBlockState(machine).isAir()) {
                helper.fail("the bay still holds " + backshop.getBlockState(machine) + " after ejecting");
                return;
            }

            // The contents have to be on the item, not merely 'not on the floor'.
            var beData = ejected.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            var container = ejected.get(net.minecraft.core.component.DataComponents.CONTAINER);
            boolean carriesDiamonds =
                (beData != null && beData.copyTag().toString().contains("diamond"))
                    || (container != null && container.stream().anyMatch(s -> s.is(Items.DIAMOND)));
            if (!carriesDiamonds) {
                helper.fail("the ejected chest carries none of its contents. beData=" + beData
                    + " container=" + container);
                return;
            }

            // The spill assertion that belongs here lives in extractOrderMustRemoveTheBlockEntityFirst
            // instead: item entities never materialise in the Backshop at all (OPEN_ISSUES #19), so
            // a "nothing spilled" check would pass there whatever the order was.
            if (!BayHosting.rack(backshop, CHEST_COLUMN, 0, ejected, player, Direction.NORTH)) {
                helper.fail("re-racking the ejected chest was refused");
                return;
            }
            if (!(backshop.getBlockEntity(machine) instanceof Container reracked)) {
                helper.fail("the re-racked chest has no container block entity");
                return;
            }
            int diamonds = 0;
            int redstone = 0;
            for (int slot = 0; slot < reracked.getContainerSize(); slot++) {
                ItemStack in = reracked.getItem(slot);
                if (in.is(Items.DIAMOND)) {
                    diamonds += in.getCount();
                }
                if (in.is(Items.REDSTONE)) {
                    redstone += in.getCount();
                }
            }
            helper.assertValueEqual(diamonds, 17, "diamonds after a full round trip");
            helper.assertValueEqual(redstone, 64, "redstone after a full round trip");

            BayHosting.eject(backshop, CHEST_COLUMN, 0, player);
            WorkbayTickets.release(backshop, CHEST_OWNER, CHEST_COLUMN);
            helper.succeed();
        });
    }

    /**
     * SPEC.md §10's extract step 3, on its own and where it can actually be seen: removing the block
     * entity <em>before</em> the setBlock. Run in the overworld deliberately - the Backshop never
     * materialises item entities (OPEN_ISSUES #19), so the same check there would pass whatever the
     * order was, which is worse than no check.
     *
     * <p>The wrong order is run too. Without that, a change that stopped spills happening for some
     * unrelated reason would leave this test green and meaningless.
     *
     * <p><b>The two chests are eight blocks apart and each box reaches 1.5.</b> They used to be four
     * apart inside a 5x5x5 template with boxes inflated by <b>3</b>, which overlap across four blocks
     * of x and z - so a drop from the wrongly-ordered chest that drifted a little counted as a spill
     * from the rightly-ordered one, and the test went red saying {@code Block#onRemove} was dropping
     * contents. Rare, and rare is the worst kind: it made every future red read as "probably that
     * one again". The boxes no longer touch, so nothing either chest drops can be attributed to the
     * other whatever the random offset and motion of the drop happen to be.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "Removing a container's block entity before the setBlock is what stops its contents spilling.")
    public static void extractOrderMustRemoveTheBlockEntityFirst(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(9, 5, 9));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            BlockPos right = helper.absolutePos(new BlockPos(0, 3, 0));
            BlockPos wrong = helper.absolutePos(new BlockPos(8, 3, 8));

            fillChest(level, right);
            // SPEC.md §10: block entity first, then the block.
            level.removeBlockEntity(right);
            level.setBlock(right, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

            fillChest(level, wrong);
            // The order this rule exists to forbid.
            level.setBlock(wrong, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.removeBlockEntity(wrong);

            helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    int spilledByWrongOrder = level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new AABB(wrong).inflate(1.5)).size();
                    if (spilledByWrongOrder == 0) {
                        helper.fail("the wrong order spilled nothing either, so this test cannot "
                            + "detect a spill and proves nothing about SPEC.md §10 step 3");
                        return;
                    }
                    int spilledByRightOrder = level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new AABB(right).inflate(1.5)).size();
                    if (spilledByRightOrder != 0) {
                        helper.fail("removing the block entity first still spilled "
                            + spilledByRightOrder + " stacks, so Block#onRemove is dropping contents "
                            + "some other way and the extract order needs revisiting");
                    }
                })
                .thenSucceed();
        });
    }

    private static void fillChest(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof Container chest) {
            chest.setItem(0, new ItemStack(Items.EMERALD, 9));
        }
    }

    /**
     * A custom name is the cheapest thing to lose and the most obvious to the player when it goes.
     * It also travels by a different route from the inventory, so it is worth its own assertion.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A named container keeps its name through a rack and eject.")
    public static void roundTripPreservesACustomName(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            WorkbayTickets.force(backshop, NAMED_OWNER, NAMED_COLUMN);

            BlockPos machine = BayGeometry.machinePos(NAMED_COLUMN, 0);
            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            ItemStack named = new ItemStack(Blocks.BARREL);
            named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("Ore Buffer"));

            if (!BayHosting.rack(backshop, NAMED_COLUMN, 0, named, player, Direction.UP)) {
                helper.fail("racking a named barrel was refused");
                return;
            }
            ItemStack ejected = BayHosting.eject(backshop, NAMED_COLUMN, 0, player);
            Component name = ejected.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            if (name == null || !name.getString().equals("Ore Buffer")) {
                helper.fail("the ejected barrel came back named " + name);
            }

            WorkbayTickets.release(backshop, NAMED_OWNER, NAMED_COLUMN);
            helper.succeed();
        });
    }

    /**
     * The same round trip with a machine from another mod, which is the case that actually matters:
     * its own containers, its own owner UUID and its own serialization, none of it ours.
     */
    @GameTest(timeoutTicks = 900)
    @TestHolder(description = "A Mekanism machine racked and ejected keeps the energy it was given.")
    public static void foreignMachineRoundTripsItsEnergy(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            ResourceLocation id = ResourceLocation.parse("mekanism:enrichment_chamber");
            Block machineBlock = BuiltInRegistries.BLOCK.get(id);
            if (machineBlock == Blocks.AIR) {
                helper.fail(id + " is not registered; check the gametestRuntimeOnly Mekanism "
                    + "dependency in build.gradle");
                return;
            }
            WorkbayTickets.force(backshop, FOREIGN_OWNER, FOREIGN_COLUMN);
            BlockPos machine = BayGeometry.machinePos(FOREIGN_COLUMN, 0);
            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            if (!BayHosting.rack(backshop, FOREIGN_COLUMN, 0, new ItemStack(machineBlock), player,
                Direction.NORTH)) {
                helper.fail("racking " + id + " was refused");
                return;
            }

            int given = 0;
            for (Direction side : Direction.values()) {
                IEnergyStorage energy = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side);
                if (energy != null) {
                    given = energy.receiveEnergy(20_000, false);
                    if (given > 0) {
                        break;
                    }
                }
            }
            if (given <= 0) {
                helper.fail("the racked " + id + " would not take any energy, so this test cannot "
                    + "tell whether ejecting preserves it");
                return;
            }

            ItemStack ejected = BayHosting.eject(backshop, FOREIGN_COLUMN, 0, player);
            if (ejected.isEmpty()) {
                helper.fail("ejecting " + id + " gave back nothing");
                return;
            }
            if (looseItemsAround(backshop, machine) > 0) {
                helper.fail("ejecting " + id + " spilled item entities into the bay");
                return;
            }

            if (!BayHosting.rack(backshop, FOREIGN_COLUMN, 0, ejected, player, Direction.NORTH)) {
                helper.fail("re-racking " + id + " was refused");
                return;
            }
            int back = 0;
            for (Direction side : Direction.values()) {
                IEnergyStorage energy = backshop.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side);
                if (energy != null) {
                    back = Math.max(back, energy.getEnergyStored());
                }
            }
            if (back <= 0) {
                helper.fail("the re-racked " + id + " came back with no energy; it was given " + given);
            }

            BayHosting.eject(backshop, FOREIGN_COLUMN, 0, player);
            WorkbayTickets.release(backshop, FOREIGN_OWNER, FOREIGN_COLUMN);
            helper.succeed();
        });
    }

    /** A machine racked with a facing must come out of the bay pointing the way it was put in. */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A racked machine is placed with the facing it was given.")
    public static void rackedMachineKeepsItsFacing(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000bb04");
            ChunkPos column = new ChunkPos(4096, 192);
            WorkbayTickets.force(backshop, owner, column);
            BlockPos machine = BayGeometry.machinePos(column, 0);
            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                BayHosting.rack(backshop, column, 0, new ItemStack(Blocks.FURNACE), player, facing);
                var state = backshop.getBlockState(machine);
                if (state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    != facing) {
                    helper.fail("a furnace racked facing " + facing + " came out facing "
                        + state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING));
                    return;
                }
                BayHosting.eject(backshop, column, 0, player);
            }
            WorkbayTickets.release(backshop, owner, column);
            helper.succeed();
        });
    }

    /**
     * A block that writes into the blocks around it is given a bay it fits in, not a refusal.
     * SPEC.md §10 step 5.
     *
     * <p>A bed, because it is vanilla and it does the thing: {@code BedBlock#setPlacedBy} puts the
     * head half in the next block along, which is where a narrow bay keeps a Port. That is the same
     * shape as a machine with bounding blocks - Mekanism's Digital Miner writes 3x2x3 of filler
     * around itself - so the rule can be tested without Mekanism installed.
     *
     * <p>So the bed is refused a 5x5x5 bay, the bay is rebuilt at 7x7x7, and the second try stands
     * it up with its head in the clearance and all six Ports where they belong. <b>The furnace at
     * the end is the positive control</b>: a bay does not grow for a machine that fits, or this
     * test would pass with the narrow attempt deleted.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A block that claims the space around it gets a bay that fits it.")
    public static void aBlockThatWritesOutsideItselfGetsAWiderBay(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000bb05");
            ChunkPos column = new ChunkPos(4096, 256);
            WorkbayTickets.force(backshop, owner, column);
            BlockPos machine = BayGeometry.machinePos(column, 0);
            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            BayHosting.Outcome outcome = BayHosting.place(backshop, column, 0,
                new ItemStack(Blocks.RED_BED), player, Direction.NORTH);
            if (outcome != BayHosting.Outcome.PLACED) {
                helper.fail("racking a bed answered " + outcome + ", not PLACED");
                return;
            }
            int shell = BayBuilder.shellAt(backshop, column, 0);
            if (shell != BayGeometry.WIDE_SHELL) {
                helper.fail("the bay holding a bed is " + shell + " wide, not "
                    + BayGeometry.WIDE_SHELL);
                return;
            }
            if (!backshop.getBlockState(machine).is(Blocks.RED_BED)) {
                helper.fail("the bay holds " + backshop.getBlockState(machine) + ", not a bed");
                return;
            }
            // The half setPlacedBy wrote is in the clearance the wider bay opened for it, which is
            // the block a narrow bay keeps a Port in.
            if (!backshop.getBlockState(machine.north()).is(Blocks.RED_BED)) {
                helper.fail("the bed's head is " + backshop.getBlockState(machine.north())
                    + ", so it did not get the block it claimed");
                return;
            }
            for (Direction face : Direction.values()) {
                BlockPos port = BayGeometry.portPos(column, 0, face, shell);
                if (!backshop.getBlockState(port).is(WBBlocks.PORT.get())) {
                    helper.fail("the " + face + " Port is " + backshop.getBlockState(port)
                        + ", so the wider bay was not built around the machine");
                    return;
                }
            }
            // And it is still a sealed box: the grown shell is bedrock on every one of its faces.
            BlockPos origin = BayGeometry.shellOrigin(column, 0, shell);
            for (int x = 0; x < shell; x++) {
                for (int y = 0; y < shell; y++) {
                    for (int z = 0; z < shell; z++) {
                        boolean edge = x == 0 || y == 0 || z == 0
                            || x == shell - 1 || y == shell - 1 || z == shell - 1;
                        BlockPos pos = origin.offset(x, y, z);
                        if (edge && !backshop.getBlockState(pos).is(Blocks.BEDROCK)) {
                            helper.fail("the wide bay's shell at " + pos + " is "
                                + backshop.getBlockState(pos).getBlock() + ", not bedrock");
                            return;
                        }
                    }
                }
            }

            ItemStack back = BayHosting.eject(backshop, column, 0, player);
            if (!back.is(Blocks.RED_BED.asItem())) {
                helper.fail("ejecting the bed gave back " + back + ", not a bed");
                return;
            }
            if (looseItemsAround(backshop, machine) != 0) {
                helper.fail("hosting a bed left items loose in the bay");
                return;
            }

            // The control: a furnace fits a narrow bay, so a narrow bay is what it gets.
            ChunkPos fits = new ChunkPos(4096, 257);
            WorkbayTickets.force(backshop, owner, fits);
            if (BayHosting.place(backshop, fits, 0, new ItemStack(Blocks.FURNACE), player,
                Direction.NORTH) != BayHosting.Outcome.PLACED) {
                helper.fail("racking a furnace was refused");
                return;
            }
            if (BayBuilder.shellAt(backshop, fits, 0) != BayGeometry.SHELL) {
                helper.fail("a bay grew for a furnace, which fits the narrow one");
                return;
            }
            BayHosting.eject(backshop, fits, 0, player);

            WorkbayTickets.release(backshop, owner, fits);
            WorkbayTickets.release(backshop, owner, column);
            helper.succeed();
        });
    }

    /**
     * Ejecting leaves the bay the way a bay comes out of the box, whatever was standing in it.
     *
     * <p>The bay is dirtied every way one can be: a bed, whose {@code setPlacedBy} writes a second
     * half into the clearance and which grew the bay to 7 to get it; a loose block in that
     * clearance, standing in for the filler a machine with bounding blocks leaves behind; a chest
     * with something in it, which is the one that used to spill; and an item entity already lying
     * on the floor. Then it is emptied, and compared block for block against a bay in a column
     * nothing has ever touched.
     *
     * <p><b>The comparison is the whole 7x7x7</b>, not the 5x5x5 the emptied bay ends up being, or
     * the ring of bedrock a shrunk bay used to leave behind would pass unnoticed.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "An emptied bay is the same blocks as a bay that was never used.")
    public static void ejectingLeavesTheBayAsNewlyBuilt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = backshop(helper);
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000bb06");
            ChunkPos fresh = new ChunkPos(4096, 320);
            ChunkPos used = new ChunkPos(4096, 321);
            WorkbayTickets.force(backshop, owner, fresh);
            WorkbayTickets.force(backshop, owner, used);

            // What a bay looks like when nothing has ever been in it.
            BayBuilder.ensure(backshop, fresh, 0, BayGeometry.SHELL);

            // And one that has been lived in. The bed grows the bay and leaves a second half in it.
            if (BayHosting.place(backshop, used, 0, new ItemStack(Blocks.RED_BED), player,
                Direction.NORTH) != BayHosting.Outcome.PLACED) {
                helper.fail("the bed this test needs in the bay was refused");
                return;
            }
            BlockPos machine = BayGeometry.machinePos(used, 0);
            backshop.setBlock(machine.east(), Blocks.COBBLESTONE.defaultBlockState(),
                Block.UPDATE_ALL);
            backshop.setBlock(machine.west(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (backshop.getBlockEntity(machine.west()) instanceof Container chest) {
                chest.setItem(0, new ItemStack(Items.IRON_INGOT, 7));
            } else {
                helper.fail("the chest this test needs in the bay has no container");
                return;
            }
            backshop.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(backshop,
                machine.getX() + 0.5, machine.getY() + 0.5, machine.getZ() + 1.5,
                new ItemStack(Items.REDSTONE, 3)));

            // A freshly forced chunk's entity sections stay hidden until its chunk holder works
            // its way up to entity-ticking, which is tens of ticks: a query before that answers
            // none, and every assertion here about items in the bay would pass on a bay that was
            // never swept. So the wait is on the item being *visible*, not on a tick count.
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(looseItemsAround(backshop, machine) > 0,
                    "the loose item this test needs in the bay is not visible yet"))
                .thenExecute(() -> {
                    ItemStack back = BayHosting.eject(backshop, used, 0, player);
                    if (!back.is(Blocks.RED_BED.asItem())) {
                        helper.fail("ejecting gave back " + back + ", not the bed");
                        return;
                    }

                    int width = BayGeometry.WIDE_SHELL;
                    BlockPos a = BayGeometry.shellOrigin(fresh, 0, width);
                    BlockPos b = BayGeometry.shellOrigin(used, 0, width);
                    for (int x = 0; x < width; x++) {
                        for (int y = 0; y < width; y++) {
                            for (int z = 0; z < width; z++) {
                                BlockState was = backshop.getBlockState(a.offset(x, y, z));
                                BlockState now = backshop.getBlockState(b.offset(x, y, z));
                                if (!was.equals(now)) {
                                    helper.fail("the emptied bay is " + now + " at " + x + "/" + y
                                        + "/" + z + " where a new one is " + was);
                                    return;
                                }
                            }
                        }
                    }
                    if (BayBuilder.shellAt(backshop, used, 0) != BayGeometry.SHELL) {
                        helper.fail("the emptied bay is still standing wide");
                        return;
                    }
                    if (looseItemsAround(backshop, machine) != 0) {
                        helper.fail("emptying the bay left items loose in it");
                        return;
                    }
                    WorkbayTickets.release(backshop, owner, used);
                    WorkbayTickets.release(backshop, owner, fresh);
                })
                .thenSucceed();
        });
    }
}
