package com.neryos.workbay.content.room;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;
import java.util.UUID;

/**
 * What a room item carries: which room, and the ticket that says this item is the one that was
 * pulled out of a bay. SPEC.md §0. An item with no stamp is a room nobody has opened yet; one
 * whose ticket is not the registry's is a copy, and cannot open the room.
 */
public record RoomStamp(UUID room, Optional<UUID> ticket) {

    public static final Codec<RoomStamp> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("room").forGetter(RoomStamp::room),
        UUIDUtil.CODEC.optionalFieldOf("ticket").forGetter(RoomStamp::ticket)
    ).apply(i, RoomStamp::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoomStamp> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RoomStamp::room,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), RoomStamp::ticket,
            RoomStamp::new);
}
