package com.neryos.workbay.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

/**
 * Where a bay is. SPEC.md §8, and nothing else in the mod may work this out for itself.
 *
 * <p>Pure arithmetic on purpose: every Y coordinate here is baked into saved worlds the moment
 * anyone plays, and there are no mod DataFixers to move a bay afterwards. Changing a constant in
 * this class strands every machine already hosted.
 *
 * <p><b>A bay has two sizes and the machine never moves between them.</b> Every method that depends
 * on the size takes the shell as an argument rather than reading a constant, because one network's
 * bay 3 can be wide while its bay 4 is not; {@link WorkbayRecord.Bay#shell()} is where the answer
 * is kept and {@code BayBuilder.shellAt} is where it is read back out of the world.
 */
public final class BayGeometry {
    private BayGeometry() {}

    /** A 3x3x3 interior inside a 5x5x5 shell: the machine at the centre, a Port on each face. */
    public static final int INTERIOR = 3;
    public static final int SHELL = 5;

    /**
     * The size a bay grows to for a machine that claims the blocks around it. A 5x5x5 shell puts
     * its Ports directly against the machine, so anything writing into its own neighbours - a
     * Mekanism Digital Miner writes 3x2x3 of filler - lands on them; at 7 the Ports stand two out
     * and there is a block of clearance on every side for the machine to write into.
     *
     * <p><b>It grows symmetrically and the machine does not move</b>, which is the whole reason 7
     * is the number: the interior goes from 3x3x3 to 5x5x5 around the same centre, so every machine
     * in every world already saved is still at the position {@link #machinePos} has always given.
     * The extra ring comes out of the gap between bays - {@link #BAY_PITCH} is untouched, so bay 0
     * runs y 7 to 13 instead of 8 to 12 and bay 1 still starts clear of it, at y 15 instead of 16.
     */
    public static final int WIDE_SHELL = 7;

    /** Every size a bay may be, smallest first: the order {@code BayHosting} tries them in. */
    public static final int[] SHELLS = {SHELL, WIDE_SHELL};

    /** Bay 0's floor. Above bedrock-equivalent depth, and clear of the dimension's min_y of 0. */
    public static final int FIRST_FLOOR_Y = 8;

    /** 5 for the shell plus a 3-block gap, so eight bays sit between y 8 and y 68. */
    public static final int BAY_PITCH = 8;

    /** SPEC.md §4: eight bay buttons fit the screen strip without scrolling, so eight is the cap. */
    public static final int MAX_BAYS = 8;

    /** 5 or 7, and 5 for anything else a save or a caller hands over. */
    public static int validShell(int shell) {
        return shell == WIDE_SHELL ? WIDE_SHELL : SHELL;
    }

    /** How far a wider shell reaches past the 5x5x5 one, on every axis and in both directions. */
    private static int inset(int shell) {
        return (validShell(shell) - SHELL) / 2;
    }

    /**
     * North-west, bottom corner of the bay's shell. Chunk-local (6, 6) at {@link #SHELL}, so the
     * shell fits one chunk; (5, 5) at {@link #WIDE_SHELL}, which still does, with its far corner
     * at chunk-local 11.
     */
    public static BlockPos shellOrigin(ChunkPos column, int bay, int shell) {
        int out = inset(shell);
        return new BlockPos(column.getMinBlockX() + 6 - out,
            FIRST_FLOOR_Y + bay * BAY_PITCH - out, column.getMinBlockZ() + 6 - out);
    }

    /**
     * Where the hosted machine goes: the middle of the interior, two in from the 5x5x5 shell's
     * corner. <b>Takes no shell and never will</b> - it is the centre of both, and every saved
     * world has a machine standing on the answer.
     */
    public static BlockPos machinePos(ChunkPos column, int bay) {
        return shellOrigin(column, bay, SHELL).offset(2, 2, 2);
    }

    /** How far a Port stands from the machine: 1 in a 5x5x5 bay, 2 in a 7x7x7 one. */
    public static int portDistance(int shell) {
        return (validShell(shell) - INTERIOR) / 2;
    }

    /** The Port on one face of the interior, at the far end of that face's clearance. */
    public static BlockPos portPos(ChunkPos column, int bay, Direction face, int shell) {
        return machinePos(column, bay).relative(face, portDistance(shell));
    }

    /** True for the six interior positions that hold a Port. */
    public static boolean isPort(ChunkPos column, int bay, BlockPos pos, int shell) {
        BlockPos machine = machinePos(column, bay);
        int distance = portDistance(shell);
        for (Direction face : Direction.values()) {
            if (machine.relative(face, distance).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which bay a position falls inside, or -1 for the gap between bays and anything outside them.
     * The inverse of {@link #shellOrigin}, and the only one there will ever be.
     *
     * <p>Answers for the <b>widest</b> shell, so a position is attributed to a bay whether or not
     * that bay has grown; the one block of gap a wide bay leaves (y 14 above bay 0) belongs to
     * neither. The +1 is that widest shell's floor, one below the narrow one's.
     */
    public static int bayAt(BlockPos pos) {
        int offset = pos.getY() - FIRST_FLOOR_Y;
        int out = inset(WIDE_SHELL);
        int bay = Math.floorDiv(offset + out, BAY_PITCH);
        int local = offset - bay * BAY_PITCH;
        return bay >= 0 && bay < MAX_BAYS && local >= -out && local < WIDE_SHELL - out ? bay : -1;
    }

    /**
     * The machine a Port stands against, or null when this is not a Port.
     *
     * <p>Exists because a Port is the machine's <b>door</b> as well as its socket: the six of them
     * seal the machine on every face, so a player standing in the bay cannot right-click it
     * directly and the Port has to pass the click through.
     *
     * <p><b>Both distances are tried</b>, because the only thing this is ever given is a position:
     * a Port block being clicked knows where it stands and nothing else, and reaching the record
     * that would say how wide its bay is would mean a registry lookup per right-click on a
     * question two subtractions answer.
     */
    public static BlockPos machineBehind(BlockPos portPos) {
        int bay = bayAt(portPos);
        if (bay < 0) {
            return null;
        }
        ChunkPos column = new ChunkPos(portPos);
        for (int shell : SHELLS) {
            if (isPort(column, bay, portPos, shell)) {
                return machinePos(column, bay);
            }
        }
        return null;
    }

    /** Highest block a bay occupies, so callers can check the dimension is tall enough. */
    public static int topY(int bay, int shell) {
        return shellOrigin(ChunkPos.ZERO, bay, shell).getY() + validShell(shell) - 1;
    }
}
