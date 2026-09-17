package com.neryos.workbay.content.port;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

/**
 * One face of a bay's interior. SPEC.md §2.
 *
 * <p>It is what makes a bay a room rather than a hole: a machine with air neighbours has nothing to
 * push into, never sees redstone, and corrupts if it writes a bounding block. A Port gives the
 * machine something on every side.
 *
 * <p>Deliberately has <b>no block entity</b>. A Port's identity is its position - the bay column
 * layout in {@link com.neryos.workbay.world.BayGeometry} is pure arithmetic, so which Workbay and
 * which bay a Port belongs to is derivable and never needs storing, syncing or migrating.
 *
 * <p>Not craftable, not obtainable and not breakable. A bay with a missing wall is a state nothing
 * can recover from, so there is no way to reach it.
 */
public class PortBlock extends Block {
    public static final MapCodec<PortBlock> CODEC = simpleCodec(PortBlock::new);

    public PortBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return ItemStack.EMPTY;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 0.0F;
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    /**
     * A Port is the machine's door as well as its socket.
     *
     * <p>Six Ports seal the hosted block on every face, so a player who has stepped into the bay
     * (SPEC.md §5) is standing next to a machine they physically cannot click. Rather than move a
     * Port, take one away, or change geometry SPEC.md §8 has baked into saved worlds, the Port
     * forwards the click to the block behind it - so a right-click in a bay is an ordinary
     * right-click on the machine, and every mod's own screen opens with no per-mod code.
     *
     * <p><b>This is the only place the machine's own GUI can be reached from.</b> Opening it
     * remotely from the overworld does not work and is not a matter of tuning: the client resolves
     * the menu's block entity out of the one level it has, finds nothing, and disconnects. Standing
     * here, the client <em>is</em> in the Backshop with the chunk loaded, so the same lookup
     * succeeds. See OPEN_ISSUES "Facts worth not rediscovering".
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level,
        BlockPos pos, Player player, net.minecraft.world.phys.BlockHitResult hit) {
        BlockState hosted = behind(level, pos);
        return hosted == null ? net.minecraft.world.InteractionResult.PASS
            : hosted.useWithoutItem(level, player, onMachine(level, pos, hit));
    }

    /** The same door, for a player holding something - a wrench, a configurator, an upgrade. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
        Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
        net.minecraft.world.phys.BlockHitResult hit) {
        BlockState hosted = behind(level, pos);
        return hosted == null ? net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            : hosted.useItemOn(stack, level, player, hand, onMachine(level, pos, hit));
    }

    private static BlockState behind(Level level, BlockPos pos) {
        BlockPos machine = com.neryos.workbay.world.BayGeometry.machineBehind(pos);
        if (machine == null) {
            return null;
        }
        BlockState hosted = level.getBlockState(machine);
        return hosted.isAir() ? null : hosted;
    }

    /**
     * The click, moved onto the machine. The face is the one this Port covers, worked out from
     * where the machine actually is rather than from the Port's own hit face - the player is
     * clicking the Port from inside the bay, so its hit face points back at them.
     */
    private static net.minecraft.world.phys.BlockHitResult onMachine(Level level, BlockPos pos,
        net.minecraft.world.phys.BlockHitResult hit) {
        BlockPos machine = com.neryos.workbay.world.BayGeometry.machineBehind(pos);
        // Signum, not the delta: a grown bay stands its Ports two blocks out and
        // Direction.fromDelta answers null for anything longer than one, which silently fell back
        // to the player's own hit face and handed the machine a click on the wrong side.
        net.minecraft.core.Direction face = net.minecraft.core.Direction.fromDelta(
            Integer.signum(pos.getX() - machine.getX()),
            Integer.signum(pos.getY() - machine.getY()),
            Integer.signum(pos.getZ() - machine.getZ()));
        return new net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(machine),
            face == null ? hit.getDirection() : face, machine, false);
    }
}
