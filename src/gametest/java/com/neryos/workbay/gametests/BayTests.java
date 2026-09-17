package com.neryos.workbay.gametests;

import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.world.BayBuilder;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.BayHosting;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayDimensions;
import com.neryos.workbay.world.WorkbayRecord;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

import java.util.UUID;

/**
 * SPEC.md §8. The bay's shape is the answer to why bays are not bare 1x1x1 holes, so it is worth
 * asserting rather than eyeballing: six Ports against the machine, a solid shell around them, and
 * enough room between bays that they never touch.
 */
@ForEachTest(groups = "bay")
public class BayTests {

    private static final UUID SHAPE_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000ba91");
    private static final ChunkPos SHAPE_COLUMN = new ChunkPos(3072, 0);

    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A generated bay is a 5x5x5 shell with six Ports around an empty middle.")
    public static void bayIsBuiltToShape(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            WorkbayTickets.force(backshop, SHAPE_OWNER, SHAPE_COLUMN);

            BlockPos machine = BayBuilder.ensure(backshop, SHAPE_COLUMN, 0, BayGeometry.SHELL);
            helper.assertValueEqual(machine, BayGeometry.machinePos(SHAPE_COLUMN, 0), "machine position");

            if (!backshop.getBlockState(machine).isAir()) {
                helper.fail("the machine slot is not empty after building a bay: "
                    + backshop.getBlockState(machine));
                return;
            }

            // Every side of the machine must be a Port. This is the whole point of the shape: a
            // pushing machine needs somewhere to push and a redstone-gated one needs a neighbour.
            for (Direction face : Direction.values()) {
                BlockPos port = BayGeometry.portPos(SHAPE_COLUMN, 0, face, BayGeometry.SHELL);
                if (!backshop.getBlockState(port).is(WBBlocks.PORT.get())) {
                    helper.fail("the " + face + " face of the bay is "
                        + backshop.getBlockState(port) + ", not a Port");
                    return;
                }
            }

            // The shell has to be closed, or a machine's output leaves the bay - and bedrock, so
            // that nothing a survival player carries can open it from the inside.
            BlockPos origin = BayGeometry.shellOrigin(SHAPE_COLUMN, 0, BayGeometry.SHELL);
            int walls = 0;
            for (int x = 0; x < BayGeometry.SHELL; x++) {
                for (int y = 0; y < BayGeometry.SHELL; y++) {
                    for (int z = 0; z < BayGeometry.SHELL; z++) {
                        boolean edge = x == 0 || y == 0 || z == 0
                            || x == BayGeometry.SHELL - 1 || y == BayGeometry.SHELL - 1
                            || z == BayGeometry.SHELL - 1;
                        if (!edge) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        if (!backshop.getBlockState(pos).is(Blocks.BEDROCK)) {
                            helper.fail("the bay shell at " + pos + " is "
                                + backshop.getBlockState(pos).getBlock() + ", not bedrock");
                            return;
                        }
                        walls++;
                    }
                }
            }
            helper.assertValueEqual(walls, 5 * 5 * 5 - 3 * 3 * 3, "blocks in the shell");

            if (!BayBuilder.isBuilt(backshop, SHAPE_COLUMN, 0, BayGeometry.SHELL)) {
                helper.fail("isBuilt disagrees with the bay this test just checked block by block");
            }
            WorkbayTickets.release(backshop, SHAPE_OWNER, SHAPE_COLUMN);
            helper.succeed();
        });
    }

    /**
     * Building a bay a second time must not disturb what is standing in it. This is called on every
     * insert, so a rebuild would replace a hosted machine with air and lose it silently.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "Building a bay that already exists leaves the machine in it alone.")
    public static void rebuildingABayDoesNotClearIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            ChunkPos column = new ChunkPos(3072, 64);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000ba92");
            WorkbayTickets.force(backshop, owner, column);

            BlockPos machine = BayBuilder.ensure(backshop, column, 1, BayGeometry.SHELL);
            backshop.setBlock(machine, Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);

            BayBuilder.ensure(backshop, column, 1, BayGeometry.SHELL);
            if (!backshop.getBlockState(machine).is(Blocks.FURNACE)) {
                helper.fail("rebuilding the bay replaced the hosted machine with "
                    + backshop.getBlockState(machine));
            }

            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            WorkbayTickets.release(backshop, owner, column);
            helper.succeed();
        });
    }

    /**
     * A bay built before the walls became bedrock still has obsidian in every save already played,
     * because {@code ensure} returns early once the Ports are there. The upgrade has to happen
     * without disturbing the machine standing in the middle -- which is the same constraint that
     * made {@code ensure} return early in the first place, so both halves are asserted here.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A bay built with the old obsidian walls comes back unbreakable.")
    public static void anOldBaysWallsAreBroughtUpToBedrock(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            ChunkPos column = new ChunkPos(3072, 128);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000ba93");
            WorkbayTickets.force(backshop, owner, column);

            BlockPos machine = BayBuilder.ensure(backshop, column, 2, BayGeometry.SHELL);
            backshop.setBlock(machine, Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);

            // Put the bay back the way a pre-bedrock world holds it: obsidian shell, Ports intact.
            BlockPos origin = BayGeometry.shellOrigin(column, 2, BayGeometry.SHELL);
            for (int x = 0; x < BayGeometry.SHELL; x++) {
                for (int y = 0; y < BayGeometry.SHELL; y++) {
                    for (int z = 0; z < BayGeometry.SHELL; z++) {
                        boolean interior = x > 0 && x < BayGeometry.SHELL - 1
                            && y > 0 && y < BayGeometry.SHELL - 1
                            && z > 0 && z < BayGeometry.SHELL - 1;
                        if (!interior) {
                            backshop.setBlock(origin.offset(x, y, z),
                                Blocks.OBSIDIAN.defaultBlockState(),
                                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
            // The test must actually stage the old world, or it proves nothing.
            helper.assertTrue(backshop.getBlockState(origin).is(Blocks.OBSIDIAN),
                "the staged old bay is not obsidian, so this test cannot fail");

            BayBuilder.ensure(backshop, column, 2, BayGeometry.SHELL);

            for (int x = 0; x < BayGeometry.SHELL; x++) {
                for (int y = 0; y < BayGeometry.SHELL; y++) {
                    for (int z = 0; z < BayGeometry.SHELL; z++) {
                        boolean interior = x > 0 && x < BayGeometry.SHELL - 1
                            && y > 0 && y < BayGeometry.SHELL - 1
                            && z > 0 && z < BayGeometry.SHELL - 1;
                        if (interior) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        if (!backshop.getBlockState(pos).is(Blocks.BEDROCK)) {
                            helper.fail("the old bay's shell at " + pos + " is still "
                                + backshop.getBlockState(pos).getBlock());
                            return;
                        }
                    }
                }
            }
            // And the machine standing in it is untouched: the whole reason ensure returns early.
            if (!backshop.getBlockState(machine).is(Blocks.FURNACE)) {
                helper.fail("bringing the walls up to bedrock replaced the hosted machine with "
                    + backshop.getBlockState(machine));
                return;
            }
            for (Direction face : Direction.values()) {
                if (!backshop.getBlockState(
                    BayGeometry.portPos(column, 2, face, BayGeometry.SHELL))
                    .is(WBBlocks.PORT.get())) {
                    helper.fail("the " + face + " Port did not survive the wall upgrade");
                    return;
                }
            }

            backshop.setBlock(machine, Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            WorkbayTickets.release(backshop, owner, column);
            helper.succeed();
        });
    }

    /**
     * <b>At both widths</b>, because growing a bay spends the gap between bays and nothing else:
     * {@link BayGeometry#BAY_PITCH} did not move, so the arithmetic that says eight wide bays still
     * fit, still miss each other and still sit in one chunk is the thing to prove. A wide bay 0
     * runs y 7 to 13 and a wide bay 1 starts at 15.
     */
    @GameTest
    @TestHolder(description = "Eight bays fit the dimension without touching, narrow or wide.")
    public static void eightBaysFitWithoutTouching(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ChunkPos column = new ChunkPos(0, 0);
            for (int shell : BayGeometry.SHELLS) {
                for (int bay = 0; bay < BayGeometry.MAX_BAYS; bay++) {
                    int floor = BayGeometry.shellOrigin(column, bay, shell).getY();
                    if (floor < WorkbayDimensions.MIN_Y) {
                        helper.fail("bay " + bay + " at shell " + shell
                            + " starts below the dimension at y " + floor);
                        return;
                    }
                    if (BayGeometry.topY(bay, shell)
                        >= WorkbayDimensions.MIN_Y + WorkbayDimensions.HEIGHT) {
                        helper.fail("bay " + bay + " at shell " + shell + " reaches y "
                            + BayGeometry.topY(bay, shell) + ", past the top of the dimension");
                        return;
                    }
                    if (bay > 0 && floor <= BayGeometry.topY(bay - 1, shell)) {
                        helper.fail("bay " + bay + " at shell " + shell + " starts at y " + floor
                            + " but bay " + (bay - 1) + " ends at y "
                            + BayGeometry.topY(bay - 1, shell) + " - the shells overlap");
                        return;
                    }
                }

                // The shell must stay inside one chunk, or a bay column costs more than one chunk
                // to keep loaded and the whole mirroring model in §12 stops being free.
                BlockPos origin = BayGeometry.shellOrigin(column, 0, shell);
                if (origin.getX() < column.getMinBlockX()
                    || origin.getX() + shell - 1 > column.getMaxBlockX()
                    || origin.getZ() < column.getMinBlockZ()
                    || origin.getZ() + shell - 1 > column.getMaxBlockZ()) {
                    helper.fail("a bay shell at " + origin + " at shell " + shell
                        + " leaves chunk " + column);
                    return;
                }
            }

            // The machine does not move when the bay grows. Every saved world has one standing on
            // the answer, so this is the assertion that costs the most to get wrong.
            helper.assertValueEqual(BayGeometry.machinePos(column, 3),
                BayGeometry.shellOrigin(column, 3, BayGeometry.WIDE_SHELL).offset(3, 3, 3),
                "the machine is the centre of a wide bay too");
            helper.succeed();
        });
    }
    /**
     * OPEN_ISSUES #99. A barrel's default facing is UP; turning it north to "face the camera" laid
     * it on its side in the bay and on the face cube, while the item preview stood it up. A
     * furnace is the positive control: its facing is horizontal and racking still turns it north.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A barrel racks standing up; a furnace still racks facing north.")
    public static void anUprightBlockIsRackedUpright(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            ChunkPos column = new ChunkPos(3072, 128);
            UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000ba99");
            WorkbayTickets.force(backshop, owner, column);
            var player = helper.makeTickingMockServerPlayerInLevel(
                net.minecraft.world.level.GameType.SURVIVAL);

            com.neryos.workbay.world.BayHosting.rack(backshop, column, 0,
                new net.minecraft.world.item.ItemStack(Blocks.BARREL), player, Direction.NORTH);
            com.neryos.workbay.world.BayHosting.rack(backshop, column, 1,
                new net.minecraft.world.item.ItemStack(Blocks.FURNACE), player, Direction.NORTH);

            var barrel = backshop.getBlockState(BayGeometry.machinePos(column, 0));
            var furnace = backshop.getBlockState(BayGeometry.machinePos(column, 1));
            helper.assertValueEqual(barrel.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING),
                Direction.UP, "the way a racked barrel faces");
            helper.assertValueEqual(furnace.getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING),
                Direction.NORTH, "the way a racked furnace faces");
            // The face cube draws through BayHosting.orient too (BlockPreview#facingCamera), so
            // it cannot disagree with this; a client class is not loadable here to say so.
            WorkbayTickets.release(backshop, owner, column);
            helper.succeed();
        });
    }

    /**
     * <b>A hole in a bay's shell closes itself.</b> The bedrock is what stops a hosted quarry
     * getting out, and it only stops one that reads hardness before it breaks anything; a machine
     * calling {@code level.setBlock} straight out takes the wall away, and no hook could tell that
     * call from a legitimate one without charging every player for a check on every block placed
     * in the game. So the shell is repaired rather than defended, on the tick wheel, and this is
     * the test of "repaired": one wall gone and one Port gone, and both back within a wheel step.
     *
     * <p>The hole is opened with {@code setBlock}, which is exactly what the machine this guards
     * against would do - there is no point staging it with something a bedrock wall would have
     * refused anyway.
     */
    @GameTest(timeoutTicks = 600)
    @TestHolder(description = "A block taken out of a bay's shell is put back within one wheel step.")
    public static void aHoleInTheShellIsClosedOnTheNextWheelStep(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(3, 3, 3));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel level = helper.getLevel();
            ServerLevel backshop = level.getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);

            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlock(pos, WBBlocks.WORKBAY.get().defaultBlockState(), Block.UPDATE_ALL);
            WBBlocks.WORKBAY.get().setPlacedBy(level, pos, level.getBlockState(pos), player,
                new ItemStack(WBBlocks.WORKBAY.get()));
            WorkbayBlockEntity workbay = (WorkbayBlockEntity) level.getBlockEntity(pos);
            WorkbayRecord record = workbay.record().orElse(null);
            helper.assertNotNull(record, "the placed Workbay bound to no network");

            // Only a bay with something in it is audited, so give it something.
            if (!BayHosting.rack(backshop, record.bayColumn(), 0, new ItemStack(Blocks.FURNACE),
                player, Direction.NORTH)) {
                helper.fail("the furnace this test needs in the bay was refused");
                return;
            }
            RoomRegistry.get(level.getServer()).put(record.withBay(record.bay(0)
                .withHosted(java.util.Optional.of(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:furnace")))));

            BlockPos wall = BayGeometry.shellOrigin(record.bayColumn(), 0, BayGeometry.SHELL);
            BlockPos port = BayGeometry.portPos(record.bayColumn(), 0, Direction.UP,
                BayGeometry.SHELL);
            backshop.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            backshop.setBlock(port, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(backshop.getBlockState(wall).isAir() && backshop.getBlockState(port).isAir(),
                "the hole this test needs was not made, so it cannot fail");

            helper.startSequence()
                // One wheel step, and one more tick for the audit's own position offset.
                .thenIdle(BusRunner.STEP_TICKS + 1)
                .thenExecute(() -> {
                    if (!backshop.getBlockState(wall).is(Blocks.BEDROCK)) {
                        helper.fail("the wall is still " + backshop.getBlockState(wall)
                            + " a wheel step after it was taken out");
                        return;
                    }
                    if (!backshop.getBlockState(port).is(WBBlocks.PORT.get())) {
                        helper.fail("the Port is still " + backshop.getBlockState(port)
                            + " a wheel step after it was taken out");
                        return;
                    }
                    // And the machine it was protecting is where it was left.
                    if (!backshop.getBlockState(
                        BayGeometry.machinePos(record.bayColumn(), 0)).is(Blocks.FURNACE)) {
                        helper.fail("closing the shell took the machine with it");
                    }
                })
                .thenSucceed();
        });
    }
}
