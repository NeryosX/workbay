package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.world.BayVisit;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Data attached to something the mod does not own. One holder, the same shape as the rest of {@code init}. */
public final class WBAttachments {
    private WBAttachments() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Workbay.MOD_ID);

    /**
     * Where a bay visitor came from. On the player, not in the network record: it is the
     * <em>player</em> who has to be put back, one player can only be in one bay, and a record that
     * outlived its Workbay would strand them. Serialized, so a server restart with somebody inside
     * still knows the way home.
     */
    public static final Supplier<AttachmentType<BayVisit.Return>> BAY_RETURN =
        ATTACHMENTS.register("bay_return", () -> AttachmentType
            .<BayVisit.Return>builder(() -> null)
            .serialize(BayVisit.Return.CODEC)
            .build());

    /**
     * Where a room's occupant came from, and which room they are in. Separate from
     * {@link #BAY_RETURN} on purpose: the two visits have opposite rules. A bay visitor with no
     * screen open is put out on the next tick; a room's occupant is meant to stand there with
     * nothing open for as long as they like, and to log back in exactly where they logged out.
     */
    public static final Supplier<AttachmentType<com.neryos.workbay.world.RoomVisit.Inside>> ROOM_RETURN =
        ATTACHMENTS.register("room_return", () -> AttachmentType
            .<com.neryos.workbay.world.RoomVisit.Inside>builder(() -> null)
            .serialize(com.neryos.workbay.world.RoomVisit.Inside.CODEC)
            .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }
}
