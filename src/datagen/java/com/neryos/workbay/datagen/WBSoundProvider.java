package com.neryos.workbay.datagen;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.init.WBSounds;
import net.minecraft.data.PackOutput;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.SoundDefinition;
import net.neoforged.neoforge.common.data.SoundDefinitionsProvider;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * {@code sounds.json}: each of the mod's events plays a vanilla event's recording and carries its
 * own subtitle. Which recording, and why, is the table in {@code WorkbaySounds}; the subtitle
 * text is in {@link WBLanguageProvider} under {@code subtitles.workbay.<name>}. OPEN_ISSUES #91.
 */
public class WBSoundProvider extends SoundDefinitionsProvider {

    public WBSoundProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, Workbay.MOD_ID, helper);
    }

    @Override
    public void registerSounds() {
        borrow(WBSounds.REFUSE, SoundEvents.VAULT_INSERT_ITEM_FAIL);
        borrow(WBSounds.CONFIRM, SoundEvents.VAULT_INSERT_ITEM);
        borrow(WBSounds.RELAY, SoundEvents.COMPARATOR_CLICK);
        borrow(WBSounds.LINKED, SoundEvents.COPPER_BULB_TURN_ON);
        borrow(WBSounds.UPGRADED, SoundEvents.SMITHING_TABLE_USE);
        borrow(WBSounds.ANCHOR_ON, SoundEvents.BEACON_ACTIVATE);
        borrow(WBSounds.ANCHOR_OFF, SoundEvents.BEACON_DEACTIVATE);
        borrow(WBSounds.DOOR, SoundEvents.IRON_DOOR_OPEN);
        borrow(WBSounds.TRAVEL, SoundEvents.ENDERMAN_TELEPORT);
        borrow(WBSounds.STARTED, SoundEvents.VAULT_OPEN_SHUTTER);
        borrow(WBSounds.STOPPED, SoundEvents.VAULT_CLOSE_SHUTTER);
        borrow(WBSounds.STUCK, SoundEvents.COPPER_BULB_TURN_OFF);
        borrow(WBSounds.NETWORK_ARRIVES, SoundEvents.BEACON_ACTIVATE);
        borrow(WBSounds.ROOM_RETURNED, SoundEvents.COPPER_BULB_TURN_OFF);
    }

    /** Our event, vanilla's recording, our subtitle. */
    private void borrow(DeferredHolder<SoundEvent, SoundEvent> ours, SoundEvent vanilla) {
        String name = ours.getId().getPath();
        add(ours, SoundDefinition.definition()
            .subtitle("subtitles." + Workbay.MOD_ID + "." + name)
            .with(SoundDefinition.Sound.sound(vanilla.getLocation(),
                SoundDefinition.SoundType.EVENT)));
    }
}
