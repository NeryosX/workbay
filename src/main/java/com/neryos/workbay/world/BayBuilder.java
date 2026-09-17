package com.neryos.workbay.world;

import com.neryos.workbay.init.WBBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds a bay in the Backshop. SPEC.md §8.
 *
 * <p>A 5x5x5 shell around a 3x3x3 interior, a Port on each of the interior's six faces, and the
 * machine in the middle. The shape is the whole reason bays are not bare 1x1x1 holes: a machine
 * with air neighbours has nothing to push into, every {@code hasNeighborSignal} machine is dead,
 * and anything writing a bounding block at {@code pos.above()} corrupts.
 *
 * <p>Or 7x7x7, for a machine that writes into its own neighbours anyway. The grown shell is the
 * same shape one ring further out ({@link BayGeometry#WIDE_SHELL}), so everything here takes the
 * shell as an argument and the machine's position is the same either way.
 */
public final class BayBuilder {
    private BayBuilder() {}

    /**
     * Bedrock: nothing a survival player carries gets through it, and it has no gravity, no
     * burning, no piston interaction and no block entity. It is the layer <em>under</em> the
     * rule that keeps a visitor in a bay ({@link BayVisit}: no screen open, no visit), not the
     * rule itself. SPEC.md §2 lists exactly three blocks for this mod and a wall material is not
     * one of them, so it is vanilla.
     */
    private static final BlockState WALL = Blocks.BEDROCK.defaultBlockState();

    /**
     * How wide the bay standing in the world is, read rather than remembered.
     *
     * <p>One block state: a Port two out from the machine can only be a wide bay's, because a
     * narrow one has bedrock there. {@link WorkbayRecord.Bay#shell()} is the saved copy of this
     * answer and the two are written together, but the world is the one that cannot be wrong.
     */
    public static int shellAt(ServerLevel backshop, ChunkPos column, int bay) {
        return backshop.getBlockState(
            BayGeometry.portPos(column, bay, Direction.UP, BayGeometry.WIDE_SHELL))
            .is(WBBlocks.PORT.get()) ? BayGeometry.WIDE_SHELL : BayGeometry.SHELL;
    }

    /**
     * Builds the bay at the given width if it is not already there, and returns where the machine
     * goes.
     *
     * <p>Idempotent, and deliberately credulous: it is called every time a machine is racked, and
     * a rebuild would replace the machine standing in the middle with air. One Port is enough to
     * say the bay is there, the way it always has been - a bay missing some of its Ports is
     * repaired by the audit ({@code BayBuilder#audit}), which puts them back without touching what
     * is in the middle. A bay standing at the <em>other</em> width is a different bay and is
     * rebuilt, which is only ever asked for by {@link BayHosting}, with the middle empty.
     */
    public static BlockPos ensure(ServerLevel backshop, ChunkPos column, int bay, int shell) {
        BlockPos machine = BayGeometry.machinePos(column, bay);
        int width = BayGeometry.validShell(shell);
        if (shellAt(backshop, column, bay) == width
            && backshop.getBlockState(BayGeometry.portPos(column, bay, Direction.UP, width))
                .is(WBBlocks.PORT.get())) {
            audit(backshop, column, bay, width);
            return machine;
        }
        rebuild(backshop, column, bay, width);
        return machine;
    }

    /**
     * <b>The bay's box, put back.</b> Every wall that is not {@link #WALL} and every Port that is
     * not a Port is written again; nothing in the interior is read or touched, so whatever the
     * machine is holding, and whatever it wrote into the clearance a wide bay gives it, is left
     * exactly alone.
     *
     * <p>It exists for two jobs that turn out to be one. The old one: every bay built before the
     * walls became bedrock still has obsidian in every save already played, because {@link #ensure}
     * returns early once the Ports are there - a shell a survival player can break out of, backing
     * a rule that says nobody is in the Backshop without a screen open.
     *
     * <p>The new one: <b>the shell is the only thing standing between a hosted machine and the rest
     * of the Backshop, and it only stops a machine that asks.</b> Bedrock turns away everything
     * that reads hardness, which is every quarry and every miner worth the name, and it does
     * nothing at all about a machine that calls {@code level.setBlock} or {@code destroyBlock} on a
     * position it picked. Nothing can: there is no hook that could tell that call apart from a
     * legitimate one without paying for a check on every block placed in the game. So the shell is
     * not defended, it is <em>repaired</em> - {@code WorkbayBlockEntity} runs this over every
     * occupied bay once per wheel step, and a hole a machine opens is closed again within five
     * ticks, for as long as it keeps trying.
     *
     * <p>Putting a Port back cannot break a machine that is entitled to be here: a machine that
     * writes over a Port is refused the bay at rack time ({@link BayHosting}), so anything standing
     * has already been proved to keep to itself and its clearance.
     *
     * @return how many blocks had to be put back, which is 0 for a bay nothing has touched
     */
    public static int audit(ServerLevel backshop, ChunkPos column, int bay, int shell) {
        int width = BayGeometry.validShell(shell);
        BlockPos origin = BayGeometry.shellOrigin(column, bay, width);
        int repaired = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    if (interior(x, y, z, width)) {
                        continue;
                    }
                    BlockPos pos = origin.offset(x, y, z);
                    if (backshop.getBlockState(pos).is(WALL.getBlock())) {
                        continue;
                    }
                    put(backshop, pos, WALL);
                    repaired++;
                }
            }
        }
        BlockState port = WBBlocks.PORT.get().defaultBlockState();
        for (Direction face : Direction.values()) {
            BlockPos pos = BayGeometry.portPos(column, bay, face, width);
            if (backshop.getBlockState(pos).is(WBBlocks.PORT.get())) {
                continue;
            }
            put(backshop, pos, port);
            repaired++;
        }
        return repaired;
    }

    /** One block of the bay's own shape, over whatever is standing there. */
    private static void put(ServerLevel backshop, BlockPos pos, BlockState state) {
        // Before the write, always: whatever is being written over may be a container, and its
        // Block#onRemove would drop what it holds onto a floor no player can ever stand on.
        if (backshop.getBlockState(pos).hasBlockEntity()) {
            backshop.removeBlockEntity(pos);
            backshop.invalidateCapabilities(pos);
        }
        backshop.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    /**
     * The bay as it comes out of the box, over everything that was there: wall, Port, air, and an
     * empty middle. <b>Every position a bay of either width could occupy is written</b>, so
     * shrinking a grown bay leaves no ring of stranded bedrock behind and a rebuilt bay is the same
     * blocks as one built in a world that had never seen it.
     *
     * <p>Whatever stood here is gone, contents and all, so nothing may call this on a bay with a
     * machine in it: {@link BayHosting} clears the middle first, {@link #ensure} only reaches it
     * when the shape is wrong anyway.
     */
    public static void rebuild(ServerLevel backshop, ChunkPos column, int bay, int shell) {
        int width = BayGeometry.validShell(shell);
        int widest = BayGeometry.WIDE_SHELL;
        BlockPos outer = BayGeometry.shellOrigin(column, bay, widest);
        BlockPos origin = BayGeometry.shellOrigin(column, bay, width);
        BlockState port = WBBlocks.PORT.get().defaultBlockState();
        BlockPos machine = BayGeometry.machinePos(column, bay);

        for (int x = 0; x < widest; x++) {
            for (int y = 0; y < widest; y++) {
                for (int z = 0; z < widest; z++) {
                    BlockPos pos = outer.offset(x, y, z);
                    int lx = pos.getX() - origin.getX();
                    int ly = pos.getY() - origin.getY();
                    int lz = pos.getZ() - origin.getZ();
                    BlockState state;
                    if (lx < 0 || ly < 0 || lz < 0 || lx >= width || ly >= width || lz >= width) {
                        // Outside this bay's shell: the void a narrow bay sits in.
                        state = Blocks.AIR.defaultBlockState();
                    } else if (!interior(lx, ly, lz, width)) {
                        state = WALL;
                    } else if (BayGeometry.isPort(column, bay, pos, width)) {
                        state = port;
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    // removeBlockEntity before the write: this is the one place that writes over
                    // blocks it did not put there, and a block entity left standing spills its
                    // contents through Block#onRemove into a room nobody can reach.
                    if (backshop.getBlockState(pos).hasBlockEntity()) {
                        backshop.removeBlockEntity(pos);
                        backshop.invalidateCapabilities(pos);
                    }
                    // UPDATE_CLIENTS, not UPDATE_ALL: nothing observes the Backshop and neighbour
                    // updates across a few hundred blocks per bay are pure cost.
                    backshop.setBlock(pos, state, Block.UPDATE_CLIENTS);
                }
            }
        }
        backshop.setBlock(machine, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** True for a position inside the shell's walls, in shell-local coordinates. */
    private static boolean interior(int x, int y, int z, int width) {
        return x > 0 && x < width - 1 && y > 0 && y < width - 1 && z > 0 && z < width - 1;
    }

    /** True when the bay's shape is intact: six Ports around an empty-or-occupied middle. */
    public static boolean isBuilt(ServerLevel backshop, ChunkPos column, int bay, int shell) {
        for (Direction face : Direction.values()) {
            if (!backshop.getBlockState(BayGeometry.portPos(column, bay, face, shell))
                .is(WBBlocks.PORT.get())) {
                return false;
            }
        }
        return true;
    }
}
