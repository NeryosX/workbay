package com.neryos.workbay.content.connector;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Which Workbay a Connector belongs to. SPEC.md §0 and §9.
 *
 * <p><b>No bay.</b> It carried one, and placing the Connector minted a channel on it - a row on a
 * bay the player was not thinking about when they pressed Pair. A Connector belongs to a network
 * and may carry a channel on every bay of it at once, so there is no one bay to name.
 *
 * <p>Carried on the item so a Connector is paired <em>before</em> it is placed - the block already
 * knows its Workbay when {@code setPlacedBy} runs, and there is no second step where a freshly
 * placed Connector sits in the world doing nothing.
 *
 * <p>{@code workbayPos} is here as well as {@code workbayId} so the block can reach the Workbay's
 * block entity without walking the registry every time; the id is what it verifies against, because
 * a Workbay can be broken and re-placed somewhere else.
 *
 * <p>{@code network} is the network's <b>name</b>, carried for the tooltip and the placement
 * message: both render on the client, which has no registry to ask. It was the network's code, and
 * SPEC.md §0 shows a code nowhere a player reads - the codec key is still {@code Code} so a
 * Connector paired before this still says something, and re-pairing replaces it with the name.
 */
public record ConnectorPairing(UUID workbayId, GlobalPos workbayPos, String network) {

    public static final Codec<ConnectorPairing> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("WorkbayId").forGetter(ConnectorPairing::workbayId),
        GlobalPos.CODEC.fieldOf("WorkbayPos").forGetter(ConnectorPairing::workbayPos),
        Codec.STRING.fieldOf("Code").forGetter(ConnectorPairing::network)
    ).apply(i, ConnectorPairing::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConnectorPairing> STREAM_CODEC =
        ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
