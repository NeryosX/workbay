package com.neryos.workbay.network;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.menu.WorkbaySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The whole screen state, server to client. SPEC.md §4.
 *
 * <p>Sent only when it differs from what was last sent, so an idle Workbay costs one comparison a
 * tick and no bandwidth at all.
 */
public record SnapshotPacket(int containerId, WorkbaySnapshot snapshot) implements CustomPacketPayload {

    public static final Type<SnapshotPacket> TYPE = new Type<>(Workbay.rl("snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SnapshotPacket::containerId,
            WorkbaySnapshot.STREAM_CODEC, SnapshotPacket::snapshot,
            SnapshotPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * The container-id guard is not optional: without it a packet that arrives one tick after the
     * player closed one Workbay and opened another writes the first one's state into the second.
     */
    public static void handle(SnapshotPacket packet, IPayloadContext context) {
        if (context.player().containerMenu instanceof WorkbayMenu menu
            && menu.containerId == packet.containerId()) {
            menu.applySnapshot(packet.snapshot());
        }
    }
}
