package com.neryos.workbay;

import com.neryos.workbay.config.WorkbayConfig;
import com.neryos.workbay.host.HostChecks;
import com.neryos.workbay.host.HostResult;
import com.neryos.workbay.init.WBAttachments;
import com.neryos.workbay.init.WBBlockEntities;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBCapabilities;
import com.neryos.workbay.init.WBCreativeTabs;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.init.WBItems;
import com.neryos.workbay.init.WBMenus;
import com.neryos.workbay.world.WorkbayTickets;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;

@Mod(Workbay.MOD_ID)
public class Workbay {
    public static final String MOD_ID = "workbay";

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public Workbay(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, WorkbayConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, WorkbayConfig.CLIENT_SPEC);

        WBBlocks.register(modEventBus);
        WBBlockEntities.register(modEventBus);
        com.neryos.workbay.init.WBEntities.register(modEventBus);
        WBItems.register(modEventBus);
        WBDataComponents.register(modEventBus);
        WBCreativeTabs.register(modEventBus);
        WBMenus.register(modEventBus);
        com.neryos.workbay.init.WBSounds.register(modEventBus);
        WBAttachments.register(modEventBus);
        WorkbayTickets.register(modEventBus);
        modEventBus.addListener(WBCapabilities::register);

        // A Workbay in a Workbay is rejected here rather than by the denylist, so the player is
        // told what they actually did instead of that the pack forbade it. SPEC.md §14.
        HostChecks.register((state, stack) -> state.is(WBBlocks.WORKBAY.get())
            ? HostResult.deny("recursion")
            : HostResult.pass());
    }
}
