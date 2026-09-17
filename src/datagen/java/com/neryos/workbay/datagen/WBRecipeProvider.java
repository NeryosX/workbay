package com.neryos.workbay.datagen;

import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.CompletableFuture;

/**
 * SPEC.md §3. All quantities are provisional and all of it is pack-editable JSON, which is the
 * point: a pack rebalancing Workbay never needs us.
 */
public class WBRecipeProvider extends RecipeProvider {

    public WBRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        // Two vanilla materials, no ender pearl, two out per craft. SPEC.md §3: the entry is
        // priced to be walked past, not saved up for - the ladder above it is where the cost is,
        // and the ladder above it is what the upgrades cost.
        // The old recipe was a third of an ender pearl per Shopsteel and the first Workbay wanted
        // twenty-three of them, which is twenty minutes of killing endermen before the dial the
        // mod is actually about.
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, WBItems.SHOPSTEEL.get(), 2)
            .requires(Items.IRON_INGOT)
            .requires(Items.AMETHYST_SHARD)
            .unlockedBy("has_amethyst", has(Items.AMETHYST_SHARD))
            // OPEN_ISSUES #118: the chain's visible start. Every player smelts iron; the recipe
            // book then shows Shopsteel wanting an amethyst shard, which is what points at a
            // geode. Criteria on a recipe are OR-ed.
            .unlockedBy("has_iron", has(Items.IRON_INGOT))
            .save(output);

        // Housing is the upgrade tier's material and nothing else's. It is deliberately the
        // expensive intermediate: SPEC.md §0 prices the upgrades, not the entry, so the obsidian
        // and the ender eye sit above the first Workbay (which costs a pearl) rather than in front of it.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, WBItems.HOUSING.get())
            .pattern("SOS")
            .pattern("OEO")
            .pattern("SOS")
            .define('S', WBItems.SHOPSTEEL.get())
            .define('O', Blocks.OBSIDIAN)
            .define('E', Items.ENDER_EYE)
            .unlockedBy("has_shopsteel", has(WBItems.SHOPSTEEL.get()))
            .save(output);

        // Glass, Shopsteel and one ender pearl: four iron, four amethyst and the pearl. No Housing,
        // because Housing is what the ladder above this block is made of. A pearl, not an eye
        // (2026-09-14): the survival round found the eye put the box a blaze-rod trip after the
        // first machines it exists to hold; a pearl is an enderman on the first night, so the
        // Workbay arrives with the first furnace. The eye stays on Housing, where the ladder is.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, WBBlocks.WORKBAY.get())
            .pattern("GAG")
            .pattern("AEA")
            .pattern("GAG")
            .define('G', Blocks.GLASS)
            .define('A', WBItems.SHOPSTEEL.get())
            .define('E', Items.ENDER_PEARL)
            .unlockedBy("has_shopsteel", has(WBItems.SHOPSTEEL.get()))
            .save(output);

        // The Connector is deliberately cheap: a link the player will not make because it costs too
        // much is a link they wire with pipes instead, and then the mod has not replaced anything.
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, WBBlocks.CONNECTOR.get(), 4)
            .pattern("SRS")
            .define('S', WBItems.SHOPSTEEL.get())
            .define('R', Items.REDSTONE)
            .unlockedBy("has_shopsteel", has(WBItems.SHOPSTEEL.get()))
            .save(output);

        // The mod's only machine, and the first thing a player wants after the Workbay itself -
        // The upgrade ladder.
        // *rises* along each line, and a recipe costs the same the tenth time as the first. So the
        upgrade(output, WBItems.EXPANSION_PLATE.get(), Items.IRON_INGOT);
        upgrade(output, WBItems.RESONATOR.get(), Items.ENDER_EYE);
        upgrade(output, WBItems.IMPELLER.get(), Items.BREEZE_ROD);
        // The three rooms, SPEC.md §3: bigger costs more, and all three are survival finds.
        // A diamond for the small one, an echo shard (an Ancient City trip) for the wide one,
        // and a Nether Star for the vast one -- the mod's rarest gate on its rarest capability,
        // a thirteen-block hall for a multiblock built by hand.
        upgrade(output, WBBlocks.ROOM.get().asItem(), Items.DIAMOND);
        upgrade(output, WBBlocks.WIDE_ROOM.get().asItem(), Items.ECHO_SHARD);
        upgrade(output, WBBlocks.VAST_ROOM.get().asItem(), Items.NETHER_STAR);
        // SPEC.md §3: force loading carries the hardest gate in the mod, and a Heart of the Sea is
        // the vanilla one-time find that a Broken Spawner stands in for in a modded pack.
        upgrade(output, WBItems.ANCHOR.get(), Items.HEART_OF_THE_SEA);
    }

    private void upgrade(RecipeOutput output, net.minecraft.world.item.Item result,
        net.minecraft.world.item.Item core) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, result)
            .pattern(" S ")
            .pattern("SCS")
            .pattern(" H ")
            .define('S', WBItems.SHOPSTEEL.get())
            .define('H', WBItems.HOUSING.get())
            .define('C', core)
            .unlockedBy("has_housing", has(WBItems.HOUSING.get()))
            .save(output);
    }
}
