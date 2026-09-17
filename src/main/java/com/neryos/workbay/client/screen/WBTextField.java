package com.neryos.workbay.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The one control on these screens that was still in the game's own font. OPEN_ISSUES #50.
 *
 * <p><b>Vanilla's {@code EditBox} cannot be styled without lying.</b> {@code setFormatter} changes
 * what is <em>drawn</em>, and the caret is placed with {@code font.width} on the <em>unstyled</em>
 * string - read off {@code EditBox#renderWidget} - so a styled box puts its cursor where the
 * glyphs would have been in the other typeface, and the gap grows with every character. Its
 * private {@code displayPos} leaves no subclass hook either. So the seam was a real choice: three
 * fields (the rename box, the biome search, the guest name) in the bitmap font on nine screens that
 * are not.
 *
 * <p><b>Written rather than kept, because one measurement is the whole of what it takes.</b>
 * Everything here measures with {@link Draw#width}, which measures in the font the string is
 * actually drawn in, so the caret, the selection box and the scroll offset agree with the glyphs
 * by construction rather than by luck. That is the same argument {@code Draw} itself is: what a
 * screen cannot reach from one place is what it was borrowing from the game.
 *
 * <p>What it deliberately does not have: word jumps, undo, right-to-left ordering, a suggestion
 * line, a placeholder. None of the three fields wants any of them, and each is a behaviour that
 * has to be right in every language rather than in English.
 */
public class WBTextField extends AbstractWidget {

    private final Font font;
    private String value = "";
    private int maxLength = 32;
    private int textColour = Draw.TEXT;
    private boolean bordered = true;

    /** Where the caret is, and where a selection started. Equal means no selection. */
    private int cursor;
    private int anchor;

    /**
     * The first character drawn. A field narrower than its contents scrolls rather than clipping
     * from the left, which is what makes the end of a long name reachable at all.
     */
    private int from;

    /** Blink is on the game's own clock so two fields on one screen cannot blink out of step. */
    private int ticks;

    public WBTextField(Font font, int x, int y, int width, int height) {
        super(x, y, width, height, CommonComponents.EMPTY);
        this.font = font;
    }

    public void setMaxLength(int max) {
        maxLength = max;
        if (value.length() > max) {
            setValue(value.substring(0, max));
        }
    }

    public void setBordered(boolean nowBordered) {
        bordered = nowBordered;
    }

    public void setTextColor(int colour) {
        textColour = colour;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String now) {
        value = now.length() > maxLength ? now.substring(0, maxLength) : now;
        cursor = Math.min(cursor, value.length());
        anchor = cursor;
        scrollToCursor();
    }

    public void moveCursorToEnd() {
        cursor = value.length();
        anchor = cursor;
        scrollToCursor();
    }

    /** Vanilla's guard, kept by name: true while this field is the one the keyboard belongs to. */
    public boolean canConsumeInput() {
        return visible && isFocused();
    }

    private int innerWidth() {
        return bordered ? width - 8 : width;
    }

    private int innerX() {
        return bordered ? getX() + 4 : getX();
    }

    /**
     * Keeps the caret inside the box by moving the window, never by moving the caret.
     *
     * <p>Both directions, and the second one is the half a first draft always forgets: walking
     * left past {@link #from} has to pull the window back or the caret parks itself on the left
     * edge and stops moving while the text does not.
     */
    private void scrollToCursor() {
        from = Math.min(from, cursor);
        while (Draw.width(font, value.substring(from, cursor)) > innerWidth() && from < cursor) {
            from++;
        }
    }

    private String visibleText() {
        int end = value.length();
        while (end > from && Draw.width(font, value.substring(from, end)) > innerWidth()) {
            end--;
        }
        return value.substring(from, end);
    }

    /** Where a character index sits on screen, measured in the font it is drawn in. */
    private int xOf(int index) {
        return innerX() + Draw.width(font, value.substring(from, Mth.clamp(index, from, value.length())));
    }

    private boolean hasSelection() {
        return cursor != anchor;
    }

    private void deleteSelection() {
        int start = Math.min(cursor, anchor);
        int end = Math.max(cursor, anchor);
        value = value.substring(0, start) + value.substring(end);
        cursor = start;
        anchor = start;
        scrollToCursor();
    }

    private void insert(String typed) {
        if (hasSelection()) {
            deleteSelection();
        }
        String allowed = net.minecraft.util.StringUtil.filterText(typed);
        int room = maxLength - value.length();
        if (room <= 0 || allowed.isEmpty()) {
            return;
        }
        String fitted = allowed.length() > room ? allowed.substring(0, room) : allowed;
        value = value.substring(0, cursor) + fitted + value.substring(cursor);
        cursor += fitted.length();
        anchor = cursor;
        scrollToCursor();
    }

    private void moveTo(int where, boolean selecting) {
        cursor = Mth.clamp(where, 0, value.length());
        if (!selecting) {
            anchor = cursor;
        }
        scrollToCursor();
    }

    @Override
    public boolean charTyped(char typed, int modifiers) {
        if (!canConsumeInput() || !net.minecraft.util.StringUtil.isAllowedChatCharacter(typed)) {
            return false;
        }
        insert(String.valueOf(typed));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!canConsumeInput()) {
            return false;
        }
        // The clipboard first, so ctrl+A/C/V/X never fall through to a bare-letter binding.
        if (Screen.isSelectAll(key)) {
            anchor = 0;
            cursor = value.length();
            scrollToCursor();
            return true;
        }
        if (Screen.isCopy(key)) {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(selected());
            return true;
        }
        if (Screen.isPaste(key)) {
            insert(net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        }
        if (Screen.isCut(key)) {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(selected());
            deleteSelection();
            return true;
        }
        boolean selecting = Screen.hasShiftDown();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> moveTo(cursor - 1, selecting);
            case GLFW.GLFW_KEY_RIGHT -> moveTo(cursor + 1, selecting);
            case GLFW.GLFW_KEY_HOME -> moveTo(0, selecting);
            case GLFW.GLFW_KEY_END -> moveTo(value.length(), selecting);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (cursor > 0) {
                    value = value.substring(0, cursor - 1) + value.substring(cursor);
                    moveTo(cursor - 1, false);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (cursor < value.length()) {
                    value = value.substring(0, cursor) + value.substring(cursor + 1);
                }
            }
            // Everything else is somebody else's: Return commits, Escape abandons, and both are
            // the screen's to decide. Saying "not mine" is what lets it.
            default -> {
                return false;
            }
        }
        return true;
    }

    private String selected() {
        return value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor));
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // Click to place the caret, measured the same way it is drawn: walk the visible text until
        // the click is passed. A binary search would be the same answer on a string this short.
        String shown = visibleText();
        int at = from;
        while (at < from + shown.length() && xOf(at + 1) <= mouseX) {
            at++;
        }
        moveTo(at, false);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (!visible) {
            return;
        }
        if (bordered) {
            Draw.well(g, getX(), getY(), width, height);
        }
        String shown = visibleText();
        int textY = getY() + (height - font.lineHeight) / 2 + 1;

        // The selection box, under the glyphs. Drawn from the same measurement the caret uses, so
        // the highlight cannot end anywhere the text does not.
        if (hasSelection()) {
            int start = xOf(Math.min(cursor, anchor));
            int end = xOf(Math.max(cursor, anchor));
            g.fill(Math.min(start, end), textY - 1, Math.max(start, end), textY + font.lineHeight,
                Draw.alpha(Draw.SELECT, 0.45F));
        }
        Draw.text(g, font, shown, innerX(), textY, innerWidth(), textColour);

        // A caret that blinks in the same rhythm as the game's own, and is a line rather than an
        // underscore because an underscore in this font sits under the following glyph.
        if (isFocused() && ticks / 6 % 2 == 0) {
            int caretX = xOf(cursor);
            g.fill(caretX, textY - 1, caretX + 1, textY + font.lineHeight, Draw.TEXT);
        }
    }

    /** Called from the screen's tick, so the caret blinks whether or not anything is moving. */
    public void tick() {
        ticks++;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            net.minecraft.network.chat.Component.literal(value));
    }
}
