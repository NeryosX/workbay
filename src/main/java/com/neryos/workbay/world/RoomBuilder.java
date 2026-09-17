package com.neryos.workbay.world;

import com.neryos.workbay.content.room.RoomPart;
import com.neryos.workbay.content.room.RoomWallBlock;
import com.neryos.workbay.init.WBBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds, grows and paints a room in the Backshop. SPEC.md §8.
 *
 * <p>Shell and interior air, and nothing else: a floor the player did not choose is a floor they
 * have to dig up. The chunks generate empty - {@code FlatLevelSource}
 * with no layers - so a room is a block write and never a worldgen cost.
 *
 * <p><b>Growth only ever moves the far walls outward and the ceiling up.</b> Because the shell is
 * corner-anchored at the region origin and every tier is a cube, expanding turns the old walls and
 * ceiling into air and writes new ones where there was only air. Nothing a player built can be in
 * the way, and the old shell is cleared <em>only where it is still shell</em>, so a creative player
 * who built into it loses nothing.
 */
public final class RoomBuilder {
    private RoomBuilder() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * The tallest a room ever was: every tier used to be 32 high before they became cubes. Rooms
     * are a cube now, so a repaint has to sweep the shell an old room left <b>above</b> the new
     * ceiling - otherwise the first Frame upgrade puts that old bedrock box back inside the bigger
     * room, in mid-air, where it looks exactly like a bug. One number, and it goes when nothing
     * can have been built before cubes.
     */
    private static final int LEGACY_TOP = 33;

    /** One piece of this room's shell, wearing the record's colour. */
    private static BlockState shellState(RoomRecord room, RoomPart part) {
        return WBBlocks.ROOM_WALL.get().defaultBlockState()
            .setValue(RoomWallBlock.COLOUR, room.colour())
            .setValue(RoomWallBlock.PART, part);
    }

    /**
     * True for anything that is shell rather than something a player put there. Bedrock is in it
     * because rooms were built out of bedrock before they had a colour, and those rooms have to be
     * able to grow and to be repainted without leaving a bedrock ring where the old wall was.
     */
    private static boolean isShell(BlockState state) {
        return state.is(WBBlocks.ROOM_WALL.get()) || state.is(Blocks.BEDROCK);
    }

    /**
     * Makes the room in the world match {@code tier} and the record's colour, and returns the
     * record that says so.
     *
     * <p>Called on every entry, so it is deliberately cheap in the common case: a room already the
     * right size and the right colour is <b>three block reads</b> - one corner of the shell, the
     * course above it and one
     * of its doorways.
     */
    public static RoomRecord ensure(ServerLevel backshop, RoomRecord room, int tier) {
        if (tier <= 0 || tier < room.builtTier()) {
            // Frames only ever go up (SPEC.md §1 has no removal path), so a smaller tier is a
            // datapack or a config edit shrinking the ladder under a room that is already bigger.
            // Shrinking would put a wall through somebody's factory; leaving it alone costs a few
            // extra chunks.
            return tier <= 0 ? room : repair(backshop, room);
        }
        if (tier > room.builtTier()) {
            if (room.built()) {
                clearShell(backshop, room, room.builtTier());
            }
            RoomRecord grown = room.withBuiltTier(tier);
            paint(backshop, grown);
            // After the shell, and on every growth: a bigger room reaches chunks that were never
            // written, and they would otherwise carry whatever the Backshop generates.
            RoomBiomes.apply(backshop, grown);
            return grown;
        }
        return repair(backshop, room);
    }

