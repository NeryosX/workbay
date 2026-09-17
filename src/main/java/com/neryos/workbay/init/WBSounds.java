package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Every sound the mod makes, as its own event. <b>Still vanilla's recordings</b> -- each event's
 * definition (datagen, {@code sounds.json}) points at the vanilla event whose sound it borrows, so
 * there is no {@code .ogg} and no second art budget. What an event of our own buys is the
 * <b>subtitle</b>: a borrowed event carries the other block's caption, so a Workbay refusing read
 * "Vault rejects item" and a player leaving for a bay read "Enderman teleports". OPEN_ISSUES #91.
 *
 * <p>The moment each stands for, and why it sounds the way it does, is in {@code WorkbaySounds}.
 */
public final class WBSounds {
    private WBSounds() {}

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, Workbay.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> REFUSE = sound("refuse");
    public static final DeferredHolder<SoundEvent, SoundEvent> CONFIRM = sound("confirm");
    /** A Connector paired, a Workbay stamped onto a network, a block placed holding nothing. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RELAY = sound("relay");
    /** The placed Connector's link closing. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LINKED = sound("linked");
    public static final DeferredHolder<SoundEvent, SoundEvent> UPGRADED = sound("upgraded");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANCHOR_ON = sound("anchor_on");
    public static final DeferredHolder<SoundEvent, SoundEvent> ANCHOR_OFF = sound("anchor_off");
    public static final DeferredHolder<SoundEvent, SoundEvent> DOOR = sound("door");
    public static final DeferredHolder<SoundEvent, SoundEvent> TRAVEL = sound("travel");
    public static final DeferredHolder<SoundEvent, SoundEvent> STARTED = sound("started");
    public static final DeferredHolder<SoundEvent, SoundEvent> STOPPED = sound("stopped");
    public static final DeferredHolder<SoundEvent, SoundEvent> STUCK = sound("stuck");
    /** A network arriving in a block: transferred in, or made in place. */
    public static final DeferredHolder<SoundEvent, SoundEvent> NETWORK_ARRIVES = sound("network_arrives");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROOM_RETURNED = sound("room_returned");

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(Workbay.rl(name)));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
