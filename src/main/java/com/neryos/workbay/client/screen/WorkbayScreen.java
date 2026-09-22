package com.neryos.workbay.client.screen;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbayMenu;
import com.neryos.workbay.menu.WorkbaySnapshot;
import com.neryos.workbay.network.ActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All three Workbay screens. SPEC.md §4.
 *
 * <p><b>No player inventory and no slots.</b> A machine enters a bay by clicking the bay slot while
 * holding it, so there is no grid to drag from and nothing for vanilla's slot machinery to do.
 *
 * <p>Clickable regions are registered as rectangles while the page draws, rather than as
 * {@code Button} widgets built in {@code init()}. With a layout this dense that keeps each control's
 * geometry, its tooltip and its action in one place - the alternative is the same coordinates
 * written out three times and drifting apart the first time anything moves.
 */
public class WorkbayScreen extends AbstractContainerScreen<WorkbayMenu> {

    /** Which screen is showing. They share one menu; there is nothing to re-bind between them. */
    public enum Page { BAYS, FLOW, UPGRADES, NETWORKS }

    /** Whether the snapshot the current page was built for had a network. See containerTick. */
    private boolean builtBound = true;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private Page page = Page.BAYS;

    @Nullable
    private WorkbayPage current;

    /**
     * The rename field, when one is open. SPEC.md §4: a {@link WBTextField} over the name line,
     * committed by Return and abandoned by Escape.
     */
    @Nullable
    private WBTextField renaming;

    @Nullable
    private java.util.function.Consumer<String> onRenamed;

    /**
     * A page's own search field, for as long as it draws one. The room picker's biome search is
     * the only one.
     *
     * <p>Separate from {@link #renaming} because the two answer opposite questions: a rename is a
     * value being <em>committed</em>, so it ends on Return and on a click elsewhere, and a filter
     * is only ever <em>read</em> - ending it on the click that picks a row would swallow that
     * click and unfilter the list underneath it in the same frame.
     */
    @Nullable
    private WBTextField filter;

    /**
     * True while a right-click is being dispatched, so every cycling control steps <b>backwards</b>.
     *
     * <p>A field read during dispatch rather than a second Runnable on every {@code hit}: forty-odd
     * call sites would each have to name a mirrored lambda, and the mirror is always the same
     * question - which way round the ring. The server-side ones carry it on the action packet; the
     * client-only ones (the list's filter and sort) read it straight from here.
     */
    private boolean back;

    /**
     * The last thing the server said on the action bar while this screen was open, and when.
     * OPEN_ISSUES #38: the HUD draws the action bar before the screen, so the panel covers every
     * refusal the panel itself caused. Drawn inside the panel instead, over the bottom of whatever
     * page is showing - a strip with its own background rather than a gap reserved on three pages,
     * because a notice is a thing that comes and goes and the layouts have no spare line in common.
     */
    @Nullable
    private Component notice;

    private long noticeAt;

    /** How long a notice stays up. Vanilla's action bar holds for three seconds; this is five. */
    private static final long NOTICE_MS = 5000;

