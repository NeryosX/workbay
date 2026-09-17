package com.neryos.workbay.world;

import net.minecraft.util.StringRepresentable;

/**
 * When a bay's links are allowed to run. SPEC.md §4.
 *
 * <p><b>These four and no others.</b> Every machine mod in the reference set offers exactly this
 * set, and a player who has met it once in AE2, Mekanism or EnderIO already knows what each one
 * does. Inventing a fifth would teach them something untrue about all the others.
 */
public enum RedstoneMode implements StringRepresentable {
    ALWAYS("always"),
    WITH_SIGNAL("with_signal"),
    WITHOUT_SIGNAL("without_signal"),
    /** One operation per rising edge, so a clock drives exactly one move per tick of the clock. */
    PULSE("pulse");

    private final String name;

    RedstoneMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** One step round the ring. Back is what a right-click asks for. */
    public RedstoneMode step(boolean back) {
        return values()[Math.floorMod(ordinal() + (back ? -1 : 1), values().length)];
    }

    /**
     * @param armed a rising edge has been seen and not yet spent. Only {@link #PULSE} reads it, and
     *              spending it is the caller's job - this must stay a pure question.
     */
    public boolean allows(boolean powered, boolean armed) {
        return switch (this) {
            case ALWAYS -> true;
            case WITH_SIGNAL -> powered;
            case WITHOUT_SIGNAL -> !powered;
            case PULSE -> armed;
        };
    }
}
