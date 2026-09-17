package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.world.RoomBiomes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * What a room may be. SPEC.md §8.
 *
 * <p><b>Six, not the whole registry.</b> A picker over every biome in a modded game is a list
 * nobody scrolls, and most of it answers nothing a machine asked. What a machine asks is
 * temperature, rainfall and whether it is cold enough to snow, so the six here are the corners of
 * that: frozen, cold and wet, temperate, temperate and humid, hot and dry, hot and wet. Left out on
 * purpose are the ones that differ only in what grows on them (birch forest, meadow, cherry grove),
 * the ones whose point is their terrain (caves, oceans, peaks), and the ones whose point is where
 * they are (nether, end) -- and every modded biome, which a pack adds by adding to this tag.
 */
public class WBBiomeTagProvider extends TagsProvider<Biome> {

    public WBBiomeTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
        ExistingFileHelper existingFileHelper) {
        super(output, Registries.BIOME, registries, Workbay.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        // Order is the order the room screen's button steps through, and plains is first because it
        // is the default a room is born with.
        tag(RoomBiomes.ROOM_BIOMES)
            .add(Biomes.PLAINS)
            .add(Biomes.SNOWY_PLAINS)
            .add(Biomes.TAIGA)
            .add(Biomes.SWAMP)
            .add(Biomes.JUNGLE)
            .add(Biomes.DESERT);
    }
}
