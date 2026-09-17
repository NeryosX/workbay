package com.neryos.workbay.client;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.client.screen.WorkbayScreen;
import com.neryos.workbay.init.WBMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** The client half of the mod. Nothing here may be reachable from the server side. */
@EventBusSubscriber(modid = Workbay.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WorkbayClient {
    private WorkbayClient() {}

    /** The eight per-link pips on the front of a Workbay. {@link WorkbayPips} says why they are
     *  drawn rather than put in the blockstate. */
    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
            com.neryos.workbay.init.WBBlockEntities.WORKBAY.get(), WorkbayPips::new);
        // A room on the ground draws as any item does; only its dying is different.
        event.registerEntityRenderer(com.neryos.workbay.init.WBEntities.ROOM_ITEM.get(),
            net.minecraft.client.renderer.entity.ItemEntityRenderer::new);
    }

    /**
     * A room's shell is <b>one greyscale texture multiplied by the room's colour</b>, not ten
     * textures. Ten PNGs would be ten files to redraw the day the wall art changes, and a
     * player-chosen eleventh colour would be a new PNG rather than a new line in
     * {@link com.neryos.workbay.content.room.RoomColour}.
     */
    @SubscribeEvent
    static void blockColours(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> tint == 0
            ? state.getValue(com.neryos.workbay.content.room.RoomWallBlock.COLOUR)
                .tint(state.getValue(com.neryos.workbay.content.room.RoomWallBlock.PART).isFloor())
            : -1, com.neryos.workbay.init.WBBlocks.ROOM_WALL.get());
    }

    @SubscribeEvent
    static void screens(RegisterMenuScreensEvent event) {
        event.register(WBMenus.WORKBAY.get(), WorkbayScreen::new);
        event.register(WBMenus.ROOM_DOOR.get(),
            com.neryos.workbay.client.screen.RoomDoorScreen::new);
        event.register(WBMenus.CONNECTOR.get(),
            com.neryos.workbay.client.screen.ConnectorScreen::new);
    }

    /**
     * <b>A room is not the bottom of a cave, and the fog said it was.</b>
     *
     * <p>Minecraft darkens everything distant towards black as the camera approaches the bottom of
     * the world: {@code FogRenderer} scales the fog colour by {@code (y - minBuildHeight) * 0.03125}
     * and then squares it. A room's floor <em>is</em> {@code minBuildHeight} - {@code FLOOR_Y} is 0
     * and the Backshop's {@code min_y} is 0, and both are one-way doors - so a player standing in
     * one is at the very bottom of the world and every fogged thing renders black.
     *
     * <p>Which is invisible in a sealed room and unmissable in an open one: the sky an
     * {@link com.neryos.workbay.content.room.RoomColour#OVERWORLD} room shows is drawn <b>past</b>
     * the fog for the clouds and not for the sky dome, so a blue midday sky came with a handful of
     * <b>black clouds</b> hanging in it. Found by standing in the room and looking up, which is the
     * only place it can be found. The scale is vanilla's and reads the overworld's own
     * {@code isFlat}, so there is nothing to configure our way out of.
     *
     * <p>The fog becomes the sky's colour instead, which is what the overworld's fog is at that
     * distance anyway. Whole dimension rather than only an open room: nothing sealed can tell, and
     * a check per frame for which room the camera is in would cost more than the effect is worth.
     */
    @EventBusSubscriber(modid = Workbay.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class Sky {
        private Sky() {}

        @SubscribeEvent
        static void fogColour(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFogColor event) {
            var camera = event.getCamera();
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null
                || !level.dimension().equals(com.neryos.workbay.world.WorkbayDimensions.BACKSHOP)) {
                return;
            }
            net.minecraft.world.phys.Vec3 sky =
                level.getSkyColor(camera.getPosition(), (float) event.getPartialTick());
            event.setRed((float) sky.x);
            event.setGreen((float) sky.y);
            event.setBlue((float) sky.z);
        }
    }

    /**
     * OPEN_ISSUES #38. Every refusal in the mod is an action bar message, and the HUD draws the
     * action bar <b>before</b> the screen - so a rejection raised by a click on the Workbay screen
     * is painted and then covered by the panel that caused it. What the player sees is a button
     * that did nothing, which SPEC.md §6 calls the worst failure a screen can have.
     *
     * <p>Caught here rather than at the twenty-odd {@code displayClientMessage} call sites: the
     * hole is that the action bar is invisible behind a screen, not that any one refusal forgot to
     * say so, and a list of call sites closes the list without closing the hole. Anything the
     * server puts on the action bar while a Workbay screen is open was caused by that screen, so
     * the screen draws it - including refusals nobody has written yet.
     *
     * <p>Not cancelled: the message still goes to the HUD, so it is there when the screen closes.
     */
    @EventBusSubscriber(modid = Workbay.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class Notices {
        private Notices() {}

        @SubscribeEvent
        static void overlayMessage(net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System event) {
            if (!event.isOverlay()) {
                return;
            }
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof WorkbayScreen screen) {
                screen.notice(event.getMessage());
            }
        }
    }
}