    /**
     * Puts right whatever is wrong with a room that is already the right size: a shell built out of
     * bedrock before rooms had a colour, or one repainted while nobody was in it. Two reads when
     * there is nothing to do.
     */
    private static RoomRecord repair(ServerLevel backshop, RoomRecord room) {
        if (!room.built()) {
            return room;
        }
        // One corner, the course above it and one door panel are enough: every path that writes a
        // shell writes all of it, so the shell is never half one colour and never half doorless.
        // The course above the corner is what catches a room built before the shell grew a
        // skirting and a ceiling -- its floor corner is already right, so probing only that would
        // leave every existing room a one-value box for ever.
        var doors = RoomGeometry.doors(room.region(), room.builtTier()).entrySet().iterator().next();
        BlockPos corner = RoomGeometry.origin(room.region());
        // And one lamp, which is what catches a room built before the ceiling had any: its floor,
        // its skirting and its doors are all already right, so probing only those would leave every
        // existing room unlit for ever.
        int first = RoomGeometry.lightAxis(room.builtTier())[0];
        BlockPos lamp = corner.offset(first, RoomGeometry.ceilingY(room.builtTier()), first);
        if (!backshop.getBlockState(corner).equals(shellState(room, RoomPart.FLOOR))
            || !backshop.getBlockState(corner.above()).equals(shellState(room, RoomPart.SKIRTING))
            || !backshop.getBlockState(lamp).equals(shellState(room, RoomPart.LIGHT))
            || !backshop.getBlockState(doors.getKey()).equals(shellState(room, doors.getValue()))) {
            paint(backshop, room);
        }
        return room;
    }

    /** The shell at the record's tier and colour, doorways included. */
    private static void paint(ServerLevel backshop, RoomRecord room) {
        sweepAboveCeiling(backshop, room);
        shell(backshop, room, room.builtTier());
    }