    public WorkbayScreen(WorkbayMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    /** Called from {@code WorkbayClient.Notices} when the server puts something on the action bar. */
    public void notice(Component message) {
        notice = message;
        noticeAt = net.minecraft.Util.getMillis();
    }

    @Override
    protected void init() {
        // A Workbay holding no network has no bays, no energy and no upgrades to draw, so NETWORKS
        // is not merely where it opens -- it is the only page there is, and the tab strip is not
        // drawn at all. This is the "Workbay quota reached" state, and it is a state, not an error.
        builtBound = menu.snapshot().bound();
        if (!builtBound) {
            page = Page.NETWORKS;
        }
        current = switch (page) {
            case BAYS -> new BaysPage(this);
            case FLOW -> new FlowPage(this);
            case UPGRADES -> new UpgradesPage(this);
            case NETWORKS -> new NetworksPage(this);
        };
        imageWidth = current.width();
        imageHeight = current.height();
        // init() clears every widget, so a field left over from before a resize would be a ghost.
        renaming = null;
        onRenamed = null;
        filter = null;
        super.init();
    }

    /**
     * A page whose height changed under an open screen is re-laid out: {@code imageHeight} is
     * only read in {@link #init()}, and re-running init is the same resize {@link #goTo} does.
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (renaming != null) {
            renaming.tick();
        }
        if (filter != null) {
            filter.tick();
        }
        if (current != null && current.height() != imageHeight) {
            init(minecraft, width, height);
        }
        // A Transfer turns an empty Workbay into a working one, or the other way round, while the
        // screen is open -- and which pages exist at all changes with it.
        if (menu.snapshot().bound() != builtBound) {
            init(minecraft, width, height);
        }
    }

    public void goTo(Page next) {
        page = next;
        // Re-running init is what resizes the window: the three pages are different heights, and
        // leftPos/topPos are derived from imageWidth/imageHeight in super.init().
        init(minecraft, width, height);
    }

    public Page page() {
        return page;
    }


    public WorkbaySnapshot snapshot() {
        return menu.snapshot();
    }

    /**
     * <b>The bay the player has clicked</b>, which is not always the bay the last snapshot named.
     *
     * <p>A snapshot is rebuilt every five ticks and is the <em>server's</em> answer, so between a
     * click on bay 3 and the packet coming back it still says bay 2. Reading it was harmless for
     * everything that only draws -- the rack marker was a frame behind -- and destructive for the
     * one thing that writes: Add assigned the picked links to the bay before the one the player
     * was looking at. OPEN_ISSUES #71. Every page reads the selection through here now, so the
     * screen cannot disagree with itself about which bay it is showing.
     */
    public int selectedBay() {
        return menu.selectedBay();
    }

    public int left() {
        return leftPos;
    }

    public int top() {
        return topPos;
    }

    /**
     * Which line's rename field is open, if any.
     *
     * <p><b>A flag is not enough, and shipping one proved it.</b> Every line that can be renamed
     * hides its own text while a field is over it, because the field is drawn on top and a name
     * left underneath shows through. With one boolean for the whole screen, opening a Connector's
     * rename box hid <em>every</em> name on the screen -- thirty link rows and the bay title all
     * went blank at once, and the player who opened one box had to close it again to read anything
     * else. OPEN_ISSUES #76. The subject is whatever the caller uses to tell its own lines apart:
     * a link's UUID, a bay's index, a room's.
     */
    @Nullable
    private Object renameSubject;

    /** Opens the rename field over a line of the page. Called from the page while it draws. */
    public void beginRename(Object subject, int x, int y, int w, int h, String initial,
        java.util.function.Consumer<String> committed) {
        if (renaming != null) {
            return;
        }
        renameSubject = subject;
        renaming = new WBTextField(font, x, y, w, h);
        renaming.setMaxLength(48);
        renaming.setValue(initial);
        renaming.moveCursorToEnd();
        renaming.setFocused(true);
        setFocused(renaming);
        onRenamed = committed;
        addRenderableWidget(renaming);
    }

    /** True only for the one line whose field is open, so no other line hides its own name. */
    public boolean renaming(Object subject) {
        return renaming != null && java.util.Objects.equals(renameSubject, subject);
    }

    /**
     * The search field, opened where the page says and moved there on every frame after - a page
     * redraws at a new position when the window it belongs to does. Returns what is in it.
     */
    public String openFilter(int x, int y, int w, int h) {
        if (filter == null) {
            filter = new WBTextField(font, x, y, w, h);
            filter.setMaxLength(32);
            // No frame of its own: the page draws a well behind it, so the box is the text and the
            // caret and nothing else. Vanilla's own border would be a second edge inside ours.
            filter.setBordered(false);
            filter.setTextColor(Draw.TEXT);
            addRenderableWidget(filter);
            setFocused(filter);
            filter.setFocused(true);
        } else {
            filter.setPosition(x, y);
        }
        return filter.getValue();
    }

    /** Puts the caret back in the search box after a click the hit list took first. */
    public void focusFilter() {
        if (filter != null) {
            setFocused(filter);
            filter.setFocused(true);
        }
    }

    /**
     * Empties the search field without closing it. What the field <em>means</em> changes between
     * the room window's two tabs -- a biome to look for, a player to invite -- so a word typed for
     * one must not be sitting in it as the other's first move.
     */
    /** What is in the search field right now, without moving it. Empty when there is none. */
    public String filterText() {
        return filter == null ? "" : filter.getValue();
    }

    public void clearFilter() {
        if (filter != null) {
            filter.setValue("");
        }
    }

    public void closeFilter() {
        if (filter != null) {
            removeWidget(filter);
            filter = null;
            setFocused(null);
        }
    }

    private void endRename(boolean commit) {
        if (renaming == null) {
            return;
        }
        String value = renaming.getValue();
        removeWidget(renaming);
        renaming = null;
        renameSubject = null;
        setFocused(null);
        var committed = onRenamed;
        onRenamed = null;
        if (commit && committed != null) {
            committed.accept(value);
        }
    }

    /**
     * While the rename field is open it takes the keyboard whole. Without this, {@code e} closes
     * the screen mid-word and Escape throws the player out instead of abandoning the edit - both
     * are {@link AbstractContainerScreen}'s defaults and both are wrong here.
     */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (renaming != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                endRename(true);
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                endRename(false);
                return true;
            }
            return renaming.keyPressed(key, scan, modifiers) || renaming.canConsumeInput();
        }
        // Escape backs out of whatever the page has open before it backs out of the screen. A
        // player who opened the room picker to look at the colours presses the one key everybody
        // presses to close a thing, and vanilla's answer is to throw them out of the Workbay.
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && current != null && current.escaped()) {
            return true;
        }
        // While the search field has the caret it takes the keyboard whole, exactly as the rename
        // field does. Without it, typing a player's name into the guest field closes the screen the
        // moment the name contains an <b>e</b>: a letter reaches the box through {@code charTyped},
        // so {@code EditBox#keyPressed} declines it, and the inventory-key check below is what runs
        // instead. {@code canConsumeInput} is the guard vanilla uses for this and it is the whole
        // fix. Found in play, typing a name.
        if (filter != null && filter.isFocused()) {
            if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && current != null && current.entered()) {
                return true;
            }
            return filter.keyPressed(key, scan, modifiers) || filter.canConsumeInput();
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char typed, int modifiers) {
        if (renaming != null) {
            return renaming.charTyped(typed, modifiers);
        }
        if (filter != null && filter.isFocused()) {
            return filter.charTyped(typed, modifiers);
        }
        return super.charTyped(typed, modifiers);
    }

    public net.minecraft.client.gui.Font font() {
        return font;
    }

    /** The scaled canvas height, which a page has to fit inside however the player has it scaled. */
    public int availableHeight() {
        return height;
    }

    /**
     * <b>One height for every page.</b> The window's, between the floor Minecraft's guiScale cap
     * guarantees and what the LINKS list can use; every page of a bound Workbay is this tall, so
     * the panel keeps its top edge and the tab strip stays under the cursor from one tab to the
     * next. Five pages were five heights, centred, and every control moved on every tab press.
     * OPEN_ISSUES #89. A page with more to show than this may still grow past it.
     */
    public int panelHeight() {
        return Math.clamp(height - 8, 240, 316);
    }

    // ------------------------------------------------------------- clickable

    /**
     * A string that would not fit where it was drawn, and the whole of what it said.
     *
     * <p>Kept apart from {@link Hit} on purpose: a cut string must never swallow the click that
     * belongs to the row it sits on, and it must never shadow that row's own tooltip either. So
     * these are consulted only after every hit has declined, and never for clicks at all. Registered
     * by {@code WorkbayPage#clip}, which is the only thing in the mod allowed to shorten a string.
     */
    private record Overflow(int x, int y, int w, int h, Component full) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Overflow> overflows = new ArrayList<>();

    public void overflow(int x, int y, int w, int h, Component full) {
        overflows.add(new Overflow(x, y, w, h, full));
    }

    /** A shape inside a hit's bounding box. The isometric cube's faces are the only non-rectangles. */
    public interface Inside {
        boolean test(double mx, double my);
    }

    /**
     * {@code ring} is false for a region that exists only to <b>swallow</b> a click - a dialog's
     * own background, standing over controls that are still registered underneath it. The hover
     * ring is drawn from this list, so without the flag pointing anywhere inside an open panel
     * outlined the whole panel. Photographed.
     */
    private record Hit(int x, int y, int w, int h, @Nullable Inside shape, Runnable onClick,
        @Nullable List<Component> tooltip, boolean ring) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h
                && (shape == null || shape.test(mx, my));
        }
    }

    /**
     * A region that takes a click and does nothing with it, so nothing registered underneath gets
     * it. What an open dialog puts behind itself: the page's own controls are still in the list and
     * still where they were drawn, and hits dispatch in reverse registration order, so a panel
     * drawn over them needs one of these under its own controls. It draws no hover ring, because
     * it is not a control.
     */
    public void swallow(int x, int y, int w, int h) {
        hits.add(new Hit(x, y, w, h, null, () -> { }, null, false));
    }

    /**
     * A region that only answers a hover: a label, a figure, or a well whose clicks the page takes
     * itself. No ring, because nothing here is a button.
     */
    public void tip(int x, int y, int w, int h, Component... tooltip) {
        hits.add(new Hit(x, y, w, h, null, () -> { }, List.of(tooltip), false));
    }

    /** Registers a clickable region in screen coordinates. Called by a page while it draws. */
    public void hit(int x, int y, int w, int h, Runnable onClick, Component... tooltip) {
        hits.add(new Hit(x, y, w, h, null, onClick, tooltip.length == 0 ? null : List.of(tooltip), true));
    }

    /** The same, for a region that is not a rectangle: the box is tested first, then the shape. */
    public void hit(int x, int y, int w, int h, Inside shape, Runnable onClick, Component... tooltip) {
        hits.add(new Hit(x, y, w, h, shape, onClick,
            tooltip.length == 0 ? null : List.of(tooltip), true));
    }

    /**
     * A slot that takes an item dragged out of a recipe viewer. Collected while the page draws, the
     * same way clicks are, so a slot's geometry is written once - and read by both the JEI and the
     * EMI plugin, neither of which then knows anything about the layout.
     */
    public record Ghost(int x, int y, int w, int h, java.util.function.Consumer<ItemStack> accept) {}

    public void ghost(int x, int y, int w, int h, java.util.function.Consumer<ItemStack> accept) {
        ghosts.add(new Ghost(x, y, w, h, accept));
    }

    /**
     * What the cursor is carrying, EnderIO's idiom: one click lifts a filter entry, one click puts
     * it down. It is a <b>picture</b>, never a stack - nothing here is in an inventory, nothing is
     * sent to the server on pick-up, and losing it on a screen close loses nothing.
     *
     * <p>JEI's own drag is untouched and still works. JEI owns that gesture start to finish
     * ({@code GhostIngredientDragManager} begins on the press and ends on the release), so it
     * cannot be turned into click-then-click from a plugin; this is the half that is ours.
     */
    private ItemStack carried = ItemStack.EMPTY;

    public ItemStack carried() {
        return carried;
    }

    public void carry(ItemStack stack) {
        carried = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    public List<Ghost> ghostTargets() {
        return ghosts;
    }

    /** Which way a cycling control was asked to step. Right-click is backwards. */
    public boolean back() {
        return back;
    }

    public boolean hovered(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- render

    @Override
    protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        hits.clear();
        ghosts.clear();
        overflows.clear();
        // Cleared here, not in the page: the flow and upgrade pages have no rows to hover, and a
        // highlight left behind by the bays page would outline a block nothing on screen mentions.
        com.neryos.workbay.client.LinkHighlight.clear();
        // One clock for the whole screen, ticked before anything reads it.
        Draw.frame();
        Draw.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (current != null) {
            current.render(graphics, mouseX, mouseY);
        }
        hoverRing(graphics, mouseX, mouseY);
        renderNotice(graphics);
    }


    /**
     * The ring around whatever the cursor is on. <b>Drawn from the hit list rather than by each
     * control</b>, which is the whole point: a page registers forty regions and about a dozen of
     * them remembered to light up when hovered, so half this screen answered the mouse and half
     * of it did not - and which half was a matter of who wrote the control. One loop over the
     * regions that already exist gives every one of them the same feedback, including the readouts
     * whose only answer is a tooltip.
     *
     * <p>It fades in rather than appearing, because a hard outline snapping between two adjacent
     * rows as the cursor crosses them is the thing that reads as flicker.
     */
    /** Which region the ring is currently on, so moving to another one restarts the fade. */
    private int ringOn = -1;

    private void hoverRing(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!hit.contains(mouseX, mouseY)) {
                continue;
            }
            if (!hit.ring()) {
                return;
            }
            int where = hit.x() * 31 + hit.y();
            if (where != ringOn) {
                ringOn = where;
                Draw.reset("ring", 0);
            }
            float lit = Draw.approach("ring", 1.0F, 16.0F);
            Draw.ring(g, hit.x() - 1, hit.y() - 1, hit.w() + 2, hit.h() + 2,
                Math.min(5, Math.min(hit.w(), hit.h()) / 3 + 1), Draw.alpha(Draw.SELECT, lit * 0.55F));
            return;
        }
    }

    /** Amber, because SPEC.md §4 makes amber "a problem" everywhere but the power bar. */
    private void renderNotice(GuiGraphics graphics) {
        if (notice == null) {
            return;
        }
        if (net.minecraft.Util.getMillis() - noticeAt > NOTICE_MS) {
            notice = null;
            return;
        }
        int h = font.lineHeight + 8;
        int px = leftPos + 8;
        int py = topPos + imageHeight - h - 6;
        // Above the page, including its item sprites: an item is rendered on its own layer well in
        // front of everything a page fills, so a strip drawn flat came out with a chest sprite
        // showing through the word it was covering. Measured, in a client. The carried stack does
        // the same thing at 400.
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        // Its own amber-edged card rather than a plain well: a refusal is the one thing on the
        // screen the player did not ask to see, so it has to read as laid *over* the page rather
        // than as a row of it.
        Draw.notice(graphics, px, py, imageWidth - 16, h);
        Draw.text(graphics, font, notice.getString(), px + 6, py + 5, imageWidth - 28, Draw.AMBER);
        graphics.pose().popPose();
    }

    /** Vanilla would draw "Workbay" and "Inventory" here; this screen has neither in that place. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.render(graphics, mouseX, mouseY, partial);
        if (!carried.isEmpty()) {
            // Centred on the cursor and drawn over everything, exactly where a vanilla carried
            // stack goes. No tooltip while carrying: the answer to "what is this?" is under the
            // mouse already, and a tooltip there covers the slots being aimed at.
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 400);
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
            return;
        }
        tooltips(graphics, mouseX, mouseY);
    }

    private List<net.minecraft.util.FormattedCharSequence> wrapTooltip(List<Component> lines) {
        return Draw.tooltip(font, lines);
    }

    // ---------------------------------------------------------------- dwell

    /** Which region the cursor is resting on, as a cheap hash of its corner, or -1. */
    private int hoverOn = -1;

    private long hoverSince;

    /**
     * How long the cursor has to rest on a control before it explains itself.
     *
     * <p><b>The single biggest thing wrong with this screen was that it never stopped talking.</b>
     * Forty regions on a page and a tooltip on nearly every one, drawn the instant the cursor
     * crossed it, so moving from one side of the screen to the other strobed six panels of prose
     * at the player. Nothing was deleted to fix that: a tooltip is an answer to a question, and
     * resting on a control is how the player asks it.
     *
     * <p>Four hundred milliseconds. Long enough that crossing a row of eight buttons says nothing,
     * short enough that stopping on one is not a wait.
     */
    private static final long DWELL_MS = 400;

    /**
     * Every tooltip on the screen, in one place, in the order the click resolves: a region drawn
     * on top answers on top, and <b>the first region that covers the pixel owns it</b> whether or
     * not it has anything to say. Only when none does are the cut strings consulted.
     */
    private void tooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!hit.contains(mouseX, mouseY)) {
                continue;
            }
            if (dwelt(hit.x() * 31 + hit.y()) && hit.tooltip() != null) {
                Draw.tooltip(graphics, font, wrapTooltip(hit.tooltip()), mouseX, mouseY,
                    width, height);
            }
            return;
        }
        for (Overflow cut : overflows) {
            if (!cut.contains(mouseX, mouseY)) {
                continue;
            }
            // Offset so a cut string and a control sharing a corner are two different rests.
            if (dwelt(cut.x() * 31 + cut.y() + 7)) {
                Draw.tooltip(graphics, font, wrapTooltip(List.of(cut.full())), mouseX, mouseY,
                    width, height);
            }
            return;
        }
        hoverOn = -1;
    }

    /** True once the cursor has been on this region long enough to have meant it. */
    private boolean dwelt(int where) {
        if (where != hoverOn) {
            hoverOn = where;
            hoverSince = net.minecraft.Util.getMillis();
        }
        return net.minecraft.Util.getMillis() - hoverSince >= DWELL_MS;
    }

    @Override
    public void removed() {
        super.removed();
        carried = ItemStack.EMPTY;
        com.neryos.workbay.client.LinkHighlight.clear();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The block preview takes the press first: inside it a press starts a turn, and only a
        // press that never turned into one counts as a click on a face.
        if (current != null && current.mousePressed(mouseX, mouseY, button)) {
            return true;
        }
        if (renaming != null && !renaming.isMouseOver(mouseX, mouseY)) {
            endRename(true);
            return true;
        }
        // Right-click is the same control asked for the previous value instead of the next. Every
        // control in the mod that cycles reads it, and the ones that do not simply ignore it, which
        // is why it is one flag here rather than a second handler on each.
        if (button == 0 || button == 1) {
            back = button == 1;
            // Reverse order, so a control drawn on top of another wins the click the way it looks.
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(mouseX, mouseY)) {
                    hits.get(i).onClick().run();
                    back = false;
                    playClick();
                    return true;
                }
            }
            back = false;
            // A click that hit nothing, while carrying, puts the thing down. Without this the
            // only way out of a carry is closing the screen, which is how a cursor gets stuck.
            if (!carried.isEmpty()) {
                carry(ItemStack.EMPTY);
                playClick();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (current != null && current.mouseDragged(dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (current != null && current.mouseReleased(mouseX, mouseY)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (current != null && current.scrolled(mouseX, mouseY, deltaY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    // --------------------------------------------------------------- actions

    public void send(WorkbayAction action) {
        send(action, 0, Optional.empty(), Optional.empty());
    }

    public void send(WorkbayAction action, long arg) {
        send(action, arg, Optional.empty(), Optional.empty());
    }

    /**
     * The same, for a control that dispatches from outside the hit list and so has to say for
     * itself which way it was clicked. The preview cube is the only one: its faces are decided on
     * <em>release</em>, because a press there may turn the block instead, and by then the flag the
     * hit list sets is long gone.
     */
    public void sendStepped(WorkbayAction action, long arg, boolean backwards) {
        back = backwards;
        send(action, arg);
        back = false;
    }

    public void send(WorkbayAction action, UUID link) {
        send(action, 0, Optional.of(link), Optional.empty());
    }

    /** An action that needs both a number and a row: the filter slot is the only one so far. */
    public void send(WorkbayAction action, long arg, UUID link) {
        send(action, arg, Optional.of(link), Optional.empty());
    }

    public void sendText(WorkbayAction action, String text) {
        send(action, 0, Optional.empty(), Optional.of(text));
    }

    /**
     * A number and a name together: which room, and which biome. The name rather than a position
     * in a list, because the list the player clicked was filtered by a search box and the server's
     * is not.
     */
    public void sendText(WorkbayAction action, long arg, String text) {
        send(action, arg, Optional.empty(), Optional.of(text));
    }

    /** A name for one row rather than for the screen's own selection. */
    public void sendText(WorkbayAction action, String text, UUID link) {
        send(action, 0, Optional.of(link), Optional.of(text));
    }

    private void send(WorkbayAction action, long arg, Optional<UUID> link, Optional<String> text) {
        PacketDistributor.sendToServer(
            new ActionPacket(menu.containerId, action, arg, link, text, back));
        // Applied here as well so the selection tracks the click rather than the round trip.
        if (action == WorkbayAction.SELECT_BAY) {
            menu.setSelectedBayClientSide((int) arg);
        }
    }

    public static Component gui(String key, Object... args) {
        return WorkbayLang.gui(key, args);
    }
}
