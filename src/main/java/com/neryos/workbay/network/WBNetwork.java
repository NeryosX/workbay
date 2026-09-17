package com.neryos.workbay.network;

import com.neryos.workbay.Workbay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Four payloads: the whole screen state out, Bay View's tanks and energy out, a hosted machine's
 * own copy out, one button press back. SPEC.md §4 and §5.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class WBNetwork {
    private WBNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(SnapshotPacket.TYPE, SnapshotPacket.STREAM_CODEC, SnapshotPacket::handle);
        registrar.playToClient(RemoteMachinePacket.TYPE, RemoteMachinePacket.STREAM_CODEC,
            RemoteMachinePacket::handle);
        registrar.playToServer(ActionPacket.TYPE, ActionPacket.STREAM_CODEC, ActionPacket::handle);
    }
}
