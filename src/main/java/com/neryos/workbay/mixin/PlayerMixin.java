package com.neryos.workbay.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.neryos.workbay.remote.RemoteScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a remote screen open. Vanilla closes any menu whose {@code stillValid} fails, and that
 * bottoms out here in a distance check the player cannot pass from another dimension.
 *
 * <p>The same shape EnderIO's Enderface uses (Unlicense, copied freely): widen the answer, never
 * replace it. {@code original ||} means a block that was already reachable stays reachable by
 * vanilla's own rule, so nothing here can make interaction stricter or subtly different.
 *
 * <p>Widened for {@code stillValid}'s question only. {@code AbstractContainerMenu#stillValid}
 * asks with a boost of 4.0; use and dig ask with 1.0, and widening those made the block at the
 * machine's coordinates in the player's <em>own</em> dimension usable and breakable from anywhere
 * while the screen was open (night audit 1A, finding 12).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    @ModifyReturnValue(method = "canInteractWithBlock", at = @At("RETURN"))
    private boolean workbay$allowRemoteMachine(boolean original, BlockPos pos, double distanceBoost) {
        return original
            || (distanceBoost >= 4.0 && RemoteScreens.isOpenAt((Player) (Object) this, pos));
    }
}
