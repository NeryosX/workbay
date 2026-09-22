package com.neryos.workbay.client.screen;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbaySnapshot;
import com.neryos.workbay.world.WorkbayRecord.Connector;
import com.neryos.workbay.world.BayGeometry;
import com.neryos.workbay.world.FaceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Screen 1 - Bays. SPEC.md §4.
 *
 * <p><b>Its job at rest is to answer "is anything wrong?" before the player reads anything.</b> The
 * problem count in the header and the status pip on each bay do that; everything else is what they
 * look at once the answer is yes.
 *
 * <p><b>The height is not fixed at SPEC.md §4's 268.</b> At 268 the LINKS list gets ninety pixels,
 * which is three rows - the exact fault that got the old section replaced, in the same section that
 * requires the list to stay readable at thirty. It wants 316. But Minecraft only ever guarantees a
 * 240-tall scaled canvas, so a fixed 268 already runs off the bottom at a high GUI scale and 316 is
 * worse. The page therefore takes whatever the window has between 240 and 316, and the bay rack's
 * pitch and the number of visible rows are derived from that rather than written down.
 */
class BaysPage extends WorkbayPage {

    private static final int WIDTH = 320;

    // SPEC.md §4 starts at 268 tall and Minecraft only guarantees 240, so the page takes what the
    // window has (WorkbayScreen#panelHeight, shared by every page) and the rack pitch and the row
    // count follow from it.

    private static final int RACK_X = 8;
    private static final int RACK_Y = 50;

    private static final int LINKS_Y_FROM_BOTTOM = 146;

    /**
     * The highest a filter panel that has outgrown the links area may start: just under the
     * summary line, which is the first row of the page that is not chrome.
     */
    private static final int PANEL_TOP = 48;
    private static final int ROW_PITCH = 20;
    /** The filter panel nine slots. Its own name, because it is not the list row pitch. */
    private static final int SLOT_PITCH = 20;
    private static final int LIST_X = 40;
    private static final int LIST_W = 268;
    /**
     * Where a row's status column starts, and how wide it is.
     *
     * <p><b>54, because that is what the longest short status measures.</b> It was cut to 46 to
     * buy the name beside it twelve pixels, on the written claim that "No machine" is forty-five
     * - it is fifty-four, and three of the eleven statuses came out clipped: "No mach...",
     * "Off-worl..." and "No targe...". The clipped ones still carry the full string in a tooltip,
     * which is why it survived a release: the fault only shows on the row itself.
     *
     * <p>The arithmetic: every glyph's
     * advance is its width plus one, so N6 o6 space4 m6 a6 c6 h6 i2 n6 e6. Nothing here is a
     * guess, and the guess is what shipped. Found in play, reading a row. OPEN_ISSUES #58.
     */
    private static final int STATUS_X = 130;
    private static final int STATUS_W = 54;

    private final int height;
    private final int rackPitch;
    private final int slot;
    private final int linksY;
    private final int rowY;
    private final int rows;

    /**
     * The faces column. 80 wide rather than 68, and everything in it derived from that: at 68 the
     * empty-bay hint wrapped to four lines in a column three words wide, the three type buttons
     * stopped two pixels short of the well above them, and the turn hint and the in/out key sat on
     * hardcoded x's that matched neither. The column read unfinished next to the rest of the screen
     * because it was the one part of it laid out by hand.
     */
    private static final int FACES_X = 232;
    /**
     * Where the machine column stops, and it is the <b>rule</b>, not the faces column.
     *
     * <p>{@code columns()} draws the divider at {@code FACES_X - 8}, two pixels wide. Everything in
     * the middle column was measured against four short of {@code FACES_X} instead, which is four
     * pixels <em>past</em> that divider -- so a long machine name, and the power figure
     * right-aligned beside its bar, both ran over the line and onto the faces column behind it.
     * Reported from play on an Industrial Foregoing Latex Processing Unit, where the name fits and
     * the bar and its figure do not. OPEN_ISSUES #64. Two clear of the rule, which is what every
     * other column on this screen leaves.
     */
    private static final int MACHINE_RIGHT = FACES_X - 10;
    private static final int WELL_X = FACES_X;
    private static final int WELL_W = 80;
    private static final int WELL_Y = 76;
    private static final int WELL_H = 62;
    /** Three type buttons, filling the column exactly: 3 x 24 on a 28 pitch is the well's width. */
    private static final int CUBE_CX = WELL_X + WELL_W / 2;
    private static final int CUBE_CY = 106;
    private static final int CUBE_SIZE = 30;

    /** The header's power readout: right-aligned here, and where the counters beside it must stop. */
    private static final int POWER_X = 246;
    /**
     * The <em>floor</em> of the readout's width, not the width. 58 was measured against
     * {@code 0 / 100.0k} and shipped cutting a full buffer to {@code 100.0k / 10...}: the string
     * grows with the number in it, so any constant here is a guess that a bigger buffer falsifies.
     * The bay rows next to it have measured their own figure since the day a Mekanism cube arrived;
     * the header was the one power figure still on a hardcoded box. Kept as a minimum so the
     * counters to its left do not shuffle every time the buffer crosses a digit.
     */
    private static final int POWER_W = 58;


    /** Client-side view state: the list's filter, sort, scroll and which row's gear is open. */
    private enum Filter { THIS_BAY, ALL_BAYS, PROBLEMS }

    /** ADDED is first and is the default: a list that reorders itself is a list you cannot learn. */
    private enum Sort { ADDED, BAY, TYPE, STATUS }

    /**
     * What Copy holds. Static and client-side on purpose: the point of copying a bay is pasting it
     * onto the next seven, and the server is told the whole config in the paste action itself, so
     * there is no second clipboard anywhere to disagree with this one.
     */
    @org.jetbrains.annotations.Nullable
    private static FaceConfig copied;

    // This bay, in the order links were made. The list is per bay the way XNet's channels are per
    // controller-block: a link belongs to exactly one bay, and the screen you configure a bay on
    // should show that bay's links and nothing else. ALL_BAYS is one click away on the funnel.
    private static Filter filter = Filter.THIS_BAY;
    private static Sort sort = Sort.ADDED;
    private static BusConfig.Resource faceType = BusConfig.Resource.ITEM;

    /** Kept between openings, so the angle a player turned a machine to is still there next time. */
    private static final BlockPreview PREVIEW = new BlockPreview();

    /**
     * Which bay and machine {@link #PREVIEW}'s angle belongs to. Keeping the angle is right for the
     * machine the player turned and wrong for the next one: racking a block, or switching bays, was
     * showing whatever side the last one had been left on, which for a chest is its back. The angle
     * now survives a re-open and resets the moment the thing in the well is a different thing.
     */
    @org.jetbrains.annotations.Nullable
    private static String previewSubject;

    private int scroll;

    /**
     * True while the list is showing what you could attach to this bay rather than what already is.
     *
     * <p>A picker, not a popup: it reuses the row list, its scrolling and its hit testing whole. A
     * floating panel over the list would need its own version of all three, and would cover the
     * bay rack the player is picking for.
     */
    private static boolean adding;

    /**
     * The picker's two lists. A Connector already standing in the world and a bay with no wire at
     * all are attached the same way and end up as the same kind of link, but they are found in
     * completely different ways -- one by walking your base, one by looking at this rack -- and
     * mixing them into one scrolling list made both harder to find.
     */
    private enum AddTab { CONNECTORS, BAYS }

    private static AddTab addTab = AddTab.CONNECTORS;

    /**
     * What is ticked in the picker, so several things can be attached in one go. Ticking is the
     * whole reason the picker has a confirm button: attaching links one at a time closed the list
     * after each one, which for a bay that needs six of them is five needless round trips.
     */
    private static final java.util.Set<UUID> pickedConnectors = new java.util.LinkedHashSet<>();
    private static final java.util.Set<Integer> pickedBays = new java.util.LinkedHashSet<>();

    /**
     * Which link's filter is open over the list, if any. The picker's idiom, for the picker's
     * reason: the panel reuses the well, its geometry and its hit testing whole, and a floating
     * window would cover the row it belongs to. Client-side, like every other view state here.
     */
    @org.jetbrains.annotations.Nullable
    private static UUID editingFilter;

    /**
     * A room's settings, drawn over this page from the swatch on a room bay's panel. The window
     * takes every input first while it is up, so its scrim is what closes it.
     */
    private final RoomWindow window;

    BaysPage(WorkbayScreen screen) {
        super(screen);
        window = new RoomWindow(screen);
        height = screen.panelHeight();
        // Eight bay slots always fit, however short the window is; they lose pitch, not slots.
        rackPitch = Math.clamp((height - RACK_Y - 12) / BayGeometry.MAX_BAYS, 20, 26);
        slot = rackPitch - 2;
        linksY = height - LINKS_Y_FROM_BOTTOM < 170 ? 170 : height - LINKS_Y_FROM_BOTTOM;
        // The header row's tallest control (Pair, 18px) reached to linksY+18; rowY-4 is the box's
        // own top edge, so the old +20 left only 2px of clearance and visually touched. Reported
        // from play as "no space between Pair/Sort/Filter and the list."
        rowY = linksY + 28;
        rows = Math.max(2, (height - rowY - 14) / ROW_PITCH);
    }

    @Override
    int width() {
        return WIDTH;
    }

    @Override
    int height() {
        return height;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        // Where the filter panel will stand, worked out before anything it stands over is drawn.
        // Two things on this page are not flat: the face cube is a block model at z=200 with its
        // markers at 400, and the racked machine is an item sprite, which draws at z=150 whatever
        // the order. Both came up through the panel's title band, one photograph each. The panel
        // cannot simply be lifted -- tooltips are at 400, the hover ring at 0 -- so the things
        // under it are what give way: the cube is not drawn at all, the sprite is cut at the
        // panel's top edge, and both need to know where that edge is before they draw.
        filterTop = Integer.MAX_VALUE;
        if (editingFilter != null) {
            snapshot().links().stream()
                .filter(l -> l.config().id().equals(editingFilter)).findFirst()
                .ifPresent(link -> filterTop = filterBlockY(filterBlockH(link.config())));
        }
        header(g, mouseX, mouseY, "WORKBAY");
        summary(g, mouseX, mouseY);
        columns(g);
        rack(g, mouseX, mouseY);
        // Everything the panel stands over is cut at its top edge, not only the sprite that was
        // photographed: half the buttons on the machine row are item sprites too on a build
        // without the drawn icons, and each of them would come up through the band the same way.
        boolean under = filterTop < y(height);
        if (under) {
            g.enableScissor(x(0), y(0), x(WIDTH), filterTop);
        }
        machine(g, mouseX, mouseY);
        faces(g, mouseX, mouseY);
        if (under) {
            g.disableScissor();
        }
        links(g, mouseX, mouseY);
        // Last, so its hit boxes win: the screen dispatches clicks in reverse registration order,
        // which is exactly "whatever is drawn on top".
        window.render(g, mouseX, mouseY);
    }

    /** The room in the selected bay, or null for a machine or nothing. */
    @org.jetbrains.annotations.Nullable
    private WorkbaySnapshot.Room roomHere() {
        return snapshot().rooms().stream()
            .filter(room -> room.index() == screen.selectedBay()).findFirst().orElse(null);
    }

    /** What a room is called on this panel: the label the record carries (#114). */
    private String roomName(WorkbaySnapshot.Room room) {
        return room.name();
    }

    @Override
    boolean escaped() {
        return window.escaped();
    }

    @Override
    boolean entered() {
        return window.entered();
    }

    /** The open filter panel's top edge in screen pixels, or MAX_VALUE while none is open. */
    private int filterTop = Integer.MAX_VALUE;

    private static int filterBlockH(BusConfig config) {
        // A chemical filter has no items to pick from -- it is named off the tank (#41) -- so it
        // is the one resource whose panel carries no inventory.
        boolean pickable = config.resource() != BusConfig.Resource.CHEMICAL;
        int panelH = STEP_H + 10 + SLOT_PITCH
            + (config.resource() == BusConfig.Resource.CHEMICAL ? 40 : 16)
            + (pickable ? INVENTORY_H : 0);
        return 18 + 6 + panelH;
    }

    /**
     * <b>Centred in the links area while it fits, and standing above it when it does not.</b>
     * With the player's inventory in it the panel is a hundred and seventy pixels and the list
     * area is a hundred and thirty, so centring put the last row of the inventory off the bottom
     * of the page -- photographed. It is a dialog and a dialog may cover the page it is over, so
     * when it outgrows the list it starts under the summary line instead and uses the room the
     * rack and the machine panel were using.
     */
    private int filterBlockY(int blockH) {
        int listTop = y(linksY);
        int listH = 18 + rows * ROW_PITCH + 12;
        return blockH <= listH ? listTop + (listH - blockH) / 2
            : Math.max(y(PANEL_TOP), y(height - 10) - blockH);
    }

    // ------------------------------------------------------ header y 30..44

    private void summary(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();
        var font = screen.font();
        int used = (int) snap.bays().stream()
            .filter(bay -> bay.hosted().isPresent()).count();

        // A bar with no figure beside it reads as broken, and an empty one reads as broken twice
        // over, so with no capacity at all the words replace the bar entirely (SPEC.md §7).
        //
        // <b>And with nothing to spend it on, neither is drawn.</b> Both power knobs ship at zero
        // (WorkbayConfig), so on a stock server this buffer is filled by nobody, spent by nothing
        // and never moves -- a column on the header saying "0 / 100.0k" about a charge that is
        // not being made. The owner's call: while the value is zero the word is not on the screen.
        // The counters take the width back, which is the rest of this method's whole argument.
        boolean powered = snap.charged() && snap.energyCapacity() > 0;
        String power = !snap.charged() ? ""
            : Draw.compact(snap.energy()) + " / " + Draw.compact(snap.energyCapacity());
        // Measured, not assumed -- see POWER_W. The counters are packed against this, so they are
        // told the same number the readout is drawn with and the two can never disagree.
        int powerW = power.isEmpty() ? 0 : Math.max(POWER_W, Draw.width(font, power));

        // Three counters, packed left to right against where the power readout starts, rather than
        // sitting on hardcoded pitches with guessed widths. Guessed widths were wrong twice on one
        // line: "no problems" arrived as "no proble..." in a 60-wide box, and "128 links" does not
        // fit 46 either. Packed, each one has exactly what it needs and the last one has the rest,
        // which is the only version of this that cannot be wrong for a count nobody tried.
        int textX = x(8);
        int limit = power.isEmpty() ? x(WIDTH - 8) : x(POWER_X) - powerW - 6;
        int cursor = textX;
        if (!snap.bays().isEmpty()) {
            cursor = counter(g, WorkbayScreen.gui("count.bays", used, snap.bayCapacity()),
                cursor, limit, Draw.TEXT_DIM);
        }
        int links = snap.links().size();
        cursor = counter(g, WorkbayScreen.gui(links == 1 ? "count.links.one" : "count.links", links),
            cursor, limit, Draw.TEXT_DIM);

        // The count and the list have to agree, and the list is filtered to one bay by default - so
        // the count is a control, not a label: clicking it shows exactly the rows it is counting.
        // Without that the header can honestly say "1 problem" while every visible row looks fine.
        int problems = snap.problems();
        String problemText = WorkbayScreen.gui(problems == 0 ? "count.problems.none"
            : problems == 1 ? "count.problems.one" : "count.problems", problems).getString();
        boolean problemHover = problems > 0
            && screen.hovered(cursor, y(29), Draw.width(font, problemText) + 2, 12, mouseX, mouseY);
        text(g, problemText, cursor, y(31), limit - cursor,
            problems == 0 ? Draw.TEXT_FAINT : problemHover ? Draw.TEXT : Draw.RED);
        if (problems > 0) {
            screen.hit(cursor, y(29), Draw.width(font, problemText) + 2, 12, () -> {
                filter = Filter.PROBLEMS;
                adding = false;
                scroll = 0;
            }, WorkbayScreen.gui("links.problems", problems),
                WorkbayScreen.gui("links.problems.tip"));
        }

        if (!power.isEmpty()) {
            textRight(g, power, x(POWER_X), y(31), powerW,
                powered ? Draw.TEXT_DIM : Draw.TEXT_FAINT);
        }
        if (powered) {
            Draw.bar(g, x(250), y(29), 44, 9, snap.energy(), snap.energyCapacity(), Draw.ENERGY);
            screen.hit(x(250), y(29), 44, 9, () -> { },
                WorkbayScreen.gui("power", Draw.exact(snap.energy()),
                    Draw.exact(snap.energyCapacity())),
                WorkbayScreen.gui("power.tip"));
        }

        // No room code on screen. SPEC.md §14: a network belongs to a player, and the Workbay finds
        // it again by owner when it is rebuilt, so there is nothing here for a player to read out,
        // type in, or lose.
        // The separator between the header band and the working area.
        g.fill(x(6), y(44), x(WIDTH - 6), y(45), Draw.EDGE_DARK);
    }

