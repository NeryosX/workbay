package com.neryos.workbay.content.workbay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * What a Workbay item remembers about the Workbay it came from. SPEC.md §2 and §14.
 *
 * <p>Breaking a Workbay leaves its bays and its machines behind in the Backshop; this is the thread
 * back to them, so re-placing the item rebinds to the same bays rather than minting an empty one.
 * That is also why the counts are here: a tooltip renders on the client, which has no registry to
 * ask, and SPEC.md §6 wants the item to say what it is holding before you place it.
 *
 * <p>The stream codec goes through the codec rather than {@code StreamCodec.composite}, which caps
 * at six components and this exceeds.
 */
public record WorkbayBinding(
    UUID id,
    String code,
    UUID owner,
    String ownerName,
    boolean locked,
    WorkbayRecord.Upgrades upgrades,
    int hosted,
    int buses,
    /**
     * What was in the block's buffer when it was broken. <b>Everything else a Workbay holds
     * survives being picked up</b> - the bays, the links, the upgrades - because they
     * live on the record and the record outlives the block. The buffer does not: it is the one
     * number that lives in the block entity, and the block entity is what the pickaxe destroys.
     * A player who fed a generator into a Workbay and then moved it lost every FE of it, silently,
     * which is the same loss the load path used to make and the same reason it matters.
     */
    int energy) {

    public static final Codec<WorkbayBinding> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("Id").forGetter(WorkbayBinding::id),
        Codec.STRING.fieldOf("Code").forGetter(WorkbayBinding::code),
        UUIDUtil.CODEC.fieldOf("Owner").forGetter(WorkbayBinding::owner),
        Codec.STRING.fieldOf("OwnerName").forGetter(WorkbayBinding::ownerName),
        Codec.BOOL.optionalFieldOf("Locked", true).forGetter(WorkbayBinding::locked),
        WorkbayRecord.Upgrades.CODEC.optionalFieldOf("Upgrades", WorkbayRecord.Upgrades.NONE)
            .forGetter(WorkbayBinding::upgrades),
        Codec.INT.optionalFieldOf("Hosted", 0).forGetter(WorkbayBinding::hosted),
        Codec.INT.optionalFieldOf("Buses", 0).forGetter(WorkbayBinding::buses),
        Codec.INT.optionalFieldOf("Energy", 0).forGetter(WorkbayBinding::energy)
    ).apply(i, WorkbayBinding::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkbayBinding> STREAM_CODEC =
        ByteBufCodecs.fromCodecWithRegistries(CODEC);

    public static WorkbayBinding of(WorkbayRecord record, int hosted, int buses, int energy) {
        return new WorkbayBinding(record.id(), record.code(), record.owner(), record.ownerName(),
            record.locked(), record.upgrades(), hosted, buses, energy);
    }
}
