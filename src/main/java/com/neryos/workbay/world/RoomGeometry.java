package com.neryos.workbay.world;

import com.neryos.workbay.content.room.RoomPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a room is, and how big. SPEC.md §8, and nothing else in the mod may work this out for
 * itself.
 *
 * <p>Pure arithmetic, like {@link BayGeometry} and for the same reason: every coordinate here is
 * baked into saved worlds the moment anyone builds in a room, and there are no mod DataFixers to
 * move a chest afterwards.
 *
 * <p><b>Three sizes, all one chunk.</b> A room is a cube of 3, 9 or 13 -- Compact Machines'
 * sizes, the ones players already know -- so its shell is 5, 11 or 15 across and every one fits
 * inside the sixteen-block chunk its region starts on. Size is a purchase at the crafting table
 * (SPEC.md §0), never a chunk bill.
 */
public final class RoomGeometry {
    private RoomGeometry() {}

    /** Interior side, by room tier. Index 0 is "not built yet", which is no shell. */
    private static final int[] INTERIOR = {0, 3, 9, 13};

    /** The biggest room there is, and the footprint every region reserves. */
    public static final int MAX_TIER = INTERIOR.length - 1;

    /**
     * Interior height: <b>the same as the interior side</b>, so every room is a cube.
     *
     * <p>Height still costs no chunks - a ticket is a column, and that is why the <em>price</em>
     * is the footprint and nothing else. What does not follow from a free axis is that it should
     * be 32 at every tier: 14 across and 32 tall is a shaft, not a room. Compact Machines' sizes
     * are cubes for the same reason, and a cube is the one proportion nobody has to think about.
     */
    public static int height(int tier) {
        return interior(tier);
    }

    /** The shell's floor. The interior is y 1..height(tier) and the ceiling is the one above. */
    public static final int FLOOR_Y = 0;

    /**
     * Blocks between one region's origin and the next. Compact Machines' answer to sound bleed,
     * and it costs nothing: the Backshop is empty and infinite.
     */
    private static final int REGION_SPACING = 512;

    /** Far negative x, so a room can never meet a bay column in the positive quadrant. */
    private static final int REGION_ORIGIN_X = -1_048_576;

    /** Regions per row before the allocator steps in z. */
    private static final int REGIONS_PER_ROW = 512;

    /**
     * The low corner of region {@code i}'s reserved space, and the low corner of the shell built
     * in it at <b>every</b> tier.
     *
     * <p>Corner-anchored, so the origin is the chunk's own corner and the shell never crosses into
     * the next chunk at any size.
     */
    public static BlockPos origin(int region) {
        return new BlockPos(REGION_ORIGIN_X + (region % REGIONS_PER_ROW) * REGION_SPACING, FLOOR_Y,
            (region / REGIONS_PER_ROW) * REGION_SPACING);
    }

    /** Interior side for a room tier, or 0 for a room that is not built. */
    public static int interior(int tier) {
        return INTERIOR[Math.max(0, Math.min(MAX_TIER, tier))];
    }

    /** Outside side, walls included. 5, 11 or 15 -- always inside one chunk. */
    public static int footprint(int tier) {
        int inside = interior(tier);
        return inside == 0 ? 0 : inside + 2;
    }

    /** The room's footprint in chunks: one, at every size, and zero before it is built. */
    public static int chunkCost(int tier) {
        return footprint(tier) == 0 ? 0 : 1;
    }

    /**
     * Shell-relative coordinates, on one axis, of the ceiling's light fixtures. OPEN_ISSUES #45.
     *
     * <p>One roughly every eight blocks and symmetric about the middle: a 3 and a 9 get one on the
     * axis (a single lamp in the middle), a 13 gets two (four lamps). The spacing is the number,
     * not the count, so a lamp every eight blocks is the same room at every size.
     */
    public static int[] lightAxis(int tier) {
        int inside = interior(tier);
        if (inside == 0) {
            return new int[0];
        }
        int lamps = Math.max(1, Math.round(inside / 8.0F));
        int[] out = new int[lamps];
        for (int i = 0; i < lamps; i++) {
            // Shell-relative, so the +1 is the wall the interior starts after.
            out[i] = 1 + (int) ((i + 0.5) * inside / lamps);
        }
        return out;
    }

    /** The shell's ceiling layer. */
    public static int ceilingY(int tier) {
        return FLOOR_Y + height(tier) + 1;
    }