    /**
     * One header counter, given exactly the width it needs, and clamped so it can never run into
     * the readout to its right.
     *
     * @return where the next counter starts
     */
    private int counter(GuiGraphics g, Component label, int px, int limit, int colour) {
        String s = label.getString();
        int room = Math.min(screen.font().width(s), limit - px);
        text(g, s, px, y(31), room, colour);
        return px + room + 6;
    }


    /**
     * The three columns this screen is actually in - the rack, the bay, the faces - said out loud.
     *
     * <p>Nothing here is new information: the rack has always been on the left and the cube always
     * on the right. But every one of them was drawn straight onto the same flat panel, so the eye
     * had to infer the grouping from where things happened to land, and a screen that dense reads
     * as one heap of controls. A hairline between them and a recess behind the rack costs three
     * fills and is the single largest difference vanilla shapes can make here.
     */
    private void columns(GuiGraphics g) {
        int topY = y(HEADER_H + 18);
        int bottomY = y(linksY - 6);
        rule(g, x(46), topY, bottomY);
        rule(g, x(FACES_X - 8), topY, bottomY);
        // The rack, in a recess of its own, so the bays read as one thing rather than as eight.
        //
        // <b>The recess ends at the capacity the header prints, not at eight.</b> It used to hold
        // all eight, so a network reading "2 / 3 bays" was drawn as one box of eight slots and
        // the picture disagreed with the number beside it -- five of them washed out, which at
        // guiScale 3 is a shade, not a sentence. Now the recess holds exactly the bays this
        // network has and the ones it has not bought stand outside it: still drawn, because what
        // an Expansion Plate buys is worth seeing, and no longer counted.
        Draw.well(g, x(RACK_X - 3), y(RACK_Y - 4), slot + 10,
            snapshot().bayCapacity() * rackPitch + 6);
        // And the line under the bay's own details.
        g.fill(x(50), y(118), x(FACES_X - 12), y(119), 0x18FFFFFF);
    }

    /** A vertical hairline: one dark pixel and one light, the same edge every panel here has. */
    private void rule(GuiGraphics g, int px, int topY, int bottomY) {
        g.fill(px, topY, px + 1, bottomY, Draw.EDGE_DARK);
        g.fill(px + 1, topY, px + 2, bottomY, 0x14FFFFFF);
    }

    // ------------------------------------------------------------- bay rack

    private void rack(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();
        for (int index = 0; index < 8; index++) {
            int px = x(RACK_X + 2);
            int py = y(RACK_Y + index * rackPitch);
            WorkbaySnapshot.Bay bay = snap.bay(index);
            boolean locked = bay.state() == WorkbaySnapshot.State.LOCKED;
            boolean selected = index == screen.selectedBay();
            boolean hover = screen.hovered(px, py, slot, slot, mouseX, mouseY);

            Draw.slot(g, px, py, slot, slot);
            if (hover && !locked) {
                g.fill(px + 1, py + 1, px + slot - 1, py + slot - 1, 0x33FFFFFF);
            }

            ItemStack icon = iconFor(bay.hosted());
            if (!icon.isEmpty()) {
                // The hosted machine's own item, so a bay is identified at a glance (SPEC.md §4).
                //
                // <b>Sized to the slot, never drawn at its natural sixteen.</b> A rack slot is
                // {@code rackPitch - 2}, which is 18 on a short window, so a 16px sprite inset by
                // four ran two pixels past the slot's own right edge and onto the rule beside it.
                // OPEN_ISSUES #61 -- and it was photographed rather than reasoned out, because a
                // sprite is drawn on the item renderer's own layer and always wins whatever it
                // lands on, so nothing on the screen looks broken; it looks like the thing
                // underneath was never drawn.
                int side = Math.min(16, slot - 4);
                WBIcons.sprite(g, icon, px + (slot - side) / 2, py + (slot - side) / 2, side, true);
            } else if (!locked) {
                // The same dashes as the big slot, for the same reason: a bay you may fill and a
                // bay you may not looked identical on a fresh Workbay.
                Draw.dashed(g, px + 3, py + 3, slot - 6, slot - 6, Draw.TEXT_FAINT);
            }
            if (locked) {
                // The shared wash, not a bespoke 60% black: at 0x99 the five bays a new network
                // has not bought yet read as five holes punched through the panel, which is what
                // the rack looked like the first time anybody photographed it. SPEC.md §7 -- a
                // slot is never black, and "unavailable" looks the same everywhere.
                Draw.disabled(g, px + 1, py + 1, slot - 2, slot - 2);
            }
            // The 5x5 status pip, top-left, inside the slot. It <b>flashes once when it changes</b>:
            // a bay going amber is five pixels changing hue on a rack of eight, which a player
            // looking anywhere else on the screen never sees. A flash is seen out of the corner of
            // an eye, which is the only place this pip is ever read from.
            //
            // <b>Above the sprite, and it has to say so.</b> Drawing it first is not enough: an
            // item goes to the item renderer's own layer at Z 150 whatever the draw order was, so
            // a pip filled before the machine's sprite is a pip the sprite covers. Four of its
            // twenty-five pixels were left, on the rack a player reads bay status off.
            // OPEN_ISSUES #61; the notice bar in WorkbayScreen carries the same note and the same
            // fix, which is what named the cause here.
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(px + 2, py + 2, px + 7, py + 7, pipColour(bay.state()));
            float changed = Draw.pulse("pip" + index, bay.state().ordinal(), 0.5F);
            if (changed > 0) {
                g.fill(px + 1, py + 1, px + 8, py + 8, Draw.flash(changed * 0.7F));
            }
            g.pose().popPose();

            int captured = index;
            screen.hit(px, py, slot, slot, () -> screen.send(WorkbayAction.SELECT_BAY, captured),
                bayTooltip(bay));
        }

        // The selection marker, drawn once and <b>slid</b> to the bay that now owns it rather than
        // redrawn beside it. Eight identical slots in a column is exactly the arrangement where a
        // marker that jumps leaves the player checking which one moved; one that travels is read
        // without being looked at.
        float at = Draw.approach("sel", screen.selectedBay(), 18.0F);
        int marker = y(RACK_Y) + Math.round(at * rackPitch);
        g.fill(x(RACK_X - 3), marker, x(RACK_X - 1), marker + slot, Draw.SELECT);
    }

    /** The rename subject for a bay title. A link is its own UUID; an index needs a namespace. */
    private static String bayRename(int index) {
        return "bay:" + index;
    }

    private Component[] bayTooltip(WorkbaySnapshot.Bay bay) {
        Component name = bay.name().isEmpty()
            ? bay.hosted().map(BaysPage::displayName).orElse(WorkbayScreen.gui("bay.empty"))
            : Component.literal(bay.name());
        return switch (bay.state()) {
            case LOCKED -> new Component[] {
                WorkbayScreen.gui("bay.locked").copy().withStyle(ChatFormatting.GRAY),
                WorkbayScreen.gui("bay.locked.tip") };
            case EMPTY -> new Component[] {
                WorkbayScreen.gui("bay.n", bay.index() + 1), WorkbayScreen.gui("bay.empty.tip") };
            case INERT -> new Component[] {
                name, WorkbayScreen.gui("bay.inert").copy().withStyle(ChatFormatting.GOLD) };
            default -> new Component[] {
                name, WorkbayScreen.gui("bay." + bay.state().name().toLowerCase(java.util.Locale.ROOT)) };
        };
    }

    private static int pipColour(WorkbaySnapshot.State state) {
        return switch (state) {
            case RUNNING -> Draw.GREEN;
            case IDLE -> Draw.BLUE;
            case INERT -> Draw.AMBER;
            case LOCKED -> Draw.EDGE_DARK;
            case EMPTY -> Draw.GREY;
        };
    }

    // ------------------------------------------- machine block x 50..220

