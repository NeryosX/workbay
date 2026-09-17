package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.menu.WorkbayMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class WBMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, Workbay.MOD_ID);

    /**
     * {@code IMenuTypeExtension.create} rather than {@code MenuType::new}: it is what lets the whole
     * initial {@link com.neryos.workbay.menu.WorkbaySnapshot} ride the menu-open buffer, so the
     * screen is correct on frame 1 instead of flashing defaults (SPEC.md §4).
     */
    public static final DeferredHolder<MenuType<?>, MenuType<WorkbayMenu>> WORKBAY =
        MENUS.register("workbay", () -> IMenuTypeExtension.create(WorkbayMenu::new));

    /**
     * Bay View (SPEC.md §5). Its own menu type because it is the only screen in the mod with real
     * slots: {@link WorkbayMenu} deliberately has none, and bolting a grid onto it would give every
     * other screen a player inventory it does not want.
     */

    /**
     * The screen a room's wall opens. Its own type because it is built from a player and a block
     * rather than from a Workbay block entity: a room has to work with no Workbay in the world.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<com.neryos.workbay.menu.RoomDoorMenu>>
        ROOM_DOOR = MENUS.register("room_door",
            () -> IMenuTypeExtension.create(com.neryos.workbay.menu.RoomDoorMenu::new));

    /**
     * The rename panel a placed Connector opens (OPEN_ISSUES #77). Its own type for the room
     * door's reason: it is built from a player and a block, and the Workbay it belongs to is
     * usually in another dimension.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<com.neryos.workbay.menu.ConnectorMenu>>
        CONNECTOR = MENUS.register("connector",
            () -> IMenuTypeExtension.create(com.neryos.workbay.menu.ConnectorMenu::new));

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
