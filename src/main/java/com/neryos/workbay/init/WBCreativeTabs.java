package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.WorkbayLang;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WBCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Workbay.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
        CREATIVE_MODE_TABS.register("workbay", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + Workbay.MOD_ID))
            .icon(() -> new ItemStack(WBBlocks.WORKBAY.get()))
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((parameters, output) -> {
                addAll(WBBlocks.ITEMS, output);
                addAll(WBItems.ITEMS, output);
            })
            .build());

    public static void register(IEventBus bus) {
        CREATIVE_MODE_TABS.register(bus);
    }

    private static void addAll(DeferredRegister<Item> items, CreativeModeTab.Output output) {
        for (var entry : items.getEntries()) {
            output.accept(entry.get());
        }
    }
}