    private void machine(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();
        WorkbaySnapshot.Bay bay = snap.bay(screen.selectedBay());
        var font = screen.font();

        int slotX = x(50);
        int slotY = y(52);
        Draw.slot(g, slotX, slotY, 40, 40);
        ItemStack icon = iconFor(bay.hosted());
        if (!icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(slotX + 4, slotY + 4, 0);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
        boolean empty = bay.hosted().isEmpty();
        if (empty && bay.state() != WorkbaySnapshot.State.LOCKED) {
            // <b>A dashed rim and a plus.</b> The slot was a plain recess, which everywhere else on
            // this screen means "a thing lives here" -- so the one square you are meant to click
            // looked exactly like the eight you are not. Dashes say "a thing goes here" and the
            // plus says how, which is what the sentence beside it is spelling out.
            Draw.dashed(g, slotX + 3, slotY + 3, 34, 34, Draw.TEXT_DIM);
            WBIcons.draw(g, WBIcons.PLUS, slotX + 14, slotY + 14, Draw.TEXT_DIM);
            // The whole insert flow, in the one place it happens.
            screen.hit(slotX, slotY, 40, 40, () -> screen.send(WorkbayAction.RACK),
                WorkbayScreen.gui("bay.empty"), WorkbayScreen.gui("bay.rack.tip"));
            if (screen.hovered(slotX, slotY, 40, 40, mouseX, mouseY)) {
                g.fill(slotX + 1, slotY + 1, slotX + 39, slotY + 39, 0x33FFFFFF);
            }
        }

        // Everything in this column is clamped to where the faces panel starts. A machine name is
        // whatever another mod called it, and an unclamped one runs across the cube and off the
        // panel entirely.
        int room = MACHINE_RIGHT - 98;
        WorkbaySnapshot.Room held = roomHere();
        if (held != null) {
            roomPanel(g, mouseX, mouseY, bay, held, room);
            return;
        }
        String shown = nameOf(bay, screen.selectedBay());
        if (!screen.renaming(bayRename(bay.index()))) {
            text(g, shown, x(98), y(56), room, Draw.TEXT);
        }

        if (bay.energyCapacity() > 0) {
            // The bar stops where the figures start rather than at a fixed 84. At 84 it ran twelve
            // pixels under a Mekanism cube's "0 / 1600.0k" - invisible while an empty bar was black
            // inside, and plain the moment the empty part got its tint.
            String power = Draw.compact(bay.energy()) + " / " + Draw.compact(bay.energyCapacity());
            int barW = Math.max(20, MACHINE_RIGHT - Draw.width(font, power) - 6 - 98);
            Draw.bar(g, x(98), y(68), barW, 9, bay.energy(), bay.energyCapacity(), Draw.ENERGY);
            textRight(g, power, x(MACHINE_RIGHT), y(69), room - barW - 6, Draw.TEXT_DIM);
            screen.hit(x(98), y(68), barW, 9, () -> { },
                WorkbayScreen.gui("power", Draw.exact(bay.energy()),
                    Draw.exact(bay.energyCapacity())),
                WorkbayScreen.gui("power.machine.tip"));
        } else if (!empty) {
            // A machine with no energy handler. The old placeholder was a bare dash floating
            // beside an empty bar, which read as a rendering fault rather than as a fact.
            text(g, WorkbayScreen.gui("power.none"), x(98), y(69), room, Draw.TEXT_FAINT);
        }

        // <b>A fresh Workbay is an empty rack, and nothing on it said so.</b> The instruction
        // existed only as the slot's tooltip, which has to be found by hovering a 40x40 square
        // that looks like every other recess on the screen -- and the one player who most needs
        // it is the one who does not yet know there is anything to hover. It goes in the band the
        // power bar leaves free while a bay is empty, so nothing moves when a machine arrives:
        // the bar takes the line back and the hint is gone, which is the whole of "goes away once
        // there is anything to show". The status line under it is skipped while it shows, because
        // "Empty bay" said the same thing in fewer, less useful words.
        boolean hint = empty && bay.state() != WorkbaySnapshot.State.LOCKED;
        if (hint) {
            wrapped(g, WorkbayScreen.gui("bay.rack.hint"), x(98), y(66), room, Draw.TEXT_DIM);
        }

        // The redstone mode shares this line with the status, right-aligned, and it is the short
        // form: "Redstone: without a signal" is 156 pixels on a line 130 wide and ran straight
        // through the status text. The long form is the button's tooltip title.
        String mode = bay.redstone() == com.neryos.workbay.world.RedstoneMode.ALWAYS ? ""
            : WorkbayScreen.gui("redstone.short." + bay.redstone().getSerializedName()).getString();
        int modeRoom = mode.isEmpty() ? 0 : Math.min(Draw.width(font, mode), room / 2);
        if (!mode.isEmpty()) {
            textRight(g, mode, x(MACHINE_RIGHT), y(82), modeRoom, Draw.TEXT_DIM);
        }
        int statusRoom = room - (mode.isEmpty() ? 0 : modeRoom + 6);
        if (!hint) {
            text(g, statusLine(bay), x(98), y(82), statusRoom, statusColour(bay.state()));
        }

        // The button row at y=98, 20x20 on a 24px pitch. Bay View is the last of the six and is
        // real now: SPEC.md §4's rule is that a control whose screen is not built is hidden rather
        // than drawn faint, and the corollary is that it appears the moment the screen exists.
        //
        // All five are drawn glyphs, not item sprites. An item says what a thing is made of, not
        // what it does: a sheet of paper does not read as "copy" and a boot reads as nothing at
        // all. Item sprites are kept for content - the hosted machine, the bay rack, the three
        // resource types - which is the same line Mekanism and AE2 draw.
        boolean canEject = !empty;
        actionButton(g, mouseX, mouseY, x(50), WBIcons.EJECT, canEject, false,
            () -> screen.send(WorkbayAction.EJECT),
            WorkbayScreen.gui("button.eject"),
            WorkbayScreen.gui(canEject ? "button.eject.tip" : "button.eject.empty.tip"));
        if (!canEject) {
            // Greyed, and still answering (#130): a click on it said nothing at all, which is the
            // one thing a button may never do. The server's refusal is the line; registered after
            // the button, so this wins the click.
            screen.hit(x(50), y(98), 20, 20, () -> screen.send(WorkbayAction.EJECT),
                WorkbayScreen.gui("button.eject"), WorkbayScreen.gui("button.eject.empty.tip"));
        }
        boolean unlocked = bay.state() != WorkbaySnapshot.State.LOCKED;
        actionButton(g, mouseX, mouseY, x(74), WBIcons.RENAME, unlocked, false,
            () -> screen.beginRename(bayRename(bay.index()), x(98), y(53), room, 14, bay.name(),
                typed -> screen.sendText(WorkbayAction.SET_BAY_NAME, typed)),
            WorkbayScreen.gui("button.rename"), WorkbayScreen.gui("button.rename.tip"));
        // Lit only while the gate is actually in use, so the button says which state it is in
        // before the player hovers it.
        boolean gated = bay.redstone() != com.neryos.workbay.world.RedstoneMode.ALWAYS;
        actionButton(g, mouseX, mouseY, x(98), WBIcons.redstone(bay.redstone()), unlocked, gated,
            () -> screen.send(WorkbayAction.CYCLE_REDSTONE),
            WorkbayScreen.gui("redstone." + bay.redstone().getSerializedName()),
            WorkbayScreen.gui("redstone." + bay.redstone().getSerializedName() + ".tip"),
            NO_PALETTE, !unlocked ? Draw.TEXT_FAINT
                : WBIcons.redstoneLit(bay.redstone()) ? Draw.TEXT : Draw.TEXT_DIM);

        // Copy and paste. Eight bays running the same machine is the first complaint this mod will
        // get, and Mekanism answers it with a Configuration Card (SPEC.md §7).
        actionButton(g, mouseX, mouseY, x(122), WBIcons.COPY, true, false,
            () -> copied = bay.faces(),
            WorkbayScreen.gui("button.copy"), WorkbayScreen.gui("button.copy.tip"));
        actionButton(g, mouseX, mouseY, x(146), WBIcons.PASTE, copied != null, false,
            () -> screen.send(WorkbayAction.PASTE_BAY, copied.bits()),
            WorkbayScreen.gui("button.paste"),
            WorkbayScreen.gui(copied == null ? "button.paste.empty" : "button.paste.tip"));

        // The machine's own screen, and now the only way into one. <b>Bay View is gone</b>
        // (OPEN_ISSUES #65): a second inventory screen over a machine that already has one, asked
        // to be deleted three times. It could only ever show what a capability exposes -- never a
        // recipe mode, a side config or an upgrade slot -- so every machine worth opening was
        // opened through this button anyway, and the one beside it was a worse copy that had to be
        // kept correct against every foreign handler in the game.
        //
        // Two ways there, and the button says which one this click gives. Where the player stands
        // when both sides have the mixins on (SPEC.md §0); a trip into the bay when either does
        // not - still here, and still the answer for a host who wants nothing patched. Both sides,
        // because the client is the half that has to find a machine in a chunk it was never sent,
        // and it is the only one that knows its own file.
        boolean here = snapshot().remoteScreens()
            && com.neryos.workbay.remote.RemoteConfig.remoteScreensEnabled();
        actionButton(g, mouseX, mouseY, x(194), WBIcons.ENTER, !empty, false,
            () -> screen.send(WorkbayAction.ENTER_BAY, here ? 1 : 0),
            WorkbayScreen.gui(here ? "button.open" : "button.enter"),
            WorkbayScreen.gui(here ? "button.open.tip" : "button.enter.tip"));
    }


    /**
     * The panel of a bay holding a room: the room's name, size and biome, and its four controls
     * -- pull it out, rename it, its settings, and the door. SPEC.md §0: no faces, no redstone,
     * nothing to copy; a room's machines are reached through Connectors placed inside it.
     */
    private void roomPanel(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot.Bay bay,
        WorkbaySnapshot.Room held, int room) {
        boolean owned = snapshot().owned();
        java.util.UUID me = net.minecraft.client.Minecraft.getInstance().player == null ? null
            : net.minecraft.client.Minecraft.getInstance().player.getUUID();
        boolean mayEnter = owned || held.guests().stream().anyMatch(guest -> guest.id().equals(me));
        if (!screen.renaming(bayRename(bay.index()))) {
            text(g, roomName(held), x(98), y(56), room, Draw.TEXT);
        }
        text(g, WorkbayScreen.gui("room.size", held.interior()), x(98), y(69), room, Draw.TEXT_DIM);
        text(g, held.built() ? RoomWindow.biomeName(held.biome())
            : WorkbayScreen.gui("room.unentered"), x(98), y(82), room,
            held.built() ? Draw.TEXT_DIM : Draw.TEXT_FAINT);

        actionButton(g, mouseX, mouseY, x(50), WBIcons.EJECT, true, false,
            () -> screen.send(WorkbayAction.EJECT),
            WorkbayScreen.gui("button.pull"), WorkbayScreen.gui("button.pull.tip"));
        int index = held.index();
        actionButton(g, mouseX, mouseY, x(74), WBIcons.RENAME, owned, false,
            () -> screen.beginRename(bayRename(bay.index()), x(98), y(53), room, 14, held.name(),
                typed -> screen.sendText(WorkbayAction.SET_ROOM_NAME, index, typed)),
            WorkbayScreen.gui("button.rename"), WorkbayScreen.gui("rooms.rename.tip"));
        // The swatch: the room's colour, drawn as the colour itself, and the way into its settings.
        int swatchX = x(98);
        boolean hover = owned && screen.hovered(swatchX, y(98), 20, 20, mouseX, mouseY);
        Draw.button(g, swatchX, y(98), 20, 20, hover, window.bay() == index, owned);
        g.fill(swatchX + 5, y(103), swatchX + 15, y(113), 0xFF000000 | held.colour().tint());
        screen.hit(swatchX, y(98), 20, 20, owned ? () -> window.setOpen(index) : () -> { },
            WorkbayScreen.gui("rooms.settings"),
            owned ? WorkbayScreen.gui("rooms.colour",
                WorkbayScreen.gui("colour." + held.colour().getSerializedName()))
                : com.neryos.workbay.WorkbayLang.message("owner_only"));
        actionButton(g, mouseX, mouseY, x(194), WBIcons.ENTER, mayEnter, false,
            () -> screen.send(WorkbayAction.ENTER_ROOM, index),
            WorkbayScreen.gui("rooms.enter"),
            mayEnter ? WorkbayScreen.gui("rooms.enter.tip")
                : com.neryos.workbay.WorkbayLang.message("room_not_yours"));
        // What a room has instead of faces, across the width a machine's own details take.
        wrapped(g, WorkbayScreen.gui("faces.room"), x(50), y(126), FACES_X - 12 - 50,
            Draw.TEXT_FAINT);
    }

    /**
     * Three real sprites, shrunk and stacked, and nothing about them is tinted or redrawn.
     *
     * <p><b>Energy, fluid and chemical stay drawn glyphs</b> for the reason they always did: a
     * bolt, a drop and a flask each mean the category outright, and the sprites that stood in for
     * them meant something else - energy was a lump of coal and a fluid was a water bottle,
     * pictures of a thing that happens to be made of the stuff.
     *
     * <p><b>Items is the one that has no such glyph, so it uses the things themselves.</b> Nothing
     * means "items"; several different ones together do. A hand-drawn version of that shipped and
     * read as two dots on a slab at the size it is actually drawn - which is the argument for
     * the sprites: a player already knows what lapis, redstone and an ingot look like, so the icon
     * is recognised rather than decoded. The owner's call, and the layout was picked off a rendered
     * mock at 3x and 8x rather than by restarting the game.
     *
     * <p>The old objection was the pixel grid: a 16x16 sprite across twelve pixels puts every edge
     * between two of them. It is still true and it is visible in the mock; what the mock also
     * shows is that it does not matter here, because the three shapes are read by colour and
     * silhouette long before an edge is. The ingot's ten pixels sit at {@code y + 3}, so its ink
     * ends inside the box even though its transparent bottom row does not.
     */
    private void resourceIcon(GuiGraphics g, BusConfig.Resource resource, int x, int y) {
        switch (resource) {
            // <b>Items stayed the three sprites.</b> The owner's call, twice now: lapis, redstone and
            // an iron ingot piled in twelve pixels is the best of the four and a drawn cube was a
            // downgrade. The other three carry their own palettes, so the colour passed here is
            // only the brightness they are drawn at.
            case ITEM -> {
                WBIcons.sprite(g, LAPIS, x, y, 7, true);
                WBIcons.sprite(g, REDSTONE, x + 5, y, 7, true);
                WBIcons.sprite(g, INGOT, x + 1, y + 3, 10, true);
            }
            case FLUID -> WBIcons.draw(g, WBIcons.FLUID, x, y, Draw.TEXT);
            case ENERGY -> WBIcons.draw(g, WBIcons.ENERGY, x, y, Draw.TEXT);
            case CHEMICAL -> WBIcons.draw(g, WBIcons.CHEMICAL, x, y, Draw.TEXT);
        }
    }

    /** Held rather than built per draw: this runs three times a row, on every row, every frame. */
    private static final ItemStack LAPIS = new ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI);
    private static final ItemStack REDSTONE = new ItemStack(net.minecraft.world.item.Items.REDSTONE);
    private static final ItemStack INGOT = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT);


    /** A 20x20 button in the machine row: enabled draws lit and clicks, disabled draws sunken. */
    private void actionButton(GuiGraphics g, int mouseX, int mouseY, int px, String[] icon,
        boolean enabled, boolean lit, Runnable onClick, Component name, Component tip) {
        actionButton(g, mouseX, mouseY, px, icon, enabled, lit, onClick, name, tip, NO_PALETTE);
    }

    private static final int[] NO_PALETTE = {};

    /** The same, for an icon that carries its own colours. Only the redstone torch does. */
    private void actionButton(GuiGraphics g, int mouseX, int mouseY, int px, String[] icon,
        boolean enabled, boolean lit, Runnable onClick, Component name, Component tip,
        int[] palette) {
        actionButton(g, mouseX, mouseY, px, icon, enabled, lit, onClick, name, tip, palette,
            enabled ? Draw.TEXT : Draw.TEXT_FAINT);
    }

    /**
     * The same, for the one button whose icon carries a state the button's own lighting does not.
     *
     * <p>Redstone has four modes and only one of them is "ignore the signal", so the torch is
     * drawn full while a gate is in use and dimmed while it is not -- which is what EnderIO and
     * Mekanism both do, and what the lit button alone cannot say on a screen where several
     * buttons light.
     */
    private void actionButton(GuiGraphics g, int mouseX, int mouseY, int px, String[] icon,
        boolean enabled, boolean lit, Runnable onClick, Component name, Component tip,
        int[] palette, int iconArgb) {
        boolean hover = screen.hovered(px, y(98), 20, 20, mouseX, mouseY);
        Draw.button(g, px, y(98), 20, 20, hover && enabled, lit && enabled, enabled);
        WBIcons.draw(g, icon, px + 4, y(102), iconArgb, palette);
        screen.hit(px, y(98), 20, 20, enabled ? onClick : () -> { }, name, tip);
    }

    /**
     * The short form. The long one - "nothing can reach this machine on any face" - is a sentence,
     * and a sentence does not fit on a line 130 pixels wide; it lives in the bay's tooltip.
     */
    private Component statusLine(WorkbaySnapshot.Bay bay) {
        return WorkbayScreen.gui("bay.short." + bay.state().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static int statusColour(WorkbaySnapshot.State state) {
        return switch (state) {
            case RUNNING -> Draw.GREEN;
            case INERT -> Draw.AMBER;
            case LOCKED -> Draw.TEXT_FAINT;
            default -> Draw.TEXT_DIM;
        };
    }

    // ---------------------------------------------- faces x 232..300

    private void faces(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();
        WorkbaySnapshot.Bay bay = snap.bay(screen.selectedBay());
        if (roomHere() != null) {
            // A room has no faces, and the panel says so where there is room to say it (below,
            // in roomPanel) rather than in this column: eighty pixels wide, the sentence was six
            // words deep and the settings window opened on top of it.
            return;
        }

        // One button per resource this install actually has. The block shows one type at a time,
        // which is why a face can take items in and send energy out without the picture
        // contradicting itself.
        //
        // <b>Three without Mekanism, four with it.</b> It was hardcoded to three, so a chemical
        // link was the one kind the cube could not be set for -- and the row was there in the
        // packing all along. The width comes off the column rather than the count coming off the
        // truth: a fourth button that cannot be reached is worse than a narrower one.
        java.util.List<BusConfig.Resource> types =
            java.util.Arrays.stream(BusConfig.Resource.values())
                .filter(BusConfig.Resource::available).toList();
        int pitch = WELL_W / types.size();
        int typeW = pitch - 4;
        for (int i = 0; i < types.size(); i++) {
            BusConfig.Resource resource = types.get(i);
            int px = x(WELL_X + i * pitch);
            int py = y(52);
            boolean active = faceType == resource;
            boolean hover = screen.hovered(px, py, typeW, 18, mouseX, mouseY);
            Draw.button(g, px, py, typeW, 18, hover, active);
            resourceIcon(g, resource, px + (typeW - 12) / 2, py + 3);
            screen.hit(px, py, typeW, 18, () -> faceType = resource,
                WorkbayScreen.gui("faces." + resource.getSerializedName()),
                WorkbayScreen.gui("faces.tip"));
        }

        Draw.well(g, x(WELL_X), y(WELL_Y), WELL_W, WELL_H);

        // The machine's own block, at whatever angle the player has dragged it to. Not a drawn
        // cube: somebody configuring an Enrichment Chamber's faces needs to see one.
        BlockState state = blockFor(bay.hosted());
        String subject = bay.index() + ":" + bay.hosted().map(ResourceLocation::toString).orElse("");
        if (!subject.equals(previewSubject)) {
            previewSubject = subject;
            PREVIEW.reset();
        }
        if (state == null) {
            // Wrapped, and started low enough that two lines sit in the middle of the well
            // rather than pinned to its top edge with an empty half underneath.
            wrapped(g, WorkbayScreen.gui("faces.empty"), x(WELL_X + 4), y(WELL_Y + 20),
                WELL_W - 8, Draw.TEXT_FAINT);
        } else {
            // A filter panel standing over this well cuts the cube at its top edge, the way it
            // cuts the racked machine's sprite: render() scissors this whole call. The cube was
            // skipped outright for one build and left a well with nothing in it, which read as a
            // broken render rather than a dialog over a control.

            // A face marker projects to wherever its face's centre lands on screen, which at a
            // steep enough drag angle is genuinely outside the well - the marker is correct, the
            // well is just not wide enough to contain every angle. Scissored to the well's own
            // rectangle so a marker (or letter) never spills onto the panel around it, reported
            // from play as text sticking out of the cube.
            g.enableScissor(x(WELL_X), y(WELL_Y), x(WELL_X + WELL_W), y(WELL_Y + WELL_H));
            PREVIEW.render(g, state, x(CUBE_CX), y(CUBE_CY), CUBE_SIZE);
            PREVIEW.renderFaces(g, screen.font(), x(CUBE_CX), y(CUBE_CY), CUBE_SIZE,
                bay.faces(), faceType);
            g.disableScissor();
            // #130: the letters had no word behind them. Over a chip the hover names that face and
            // what it is set to; anywhere else in the well it names all six. The page takes the
            // press before any hit is asked, so this region answers hovers and nothing else. Not
            // while a filter panel stands over the well: that panel answers its own hovers.
            Direction under = inWell(mouseX, mouseY)
                ? PREVIEW.faceAt(mouseX, mouseY, x(CUBE_CX), y(CUBE_CY), CUBE_SIZE) : null;
            if (under != null) {
                screen.tip(x(WELL_X), y(WELL_Y), WELL_W, WELL_H,
                    WorkbayScreen.gui("links.face." + BlockPreview.faceKey(under)),
                    WorkbayScreen.gui("faces.role." + bay.faces().role(faceType, under).name()
                        .toLowerCase(java.util.Locale.ROOT)),
                    WorkbayScreen.gui("faces.tip"));
            } else if (editingFilter == null) {
                screen.tip(x(WELL_X), y(WELL_Y), WELL_W, WELL_H,
                    WorkbayScreen.gui("faces.drag"), WorkbayScreen.gui("faces.cube.tip"));
            }
        }

        // Under the well: the turn hint, then the in/out key, both inside the well's own width and
        // both starting from its own edges. They used to sit on hardcoded x's that were neither.
        textCentre(g, WorkbayScreen.gui("faces.drag").getString(), x(WELL_X + WELL_W / 2),
            y(WELL_Y + WELL_H + 4), WELL_W, Draw.TEXT_FAINT);

        int keyY = y(WELL_Y + WELL_H + 18);
        int half = WELL_W / 2;
        g.fill(x(WELL_X), keyY + 1, x(WELL_X + 6), keyY + 7, Draw.GREEN);
        text(g, "in", x(WELL_X + 10), keyY, half - 12, Draw.TEXT_DIM);
        g.fill(x(WELL_X + half), keyY + 1, x(WELL_X + half + 6), keyY + 7, Draw.BLUE);
        text(g, "out", x(WELL_X + half + 10), keyY, half - 12, Draw.TEXT_DIM);
    }

    private boolean inWell(double mx, double my) {
        // The cube is not drawn while a filter panel is open, so it must not take the press
        // either: the panel stands over this well, and a click on one of its controls there would
        // otherwise start a turn of a cube nobody can see.
        return editingFilter == null
            && mx >= x(WELL_X) && mx < x(WELL_X + WELL_W)
            && my >= y(WELL_Y) && my < y(WELL_Y + WELL_H);
    }

    @Override
    boolean mousePressed(double mouseX, double mouseY, int button) {
        if (window.isOpen()) {
            return window.mousePressed(mouseX, mouseY, button);
        }
        if ((button != 0 && button != 1) || !inWell(mouseX, mouseY)) {
            return false;
        }
        // Which button, kept until the release: the face is chosen on release (a press may become
        // a turn instead), and right-click means the previous value of the ring everywhere else in
        // this mod. The cube took only left-clicks, so its faces were the one cycling control in
        // the screen that could not be stepped backwards.
        turningWith = button;
        PREVIEW.press();
        return true;
    }

    /** Which mouse button started the turn that is in progress, or -1. */
    private int turningWith = -1;

    @Override
    boolean mouseDragged(double dragX, double dragY) {
        if (window.isOpen()) {
            return window.mouseDragged(dragX, dragY);
        }
        PREVIEW.drag(dragX, dragY);
        return false;
    }

    /** A press that never turned into a turn is a click on whichever face it landed on. */
    @Override
    boolean mouseReleased(double mouseX, double mouseY) {
        if (window.isOpen()) {
            return window.mouseReleased(mouseX, mouseY);
        }
        if (!PREVIEW.release() || !inWell(mouseX, mouseY)) {
            return false;
        }
        Direction face = PREVIEW.faceAt(mouseX, mouseY, x(CUBE_CX), y(CUBE_CY), CUBE_SIZE);
        if (face != null) {
            screen.sendStepped(WorkbayAction.CYCLE_FACE,
                faceType.ordinal() | (face.ordinal() << 4), turningWith == 1);
        }
        turningWith = -1;
        return true;
    }

    /**
     * The block to draw, turned to face the camera. A machine's default state is not reliably the
     * one whose front points north - some mods default south - and the preview has to open showing
     * the front of the thing, every time, or the player is configuring the back of a machine they
     * cannot identify.
     */
    @org.jetbrains.annotations.Nullable
    private static BlockState blockFor(Optional<ResourceLocation> id) {
        return id.map(BuiltInRegistries.BLOCK::get)
            .filter(block -> block != net.minecraft.world.level.block.Blocks.AIR)
            .map(net.minecraft.world.level.block.Block::defaultBlockState)
            .map(BlockPreview::facingCamera)
            .orElse(null);
    }

    // ------------------------------------------------------------- the list

    private void links(GuiGraphics g, int mouseX, int mouseY) {
        var font = screen.font();
        WorkbaySnapshot snap = snapshot();

        int pairX = x(LIST_X + LIST_W - 46);
        int addX = pairX - 40;

        if (editingFilter != null) {
            // The link may have been removed, or handed to another bay, while its filter was open.
            // Falling back to the list beats drawing a panel for a row that is not there.
            Optional<WorkbaySnapshot.Link> editing = snap.links().stream()
                .filter(l -> l.config().id().equals(editingFilter)).findFirst();
            if (editing.isPresent()) {
                filterPanel(g, mouseX, mouseY, editing.get());
                return;
            }
            closeFilter();
        }
        // Nothing outside the filter panel can be carrying: the carry is a picture of one of its
        // entries, and a cursor still holding one over the links list has no slot to put it in.
        screen.carry(net.minecraft.world.item.ItemStack.EMPTY);

        if (adding) {
            text(g, WorkbayScreen.gui("links.adding", screen.selectedBay() + 1),
                x(LIST_X + 4), y(linksY + 4), 48, Draw.TEXT);
            // Laid out left to right with the widths written down, because the first version put
            // the Bays tab and Back on top of each other: heading to LIST_X+52, the tabs, then
            // Back, then the confirm where Pair sits on the normal list.
            //
            // <b>"Connectors", not "Links".</b> The list is one row per Connector now, and a tab
            // naming the rows it does not list is the same lie the panel in the world used to
            // tell. The word is ten characters, so this tab is wider than the one beside it.
            tab(g, mouseX, mouseY, x(LIST_X + 56), 62, AddTab.CONNECTORS, "Connectors");
            tab(g, mouseX, mouseY, x(LIST_X + 120), 44, AddTab.BAYS, "Bays");

            // Confirm, where Pair sits on the normal list: the count is the whole point of the
            // checkboxes, so it is on the button rather than anywhere the eye has to hunt for it.
            int picked = pickedConnectors.size() + pickedBays.size();
            boolean confirmHover = screen.hovered(pairX, y(linksY), 46, 18, mouseX, mouseY);
            Draw.button(g, pairX, y(linksY), 46, 18, confirmHover, false);
            textCentre(g, picked == 0 ? "Add" : "Add " + picked, pairX + 23, y(linksY + 5), 42,
                picked == 0 ? Draw.TEXT_FAINT : Draw.TEXT);
            screen.hit(pairX, y(linksY), 46, 18, this::applyPicked,
                WorkbayScreen.gui("links.add.apply", picked),
                WorkbayScreen.gui("links.add.apply.tip"));

            // No icon beside the word: "Back" is 22px and the icon another 12, which did not fit
            // the 36 the button had and spilled over its right edge.
            int backX = x(LIST_X + 166);
            boolean backHover = screen.hovered(backX, y(linksY), 40, 18, mouseX, mouseY);
            Draw.button(g, backX, y(linksY), 40, 18, backHover, false);
            textCentre(g, "Back", backX + 20, y(linksY + 5), 36, Draw.TEXT);
            screen.hit(backX, y(linksY), 40, 18, this::closePicker,
                WorkbayScreen.gui("links.add.close"), WorkbayScreen.gui("links.add.tip"));

            candidates(g, mouseX, mouseY, snap);
            return;
        }

        // Whose links these are. The list is per bay, so a heading that does not say which bay is
        // the one thing that can make the whole screen lie to you.
        text(g, filter == Filter.THIS_BAY ? "LINKS · BAY " + (screen.selectedBay() + 1) : "LINKS",
            x(LIST_X + 4), y(linksY + 4), 70, Draw.TEXT);

        // Clear of the heading, which is no longer the fixed-width word "LINKS": it now carries the
        // bay number, and at LIST_X+44 the funnel sat on top of it.
        // Lit only when the list is *not* showing its usual contents. They were both hardcoded
        // active, which under a flat fill was invisible and under a gradient is two bright blue
        // buttons claiming to be switched on -- and hid the one thing they could usefully say.
        iconButton(g, mouseX, mouseY, x(LIST_X + 76), y(linksY), WBIcons.FILTER,
            filter != Filter.THIS_BAY,
            () -> filter = Filter.values()[Math.floorMod(
                filter.ordinal() + (screen.back() ? -1 : 1), Filter.values().length)],
            WorkbayScreen.gui("links.filter." + filter.name().toLowerCase(java.util.Locale.ROOT)),
            WorkbayScreen.gui("links.filter.tip"));
        iconButton(g, mouseX, mouseY, x(LIST_X + 98), y(linksY), WBIcons.SORT,
            sort != Sort.ADDED,
            () -> sort = Sort.values()[Math.floorMod(
                sort.ordinal() + (screen.back() ? -1 : 1), Sort.values().length)],
            WorkbayScreen.gui("links.sort." + sort.name().toLowerCase(java.util.Locale.ROOT)),
            WorkbayScreen.gui("links.sort.tip"));


        boolean pairHover = screen.hovered(pairX, y(linksY), 46, 18, mouseX, mouseY);
        Draw.button(g, pairX, y(linksY), 46, 18, pairHover, false);
        // The Connector's own item, because the button only does anything while you are holding
        // one and a plus sign does not say that. Fourteen inside an eighteen-pixel button: at its
        // natural sixteen it covered the button's own bevel top and bottom and read as a sprite
        // dropped on the control rather than sitting in it. OPEN_ISSUES #61.
        WBIcons.sprite(g, new ItemStack(com.neryos.workbay.init.WBBlocks.CONNECTOR.get()),
            pairX + 2, y(linksY + 2), 14, true);
        text(g, "Pair", pairX + 20, y(linksY + 5), 22, Draw.TEXT);
        screen.hit(pairX, y(linksY), 46, 18, () -> screen.send(WorkbayAction.PAIR),
            WorkbayScreen.gui("links.pair"), WorkbayScreen.gui("links.pair.tip"));

        // A bay holding a room has no channels of its own (SPEC.md §0), so there is nothing for
        // Add to add: the button is drawn disabled and says why.
        boolean roomBay = roomHere() != null;
        boolean addHover = !roomBay && screen.hovered(addX, y(linksY), 36, 18, mouseX, mouseY);
        Draw.button(g, addX, y(linksY), 36, 18, addHover, false, !roomBay);
        WBIcons.draw(g, WBIcons.PLUS, addX + 3, y(linksY + 3), roomBay ? Draw.TEXT_FAINT : Draw.TEXT);
        text(g, "Add", addX + 15, y(linksY + 5), 18, roomBay ? Draw.TEXT_FAINT : Draw.TEXT);
        screen.hit(addX, y(linksY), 36, 18, roomBay ? () -> { } : () -> {
            adding = true;
            editingFilter = null;
            scroll = 0;
        }, WorkbayScreen.gui("links.add"),
            WorkbayScreen.gui(roomBay ? "links.add.room" : "links.add.tip"));

        List<WorkbaySnapshot.Link> visible = visibleLinks(snap);
        int listH = rows * ROW_PITCH + 8;
        Draw.well(g, x(LIST_X), y(rowY - 4), LIST_W, listH);

        if (visible.isEmpty()) {
            // "No links yet. Pair a Connector" is a lie the moment the list is scoped to one bay
            // and the links are all on another. Say which case this is.
            int elsewhere = snap.links().size();
            Component empty = filter == Filter.THIS_BAY && roomBay
                ? WorkbayScreen.gui("links.none.room")
                : filter == Filter.THIS_BAY && elsewhere > 0
                ? WorkbayScreen.gui("links.none.here", elsewhere)
                : WorkbayScreen.gui("links.none");
            // <b>And no bands under it.</b> The rules were drawn for every row whether the list
            // had one or not, so the two-line sentence explaining the empty list had a horizontal
            // rule struck through its second line -- an empty list, with the explanation crossed
            // out. Rows are the list's furniture; with no list there is nothing to furnish.
            emptyList(g, empty, listH);
            return;
        }
        // Empty rows are drawn as empty rows. SPEC.md §7: the alternative is growing the panel to
        // fit the list, which moves every control under the player's cursor as links are added.
        for (int emptyRow = 0; emptyRow < rows; emptyRow++) {
            int bandY = y(rowY + emptyRow * ROW_PITCH);
            // Banded, not just ruled. Thirty rows of eight controls each is the one list on this
            // screen where the eye loses which figure belongs to which row, and a rule between
            // two rows is a line the eye has to follow; a band is a row it lands on.
            if (emptyRow % 2 == 1) {
                g.fill(x(LIST_X + 2), bandY - 1, x(LIST_X + LIST_W - 8), bandY + ROW_PITCH - 2,
                    0x0AFFFFFF);
            }
            int ruleY = bandY + ROW_PITCH - 2;
            g.fill(x(LIST_X + 4), ruleY, x(LIST_X + LIST_W - 10), ruleY + 1, 0x12FFFFFF);
        }

        if (knownAtAdd != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (!knownAtAdd.contains(visible.get(i).config().id())) {
                    scroll = i - rows + 1;
                    knownAtAdd = null;
                    break;
                }
            }
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, visible.size() - rows));
        scrollbar(g, visible.size());
        for (int visibleRow = 0; visibleRow < rows && visibleRow + scroll < visible.size(); visibleRow++) {
            row(g, mouseX, mouseY, visible.get(visibleRow + scroll), y(rowY + visibleRow * ROW_PITCH));
        }
    }

    /**
     * The sentence a list says when it has nothing in it: wrapped, and centred in the well rather
     * than pinned to the top left of it. A well two hundred pixels tall with one line of grey text
     * in its corner reads as a list that failed to load; the same line in the middle of it reads
     * as an answer.
     */
    private void emptyList(GuiGraphics g, Component said, int listH) {
        int room = LIST_W - 40;
        int lines = screen.font().split(
            said.copy().withStyle(style -> style.withFont(Draw.UI_FONT)), room).size();
        wrapped(g, said, x(LIST_X + 20), y(rowY - 4) + (listH - lines * 10) / 2, room,
            Draw.TEXT_FAINT);
    }

    /**
     * One row. Per SPEC.md §4: resource icon, direction icon, name, target or status, filter slot,
     * gear - and <b>remove lives inside the gear</b>, because two controls per row is the ceiling
     * and a delete button on every row is how somebody deletes the wrong one.
     *
     * <p><b>The status swatch is gone, and its sixteen pixels bought the gutters.</b> It was a
     * 10px chip saying, in colour, the thing the status column beside it says in words and the
     * red gutter bar says louder - three copies of one fact on a row that had no room for one.
     * What was left was ten controls at four-pixel spacing, and at those gaps the status column's
     * "Off" and the face button's "-" ran together into one word. Eight pixels between the
     * right-hand three now, ten before them, and the name column came out four wider than it went
     * in.
     */
    private void row(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot.Link link, int py) {
        var font = screen.font();
        BusConfig config = link.config();
        int px = x(LIST_X + 4);
        boolean rowHover = screen.hovered(px, py, LIST_W - 14, ROW_PITCH - 2, mouseX, mouseY);
        if (rowHover) {
            g.fill(px, py, px + LIST_W - 14, py + ROW_PITCH - 2, 0x18FFFFFF);
            // And outline the block it points at, out in the world. SPEC.md §7.
            com.neryos.workbay.client.LinkHighlight.set(config.target());
        }

        // Broken gets a red bar in the well's own left gutter, where nothing else lives. The
        // header can say "1 problem" and every row still look fine otherwise: the status swatch
        // alone is a 10px chip that a player scanning thirty rows does not see, and a disabled row
        // is dim, which is what "broken" used to look like too. Dim is the player's own doing;
        // red is the mod's.
        if (link.status().isProblem()) {
            g.fill(px - 3, py, px - 1, py + ROW_PITCH - 2, Draw.RED);
        }

        // On and off are one click on the row, not two clicks through a menu. A power symbol, not
        // a tick: the picker's tick means "selected for adding", and one control that means two
        // things is a control that means neither.
        boolean on = config.enabled();
        Draw.slot(g, px, py + 3, 12, 12);
        WBIcons.draw(g, WBIcons.POWER, px, py + 3, on ? Draw.GREEN : Draw.TEXT_FAINT);
        screen.hit(px, py + 3, 12, 12,
            () -> screen.send(WorkbayAction.LINK_TOGGLE_ENABLED, config.id()),
            WorkbayScreen.gui(on ? "links.disable" : "links.enable"),
            WorkbayScreen.gui(on ? "links.disable.tip" : "links.enable.tip"));

        // <b>The arrow never turns round; the two ends swap around it.</b> A row that reads right
        // to left half the time is a row the player has to re-read to know which end is which, and
        // "which way is it going" was being asked of a twelve-pixel glyph that changes shape.
        // Left is always where it comes from and right always where it goes. The colour still says
        // which mode it is in. The owner's call; OPEN_ISSUES #75.
        //
        // <b>And the two ends are both blocks now.</b> One of them used to be the *resource* icon
        // standing in for the bay -- so a row read "iron ingot -> chest", which names what is
        // moving where a place should be, and left the resource with no control of its own that
        // said what it was. The bay end draws the machine racked in that bay, the far end draws
        // the block out in the world (or the other bay's machine, for a bay-to-bay row), and the
        // resource moves out in front as its own selector: power, type, from, arrow, into.
        // The owner's call.
        boolean insert = config.mode() == BusConfig.Mode.INSERT;
        int typeX = px + 16;
        resourceIcon(g, config.resource(), typeX, py + 3);
        screen.hit(typeX, py + 3, 12, 12,
            () -> screen.send(WorkbayAction.LINK_CYCLE_RESOURCE, config.id()),
            WorkbayScreen.gui("links.type." + config.resource().getSerializedName()),
            WorkbayScreen.gui("links.type.tip"));

        // A hairline between the selector and the two ends. Four twelve-pixel icons in a row read
        // as one run of decoration, and the first of them is the odd one out: it says what the row
        // *carries*, where the three to its right say where it goes. Faint, and only as tall as the
        // icons, so it divides without becoming a fifth thing to look at. The owner's call.
        g.fill(px + 30, py + 4, px + 31, py + 15, Draw.alpha(Draw.EDGE_LIGHT, 0.35F));

        // INSERT moves out of the bay into the target, EXTRACT the other way round, so which end
        // is which is the mode and nothing else.
        Optional<ResourceLocation> here = snapshot().bay(config.bay()).hosted();
        Optional<ResourceLocation> far = config.internal()
            ? link.targetBay().flatMap(b -> snapshot().bay(b).hosted())
            : targetIcon(link);
        end(g, px + 32, py + 3, insert ? here : far, insert ? config.bay() : -1,
            link, true);
        WBIcons.draw(g, WBIcons.ARROW_RIGHT, px + 48, py + 3, insert ? Draw.BLUE : Draw.GREEN);
        screen.hit(px + 48, py + 3, 12, 12,
            () -> screen.send(WorkbayAction.LINK_FLIP_MODE, config.id()),
            WorkbayScreen.gui("links.mode." + config.mode().getSerializedName()),
            WorkbayScreen.gui("links.mode.tip"));
        end(g, px + 64, py + 3, insert ? far : here, insert ? -1 : config.bay(),
            link, false);

        // Which bay - <b>only when the list is actually showing more than one.</b> Scoped to this
        // bay, which is the default, every row carried the same "B1" under a heading that already
        // said BAY 1: sixteen pixels and a hit region spent saying a thing twice, on the row whose
        // name column was too narrow to finish the word "Connector". A column that answers the
        // same for every row is not a column.
        boolean showBay = filter != Filter.THIS_BAY;
        // Both ends are always drawn now, so the name starts at a fixed place and the column can
        // no longer change width row by row.
        int cursor = px + 80;
        if (showBay) {
            text(g, bayBadge(config), cursor, py + 5, 14, Draw.TEXT_DIM);
            screen.hit(cursor, py + 3, 14, 12, () -> { },
                config.detached() ? WorkbayScreen.gui("status.detached")
                    : WorkbayScreen.gui("links.bay", config.bay() + 1),
                config.detached() ? WorkbayScreen.gui("status.detached.tip")
                    : WorkbayScreen.gui("links.bay.tip"));
            cursor += 18;
        }

        // The name, and where it comes from when the player has not given one: the bay an internal
        // link points at, or the target block's own name. Four rows all called "Bay link" was the
        // whole list unreadable at a glance.
        String label = labelOf(link);

        int nameW;
        int nameX = cursor;
        // <b>The name gets everything that is left, measured rather than written down.</b> It was
        // a fixed fifty-six, so "Connector" -- the name of one of this mod's own two blocks, and
        // the name half these rows carry -- arrived as "Connec...". The status column beside it
        // was cut for a longest word of "No machine" measured wrong.
        // <b>And the status column is measured too, which is what leaves the name enough.</b>
        // STATUS_W is 54, sized for the longest reading there is ("No machine"); every row not
        // showing that one was holding pixels the name needed. With the list scoped to all bays --
        // which adds the bay badge -- the name was down to forty-four, and "Block Placer" and
        // "Fluid Extractor" both arrived cut. Photographed with F3's outlines on, next to the Add
        // list that OPEN_ISSUES #74 is about; same fault, same fix, one row over.
        //
        // Right-aligned rather than left, so the column still has a straight edge to read down.
        String statusText = statusShort(link).getString();
        int statusW = Math.min(STATUS_W, Draw.width(screen.font(), statusText));
        int statusRight = px + STATUS_X + STATUS_W;
        int nameRight = statusRight - statusW - 6;
        nameW = nameRight - nameX;
        if (!screen.renaming(config.id())) {
            text(g, label, nameX, py + 5, nameW, on ? Draw.TEXT : Draw.TEXT_FAINT);
        }
        // Right-click the name to give the link one of your own. On the name itself rather than on
        // a seventh control: the row is already at SPEC.md §4's two-controls ceiling, and a name is
        // the one thing a player edits by pointing at the thing that is wrong. Left-click is left
        // alone so the row keeps behaving as it did.
        final int renameX = nameX;
        final int renameW = nameW;
        screen.hit(renameX, py + 3, renameW, 12, () -> {
            if (screen.back()) {
                screen.beginRename(config.id(), renameX, py + 2, renameW, 12, config.name(),
                    typed -> screen.sendText(WorkbayAction.SET_LINK_NAME, typed, config.id()));
            }
        }, link.label().<Component>map(Component::literal).orElseGet(() -> fullName(link)),
            WorkbayScreen.gui("links.rename.tip"),
            standsAt(config.connector().pos()));

        // The right-hand column is the status, always, for every kind of link.
        //
        // It used to be "the target, unless something is wrong with it", which meant a row whose
        // name was *derived* from the target printed that target twice: "Connect... Connector",
        // and an internal row "Bay 2 -> Bay 2". The name column is the one that says what a link
        // points at (SPEC.md §4 lists them as two columns, not two copies of one); this one says
        // what the link is doing, which is the thing no other text on the row carries -- the
        // status swatch is a 10px chip a player scanning thirty rows does not read.
        boolean broken = link.status().isProblem();
        textRight(g, statusText, statusRight, py + 5, statusW, statusColour(link.status()));
        if (config.internal()) {
            // Bay to bay is the one target a player may change from the row: there is no Connector
            // in the world to move, so the click has to live somewhere and this column is where
            // the target used to be drawn. The name column still says which bay.
            screen.hit(px + STATUS_X, py + 2, STATUS_W, ROW_PITCH - 4,
                () -> screen.send(WorkbayAction.LINK_CYCLE_TARGET_BAY, config.id()),
                statusName(link),
                broken ? statusHelp(link)
                    : WorkbayScreen.gui("links.internal.retarget.tip"));
        } else {
            screen.hit(px + STATUS_X, py + 2, STATUS_W, ROW_PITCH - 4, () -> { },
                statusName(link), statusHelp(link));
        }

        faceButton(g, mouseX, mouseY, px + 194, py + 3, config);
        filterSlot(g, px + 214, py + 1, config);

        WBIcons.draw(g, WBIcons.CROSS, px + 238, py + 3,
            screen.hovered(px + 238, py + 3, 12, 12, mouseX, mouseY) ? Draw.RED : Draw.TEXT_FAINT);
        screen.hit(px + 238, py + 3, 12, 12,
            () -> screen.send(WorkbayAction.LINK_REMOVE, config.id()),
            WorkbayScreen.gui("links.remove"), WorkbayScreen.gui("links.remove.tip"));
    }

    /**
     * One end of a link's arrow: the block that is actually there.
     *
     * <p>A <b>bay</b> end draws the machine racked in it, which is the thing the player put there
     * and the thing they think of the row as being about; an empty bay draws an empty slot, which
     * is the same picture the rack itself uses for one. A <b>world</b> end draws the block the
     * Connector is on. Either way the row reads as two places with an arrow between them, which is
     * what it is.
     */
    private void end(GuiGraphics g, int px, int py, Optional<ResourceLocation> block, int bay,
        WorkbaySnapshot.Link link, boolean from) {
        var item = block.map(BuiltInRegistries.ITEM::get)
            .filter(i -> i != net.minecraft.world.item.Items.AIR).orElse(null);
        if (item == null) {
            Draw.slot(g, px, py, 12, 12);
            screen.hit(px, py, 12, 12, () -> { },
                WorkbayScreen.gui(from ? "links.from" : "links.into"),
                WorkbayScreen.gui(bay >= 0 ? "links.end.emptybay" : "links.end.unknown"));
            return;
        }
        WBIcons.sprite(g, new ItemStack(item), px, py, 12, true);
        screen.hit(px, py, 12, 12, () -> { },
            WorkbayScreen.gui(from ? "links.from" : "links.into"),
            bay >= 0 ? WorkbayScreen.gui("links.end.bay", bay + 1,
                    displayName(block.orElseThrow()))
                : displayName(block.orElseThrow()));
    }

    /**
     * Which face of the target block this link reaches into.
     *
     * <p>The runner has honoured a pinned face since buses existed; nothing ever let a player set
     * one. A machine with a separate input and output face is unusable without it - the link takes
     * whichever face answers first, which is the wrong one about half the time.
     *
     * <p><b>The cube's letters, not the compass's.</b> It read N/S/E/W/U/D on the argument that a
     * block out in the world stands at an orientation the mod did not choose - but the player sets
     * a bay's faces on a cube two panels away that says F, B, L, R, T, and one screen naming the
     * same six directions two ways is one of them being wrong. The owner's call. {@link BlockPreview}
     * owns the mapping so the two can never drift.
     */
    private void faceButton(GuiGraphics g, int mouseX, int mouseY, int px, int py,
        BusConfig config) {
        Optional<net.minecraft.core.Direction> face = config.targetFace();
        String letter = face.map(BlockPreview::label).orElse("-");
        boolean hover = screen.hovered(px, py, 12, 12, mouseX, mouseY);
        Draw.slot(g, px, py, 12, 12);
        textCentre(g, letter, px + 6, py + 2, 10,
            face.isPresent() ? (hover ? Draw.TEXT : Draw.AMBER) : Draw.TEXT_FAINT);
        screen.hit(px, py, 12, 12,
            () -> screen.send(WorkbayAction.LINK_CYCLE_TARGET_FACE, config.id()),
            face.map(d -> WorkbayScreen.gui("links.face." + BlockPreview.faceKey(d)))
                .orElse(WorkbayScreen.gui("links.face.any")),
            WorkbayScreen.gui("links.face.tip"));
    }

    /**
     * The row's filter, in sixteen pixels: what the first entry is, and which way round the list
     * is read.
     *
     * <p>Clicking it <b>opens the filter</b> rather than setting one. It used to be a one-item
     * ghost slot that set on click and cleared on the next -- two meanings for one control, and it
     * could only ever say one thing. Nine entries and a deny mode need somewhere to live, and this
     * is the row's way in to it.
     *
     * <p>Energy links draw it unlit and refuse the click. A filter matches an identity and energy
     * has none, so a lit slot that silently ignored everything dropped on it would be the dead
     * control the bay grid's unlit slots exist to avoid (SPEC.md §5).
     */
    private void filterSlot(GuiGraphics g, int px, int py, BusConfig config) {
        Draw.slot(g, px, py, 16, 16);
        if (config.resource() == BusConfig.Resource.ENERGY) {
            WBIcons.draw(g, WBIcons.FILTER, px + 2, py + 2, Draw.EDGE_DARK);
            screen.hit(px, py, 16, 16, () -> { },
                WorkbayScreen.gui("filter.energy"), WorkbayScreen.gui("filter.energy.tip"));
            return;
        }
        com.neryos.workbay.bus.BusFilter filter = config.filter();
        if (filter.isEmpty()) {
            WBIcons.draw(g, WBIcons.FILTER, px + 2, py + 2, Draw.TEXT_FAINT);
        } else {
            entryIcon(g, config.resource(), filter.entries().get(0).id(), px, py);
            // Which way round the list is read, on the row. A blacklist drawn exactly like a
            // whitelist is the one way a filter can be understood backwards, and the row is where
            // it is read. Not amber-as-warning but amber-as-the-other-mode, the same pair the
            // direction arrow beside it already uses for insert and extract.
            g.fill(px, py + 16, px + 16, py + 17, filter.deny() ? Draw.AMBER : Draw.BLUE);
        }
        screen.hit(px, py, 16, 16, () -> editingFilter = config.id(),
            filter.isEmpty()
                ? WorkbayScreen.gui("filter.none")
                : WorkbayScreen.gui(filter.deny() ? "filter.some.deny" : "filter.some.allow",
                    filter.entries().size()),
            WorkbayScreen.gui("filter.tip"));
    }

    /**
     * The filter itself, over the list. Nine ghost slots, one mode button, one way back.
     *
     * <p>Nine because that is one row of the well, and because a link is one source and one target
     * (SPEC.md §0): a list long enough to need a second row is a routing table, which is XNet's
     * mod and not this one.
     */
    private void filterPanel(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot.Link link) {
        BusConfig config = link.config();
        com.neryos.workbay.bus.BusFilter filter = config.filter();

        // <b>Its controls travel with it.</b> The heading, the mode button and Back used to sit on
        // the list's own header line while the panel they belong to was a hundred and thirty
        // pixels lower - a title orphaned from its box, which is the one arrangement that reads
        // worse than the dead slab this panel was centred to get away from. It is a dialog; a
        // dialog moves in one piece.
        // One line taller than the slots need: SPEC.md 5's link settings put Rate and Speed on the
        // panel the row's gear opens, and until now a link had both and the player could see
        // neither. OPEN_ISSUES #32.
        // A chemical link's panel carries one line the others do not - the sentence about which
        // face carries gas - and on its first screenshot that line was drawn below the well and
        // cut in the middle of a word. The height is part of the layout, not a constant.
        int blockH = filterBlockH(config);
        int panelH = blockH - 24;
        int blockY = filterBlockY(blockH);
        boolean pickable = config.resource() != BusConfig.Resource.CHEMICAL;

        // Registered first, so every control the panel draws later wins the click and nothing
        // underneath it does. Hits dispatch in reverse registration order.
        screen.swallow(x(LIST_X), blockY, LIST_W, blockH);
        // And its own background, because the heading row used to sit on whatever the page had
        // drawn there. Inside the links area that was empty space; standing over the machine panel
        // it was the in/out key showing through the title. Photographed.
        Draw.band(g, x(LIST_X), blockY, LIST_W, 24);

        // Heading and link name as one string. They used to sit at opposite ends of the row with
        // the mode button between them, and the first screenshot of this panel read the middle and
        // the right as one phrase, "Only these Chest", which is a sentence the mod does not mean.
        String label = labelOf(link);
        // LINK, not FILTER: the panel carries what a link is set to and the filter is one of them.
        text(g, "LINK \u00B7 " + label, x(LIST_X + 4), blockY + 5, LIST_W - 82, Draw.TEXT);

        // <b>A sheet of paper, white or black.</b> The word "Whitelist" took a hundred and ten
        // pixels of a row whose other half is the link's name, and these two states are the one
        // thing on this panel a colour says faster than a word: a white list and a black one.
        // Eighteen pixels now, and the name beside it gets the rest.
        int modeX = x(LIST_X + LIST_W - 70);
        boolean modeHover = screen.hovered(modeX, blockY, 18, 18, mouseX, mouseY);
        Draw.button(g, modeX, blockY, 18, 18, modeHover, false);
        WBIcons.draw(g, WBIcons.PAPER, modeX + 3, blockY + 3,
            filter.deny() ? Draw.PAPER_BLACK : Draw.TEXT);
        screen.hit(modeX, blockY, 18, 18,
            () -> screen.send(WorkbayAction.TOGGLE_FILTER_DENY, config.id()),
            WorkbayScreen.gui(filter.deny() ? "filter.deny" : "filter.allow"),
            WorkbayScreen.gui("filter.mode.tip"));

        int backX = x(LIST_X + LIST_W - 46);
        boolean backHover = screen.hovered(backX, blockY, 46, 18, mouseX, mouseY);
        Draw.button(g, backX, blockY, 46, 18, backHover, false);
        textCentre(g, "Back", backX + 23, blockY + 5, 42, Draw.TEXT);
        screen.hit(backX, blockY, 46, 18, this::closeFilter,
            WorkbayScreen.gui("filter.close"), WorkbayScreen.gui("filter.close.tip"));

        // A well the size of what is in it. SPEC.md section 7 draws empty rows as empty rows, but
        // that is about the links list, which is a list; nine slots and a sentence are not, and
        // stretching the box to the list height gave this panel four fifths of a void, which is
        // exactly what the first screenshot of it showed.
        //
        // <b>And centred in the space the list would have had</b>, rather than pinned to its top.
        // The page does not shrink when the panel does -- it cannot, the header and the rack are
        // above it -- so the room the list gave up has to go somewhere, and above and below a
        // dialog is somewhere. Pinned to the top it was a strip of controls with a hundred and
        // sixty pixels of nothing under it, which is the shape of a screen that broke.
        int panelY = blockY + 24;
        Draw.well(g, x(LIST_X), panelY, LIST_W, panelH);
        // Centred, for the same reason. Nine slots pinned to the left edge of a 268-wide box read
        // as a list that ran out rather than as the whole of the filter.
        throughput(g, mouseX, mouseY, config, panelY + 5);
        int entriesY = panelY + 6 + STEP_H + 4;
        if (config.resource() == BusConfig.Resource.CHEMICAL) {
            chemicalFilter(g, mouseX, mouseY, config, entriesY);
            return;
        }
        int slotsW = (com.neryos.workbay.bus.BusFilter.MAX - 1) * SLOT_PITCH + 18;
        int slotsX = LIST_X + (LIST_W - slotsW) / 2;
        for (int slot = 0; slot < com.neryos.workbay.bus.BusFilter.MAX; slot++) {
            filterEntry(g, x(slotsX + slot * SLOT_PITCH), entriesY, config, slot);
        }
        int sentenceY = entriesY + SLOT_PITCH + 4;
        // One sentence, and it has to be true of what is on screen. It said "Nothing listed. This
        // link carries everything." over a slot with something in it -- caught on the first
        // screenshot of the panel with an entry, which is exactly what reading the geometry in an
        // editor cannot catch. Three readings, one per state the panel can be in.
        boolean carrying = !screen.carried().isEmpty();
        String said = carrying ? "filter.carrying"
            : filter.isEmpty() ? "filter.empty"
            : filter.deny() ? "filter.listed.deny" : "filter.listed.allow";
        textCentre(g, WorkbayScreen.gui(said).getString(),
            x(LIST_X + LIST_W / 2), sentenceY, LIST_W - 16,
            carrying ? Draw.SELECT : Draw.TEXT_FAINT);
        inventory(g, mouseX, mouseY, sentenceY + 14);
    }

    /** The caption, four rows of nine at the filter row's own pitch, the hotbar's gap, and padding. */
    private static final int INVENTORY_H = 13 + 4 * SLOT_PITCH + 4 + 8;

    /**
     * <b>The player's own inventory, inside the filter panel.</b>
     *
     * <p>The panel had nine ghost slots and three ways to fill one: hold the item in your hand and
     * click, drag it out of JEI, or lift an entry from another slot. All three need the thing to be
     * somewhere other than your backpack, so the ordinary case -- "I want to filter on the cooked
     * chicken I am carrying" -- meant closing the screen, moving a stack to the hotbar, and coming
     * back. The owner's ask.
     *
     * <p><b>Ghosts, not slots.</b> A filter entry is an item <em>id</em>, not a stack, and this
     * screen deliberately has no {@code Slot} of any kind (SPEC.md §4) -- so nothing here can be
     * picked up, moved or lost. Clicking one puts a copy on the cursor, which is the gesture the
     * filter slots already have between themselves; clicking a slot drops it in. Clicking with
     * something already on the cursor puts it back rather than swapping, because a swap here would
     * be the only place in the mod where an inventory cell changed.
     */
    private void inventory(GuiGraphics g, int mouseX, int mouseY, int py) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // <b>Its own box, at the filter row's own pitch.</b> Thirty-six cells at eighteen pixels
        // with no gap between them merged into one grey mesh on the panel's flat well -- the cells
        // were there and none of them read as a cell. Two pixels apart, on a well of their own,
        // they read the way the nine slots above them do. Photographed.
        int gridW = 9 * SLOT_PITCH - 2;
        int gridX = LIST_X + (LIST_W - gridW) / 2;
        int gridY = py + 13;
        int gridH = 4 * SLOT_PITCH + 4 - 2;
        // The caption starts where the grid does, not where the panel does: left-aligned against
        // the panel's edge it sat a long way out on its own, pointing at nothing.
        text(g, WorkbayScreen.gui("filter.inventory"), x(gridX), py, gridW, Draw.TEXT_FAINT);
        Draw.well(g, x(gridX) - 3, gridY - 3, gridW + 6, gridH + 6);
        boolean carrying = !screen.carried().isEmpty();
        for (int index = 0; index < 36; index++) {
            // The hotbar last, drawn where a player looks for it: vanilla's slot 0..8 is the
            // hotbar and 9..35 the three rows above it, and drawing them in index order would put
            // the hotbar on top.
            int slot = index < 27 ? index + 9 : index - 27;
            int cx = x(gridX + (index % 9) * SLOT_PITCH);
            int cy = gridY + (index / 9) * SLOT_PITCH + (index >= 27 ? 4 : 0);
            Draw.slot(g, cx, cy, 18, 18);
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            WBIcons.sprite(g, stack, cx + 1, cy + 1, 16, false);
            screen.hit(cx, cy, 18, 18,
                () -> screen.carry(carrying ? ItemStack.EMPTY : stack),
                stack.getHoverName(),
                WorkbayScreen.gui(carrying ? "filter.inventory.putback.tip"
                    : "filter.inventory.tip"));
        }
    }

    /**
     * The chemical link's half of the filter panel. OPEN_ISSUES #41.
     *
     * <p>Nine ghost slots and nothing that can ever be dropped in them is what a chemical link had:
     * the panel opened, the sentence under it said the link carried everything, and there was no
     * gesture anywhere that could change that. A chemical has no item and no bucket, so the entries
     * are named from the tank instead - one button, and the ids it wrote listed underneath in the
     * mod's own font rather than drawn as slots, because there is no sprite to draw.
     *
     * <p>The line about faces is here because this is where a player looks for the face row the
     * other three resources have. It is Mekanism's side configuration that decides, and saying so
     * is cheaper than a row that would be a suggestion.
     */
    private void chemicalFilter(GuiGraphics g, int mouseX, int mouseY, BusConfig config, int py) {
        com.neryos.workbay.bus.BusFilter filter = config.filter();
        int buttonW = 130;
        int buttonX = x(LIST_X + (LIST_W - buttonW) / 2);
        boolean hover = screen.hovered(buttonX, py, buttonW, 18, mouseX, mouseY);
        Draw.button(g, buttonX, py, buttonW, 18, hover, !filter.isEmpty());
        textCentre(g, WorkbayScreen.gui("filter.chemical").getString(), buttonX + buttonW / 2,
            py + 5, buttonW - 8, filter.isEmpty() ? Draw.TEXT : Draw.CHEMICAL);
        screen.hit(buttonX, py, buttonW, 18,
            () -> screen.send(WorkbayAction.FILTER_FROM_TANK, config.id()),
            WorkbayScreen.gui("filter.chemical"), WorkbayScreen.gui("filter.chemical.tip"));

        // What it wrote, one line per entry, so the button's effect is visible without a tooltip.
        int line = py + 22;
        if (filter.isEmpty()) {
            textCentre(g, WorkbayScreen.gui("filter.chemical.none").getString(),
                x(LIST_X + LIST_W / 2), line, LIST_W - 16, Draw.TEXT_FAINT);
            line += 11;
        } else {
            for (ResourceLocation id : filter.ids()) {
                textCentre(g, id.getPath().replace('_', ' '), x(LIST_X + LIST_W / 2), line,
                    LIST_W - 16, filter.deny() ? Draw.AMBER : Draw.CHEMICAL);
                line += 11;
            }
        }
        // Under whatever the button wrote, however many lines that was, rather than at a fixed
        // offset that a second listed chemical would have been drawn straight through.
        textCentre(g, WorkbayScreen.gui("filter.chemical.faces").getString(),
            x(LIST_X + LIST_W / 2), line + 2, LIST_W - 16, Draw.TEXT_FAINT);
    }

    /** How tall one line of stepper controls is. */
    private static final int STEP_H = 14;

    /**
     * <b>Rate and Speed, which every link has had since the first one and no screen has ever
     * shown.</b> SPEC.md 5 draws them on the panel the row's gear opens and OPEN_ISSUES #32 is
     * that they were never built: the Impeller moves both, for every link at once, and a player
     * who wanted one link faster than the rest had nothing to press.
     *
     * <p>Minus and plus rather than a text field, which is EnderIO's and XNet's shared answer and
     * far less code -- and it is the one control shape that cannot be typed into wrongly. Holding
     * <b>shift</b> steps ten at a time and <b>ctrl</b> a hundred, so the top of a 64-rate ladder
     * is two clicks rather than sixty; the tooltip says so, because a modifier nobody is told
     * about is a modifier nobody uses.
     */
    private void throughput(GuiGraphics g, int mouseX, int mouseY, BusConfig config, int py) {
        int rate = config.rate();
        int speed = config.speed();
        int index = 0;
        for (int i = 0; i < BusConfig.SPEEDS.length; i++) {
            if (BusConfig.SPEEDS[i] == speed) {
                index = i;
            }
        }

        // The label and the figure answer a hover too (#130): the tooltip lived on the two 14px
        // buttons only, and "Rate 8" read as a bare number nobody could ask about. Registered
        // first, so the buttons over the same line still win their own clicks and tooltips.
        Component rateTip = WorkbayScreen.gui("links.rate.tip",
            BusRunner.MB_PER_RATE, BusRunner.FE_PER_RATE);
        Component speedTip = WorkbayScreen.gui("links.speed.tip");
        int left = x(LIST_X + 10);
        screen.tip(left, py, 36 + 66, STEP_H, WorkbayScreen.gui("links.rate"), rateTip);
        text(g, WorkbayScreen.gui("links.rate").getString(), left, py + 3, 34, Draw.TEXT_DIM);
        stepper(g, mouseX, mouseY, left + 36, py, String.valueOf(rate),
            // No ceiling here on purpose. The ceiling is linkMaxRate, which is a *server* config
            // this client cannot read, so a hardcoded 64 would stop the + button dead in a pack
            // that raised it. The server clamps, and the value that comes back is the truth.
            step -> screen.send(WorkbayAction.SET_LINK_RATE,
                Math.max(1, (long) rate + step), config.id()),
            WorkbayScreen.gui("links.rate"), rateTip);

        int right = x(LIST_X + LIST_W / 2 + 16);
        screen.tip(right, py, 42 + 66, STEP_H, WorkbayScreen.gui("links.speed"), speedTip);
        text(g, WorkbayScreen.gui("links.speed").getString(), right, py + 3, 40, Draw.TEXT_DIM);
        final int at = index;
        stepper(g, mouseX, mouseY, right + 42,
            py, WorkbayScreen.gui("links.speed.ticks", speed).getString(),
            // A faster link waits *less*, so plus has to move down the list or the button lies.
            step -> screen.send(WorkbayAction.SET_LINK_SPEED,
                Math.clamp(at - Integer.signum(step), 0, BusConfig.SPEEDS.length - 1), config.id()),
            WorkbayScreen.gui("links.speed"), speedTip);
    }

    /** {@code [-] value [+]}, with the modifier scaling SPEC.md 5 asks for. */
    private void stepper(GuiGraphics g, int mouseX, int mouseY, int px, int py, String value,
        java.util.function.IntConsumer move, Component name, Component tip) {
        int step = net.minecraft.client.gui.screens.Screen.hasControlDown() ? 100
            : net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 10 : 1;
        for (int i = 0; i < 2; i++) {
            boolean plus = i == 1;
            int bx = plus ? px + 52 : px;
            boolean hover = screen.hovered(bx, py, STEP_H, STEP_H, mouseX, mouseY);
            Draw.button(g, bx, py, STEP_H, STEP_H, hover, false);
            WBIcons.draw(g, plus ? WBIcons.PLUS : WBIcons.MINUS, bx + 1, py + 1, Draw.TEXT);
            int delta = plus ? step : -step;
            screen.hit(bx, py, STEP_H, STEP_H, () -> move.accept(delta), name, tip);
        }
        textCentre(g, value, px + 33, py + 3, 34, Draw.TEXT);
    }

    /** Leaving the panel puts down whatever the cursor was carrying: it belonged to this panel. */
    private void closeFilter() {
        screen.carry(ItemStack.EMPTY);
        editingFilter = null;
    }

    /**
     * One ghost slot. EnderIO gesture, which is the one the genre already teaches: a left-click
     * <b>lifts</b> the entry onto the cursor and the next left-click puts it down. Right-click
     * deletes outright, and an empty-handed click on an empty slot still lists whatever the player
     * is holding.
     *
     * <p><b>Which slot it lands in is not the player's to choose</b>, and the strings say so.
     * {@link com.neryos.workbay.bus.BusFilter#with} keeps the list without holes on purpose, so a
     * drop onto an empty slot appends and a drop onto a filled one replaces it. A first draft
     * promised "pick this up and move it", which read as "move it to that column" and is not what
     * happens -- seen on the first screenshot where an entry dropped on slot six arrived in slot
     * one.
     */
    private void filterEntry(GuiGraphics g, int px, int py, BusConfig config, int slot) {
        Draw.slot(g, px, py, 18, 18);
        Optional<com.neryos.workbay.bus.BusFilter.Entry> row = config.filter().at(slot);
        Optional<ResourceLocation> entry = row.map(
            com.neryos.workbay.bus.BusFilter.Entry::id);
        // Carrying makes every slot a destination, filled or not: dropping onto a filled slot
        // replaces it, which is what somebody who has just picked something up expects.
        boolean carrying = !screen.carried().isEmpty();
        if (carrying) {
            Draw.ring(g, px, py, 18, 18, 3, Draw.SELECT);
        }
        if (entry.isPresent()) {
            entryIcon(g, config.resource(), entry.get(), px + 1, py + 1);
            // A row matching by tag keeps its sprite and wears a corner mark, because the picture
            // is now standing for a whole shelf rather than for itself. The tooltip is the tag.
            boolean tagged = row.get().tag().isPresent();
            if (tagged) {
                Draw.ring(g, px, py, 18, 18, 1, Draw.BLUE);
            }
            screen.hit(px, py, 18, 18, () -> {
                // Shift is "the other question about this slot", which here is what it matches on.
                if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                    screen.send(WorkbayAction.CYCLE_FILTER_TAG, slot, config.id());
                    return;
                }
                if (carrying) {
                    dropCarried(config, slot);
                    return;
                }
                // <b>Clicking a full slot empties it, and that is all.</b> It used to clear the
                // slot *and* stick the entry to the cursor, so "take this off the list" left
                // something following the pointer that had to be put somewhere before anything
                // else could be clicked -- and it looked like the icon had been picked up out of
                // the filter, which is the one thing a ghost slot must never look like. Moving an
                // entry between slots was what the lift was for; the inventory below is a better
                // way to do that and does not surprise anybody clearing a slot. The owner's call.
                screen.send(WorkbayAction.SET_FILTER, (long) slot << 32, config.id());
            }, tagged
                ? Component.literal("#" + row.get().tag().get())
                : entryName(config.resource(), entry.get()),
                WorkbayScreen.gui(tagged ? "filter.entry.tag.tip" : "filter.entry.tip"));
        } else {
            screen.hit(px, py, 18, 18, () -> {
                if (carrying) {
                    dropCarried(config, slot);
                } else {
                    setFilterFromHand(config, slot);
                }
            }, WorkbayScreen.gui("filter.slot"),
                WorkbayScreen.gui(config.resource() == BusConfig.Resource.FLUID
                    ? "filter.slot.tip.fluid" : "filter.slot.tip.item"));
        }
        screen.ghost(px + 1, py + 1, 16, 16, dropped -> setFilterEntry(config, slot, dropped));
    }

    private void dropCarried(BusConfig config, int slot) {
        ItemStack carried = screen.carried();
        screen.carry(ItemStack.EMPTY);
        setFilterEntry(config, slot, carried);
    }

    /**
     * An entry drawn where an item would be. Washed white at pose Z+300 so it never reads as a real
     * stack sitting in a slot, which is the anti-dupe convention SPEC.md §5 requires of every ghost
     * slot in the mod.
     */
    private static void entryIcon(GuiGraphics g, BusConfig.Resource resource, ResourceLocation id,
        int px, int py) {
        if (resource == BusConfig.Resource.FLUID) {
            Draw.fluidIcon(g, px, py, 16, BuiltInRegistries.FLUID.get(id));
        } else {
            g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(id)), px, py);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(px, py, px + 16, py + 16, 0x60FFFFFF);
        g.pose().popPose();
    }

    private static Component entryName(BusConfig.Resource resource, ResourceLocation id) {
        return resource == BusConfig.Resource.FLUID
            ? new net.neoforged.neoforge.fluids.FluidStack(BuiltInRegistries.FLUID.get(id), 1)
                .getHoverName()
            : new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName();
    }

    /**
     * Clicking an empty slot with something in hand is the way in for a player with no recipe
     * viewer installed. This menu has no slots of its own (SPEC.md §4), so there is no cursor stack
     * to take it from -- the main hand is what a player is holding here.
     */
    private void setFilterFromHand(BusConfig config, int slot) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            setFilterEntry(config, slot, minecraft.player.getMainHandItem());
        }
    }

    /**
     * What a dropped item names, in the registry this link filters on.
     *
     * <p>A fluid link takes <b>the fluid inside whatever was dropped</b> -- a bucket, a tank, a
     * mod's own cell -- read through the item fluid capability. That is EnderIO's
     * {@code FluidFilterSlot#getResourceFrom}, and it is the only way to name a fluid without
     * typing one: a fluid has no item of its own to drag.
     */
    private void setFilterEntry(BusConfig config, int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        long plusOne = switch (config.resource()) {
            case ITEM -> BuiltInRegistries.ITEM.getId(stack.getItem()) + 1L;
            case FLUID -> {
                var handler = stack.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM);
                var held = handler == null || handler.getTanks() == 0
                    ? net.neoforged.neoforge.fluids.FluidStack.EMPTY : handler.getFluidInTank(0);
                yield held.isEmpty() ? 0L : BuiltInRegistries.FLUID.getId(held.getFluid()) + 1L;
            }
            // Neither has a filter: energy has nothing to name and a chemical has no item to drag
            // from. Zero is "no entry", which is what the caller already refuses.
            case ENERGY, CHEMICAL -> 0L;
        };
        if (plusOne <= 0) {
            return;
        }
        screen.send(WorkbayAction.SET_FILTER, ((long) slot << 32) | plusOne, config.id());
    }

    /** One of the picker's two tabs, drawn as a button that stays pressed while it is the one shown. */
    private void tab(GuiGraphics g, int mouseX, int mouseY, int px, int w, AddTab which,
        String label) {
        boolean active = addTab == which;
        boolean hover = screen.hovered(px, y(linksY), w, 18, mouseX, mouseY);
        Draw.button(g, px, y(linksY), w, 18, hover, active);
        textCentre(g, label, px + w / 2, y(linksY + 5), w - 4, active ? Draw.TEXT : Draw.TEXT_DIM);
        screen.hit(px, y(linksY), w, 18, () -> {
            addTab = which;
            scroll = 0;
        }, WorkbayScreen.gui("links.add.tab." + which.name().toLowerCase(java.util.Locale.ROOT)),
            WorkbayScreen.gui("links.add.tab.tip"));
    }

    private void closePicker() {
        adding = false;
        pickedConnectors.clear();
        pickedBays.clear();
        scroll = 0;
    }

    /**
     * Attaches everything that is ticked, then leaves the picker.
     *
     * <p>One action per pick rather than one action carrying a list. The two are different actions
     * on the server -- minting a channel through a Connector, and minting an internal one -- and
     * each already validates its own arguments, so a batching packet would buy nothing but a
     * second place for the same rules to be written down.
     */
    private void applyPicked() {
        int bay = screen.selectedBay();
        pickedConnectors.forEach(id -> screen.send(WorkbayAction.ADD_CHANNEL, bay, id));
        pickedBays.forEach(target -> screen.send(WorkbayAction.CREATE_INTERNAL_LINK, target));
        // A channel given back off the waiting list keeps its id, so only the attached ones are
        // "known": the row that appears is new to the bay whether or not it is new to the network.
        knownAtAdd = snapshot().links().stream().filter(l -> !l.config().detached())
            .map(l -> l.config().id()).collect(java.util.stream.Collectors.toSet());
        closePicker();
    }

    /**
     * Every link the snapshot carried when Add was pressed, kept until a snapshot arrives with
     * one that is not in it -- that one is the row Add just made, and the list scrolls to it.
     * Add appends, and on a bay with six rows the new one landed below the fold, where a player
     * who has just pressed a button sees nothing happen. Scrolled to, never re-sorted: rows must
     * not move under the cursor (OPEN_ISSUES #74). Null while nothing is expected. #100.
     */
    @org.jetbrains.annotations.Nullable
    private static java.util.Set<UUID> knownAtAdd;

    /**
     * What the selected bay could be given a channel through: on the Connectors tab <b>every
     * Connector this network owns</b>, and on the Bays tab every other bay, for a channel that
     * needs no Connector at all.
     *
     * <p><b>A list of Connectors, not of channels.</b> A Connector is one object; it is offered to
     * every bay, always, and ticking it on two bays gives two channels through the same block --
     * power into the bay holding the energy cube, cobble out of the bay holding the generator. The
     * list used to be of rows, so a Connector carrying four appeared four times and ticking one of
     * them moved it off the bay that had it. Adding never takes anything away. SPEC.md §0.
     */
    private void candidates(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot snap) {
        int selected = screen.selectedBay();

        // Every Connector, every time, on every bay. Nothing is filtered out for already being in
        // use here: using one twice on one bay is two channels, and using it on a second bay is
        // the whole reason a Connector is an object rather than a row.
        List<Connector> loose = snap.connectors();
        // Bays that exist and are not this one. A bay with no machine is still worth offering: the
        // link outlives the machine, and racking one later is the normal order of work.
        List<Integer> bays = java.util.stream.IntStream.range(0, snap.bayCapacity())
            .filter(bay -> bay != selected)
            .boxed()
            .toList();

        int listH = rows * ROW_PITCH + 8;
        Draw.well(g, x(LIST_X), y(rowY - 4), LIST_W, listH);
        int total = addTab == AddTab.CONNECTORS ? loose.size() : bays.size();
        if (total == 0) {
            emptyList(g, WorkbayScreen.gui(addTab == AddTab.CONNECTORS
                ? "links.add.none.links" : "links.add.none.bays"), listH);
            return;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, total - rows));
        scrollbar(g, total);

        for (int visibleRow = 0; visibleRow < rows && visibleRow + scroll < total; visibleRow++) {
            int index = visibleRow + scroll;
            int py = y(rowY + visibleRow * ROW_PITCH);
            int px = x(LIST_X + 4);
            boolean hover = screen.hovered(px, py, LIST_W - 14, ROW_PITCH - 2, mouseX, mouseY);
            if (hover) {
                g.fill(px, py, px + LIST_W - 14, py + ROW_PITCH - 2, 0x18FFFFFF);
            }

            if (addTab == AddTab.CONNECTORS) {
                Connector connector = loose.get(index);
                boolean ticked = pickedConnectors.contains(connector.id());
                if (hover) {
                    com.neryos.workbay.client.LinkHighlight.set(connector.target());
                }
                checkbox(g, px, py + 3, ticked);
                // <b>The block it is stuck to, not what it carries.</b> OPEN_ISSUES #77: a
                // Connector on an Energy Cube is recognised as an Energy Cube long before a drop
                // or a cell is decoded as "fluid" or "energy" -- and it carries nothing yet
                // anyway, because what it carries is decided on the row this tick mints.
                Optional<ResourceLocation> pickIcon = connector.targetBlock()
                    .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")));
                if (pickIcon.isPresent()
                    && BuiltInRegistries.ITEM.get(pickIcon.get()) != net.minecraft.world.item.Items.AIR) {
                    WBIcons.sprite(g, new ItemStack(BuiltInRegistries.ITEM.get(pickIcon.get())),
                        px + 18, py + 3, 12, true);
                } else {
                    // The Connector's own item, for one whose far end this client has never
                    // loaded: the row is about the plate, and a plate is what it draws.
                    WBIcons.sprite(g, new ItemStack(
                        com.neryos.workbay.init.WBBlocks.CONNECTOR.get()), px + 18, py + 3, 12,
                        true);
                }
                String blockName = connectorTarget(connector);
                String label = connector.name().isBlank()
                    ? derivedName(connector.targetBlock(), connector.pos().pos(),
                        connector.hasTwin(loose))
                    : connector.name();
                String from = blockName;
                // Right-aligned against the badge rather than parked on a fixed x: the two
                // strings are "what it is" and "where it points", and a fixed column left a
                // forty-pixel hole between them on every row whose name was short.
                //
                // And drawn only when it is a second fact. A link into a room derives both from
                // the room, so the row read "Room 1 ... Room 1" -- the same word twice, which is
                // the one thing a two-column row must never be.
                // <b>The name gets what is left, measured.</b> Seventy was written here by
                // hand on a row 254 wide, so "Creative Energy Cell" -- the sort of block this list
                // exists to point at -- arrived as "Creative Energ..." with a column of nothing
                // beside it. Same fault and same fix as the link row's own name column, which
                // stopped writing its width down for exactly this reason. OPEN_ISSUES #74.
                boolean secondFact = !connector.name().isBlank() && !from.equals(label);
                int fromRight = px + 186;
                // <b>Measured, not capped at ninety-two.</b> That number was written for a list
                // of rows with a bay badge and an arrow on it; this list has one row per Connector
                // and room to spare, and "Rotary Condensentrator" -- the block this rig is built
                // on -- arrived as "Rotary Condensent...". OPEN_ISSUES #74, one list over.
                int fromW = secondFact ? Draw.width(screen.font(), from) : 0;
                int labelW = (secondFact ? fromRight - fromW - 6 : fromRight) - (px + 34);
                text(g, label, px + 34, py + 5, labelW, ticked ? Draw.TEXT : Draw.TEXT_DIM);
                if (secondFact) {
                    textRight(g, from, fromRight, py + 5, fromW, Draw.TEXT_DIM);
                }
                // Which bays already talk through it -- "B1 B2", or nothing for one nobody has
                // used yet. The fact a player needs beside a list whose whole point is that one
                // Connector serves several bays at once.
                textRight(g, inUse(snap, connector), px + LIST_W - 18, py + 5, 44, Draw.TEXT_FAINT);
                boolean waiting = snap.links().stream().anyMatch(other ->
                    other.config().detached() && !other.config().internal()
                        && other.config().connector().equals(connector.pos()));
                screen.hit(px, py, LIST_W - 14, ROW_PITCH - 2, () -> {
                    if (!pickedConnectors.remove(connector.id())) {
                        pickedConnectors.add(connector.id());
                    }
                }, waiting ? WorkbayScreen.gui("links.add.connector.back", label)
                    : WorkbayScreen.gui("links.add.connector", label),
                    waiting ? WorkbayScreen.gui("links.add.connector.back.tip", selected + 1)
                        : WorkbayScreen.gui("links.add.connector.tip", selected + 1),
                    // #125: a Connector standing against nothing was a row with no way to find it.
                    standsAt(connector.pos().pos()));
            } else {
                int bay = bays.get(index);
                boolean ticked = pickedBays.contains(bay);
                WorkbaySnapshot.Bay other = snap.bays().size() > bay ? snap.bays().get(bay) : null;
                checkbox(g, px, py + 3, ticked);
                WBIcons.draw(g, WBIcons.ARROW_RIGHT, px + 18, py + 3, Draw.BLUE);
                String label = "Bay " + (bay + 1)
                    + (other == null || other.name().isEmpty() ? "" : " \u00b7 " + other.name());
                text(g, label, px + 34, py + 5, 150, ticked ? Draw.TEXT : Draw.TEXT_DIM);
                textRight(g, WorkbayScreen.gui("links.add.nowire").getString(),
                    px + LIST_W - 18, py + 5, 70, Draw.TEXT_FAINT);
                screen.hit(px, py, LIST_W - 14, ROW_PITCH - 2, () -> {
                    if (!pickedBays.remove(Integer.valueOf(bay))) {
                        pickedBays.add(bay);
                    }
                }, WorkbayScreen.gui("links.add.bay", bay + 1),
                    WorkbayScreen.gui("links.add.bay.tip"));
            }
        }
    }

    /**
     * The list's scrollbar, drawn only when the list actually scrolls.
     *
     * <p>Rows stop at {@code LIST_X + LIST_W - 10}, so the track lives in the ten pixels the well
     * already leaves at its right edge and nothing has to move to make room. Without it the only
     * clue that a list continued past the last visible row was the wheel doing something.
     */
    private void scrollbar(GuiGraphics g, int total) {
        if (total <= rows) {
            return;
        }
        int trackX = x(LIST_X + LIST_W - 9);
        int trackY = y(rowY - 2);
        int trackH = rows * ROW_PITCH + 4;
        g.fill(trackX, trackY, trackX + 5, trackY + trackH, Draw.EDGE_DARK);

        // At least six pixels tall: a thumb proportional to a thirty-row list is two pixels and
        // reads as a speck of dirt on the screen rather than as a control.
        int thumbH = Math.max(6, trackH * rows / total);
        int travel = trackH - thumbH;
        int thumbY = trackY + (total == rows ? 0 : travel * scroll / (total - rows));
        g.fill(trackX + 1, thumbY, trackX + 4, thumbY + thumbH, Draw.TEXT_FAINT);
    }

    /**
     * The picker's tick, and the only tick in the mod: it means <b>selected for adding</b>. The
     * list's rows carry a power symbol instead, because one control cannot mean "ticked to attach"
     * on one list and "switched on" on the other.
     */
    private void checkbox(GuiGraphics g, int px, int py, boolean ticked) {
        Draw.slot(g, px, py, 12, 12);
        if (ticked) {
            WBIcons.draw(g, WBIcons.CHECK, px, py, Draw.GREEN);
        }
    }

    private List<WorkbaySnapshot.Link> visibleLinks(WorkbaySnapshot snap) {
        // ADDED has no comparator on purpose: the snapshot arrives in the order the links were
        // made, and re-sorting it is what makes rows move under the player's cursor.
        Comparator<WorkbaySnapshot.Link> order = switch (sort) {
            case ADDED -> null;
            case BAY -> Comparator.comparingInt(link -> link.config().bay());
            case TYPE -> Comparator.comparing(link -> link.config().resource());
            // Problems first: the default, because the screen's job at rest is to surface them.
            case STATUS -> Comparator.comparing((WorkbaySnapshot.Link link) -> !link.status().isProblem())
                .thenComparing(link -> link.config().bay());
        };
        var kept = snap.links().stream()
            .filter(link -> switch (filter) {
                case THIS_BAY -> link.config().bay() == screen.selectedBay();
                case ALL_BAYS -> true;
                case PROBLEMS -> link.status().isProblem();
            });
        return (order == null ? kept : kept.sorted(order)).toList();
    }

    @Override
    boolean scrolled(double mouseX, double mouseY, double delta) {
        if (window.isOpen()) {
            return window.scrolled(mouseX, mouseY, delta);
        }
        if (mouseY < y(rowY - 4) || mouseY > y(rowY + rows * ROW_PITCH + 4)) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    /**
     * The three failing statuses are never collapsed into one another (SPEC.md §4): a target that is
     * gone, a target whose chunk is not loaded, and a target with nothing to connect to are three
     * different things to do next, and one amber blob for all three tells the player none of them.
     */
    private static int statusColour(BusRunner.BusStatus status) {
        return switch (status) {
            case RUNNING -> Draw.GREEN;
            case IDLE -> Draw.BLUE;
            // Grey, with the other two states the player chose. Detached is not a fault.
            case DISABLED, HELD_BY_REDSTONE, DETACHED -> Draw.GREY;
            case TARGET_MISSING, CONNECTOR_GONE -> Draw.RED;
            // Amber is "you can fix this from here". The face config and an unreachable machine
            // both are; a target that has gone is not.
            // NO_POWER joins them: it is amber because feeding the block is something the
            // player can do from here, which is what amber means on these screens.
            case TARGET_NOT_LOADED, TARGET_NO_PORT, MACHINE_NO_PORT, MACHINE_NO_FACE,
                NEEDS_RESONATOR, NO_POWER -> Draw.AMBER;
        };
    }

    /**
     * The row's form of a status, two words at most. The long one is the tooltip's title and does
     * not fit a 60-pixel column: "No face for this" arrived on screen as "No face f", which reads
     * as a rendering fault rather than as a fault in the link. Same split as {@code bay.short.*}.
     */
    /** Which bay a row is on, or a dash for one that is on none. OPEN_ISSUES #70. */
    private static String bayBadge(BusConfig config) {
        return config.detached() ? "-" : "B" + (config.bay() + 1);
    }

    private static Component statusShort(WorkbaySnapshot.Link link) {
        return status("status.short.", link);
    }

    private static Component statusName(WorkbaySnapshot.Link link) {
        return status("status.", link);
    }

    private static Component statusHelp(WorkbaySnapshot.Link link) {
        return status("status.", link, ".tip");
    }

    /**
     * Every status string takes the link's resource as its one argument, so the two "no port"
     * statuses can say what is missing -- a tank, item slots -- instead of "No machine" over a
     * machine that is standing right there. The rest ignore it. OPEN_ISSUES #101.
     */
    private static Component status(String prefix, WorkbaySnapshot.Link link, String... suffix) {
        String key = prefix + link.status().name().toLowerCase(java.util.Locale.ROOT)
            + (suffix.length == 0 ? "" : suffix[0]);
        return WorkbayScreen.gui(key,
            WorkbayScreen.gui("port." + link.config().resource().getSerializedName()));
    }


    private static ItemStack iconFor(Optional<ResourceLocation> id) {
        return id.map(BuiltInRegistries.ITEM::get).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** A bay shows the name the player gave it, or the machine's, or just its number. */
    private static String nameOf(WorkbaySnapshot.Bay bay, int selected) {
        if (!bay.name().isEmpty()) {
            return bay.name();
        }
        return bay.hosted().map(BaysPage::displayName).map(Component::getString)
            .orElseGet(() -> WorkbayScreen.gui("bay.n", selected + 1).getString());
    }

    /**
     * What a row calls a link: the name the player gave it, an internal link's bay, or the target.
     * Never the link's <em>state</em> - the status column beside it is already saying that, and the
     * name column has fifty pixels it can spend on something the row is not otherwise carrying.
     */
    /**
     * What the row calls this link.
     *
     * <p>The name the player gave its Connector; failing that <b>the block the Connector stands
     * on</b>, which is what the Add list and the rename panel call it too (OPEN_ISSUES #122). The
     * row used to print the Connector's coordinates while the Add list said "Chest" and the panel
     * offered "Empty leaves it -3 -59 -3": three names for one object, and the one printed largest
     * named nothing a player recognises. The coordinates were there for #77's three Energy Cubes
     * reading alike, so only there -- a twin -- does the name carry its place; everywhere else the
     * place is one hover away.
     *
     * <p>A link into a room is still named by the room: the coordinate of a barrel in a dimension
     * the player cannot walk to names nothing.
     */
    private String labelOf(WorkbaySnapshot.Link link) {
        return link.label().orElseGet(() -> {
            if (link.targetRoom().isPresent()) {
                return snapshot().roomLabel(link.targetRoom().get()).getString();
            }
            List<Connector> all = snapshot().connectors();
            return all.stream().filter(c -> c.pos().equals(link.config().connector())).findFirst()
                .map(c -> derivedName(c.targetBlock(), c.pos().pos(), c.hasTwin(all)))
                .orElseGet(() -> derivedName(link.targetBlock(), link.config().connector().pos(),
                    false));
        });
    }

    /**
     * An unnamed Connector's name, and the only place one is made: the block it stands on, with
     * its x and z when another unnamed one stands on the same kind, and its coordinates alone when
     * this client has never known the block. The rename panel says the same thing as its hint.
     */
    static String derivedName(Optional<ResourceLocation> block, net.minecraft.core.BlockPos at,
        boolean twin) {
        return block.filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(BaysPage::displayName).map(Component::getString)
            .map(name -> twin ? name + " " + at.getX() + " " + at.getZ() : name)
            .orElseGet(() -> at.getX() + " " + at.getY() + " " + at.getZ());
    }

    /** Where a Connector stands, for a hover: the one fact its derived name leaves out. */
    private static Component standsAt(net.minecraft.core.BlockPos at) {
        return WorkbayScreen.gui("links.connector.at", at.getX(), at.getY(), at.getZ());
    }

    /**
     * The block this link talks to, for the row's own icon: a bay's hosted machine when the far end
     * is a bay, and otherwise whatever the link is pointed at out in the world - read live when the
     * chunk is loaded and remembered from when the Connector was placed when it is not, which is
     * nearly always. Empty means there is genuinely nothing to draw, and the row keeps its full
     * name column.
     */
    private Optional<ResourceLocation> targetIcon(WorkbaySnapshot.Link link) {
        if (link.config().internal()) {
            return link.targetBay().flatMap(bay -> snapshot().bay(bay).hosted());
        }
        return link.targetBlock()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")));
    }

    /**
     * The target's own name, or - when this client cannot know it - <b>where</b> it is.
     *
     * <p>Four rows read "not load..." in the name column beside four status columns reading
     * "Unloaded": the same fact twice, and the half that was cut off was the redundant one. A
     * client is told the block only when the chunk happens to be loaded ({@code targetBlockOf}
     * never loads one to find out), so this is the ordinary case for anything far from the
     * player, not an edge one.
     *
     * <p><b>X and Z, not all three.</b> The column is fifty pixels and three coordinates are
     * sixty-two of them, so the third would be an ellipsis; Y is the coordinate that distinguishes
     * two Connectors least and is the one every tooltip and F3 screen already gives back. Air is
     * treated the same way as an unloaded chunk on purpose: a target that has been broken is one
     * the status column calls "Gone", and printing "Air" would be a third word for it.
     */
    private String targetName(WorkbaySnapshot.Link link) {
        // A link into a room is named by the <b>room and nothing else</b>. "Barrel in Room 1" is
        // the honest full answer and it is eighty-five pixels in a seventy-pixel column, so it
        // arrived as "Room Wall in Roo..." on the first row it ever drew -- seen in a client. The
        // room is the half that differs from stage to stage, the icon beside it is already saying
        // which block, and the full form is one hover away on the row's own tooltip.
        if (link.targetRoom().isPresent()) {
            return snapshot().roomLabel(link.targetRoom().get()).getString();
        }
        return link.targetBlock()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(BaysPage::displayName).map(Component::getString)
            .orElseGet(() -> link.config().target().pos().getX() + " "
                + link.config().target().pos().getZ());
    }

    /**
     * What a Connector in the Add list points at: the block it is stuck to when this client knows
     * it, and otherwise where it is. Same fallback ladder as a row's own target column, and the
     * same reason -- a Connector's target is nearly always in a chunk nobody is standing in.
     */
    private static String connectorTarget(Connector connector) {
        return connector.targetBlock()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(BaysPage::displayName).map(Component::getString)
            .orElseGet(() -> connector.target().pos().getX() + " "
                + connector.target().pos().getZ());
    }

    /** Which bays already hold a channel through this Connector, as "B1 B2". Empty for none. */
    private static String inUse(WorkbaySnapshot snap, Connector connector) {
        return snap.links().stream()
            .filter(link -> !link.config().internal() && !link.config().detached()
                && link.config().connector().equals(connector.pos()))
            .map(link -> link.config().bay()).distinct().sorted()
            .map(bay -> "B" + (bay + 1))
            .collect(java.util.stream.Collectors.joining(" "));
    }

    /** The whole answer, for a tooltip: the block and the room it is standing in. */
    private Component fullName(WorkbaySnapshot.Link link) {
        Optional<Component> block = link.targetBlock()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(BaysPage::displayName);
        if (link.targetRoom().isEmpty()) {
            return block.orElseGet(() -> Component.literal(targetName(link)));
        }
        Component room = snapshot().roomLabel(link.targetRoom().get());
        return block.map(name -> WorkbayScreen.gui("links.in_room", name, room)).orElse(room);
    }

    private static Component displayName(ResourceLocation id) {
        var item = BuiltInRegistries.ITEM.get(id);
        if (item != net.minecraft.world.item.Items.AIR) {
            return item.getDescription();
        }
        var block = BuiltInRegistries.BLOCK.get(id);
        if (block != net.minecraft.world.level.block.Blocks.AIR) {
            return block.getName();
        }
        // Air is not a block the player put there - it is this client not having the target, which
        // happens for anything in the Backshop and for a chunk nobody has loaded. Printing
        // "minecraft:air" tells them the mod is broken; the raw id is still worth showing for a
        // modded block whose registration this client really is missing.
        return id.equals(ResourceLocation.withDefaultNamespace("air"))
            ? WorkbayScreen.gui("links.unknown")
            : Component.literal(id.toString());
    }
}
