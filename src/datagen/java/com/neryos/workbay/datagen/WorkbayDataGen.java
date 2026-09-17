package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.List;

/**
 * Datagen entry point. Its own source set and class but the same mod id as {@link Workbay},
 * which is how EnderIO does it - these classes never ship, only their output under
 * src/generated/resources does.
 */
@Mod(Workbay.MOD_ID)
public class WorkbayDataGen {

    public WorkbayDataGen(IEventBus modEventBus) {
        modEventBus.addListener(this::onGatherData);
    }

    private void onGatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        generator.addProvider(event.includeClient(), new WBBlockStateProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new WBItemModelProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new WBLanguageProvider(output));
        generator.addProvider(event.includeClient(), new WBSoundProvider(output, existingFileHelper));
        generator.addProvider(event.includeServer(), new WBDatapackProvider(output, event.getLookupProvider()));
        generator.addProvider(event.includeServer(),
            new WBBlockTagProvider(output, event.getLookupProvider(), existingFileHelper));
        generator.addProvider(event.includeServer(),
            new WBBiomeTagProvider(output, event.getLookupProvider(), existingFileHelper));
        generator.addProvider(event.includeServer(), new WBRecipeProvider(output, event.getLookupProvider()));
        generator.addProvider(event.includeServer(), new LootTableProvider(output, java.util.Set.of(),
            List.of(new LootTableProvider.SubProviderEntry(WBLootTableProvider::new, LootContextParamSets.BLOCK)),
            event.getLookupProvider()));
    }
}
