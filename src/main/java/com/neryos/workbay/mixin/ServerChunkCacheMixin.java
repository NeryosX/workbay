package com.neryos.workbay.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.neryos.workbay.remote.RemoteScreens;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The question mods ask before {@link LevelMixin}'s: is that chunk even loaded?
 *
 * <p>Mekanism's {@code WorldUtils.isBlockLoaded} gates every tile lookup on it, so without this the
 * block-entity answer is never reached. Also a fact about a block and not about the player.
 *
 * <p>True only for the one chunk holding a machine whose own screen is open, and only while it is
 * open. The honest cost: for that chunk, in the player's own dimension, "loaded" is answered yes
 * when the ordinary answer would be no - so code that asks and then fetches gets whatever is really
 * there, which is air.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @ModifyReturnValue(method = "hasChunk", at = @At("RETURN"))
    private boolean workbay$remoteMachineChunk(boolean original, int chunkX, int chunkZ) {
        return original || RemoteScreens.chunkHasOpenMachine(chunkX, chunkZ);
    }
}
