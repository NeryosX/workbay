package com.neryos.workbay.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Racking a machine into a bay and getting it back out again. SPEC.md §10.
 *
 * <p>Both orders are requirements, not suggestions. Every step exists because of a specific way of
 * losing somebody's factory silently, and the two that are easiest to talk yourself out of are the
 * ones that cost the most:
 *
 * <ul>
 * <li><b>Insert steps 2 and 3.</b> Skipping them leaves a Mekanism machine with no containers at
 *     all - it answers on none of its six faces, so a bay reports "no ports" for a machine that is
 *     perfectly fine. Measured, and pinned by {@code shortInsertOrderLeavesTheMachineUnreachable}.
 * <li><b>Extract step 3.</b> Removing the block entity <em>before</em> the setBlock. Otherwise
 *     {@code Block#onRemove} runs {@code Containers.dropContents} and spills the machine's entire
 *     inventory into a sealed room in a dimension nobody can reach.
 * </ul>
 */
public final class BayHosting {
    private BayHosting() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** How a rack ended. Anything but {@link #PLACED} means the player still has the item. */
    public enum Outcome {
        /** The machine is standing in the bay. */
        PLACED,
        /** It would not stand up: {@code setPlacedBy} threw, and the placement was undone. */
        FAILED,
        /**
         * It stood up and then wrote over the bay around it - a machine that claims more than its
         * own block. The bay was put back the way it was and the machine was taken out again.
         */
        NEEDS_ROOM;

        public boolean placed() {
            return this == PLACED;
        }
    }

    /**
     * Puts a machine in a bay. SPEC.md §10's insert order, in order.
     *
     * @param placer the real player doing the inserting, never null - every Mekanism machine records
     *               an owner UUID in {@code setPlacedBy}, and an owner-less machine is GUI-locked.
     * @return true if the machine is now standing in the bay
     */
    public static boolean rack(ServerLevel backshop, ChunkPos column, int bay, ItemStack stack,
        ServerPlayer placer, Direction facing) {
        return place(backshop, column, bay, stack, placer, facing).placed();
    }

    /**
     * The same rack, with the reason when it did not happen, for the one caller that has a player
     * to tell. SPEC.md §10.
     *
     * <p><b>A bay a machine does not fit in is grown once before it is refused.</b> The first try
     * is the bay as it stands - narrow, for every bay in every world already saved. A machine that
     * claims the blocks around it lands on the Ports there and is taken back out; the bay is then
     * rebuilt at {@link BayGeometry#WIDE_SHELL}, which moves the Ports one block out and leaves
     * the machine a block of clearance on every side, and the same placement is tried once more.
     * Only a machine that reaches past <em>that</em> is refused, and the bay is put back narrow on
     * the way out so nothing stands wider than what it holds.
     *
     * <p>Grown rather than every bay being wide from the start, because the width is paid for out
     * of the gap between bays: {@link BayGeometry#BAY_PITCH} is a saved coordinate and cannot
     * move, so a wide bay spends most of the gap it has. A bay is wide only while something in it
     * needs it to be.
     */
    public static Outcome place(ServerLevel backshop, ChunkPos column, int bay, ItemStack stack,
        ServerPlayer placer, Direction facing) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return Outcome.FAILED;
        }
        int shell = BayBuilder.shellAt(backshop, column, bay);
        Outcome first = attempt(backshop, column, bay, stack, placer, facing, shell);
        if (first != Outcome.NEEDS_ROOM || shell == BayGeometry.WIDE_SHELL) {
            return first;
        }

        LOG.info("{} needs the blocks around it; growing bay {} to {} and trying once more",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()),
            bay, BayGeometry.WIDE_SHELL);
        BayBuilder.rebuild(backshop, column, bay, BayGeometry.WIDE_SHELL);
        Outcome wide = attempt(backshop, column, bay, stack, placer, facing,
            BayGeometry.WIDE_SHELL);
        if (wide != Outcome.PLACED) {
            // Nothing is standing in it, so it goes back to the width every other empty bay is.
            BayBuilder.rebuild(backshop, column, bay, BayGeometry.SHELL);
        }
        return wide;
    }

    /** One placement into a bay of one width. The bay is left as it was found unless it worked. */
    private static Outcome attempt(ServerLevel backshop, ChunkPos column, int bay, ItemStack stack,
        ServerPlayer placer, Direction facing, int shell) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return Outcome.FAILED;
        }
        Block block = blockItem.getBlock();
        BlockPos pos = BayBuilder.ensure(backshop, column, bay, shell);

        BlockState state = orient(block.defaultBlockState(), facing);

        // What the bay looks like before the machine touches it. Read rather than assumed, so a
        // bay built by an older version is compared against itself and not against today's shape.
        BlockState[] before = shellSnapshot(backshop, column, bay, shell);

        // 1. The block itself.
        backshop.setBlock(pos, state, Block.UPDATE_ALL);

        // Steps 2-4 are one guarded block: a foreign loader or setPlacedBy throwing is a real
        // outcome, and a throw between the setBlock and the caller's hand shrink would leave the
        // machine standing in the bay AND the item in the hand (night 2026-09-11, 1B #5b). Undo the
        // placement rather than leave a half-placed machine.
        try {
            // 2 and 3. What the item was carrying. minecraft:block_entity_data excludes x, y, z,
            // components and keepPacked, so the BlockState is not carried by the item and the
            // facing above is ours to choose - which is why it is stored per bay rather than
            // inferred.
            BlockItem.updateCustomBlockEntityTag(backshop, placer, pos, stack);
            BlockEntity placed = backshop.getBlockEntity(pos);
            if (placed != null) {
                placed.applyComponentsFromItemStack(stack);
            }

            // 4. setBlock alone never calls this, and skipping it leaves machines that record an
            // owner owner-less and GUI-locked, and leaves bounding blocks unplaced.
            block.setPlacedBy(backshop, pos, state, placer, stack);
        } catch (Exception e) {
            LOG.error("placing {} in a bay threw; refusing to host it",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block), e);
            backshop.removeBlockEntity(pos);
            backshop.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            backshop.invalidateCapabilities(pos);
            return Outcome.FAILED;
        }

        // 5. Did it stay inside the space this bay gives it? A machine that claims the blocks
        // around it - Mekanism's Digital Miner writes bounding blocks over 3x2x3 - has just
        // replaced a narrow bay's Ports with its own filler, which is a machine that can never be
        // reached and a bay that can never be rebuilt without deleting it. There is no way to ask
        // a block this in advance (the space it claims is decided inside its own setPlacedBy), so
        // it is asked afterwards, and the answer is the bay put back exactly as it was found -
        // after which place() gives it a wider bay and asks again.
        if (scribbled(backshop, column, bay, before, shell)) {
            LOG.info("{} wrote outside the space bay {} gives it at shell {}; putting the bay back",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block), bay, shell);
            restore(backshop, column, bay, before, shell);
            return Outcome.NEEDS_ROOM;
        }

        // 6. The bay's ports and any bus pointed here have to re-resolve against the new machine.
        backshop.invalidateCapabilities(pos);
        return Outcome.PLACED;
    }

    /** Every block of the bay's shell, in x-y-z order. */
    private static BlockState[] shellSnapshot(ServerLevel backshop, ChunkPos column, int bay,
        int shell) {
        int width = BayGeometry.validShell(shell);
        BlockPos origin = BayGeometry.shellOrigin(column, bay, width);
        BlockState[] states = new BlockState[width * width * width];
        int i = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    states[i++] = backshop.getBlockState(origin.offset(x, y, z));
                }
            }
        }
        return states;
    }

    /**
     * True when anything in the bay the machine was not given is not what it was.
     *
     * <p>Compared against the snapshot rather than against the bay's ideal shape, because a bay
     * that was already odd before this machine arrived is not this machine's fault and refusing
     * it would strand a player with a bay nothing can be put into.
     *
     * <p>The machine's own block is its to write, and in a grown bay so is the {@link #clearance}
     * around it - a ring of air a narrow bay does not have, and the entire difference growing
     * makes. Everything else is the bay: its walls, its Ports, and in a grown bay whatever lies
     * past the clearance. A machine that reaches into any of that does not fit here at any width.
     */
    private static boolean scribbled(ServerLevel backshop, ChunkPos column, int bay,
        BlockState[] before, int shell) {
        int width = BayGeometry.validShell(shell);
        BlockPos origin = BayGeometry.shellOrigin(column, bay, width);
        BlockPos machine = BayGeometry.machinePos(column, bay);
        int i = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState was = before[i++];
                    if (pos.equals(machine) || clearance(machine, pos, width)) {
                        continue;
                    }
                    if (!backshop.getBlockState(pos).equals(was)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The blocks a grown bay holds open around the machine for the machine to write into: the
     * 3x3x3 it touches, which is the shape a bounding-block machine claims.
     *
     * <p><b>A narrow bay has none.</b> Its Ports are exactly those blocks, so there is nothing
     * there to give away and every position that is not the machine belongs to the bay - which is
     * the rule this class shipped with, and the reason a bed is refused a narrow bay at all.
     */
    private static boolean clearance(BlockPos machine, BlockPos pos, int shell) {
        return shell == BayGeometry.WIDE_SHELL
            && Math.abs(pos.getX() - machine.getX()) <= 1
            && Math.abs(pos.getY() - machine.getY()) <= 1
            && Math.abs(pos.getZ() - machine.getZ()) <= 1;
    }

    /**
     * The bay as it was, machine included: the machine first, so that a filler block asking after
     * its owner finds nothing there and drops nothing, and the rest after it. The clearance goes
     * back too - it is the machine's to write only while the machine is standing in it.
     */
    private static void restore(ServerLevel backshop, ChunkPos column, int bay, BlockState[] before,
        int shell) {
        int width = BayGeometry.validShell(shell);
        BlockPos origin = BayGeometry.shellOrigin(column, bay, width);
        BlockPos machine = BayGeometry.machinePos(column, bay);
        backshop.removeBlockEntity(machine);
        backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        backshop.invalidateCapabilities(machine);
        int i = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState was = before[i++];
                    if (pos.equals(machine) || backshop.getBlockState(pos).equals(was)) {
                        continue;
                    }
                    // removeBlockEntity first: a filler block's own onRemove is the thing that
                    // breaks the machine it belongs to, and the machine is already gone.
                    backshop.removeBlockEntity(pos);
                    backshop.setBlock(pos, was, Block.UPDATE_CLIENTS);
                    backshop.invalidateCapabilities(pos);
                }
            }
        }
    }

    /**
     * Takes the machine back out, with everything inside it. SPEC.md §10's extract order.
     *
     * @return the machine as an item, or an empty stack if the bay was empty
     */
    public static ItemStack eject(ServerLevel backshop, ChunkPos column, int bay, @Nullable ServerPlayer player) {
        BlockPos pos = BayGeometry.machinePos(column, bay);
        BlockState state = backshop.getBlockState(pos);
        if (state.isAir()) {
            return ItemStack.EMPTY;
        }

        // 1. getCloneItemStack, not Block#getDrops: getDrops is loot-table driven and hands back the
        // machine and its contents as separate stacks, which in a sealed room means the contents
        // are simply gone.
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        ItemStack stack = state.getCloneItemStack(hit, backshop, pos, player);
        if (stack.isEmpty()) {
            stack = new ItemStack(state.getBlock());
        }

        // 2. Everything the machine was holding, onto the item.
        BlockEntity be = backshop.getBlockEntity(pos);
        if (be != null) {
            be.saveToItem(stack, backshop.registryAccess());
        }

        // 3. Before the setBlock. Block#onRemove runs Containers.dropContents, and a chest's worth
        // of somebody's ore would spill into a room nobody can reach.
        backshop.removeBlockEntity(pos);

        // 4 and 5. The whole bay, not just the machine's block: see #wipe.
        wipe(backshop, column, bay);
        return stack;
    }

    /**
     * <b>An emptied bay is an empty bay.</b> Taking the machine out used to set its one block to
     * air and leave everything else standing, and by then a bay can be holding plenty: the half a
     * bed's {@code setPlacedBy} wrote into the clearance, the filler a machine with bounding blocks
     * left behind, whatever an older version of this mod put there, and the shell itself one width
     * too wide for a bay with nothing in it. All of it invisible - nobody can walk into an empty
     * bay, {@link BayVisit} refuses one - and all of it in the way of the next machine, which is
     * offered a bay that is not the shape a bay is supposed to be.
     *
     * <p>So the bay is rebuilt from nothing at {@link BayGeometry#SHELL}: every position either
     * width could occupy written back to wall, Port or air, block entities taken out ahead of each
     * write so none of them spills its contents on the way, and the shell narrow again because
     * nothing is standing in it that needed it wide.
     *
     * <p>The item sweep is <b>after</b> the rebuild and not before. Anything a removal does manage
     * to drop is dropped during it, and an item entity in a sealed room in a dimension nobody can
     * reach is a thing that ticks for ever and can never be picked up.
     */
    private static void wipe(ServerLevel backshop, ChunkPos column, int bay) {
        BayBuilder.rebuild(backshop, column, bay, BayGeometry.SHELL);

        BlockPos origin = BayGeometry.shellOrigin(column, bay, BayGeometry.WIDE_SHELL);
        int width = BayGeometry.WIDE_SHELL;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + width, origin.getY() + width, origin.getZ() + width);
        for (net.minecraft.world.entity.item.ItemEntity loose : backshop.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class, box)) {
            loose.discard();
        }
    }

    /**
     * The machine turned to face {@code facing} -- and <b>a block that can stand up, stood up</b>.
     * A block with a horizontal facing (a furnace, every Mekanism machine) is choosing a front,
     * and gets the one asked for. A block with the six-way {@code FACING} -- a barrel, a piston,
     * a dispenser -- is drawn upright by its own item model, and turning it north laid a barrel
     * on its side in the bay and on the face cube while the item preview beside it stood up.
     * Its default is no help (a barrel's is north); UP is what its picture shows, and a hopper,
     * which cannot face up, keeps its own. OPEN_ISSUES #99. Public because the face cube draws
     * through the same rule, so the block in the bay and the block on the screen cannot disagree.
     */
    public static BlockState orient(BlockState state, Direction facing) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        if (state.hasProperty(BlockStateProperties.FACING)
            && BlockStateProperties.FACING.getPossibleValues().contains(Direction.UP)) {
            return state.setValue(BlockStateProperties.FACING, Direction.UP);
        }
        return state;
    }
}
