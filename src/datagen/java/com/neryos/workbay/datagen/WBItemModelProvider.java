package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBItems;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class WBItemModelProvider extends ItemModelProvider {

    public WBItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, Workbay.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        blockItem(WBBlocks.WORKBAY.getId().getPath());
        blockItem(WBBlocks.ROOM.getId().getPath());
        blockItem(WBBlocks.WIDE_ROOM.getId().getPath());
        blockItem(WBBlocks.VAST_ROOM.getId().getPath());

        // SPEC.md §7: Shopsteel reads as a material rather than a tool, Plates are flat and
        // stacked, Frames are open squares. All six sprites are generated to that rule.
        flatItem(WBItems.SHOPSTEEL.getId().getPath());
        flatItem(WBItems.HOUSING.getId().getPath());
        flatItem(WBItems.EXPANSION_PLATE.getId().getPath());
        flatItem(WBItems.ANCHOR.getId().getPath());
        flatItem(WBItems.RESONATOR.getId().getPath());
        flatItem(WBItems.IMPELLER.getId().getPath());
        // <b>Flat, not the block model.</b> A Connector is an 8x8x2 slab against one wall of its
        // cube, so as a block item it drew a small plate hanging in the lower right of an empty
        // box -- in the inventory, in JEI and on the Pair button. Its own 16x16 sprite fills the
        // icon and fixes all three.
        flatItem(WBBlocks.CONNECTOR.getId().getPath());
    }

    private void blockItem(String name) {
        withExistingParent(name, ResourceLocation.fromNamespaceAndPath(Workbay.MOD_ID, "block/" + name));
    }

    private void flatItem(String name) {
        withExistingParent(name, ResourceLocation.withDefaultNamespace("item/generated"))
            .texture("layer0", ResourceLocation.fromNamespaceAndPath(Workbay.MOD_ID, "item/" + name));
    }
}
