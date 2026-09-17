package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.content.room.RoomItemEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WBEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(Registries.ENTITY_TYPE, Workbay.MOD_ID);

    /** A room on the ground: vanilla's item entity, minus the two ways it can die. */
    public static final DeferredHolder<EntityType<?>, EntityType<RoomItemEntity>> ROOM_ITEM =
        ENTITIES.register("room_item", () -> EntityType.Builder
            .<RoomItemEntity>of(RoomItemEntity::new, MobCategory.MISC)
            .sized(0.25F, 0.25F).eyeHeight(0.2125F)
            .clientTrackingRange(6).updateInterval(20)
            .build(Workbay.rl("room_item").toString()));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
