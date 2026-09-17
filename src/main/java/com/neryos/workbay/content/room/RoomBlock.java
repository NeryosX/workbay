package com.neryos.workbay.content.room;

import net.minecraft.world.level.block.Block;

/**
 * A room, as the block a bay hosts. SPEC.md §0 and §8.
 *
 * <p>The block is the room's <em>presence</em> in a bay and nothing more: it has no block entity,
 * no capabilities and no faces, so a bay holding one has no channels of its own -- the room's
 * machines are reached through Connectors placed inside it. Which room it is lives on the bay's
 * record ({@code WorkbayRecord.Bay#room}), never on the block; the block is racked and ejected by
 * {@link com.neryos.workbay.world.BayHosting} like any machine and {@link RoomItem} is what carries
 * the room between bays. It cannot be placed anywhere else: {@link RoomItem#useOn} refuses.
 *
 * <p>Three sizes are three blocks of one class, so the bay's {@code hosted} id says the size.
 */
public class RoomBlock extends Block {

    private final int tier;

    public RoomBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    /** Which of the three sizes: 1, 2 or 3, the index into {@code RoomGeometry}'s table. */
    public int tier() {
        return tier;
    }

    /**
     * How far the block's cube sits in from the full block: 4, 2 and 0 pixels a side, so the three
     * sizes are an 8, a 12 and a 16 pixel cube standing on the block's floor. <b>Size is the
     * size</b> - the owner's call over three pictures with more or fewer windows on them: a
     * small room is a small block, in the hand, in a slot and in a bay, and one texture serves
     * all three. {@code WBBlockStateProvider} draws the same numbers.
     */
    public static int inset(int tier) {
        return Math.max(0, 6 - tier * 2);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
        net.minecraft.world.level.block.state.BlockState state,
        net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos,
        net.minecraft.world.phys.shapes.CollisionContext context) {
        int in = inset(tier);
        return Block.box(in, 0, in, 16 - in, 16 - 2 * in, 16 - in);
    }
}
