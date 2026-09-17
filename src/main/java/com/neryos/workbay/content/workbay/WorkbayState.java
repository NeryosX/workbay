package com.neryos.workbay.content.workbay;

import net.minecraft.util.StringRepresentable;

/**
 * What the block looks like from across the room. SPEC.md §7: three readings, distinguishable at
 * distance and in the dark, so "is something wrong?" is answerable without opening anything.
 */
public enum WorkbayState implements StringRepresentable {
    /** Powered, nothing to do. Dim interior, frame unlit. */
    IDLE("idle"),
    /** At least one bus moved something recently. Interior lit, a soft frame glow. */
    RUNNING("running"),
    /** A bus has a red status, or there is no power. Frame lit amber, slow pulse. */
    STUCK("stuck");

    private final String name;

    WorkbayState(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
