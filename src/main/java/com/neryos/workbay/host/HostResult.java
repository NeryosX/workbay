package com.neryos.workbay.host;

import com.neryos.workbay.WorkbayLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A verdict with the reason that produced it. SPEC.md §11.
 *
 * <p>Tri-state, and the third state is the point: a check must be able to say "no opinion" so that
 * a third-party check about one mod does not clobber the tag layer for every other.
 */
public record HostResult(HostResult.Verdict verdict, @Nullable String reasonKey, Object[] args) {

    public enum Verdict { ALLOW, DENY, PASS }

    private static final HostResult ALLOWED = new HostResult(Verdict.ALLOW, null, new Object[0]);
    private static final HostResult PASSED = new HostResult(Verdict.PASS, null, new Object[0]);

    public static HostResult allow() {
        return ALLOWED;
    }

    /** No opinion. Evaluation carries on to the next rule. */
    public static HostResult pass() {
        return PASSED;
    }

    /** @param reasonKey one of the keys under {@code message.workbay.reject.*} in SPEC.md §6 */
    public static HostResult deny(String reasonKey, Object... args) {
        return new HostResult(Verdict.DENY, reasonKey, args);
    }

    public boolean allowed() {
        return verdict == Verdict.ALLOW;
    }

    /** True when this check had no opinion and evaluation should carry on. */
    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    /** Action bar, red, and never chat: this is a transient rejection (SPEC.md §6). */
    public Component message() {
        return reasonKey == null
            ? Component.empty()
            : WorkbayLang.message("reject." + reasonKey, args).withStyle(ChatFormatting.RED);
    }
}
