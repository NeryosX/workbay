package com.neryos.workbay.content.room;

import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.menu.RoomDoorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A room's shell, and its way out. SPEC.md §8.
 *
 * <p><b>Not bedrock.</b> Bedrock was the placeholder that said "you cannot break this" and nothing
 * else; six faces of it around a room somebody lives in is a quarry, not a room. This says the
 * same thing, carries a colour while it says it, and is the door.
 *
 * <p><b>The doors are the way out, and only the doors.</b> Three shapes were tried. A single Exit
 * block on the entry pad was wrong twice over - it could be lost, and in a 46-block room it had to
 * be walked back to. Then <em>every</em> block of the shell opened the screen, which fixed both and
 * bought a new fault: a room is a place you build in, and a wall that opens a menu every time you
 * right-click near it is a wall you cannot work against. So the {@code DOOR_*} parts, and nothing
 * else. Four doors, one dead centre on each of the four walls, none of them more than half a room
 * away and none of them breakable: the reason the Exit block was replaced is answered by there
 * being four, not by there being ten thousand. The owner's call, made standing in one.
 *
 * <p><b>It refuses to be destroyed.</b> -1 hardness stops a pick and not a creative click, and a
 * hole in a room's shell is a hole into the void with a player beside it.
 */
public class RoomWallBlock extends Block {

    public static final EnumProperty<RoomColour> COLOUR =
        EnumProperty.create("colour", RoomColour.class);

    /**
     * Which piece of the shell this is. Cosmetic only - <b>every</b> block of the shell opens the
     * same screen, and the door is where a player looks for one rather than where the only one is.
     */
    public static final EnumProperty<RoomPart> PART = EnumProperty.create("part", RoomPart.class);

    public RoomWallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(COLOUR, RoomColour.DEFAULT)
            .setValue(PART, RoomPart.WALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COLOUR, PART);
    }

    /**
     * <b>An Overworld room has no ceiling to look at.</b> SPEC.md §8's last room piece: the walls
     * and floor already take a sky blue and a grass green, and what was left was the lid.
     *
     * <p>The ceiling layer - the coffers and the lamps set into them - is not drawn under
     * {@link RoomColour#OVERWORLD}, so what is overhead is the sky the client was going to draw
     * anyway: sun, moon, stars and clouds, on the overworld's own clock. No renderer and no
     * panorama, which is what this was expected to cost; the game already draws a sky and the only
     * thing between the player and it was one opaque block.
     *
     * <p><b>The block is still there.</b> {@code INVISIBLE} is what the renderer is told, not what
     * the world is: it is solid, it collides, it occludes, it refuses to be broken, and the
     * Backshop has {@code has_skylight: false} whatever is drawn over it - so nothing gains a
     * single tick of daylight, a solar panel racked under it least of all.
     *
     * <p>The lamps go with it. A fixture hanging in the sky is the one thing that would say the
     * sky is a picture, and the room does not go dark without them: the dimension's ambient light
     * is 1.0 and the shell emits block light on its own.
     */
    private static boolean openToTheSky(BlockState state) {
        RoomPart part = state.getValue(PART);
        return state.getValue(COLOUR) == RoomColour.OVERWORLD
            && (part == RoomPart.CEILING || part == RoomPart.LIGHT);
    }

    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return openToTheSky(state)
            ? net.minecraft.world.level.block.RenderShape.INVISIBLE
            : net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
        Player player, BlockHitResult hit) {
        // A door opens the way out; wall, skirting, ceiling and floor are ordinary blocks that do
        // nothing. PASS, not CONSUME, so the click carries on to whatever the player was holding --
        // building against the shell has to work exactly as building against stone does.
        if (!state.getValue(PART).isDoor()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer server) {
            WorkbaySounds.door(level, pos);
            RoomDoorMenu.open(server, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
        boolean willHarvest, FluidState fluid) {
        return false;
    }
}
