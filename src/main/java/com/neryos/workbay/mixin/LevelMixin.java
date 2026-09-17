package com.neryos.workbay.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.neryos.workbay.remote.RemoteScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Answers a mod that asks the wrong level where a hosted machine is.
 *
 * <p>A modded GUI button sends a packet whose handler resolves its machine as
 * {@code getTileEntity(player.level(), pos)} - Mekanism's {@code PacketGuiInteract} does, and it is
 * the shape nearly every mod uses. The player's level is not the bay's, so the lookup misses and
 * the click is a silent no-op.
 *
 * <p><b>The alternative was to make {@code player.level()} lie</b>, which would have been shorter
 * and is what a coremod would do. It is rejected on purpose: it tells every mod, for the duration
 * of a packet, that the player is standing somewhere they are not, and a mod that hands out
 * anything for being in a place would hand it out wrongly. This says one true thing to one caller
 * instead - the machine is there, because it is - and leaves everything about the player alone.
 *
 * <p>Consulted only when the real lookup found nothing, so a real block entity always wins.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @ModifyReturnValue(method = "getBlockEntity", at = @At("RETURN"))
    private BlockEntity workbay$remoteMachine(BlockEntity original, BlockPos pos) {
        return original != null ? original : RemoteScreens.machineAt((Level) (Object) this, pos);
    }
}
