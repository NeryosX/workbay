package com.neryos.workbay.network;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;
import java.util.UUID;

/**
 * One button press, client to server. SPEC.md §4.
 *
 * <p>One packet for every action rather than one per button: each is an id and at most one number,
 * and a payload class per button would be a dozen places to forget the container-id guard.
 *
 * <p>{@code arg} is a long because pasting a bay's face config is one action carrying 36 bits of
 * it. A second packet type for that one button would be the same guard written twice.
 *
 * <p>{@code back} is which way a cycling control was asked to step: left-click forward,
 * right-click backward. It rides the action rather than being a separate action per direction,
 * because every one of them is the same button with the same guards.
 */
public record ActionPacket(int containerId, WorkbayAction action, long arg,
    Optional<UUID> link, Optional<String> text, boolean back)
    implements CustomPacketPayload {

    public static final Type<ActionPacket> TYPE = new Type<>(Workbay.rl("action"));

    private static final StreamCodec<RegistryFriendlyByteBuf, WorkbayAction> ACTION =
        ByteBufCodecs.VAR_INT.map(i -> WorkbayAction.values()[Math.clamp(i, 0,
            WorkbayAction.values().length - 1)], WorkbayAction::ordinal).cast();

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ActionPacket::containerId,
            ACTION, ActionPacket::action,
            ByteBufCodecs.VAR_LONG, ActionPacket::arg,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), ActionPacket::link,
            // Capped, because it is drawn on a 130-pixel line and arrives from a client; and
            // filtered, because it is drawn on every viewer's screen.
            ByteBufCodecs.stringUtf8(64).map(ActionPacket::cleanName, s -> s)
                .apply(ByteBufCodecs::optional), ActionPacket::text,
            ByteBufCodecs.BOOL, ActionPacket::back,
            ActionPacket::new);

    /**
     * A player-typed name, made safe to draw: formatting codes go as a pair (vanilla's chat filter
     * drops the section sign and keeps the letter), then the chat filter for control characters,
     * then the format-category characters it lets through -- a zero-width space is one, and a name
     * of nothing but those passed {@code isBlank()}. Night audit 1A, finding 9.
     */
    public static String cleanName(String text) {
        String stripped = net.minecraft.ChatFormatting.stripFormatting(text);
        return net.minecraft.util.StringUtil.filterText(stripped == null ? "" : stripped).chars()
            .filter(c -> Character.getType(c) != Character.FORMAT)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString().strip();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActionPacket packet, IPayloadContext context) {
        if (context.player().containerMenu instanceof WorkbayMenu menu
            && menu.containerId == packet.containerId()) {
            menu.act(packet.action(), packet.arg(), packet.link(), packet.text(), packet.back());
            return;
        }
        // The room door's two buttons ride the same packet and the same guard. A second payload
        // type for "leave" and "go next door" would be this container-id check written twice.
        if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
            && player.containerMenu instanceof com.neryos.workbay.menu.RoomDoorMenu door
            && door.containerId == packet.containerId()) {
            door.act(packet.action(), (int) packet.arg(), player);
            return;
        }
        // And the Connector's rename panel, on the same guard for the same reason.
        if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
            && player.containerMenu instanceof com.neryos.workbay.menu.ConnectorMenu connector
            && connector.containerId == packet.containerId()) {
            connector.act(packet.action(), packet.text().orElse(""), player);
        }
    }
}