    /**
     * Clears shell left standing above this room's ceiling. Only shell: a creative player who built
     * up there before the ceiling came down keeps what they built, sealed above it.
     */
    private static void sweepAboveCeiling(ServerLevel backshop, RoomRecord room) {
        int side = RoomGeometry.footprint(room.builtTier());
        int from = RoomGeometry.height(room.builtTier()) + 2;
        if (side == 0 || from > LEGACY_TOP) {
            return;
        }
        BlockPos origin = RoomGeometry.origin(room.region());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                for (int y = from; y <= LEGACY_TOP; y++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (isShell(backshop.getBlockState(pos))) {
                        backshop.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /** Writes the six faces of the shell, leaving everything inside them alone. */
    private static void shell(ServerLevel backshop, RoomRecord room, int tier) {
        int side = RoomGeometry.footprint(tier);
        int top = RoomGeometry.height(tier) + 1;
        BlockState wall = shellState(room, RoomPart.WALL);
        BlockState floor = shellState(room, RoomPart.FLOOR);
        BlockState skirting = shellState(room, RoomPart.SKIRTING);
        BlockState ceiling = shellState(room, RoomPart.CEILING);
        BlockState light = shellState(room, RoomPart.LIGHT);
        // The fixture grid, as one lookup per axis: a lamp stands where both axes want one, which
        // is what makes it a grid rather than two crossing lines.
        boolean[] lamp = new boolean[side];
        for (int at : RoomGeometry.lightAxis(tier)) {
            lamp[at] = true;
        }
        BlockPos origin = RoomGeometry.origin(room.region());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                boolean edge = x == 0 || z == 0 || x == side - 1 || z == side - 1;
                for (int y = 0; y <= top; y++) {
                    if (!edge && y != 0 && y != top) {
                        continue;
                    }
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    // Four surfaces, not one. The floor is the face a player stands on and the one
                    // the OVERWORLD colour paints differently; the bottom course of the walls is a
                    // skirting, so the room has a line where the two meet; the ceiling is its own
                    // thing so looking up is not looking sideways.
                    BlockState piece = y == 0 ? floor
                        : y == top ? (lamp[x] && lamp[z] ? light : ceiling)
                        : y == 1 ? skirting
                        : wall;
                    // UPDATE_CLIENTS, not UPDATE_ALL: nothing observes the Backshop and neighbour
                    // updates across ten thousand blocks are pure cost.
                    if (!backshop.getBlockState(pos).equals(piece)) {
                        backshop.setBlock(pos, piece, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        // The four doors last, so they are never overwritten by the loop that drew the wall they
        // are set into.
        RoomGeometry.doors(room.region(), tier).forEach((at, part) ->
            backshop.setBlock(at, shellState(room, part), Block.UPDATE_CLIENTS));
    }

    /**
     * Takes down the four side walls and the ceiling of the smaller room, so the bigger one's air
     * reaches them. The floor stays - it is the floor at every tier.
     *
     * <p>Only shell is cleared. Anything else standing where a wall was is a block a creative
     * player put there, and this is not the code that decides it should go.
     */
    private static void clearShell(ServerLevel backshop, RoomRecord room, int tier) {
        int side = RoomGeometry.footprint(tier);
        int top = RoomGeometry.height(tier) + 1;
        BlockPos origin = RoomGeometry.origin(room.region());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                boolean edge = x == 0 || z == 0 || x == side - 1 || z == side - 1;
                for (int y = 1; y <= top; y++) {
                    if (!edge && y != top) {
                        continue;
                    }
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (isShell(backshop.getBlockState(pos))) {
                        backshop.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /** True when the shell is standing in the world, whatever the record believes. */
    public static boolean isBuilt(ServerLevel backshop, RoomRecord room) {
        return room.built()
            && backshop.getBlockState(RoomGeometry.origin(room.region()))
                .is(WBBlocks.ROOM_WALL.get());
    }

    /**
     * The first thing a player has put inside this room, if there is one.
     *
     * <p><b>What a room contains is a build, and a build is never silently voided</b> (SPEC.md §8;
     * OPEN_ISSUES #62). Giving a room back is therefore refused rather than destructive, and this
     * is the question that refusal asks. Walks the interior air only -- the shell is ours and does
     * not count -- and stops at the first block it finds, so an empty room costs a scan and a room
     * with a chest by the door costs almost nothing.
     *
     * <p>Entities too: an item on the floor, an item frame, a chest minecart are not blocks, and
     * the floor {@link #demolish} takes away is the bottom of the world (night 2026-09-11, 1B #10a).
     *
     * @return the name of the first thing found in the room, or empty for one holding nothing
     */
    public static java.util.Optional<net.minecraft.network.chat.Component> firstThingInside(
        ServerLevel backshop, RoomRecord room) {
        int inside = RoomGeometry.interior(room.builtTier());
        int high = RoomGeometry.height(room.builtTier());
        BlockPos low = RoomGeometry.origin(room.region()).offset(1, 1, 1);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = 0; y < high; y++) {
            for (int x = 0; x < inside; x++) {
                for (int z = 0; z < inside; z++) {
                    at.set(low.getX() + x, low.getY() + y, low.getZ() + z);
                    BlockState state = backshop.getBlockState(at);
                    if (!state.isAir() && !isShell(state)) {
                        return java.util.Optional.of(state.getBlock().getName());
                    }
                }
            }
        }
        return backshop.getEntities((net.minecraft.world.entity.Entity) null,
                RoomGeometry.interiorBox(room.region(), room.builtTier()))
            .stream().findFirst().map(net.minecraft.world.entity.Entity::getName);
    }

    /**
     * Takes the shell back down and hands the slot back, so a room opened by accident is not a
     * room owned for ever. OPEN_ISSUES #62.
     *
     * <p>The caller is what decides this is allowed: this only knows how to demolish. It clears
     * the whole footprint -- shell and interior together -- because a room's region is reused the
     * next time that slot is opened and a leftover wall would be inherited by whatever is built
     * there next.
     */
    public static void demolish(ServerLevel backshop, RoomRecord room) {
        if (!room.built()) {
            return;
        }
        int side = RoomGeometry.footprint(room.builtTier());
        int high = RoomGeometry.ceilingY(room.builtTier()) + 1;
        BlockPos origin = RoomGeometry.origin(room.region());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = RoomGeometry.FLOOR_Y; y <= high; y++) {
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    at.set(origin.getX() + x, y, origin.getZ() + z);
                    if (isShell(backshop.getBlockState(at))) {
                        backshop.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}