    /**
     * The four doors, as a block position to the part it draws, one in the middle of each wall,
     * standing on the floor. <b>An odd wall gets a 1x2 door on its middle block; an even wall gets
     * a 2x2 door on its middle seam</b> - the eye wants the door dead centre either way (found in play,
     * 2026-09-14: every shipped interior is odd, and the 2x2 sat half a block off).
     *
     * <p><b>Left and right are the viewer's, and the viewer is inside.</b> The first draft named
     * them looking <em>into</em> the room, which is the one side nobody is ever on - a player
     * stands in the room and looks <em>out</em> at the wall. Every wall came out mirrored: both
     * handles on the outer edges, a hinge stile down the middle of each leaf, two single doors hung
     * backwards rather than one double door. Found on the first screenshot of a room.
     * {@code everyDoorIsCentredOnItsWall} and {@code aDoorsLeavesMeetInTheMiddle} are the guards.
     */
    public static java.util.Map<BlockPos, RoomPart> doors(int region, int tier) {
        int side = footprint(tier);
        java.util.Map<BlockPos, RoomPart> out = new java.util.LinkedHashMap<>();
        if (side < 4) {
            return out;
        }
        BlockPos o = origin(region);
        boolean odd = (side - 2) % 2 == 1;
        int a = side / 2 - 1;
        int b = side / 2;
        for (int i = 0; i < 2; i++) {
            int y = 1 + i;
            if (odd) {
                RoomPart part = i == 0 ? RoomPart.DOOR_BOTTOM : RoomPart.DOOR_TOP;
                out.put(o.offset(0, y, b), part);
                out.put(o.offset(side - 1, y, b), part);
                out.put(o.offset(b, y, 0), part);
                out.put(o.offset(b, y, side - 1), part);
                continue;
            }
            RoomPart left = i == 0 ? RoomPart.DOOR_BOTTOM_LEFT : RoomPart.DOOR_TOP_LEFT;
            RoomPart right = i == 0 ? RoomPart.DOOR_BOTTOM_RIGHT : RoomPart.DOOR_TOP_RIGHT;
            // West wall (x = 0), seen from inside looking west: +z is on the viewer's left.
            out.put(o.offset(0, y, b), left);
            out.put(o.offset(0, y, a), right);
            // East wall, seen looking east: the order flips.
            out.put(o.offset(side - 1, y, a), left);
            out.put(o.offset(side - 1, y, b), right);
            // North wall (z = 0), seen looking north: -x is on the viewer's left.
            out.put(o.offset(a, y, 0), left);
            out.put(o.offset(b, y, 0), right);
            // South wall.
            out.put(o.offset(b, y, side - 1), left);
            out.put(o.offset(a, y, side - 1), right);
        }
        return out;
    }

    /** Where a visitor arrives: on the pad in the low corner. */
    public static Vec3 entrySpot(int region) {
        BlockPos pad = origin(region).offset(2, 1, 2);
        return new Vec3(pad.getX() + 0.5, pad.getY(), pad.getZ() + 0.5);
    }

    /**
     * Facing north from {@link #entrySpot}: the north wall's door is straight ahead in a 3-room
     * and in view in the others. Facing the corner (-45) put a player in a 3-room nose-first
     * into two walls.
     */
    public static final float ENTRY_YAW = 180.0F;

    /** The air a player may stand in. Everything outside it in the Backshop is not theirs. */
    public static AABB interiorBox(int region, int tier) {
        int inside = interior(tier);
        BlockPos low = origin(region).offset(1, 1, 1);
        return new AABB(low.getX(), low.getY(), low.getZ(),
            low.getX() + inside, low.getY() + height(tier), low.getZ() + inside);
    }

    /** True when a position is inside the room's air, walls excluded. */
    public static boolean inside(BlockPos pos, int region, int tier) {
        int inside = interior(tier);
        BlockPos low = origin(region).offset(1, 1, 1);
        return pos.getX() >= low.getX() && pos.getX() < low.getX() + inside
            && pos.getY() >= low.getY() && pos.getY() < low.getY() + height(tier)
            && pos.getZ() >= low.getZ() && pos.getZ() < low.getZ() + inside;
    }

    /** The one chunk a built room occupies, or none before it is built. */
    public static java.util.List<ChunkPos> chunks(int region, int tier) {
        return footprint(tier) == 0 ? java.util.List.of()
            : java.util.List.of(new ChunkPos(origin(region)));
    }
}
