package com.neryos.workbay.init;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * What the mod's own blocks expose to the world. SPEC.md §0: <b>energy only, on every face</b>.
 *
 * <p>No item or fluid IO on the Workbay's own faces, ever. The moment a hopper touching the block
 * does something there are two routing models, and every future bug report is ambiguous about which
 * one was in play.
 */
public final class WBCapabilities {
    private WBCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, WBBlockEntities.WORKBAY.get(),
            (workbay, side) -> workbay.energy());
    }
}
