package com.neryos.workbay.datagen;

import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.Set;
import java.util.stream.Collectors;

/** Without a loot table a block simply vanishes when broken. Every droppable block needs one. */
public class WBLootTableProvider extends BlockLootSubProvider {

    public WBLootTableProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        // The dropped item has to carry its binding, or breaking a Workbay orphans its bays and
        // leaves the player with no way back to machines that are still running. SPEC.md §14.
        Block workbay = WBBlocks.WORKBAY.get();
        add(workbay, LootTable.lootTable().withPool(LootPool.lootPool()
            .setRolls(ConstantValue.exactly(1.0F))
            .add(applyExplosionCondition(workbay, LootItem.lootTableItem(workbay)
                .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                    .include(WBDataComponents.BINDING.get()))))));


        // A Connector keeps its pairing when broken, so re-placing it restores the link rather
        // than leaving the player to re-pair a block they never unpaired.
        Block connector = WBBlocks.CONNECTOR.get();
        add(connector, LootTable.lootTable().withPool(LootPool.lootPool()
            .setRolls(ConstantValue.exactly(1.0F))
            .add(applyExplosionCondition(connector, LootItem.lootTableItem(connector)
                .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                    .include(WBDataComponents.PAIRING.get()))))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return WBBlocks.BLOCKS.getEntries().stream().map(e -> (Block) e.value()).collect(Collectors.toList());
    }
}
