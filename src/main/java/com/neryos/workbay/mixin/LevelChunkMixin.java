package com.neryos.workbay.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.neryos.workbay.client.RemoteMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The other half of {@link ClientChunkCacheMixin}: what happens when the real chunk <em>is</em> there.
 *
 * <p>{@code ClientChunkCacheMixin} substitutes a whole synthetic chunk, and can only do that when
 * vanilla had nothing to give -- a chunk it hands over is the player's own world and swapping it
 * for a chunk of air would put a hole in the terrain they are standing on. So its rule is that a
 * real chunk always wins, and its javadoc calls the cost "a collision nobody will hit".
 *
 * <p><b>Everybody hits it.</b> A network's bay column is allocated from index zero at
 * {@code COLUMN_SPACING} 8, so the first network anybody makes lives in chunk (0, 0) -- and a
 * player who builds within render distance of the world origin has chunk (0, 0) loaded in their own
 * dimension. Then {@code getChunk(0, 0)} returns their overworld, the synthetic chunk is never
 * consulted, and Mekanism's menu throws {@code Client could not locate tile at BlockPos{x=8, y=26,
 * z=8}} in the client's face. Measured, with the arming and the lookup logged in one run:
 * {@code apply} ran, the tag was not empty, the packet did not overtake it -- and
 * {@code getChunk(0,0)} answered {@code REAL WINS}. OPEN_ISSUES #80.
 *
 * <p>So the answer is given <b>per position</b> instead, which is the one thing a chunk-level hook
 * cannot do. The real chunk keeps every block state, every light value and every other block entity
 * in it; the single position a hosted machine's screen is open on, which is air in the player's own
 * dimension, answers with the machine. Nothing is added to the chunk's own map, so nothing ticks,
 * renders or saves it.
 *
 * <p>Client only -- see {@code workbay.mixins.json}'s {@code client} list. {@link LevelChunk} exists
 * on a dedicated server and {@link RemoteMachines} does not.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @ModifyReturnValue(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)"
        + "Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("RETURN"))
    private BlockEntity workbay$remoteMachine(BlockEntity original, BlockPos pos) {
        return original != null ? original : RemoteMachines.machineAt(pos);
    }
}
