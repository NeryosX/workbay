package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.host.HostChecks;
import com.neryos.workbay.init.WBBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * The compatibility boundary as data. SPEC.md §11: this is a tag rather than config precisely so a
 * pack, or a third-party mod with no dependency on us, can change it without waiting for a release.
 */
public class WBBlockTagProvider extends BlockTagsProvider {

    public WBBlockTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries,
        ExistingFileHelper existingFileHelper) {
        super(output, registries, Workbay.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        // Both blocks copy their BlockBehaviour.Properties from Blocks.IRON_BLOCK, which carries
        // requiresCorrectToolForDrops(). Without this tag no tool is ever "correct" for them: they
        // mine at unmodified speed (no pickaxe bonus) and drop nothing at all when broken in
        // survival, silently, because the game never recognises a diamond pickaxe as suitable for
        // an untagged block. Found in play, not in review, then reproduced (workbayDropsWhenMined).
        tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .add(WBBlocks.WORKBAY.get())
            .add(WBBlocks.CONNECTOR.get());
        // Iron block's own tier: at least a stone pickaxe. Copying the properties did not copy
        // tag membership, so this has to be stated again explicitly.
        tag(BlockTags.NEEDS_STONE_TOOL)
            .add(WBBlocks.WORKBAY.get())
            .add(WBBlocks.CONNECTOR.get());

        tag(HostChecks.HOST_DENIED)
            .addTag(BlockTags.BEDS)
            .addTag(BlockTags.DOORS)
            .add(Blocks.TRIAL_SPAWNER)
            .add(Blocks.VAULT)
            // A Workbay inside a Workbay. The recursion check in the mod's constructor catches it
            // first and gives a better message; this is here so the rule survives that check being
            // removed, and so it reads correctly to anyone inspecting the tag.
            .add(WBBlocks.WORKBAY.get());

        // <b>Ships empty, and that is the point.</b> It is the documented escape hatch for a block
        // a heuristic is wrong about (SPEC.md §11), and its one entry used to be the Assay -- the
        // mod's own machine, which the no_machine heuristic rejected correctly for anything else.
        // The Assay is gone; a pack author adds their own block here. It must never carry the
        // Workbay: HOST_ALLOWED beats every check, and that includes the recursion one, which
        // `heuristicsProduceTheRightReasons` caught within a minute of it being written by hand.
        // The three rooms: no block entity, so the no_machine heuristic would refuse them, and
        // a bay is the one place a room block goes (SPEC.md §0).
        tag(HostChecks.HOST_ALLOWED)
            .add(WBBlocks.ROOM.get())
            .add(WBBlocks.WIDE_ROOM.get())
            .add(WBBlocks.VAST_ROOM.get());
    }
}
