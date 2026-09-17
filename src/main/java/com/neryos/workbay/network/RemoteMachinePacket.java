package com.neryos.workbay.network;

import com.neryos.workbay.Workbay;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A hosted machine, sent to a client that will never be sent its chunk. SPEC.md §5.
 *
 * <p>Everything the client needs to build a block entity it cannot look up: the position it will be
 * asked about, the block state to build against, and the machine's saved data. An empty tag means
 * the screen closed and the copy should go.
 *
 * <p>Sent immediately before the menu opens. Packets on one connection keep their order, so the
 * copy is always in place by the time the screen asks for it.
 */
public record RemoteMachinePacket(BlockPos pos, BlockState state, CompoundTag data)
    implements CustomPacketPayload {

    public static final Type<RemoteMachinePacket> TYPE = new Type<>(Workbay.rl("remote_machine"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteMachinePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RemoteMachinePacket::pos,
            ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY::byId, Block.BLOCK_STATE_REGISTRY::getId),
            RemoteMachinePacket::state,
            ByteBufCodecs.TRUSTED_COMPOUND_TAG, RemoteMachinePacket::data,
            RemoteMachinePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RemoteMachinePacket packet, IPayloadContext context) {
        com.neryos.workbay.client.RemoteMachines.apply(packet.pos(), packet.state(), packet.data());
    }
}
