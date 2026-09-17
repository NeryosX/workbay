package com.neryos.workbay;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Every translation key in the mod is built here. SPEC.md §6.
 *
 * <p>One place, so the datagen'd language file and the code that reads it cannot drift apart, and
 * so a missing key is a compile-time rename rather than a string typo nobody notices until a
 * player screenshots {@code message.workbay.reject.no_bay} in their chat.
 */
public final class WorkbayLang {
    private WorkbayLang() {}

    /** In-screen labels and buttons. */
    public static MutableComponent gui(String path, Object... args) {
        return Component.translatable(guiKey(path), args);
    }

    /** Item and block tooltips. */
    public static MutableComponent tooltip(String path, Object... args) {
        return Component.translatable(tooltipKey(path), args);
    }

    /** Transient rejections. Action bar, never chat (SPEC.md §6). */
    public static MutableComponent message(String path, Object... args) {
        return Component.translatable(messageKey(path), args);
    }

    /** Persistent conditions shown as a synced status row, never spammed. */
    public static MutableComponent status(String path, Object... args) {
        return Component.translatable(statusKey(path), args);
    }

    /**
     * The guide pages, shown on an item's Information tab in JEI and EMI. SPEC.md §6's voice, at
     * the length a paragraph needs -- this is the one place in the mod with room for one.
     */
    public static MutableComponent info(String path, Object... args) {
        return Component.translatable(infoKey(path), args);
    }

    public static String guiKey(String path) {
        return Util.makeDescriptionId("gui", Workbay.rl(path));
    }

    public static String tooltipKey(String path) {
        return Util.makeDescriptionId("tooltip", Workbay.rl(path));
    }

    public static String messageKey(String path) {
        return Util.makeDescriptionId("message", Workbay.rl(path));
    }

    public static String statusKey(String path) {
        return Util.makeDescriptionId("status", Workbay.rl(path));
    }

    public static String infoKey(String path) {
        return Util.makeDescriptionId("info", Workbay.rl(path));
    }
}
