package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.content.connector.ConnectorBlockEntity;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WBBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Workbay.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WorkbayBlockEntity>> WORKBAY =
        BLOCK_ENTITIES.register("workbay", () ->
            BlockEntityType.Builder.of(WorkbayBlockEntity::new, WBBlocks.WORKBAY.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectorBlockEntity>> CONNECTOR =
        BLOCK_ENTITIES.register("connector", () ->
            BlockEntityType.Builder.of(ConnectorBlockEntity::new, WBBlocks.CONNECTOR.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
