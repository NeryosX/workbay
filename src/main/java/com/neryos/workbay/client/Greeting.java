package com.neryos.workbay.client;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.config.WorkbayConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One line in chat, the first time a player joins a world with Workbay: thanks, a word that the
 * mod is new, and the issue tracker. Then never again.
 *
 * <p><b>Quiet is the state this mod lives in.</b> Modpack authors drop mods that talk in chat, and
 * a pack is where most players will meet this one, so the line is said once and the server's
 * {@code greeting} knob silences it outright.
 *
 * <p><b>Remembered by the client, not the server.</b> "Once ever" is a fact about the player's
 * game, not about a world: a server-side record would greet again in every new singleplayer world.
 * So the client keeps a marker file in its config folder, and the knob it obeys is the server's,
 * which NeoForge syncs at login. A world whose server config never arrived (a server without
 * Workbay) is asked nothing and greets nobody.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class Greeting {
    private Greeting() {}

    static final String TRACKER = "https://github.com/neryosx/workbay/issues";

    @SubscribeEvent
    static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!WorkbayConfig.SERVER_SPEC.isLoaded() || !WorkbayConfig.SERVER.greeting.get()) {
            return;
        }
        Path marker = FMLPaths.CONFIGDIR.get().resolve("workbay-greeted.txt");
        if (Files.exists(marker)) {
            return;
        }
        try {
            Files.writeString(marker, "Workbay has said its one hello to this game. Delete this "
                + "file to hear it again.\n");
        } catch (IOException e) {
            // A line that cannot be remembered is not said: said on every join, it is the noise
            // this class exists to avoid.
            return;
        }
        Component link = WorkbayLang.message("greeting.link").withStyle(style -> style
            .withColor(ChatFormatting.AQUA).withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, TRACKER))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.literal(TRACKER))));
        Minecraft.getInstance().gui.getChat().addMessage(WorkbayLang.message("greeting", link));
    }
}
