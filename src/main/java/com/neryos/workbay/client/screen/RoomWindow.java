package com.neryos.workbay.client.screen;

import com.neryos.workbay.content.room.RoomColour;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbaySnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * One room's settings -- its lamp, its biome, its guests -- as a window over the BAYS page,
 * opened from the swatch on the panel of the bay that holds the room. SPEC.md §0: the room's
 * settings screen stays, reached from the bay.
 *
 * <p><b>A window, not two cycle buttons.</b> The cycle buttons were built first and were the
 * wrong control twice over: the biome one was a twelve-pixel icon that never changed, so
 * pressing it looked exactly like nothing happening, and neither ever showed what the other
 * choices <em>were</em>. Mekanism answers the same question with a window --
 * {@code GuiColorWindow}, {@code GuiRobitSkinSelect} -- and that is the right answer: a value you
 * pick out of a set is a list, not a step.
 */
class RoomWindow extends WorkbayPage {

    /** The BAYS page's own width, so the scrim covers it. */
    private static final int WIDTH = 320;

    // The settings window.
    private static final int WIN_W = 200;
    private static final int WIN_PAD = 8;
    /**
     * Twenty on a thirty pitch, so six of them span the window rather than leaving a third of the
     * Walls row empty - and a swatch is the one control here whose whole job is to be looked at,
     * so bigger is the point rather than a side effect.
     */
    private static final int SWATCH = 20;
    private static final int SWATCH_PITCH = 32;
    /**
     * Six, not eight. Eleven colours in rows of eight is eight and then three - a full row and a
     * stub, which reads as a grid that ran out rather than as the whole set. Six and five is two
     * rows that look like they were meant.
     */
    private static final int SWATCHES_PER_ROW = 6;
    private static final int BIOME_H = 13;
    /**
     * How many biomes the list shows at once. <b>The window's height does not depend on how many
     * there are</b> - the first draft drew one row per tag entry, which is fine for the six we ship
     * and is a three-thousand-pixel window the moment a pack adds Biomes O' Plenty or Terralith.
     * Six rows and a scrollbar, with the search box above, is Nature Compass's answer and it is the
     * right one: a list you can type at does not care how long it is.
     */
    private static final int BIOME_ROWS = 6;
    private static final int SEARCH_W = 84;
    private static final int SEARCH_H = 12;
    /** The scrollbar track down the right of the list, drawn only when there is more than fits. */
    private static final int SCROLLBAR_W = 6;

    /**
     * How many guests the window shows at once. Four rather than the biome list's six, so the
     * GUESTS tab is the shorter of the two and the window never grows when you switch to it - a
     * window that changes height under the cursor moves the tab you just pressed.
     */
    private static final int GUEST_ROWS = 4;

    /** Which bay's room the window shows, or -1 for closed. Client-side and never sent anywhere. */
    private int open = -1;

    /**
     * Which half of the settings window is showing.
     *
     * <p>Two tabs rather than one long window. What a room <em>is</em> and who may be <em>in</em>
     * it are two questions, and stacking both would make a window taller than the 240 the page
     * itself is not allowed to exceed - a window that overflows the panel is a window with controls
     * drawn outside the thing they belong to.
     */
    private enum Tab { ROOM, GUESTS }

    private Tab tab = Tab.ROOM;

    /** First visible row of the biome list. Reset whenever the search narrows it. */
    private int scroll;

    /** The search text as of the last frame, so a change can reset the scroll. */
    private String lastQuery = "";

    /**
     * The biome scrollbar's track as it was last drawn, or -1 for "there is none".
     *
     * <p>Kept because a press has to find the bar <b>before</b> the hit list runs. The window's
     * scrim is a full-page hit that closes it, and the bar was not a hit at all, so dragging the
     * one control on the window that has to be dragged closed the window instead.
     */
    private int barX = -1;
    private int barY;
    private int barH;
    private int barTotal;
    private int barRows = BIOME_ROWS;

    /**
     * Where the pointer is while the knob is being dragged, or -1. Carried rather than read,
     * because {@code mouseDragged} is handed deltas and never a position.
     */
    private double barAt = -1;

    RoomWindow(WorkbayScreen screen) {
        super(screen);
    }

    @Override
    int width() {
        return WIDTH;
    }

    /** The page underneath: the scrim has to cover all of it. */
    @Override
    int height() {
        return screen.panelHeight();
    }

    /** True while a window is up, which is when the page under it hands every input here first. */
    boolean isOpen() {
        return open >= 0;
    }

    /** Which bay's room is showing, or -1. */
    int bay() {
        return open;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        // Forgotten every frame and re-established by the window if it still draws one, so a bar
        // that stopped existing -- the search narrowed the list, the window closed -- cannot leave
        // a live rectangle behind for the next press to land in.
        barX = -1;
        if (open >= 0) {
            // Over the racked room's own sprite, which draws at Z 150 whatever the order and came
            // up through the window's title (OPEN_ISSUES' facts). Tooltips are at 400, above this.
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            settings(g, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    /** Opening or closing the window, and the search field that belongs to it, in one place. */
    void setOpen(int index) {
        open = index;
        tab = Tab.ROOM;
        scroll = 0;
        lastQuery = "";
        barAt = -1;
        if (index < 0) {
            screen.closeFilter();
        }
    }

    /**
     * Escape closes the window rather than the screen. Without it, a player who opened the picker
     * to look at the colours is thrown out of the Workbay entirely by the one key everybody presses
     * to back out of a thing.
     */
    @Override
    boolean escaped() {
        if (open < 0) {
            return false;
        }
        setOpen(-1);
        return true;
    }

    /**
     * Return in the guest field invites whoever is named in it. The + beside the box does the same
     * thing and stays, because a gesture with no visible control is a gesture nobody finds -- but
     * having typed a name, Return is what the hands do.
     */
    @Override
    boolean entered() {
        if (open < 0 || tab != Tab.GUESTS) {
            return false;
        }
        String typed = screen.filterText().strip();
        if (typed.isEmpty()) {
            return false;
        }
        screen.sendText(WorkbayAction.INVITE_ROOM_GUEST, open, typed);
        screen.clearFilter();
        return true;
    }

    /** The biome list scrolls; nothing else on this page does. */
    @Override
    boolean scrolled(double mouseX, double mouseY, double delta) {
        if (open < 0) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    /**
     * The knob, grabbed. Returning true here is the whole fix: the screen consults the page's press
     * before its hit list, so this takes the click off the scrim that would otherwise close the
     * window under the cursor. Anywhere on the track jumps the knob there first, which is what
     * every scrollbar in the game does.
     */
    @Override
    boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || barX < 0 || mouseX < barX || mouseX >= barX + SCROLLBAR_W
            || mouseY < barY || mouseY >= barY + barH) {
            return false;
        }
        barAt = mouseY;
        scrollToKnob();
        return true;
    }

    @Override
    boolean mouseDragged(double dragX, double dragY) {
        if (barAt < 0) {
            return false;
        }
        barAt += dragY;
        scrollToKnob();
        return true;
    }

    @Override
    boolean mouseReleased(double mouseX, double mouseY) {
        boolean was = barAt >= 0;
        barAt = -1;
        return was;
    }

    private int knobHeight() {
        return Math.max(8, barH * barRows / Math.max(1, barTotal));
    }

    /** Puts the row under the knob's centre where the pointer is. */
    private void scrollToKnob() {
        int max = Math.max(0, barTotal - barRows);
        int travel = Math.max(1, barH - knobHeight());
        scroll = Math.clamp(
            Math.round((barAt - barY - knobHeight() / 2.0) * max / travel), 0, max);
    }

    /** One room's settings, over the page. Closes itself if the room left the bay. */
    private void settings(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot.Room room = snapshot().rooms().stream()
            .filter(r -> r.index() == open).findFirst().orElse(null);
        if (room == null) {
            setOpen(-1);
            return;
        }
        int rows = (RoomColour.values().length + SWATCHES_PER_ROW - 1) / SWATCHES_PER_ROW;
        int listH = BIOME_ROWS * BIOME_H + 2;
        // As tall as the tab showing needs, and no taller. Drawing both at the room tab's height
        // left the guest list as a hundred and fifty pixels of empty box under one line of "Nobody
        // but you." -- the same wasted space this page was being pulled up for everywhere else.
        // The tabs are at the top, so the shorter window cannot move the control just clicked.
        int h = tab == Tab.GUESTS
            ? WIN_PAD * 2 + 16 + SEARCH_H + 2 + GUEST_ROWS * BIOME_H + 2
            : WIN_PAD * 2 + 12 + 10 + rows * SWATCH_PITCH + 8 + SEARCH_H + 2 + listH;
        int wx = x((WIDTH - WIN_W) / 2);
        int wy = y(Math.max(2, (height() - h) / 2));

        // The page stays drawn behind it; a scrim says which of the two is taking clicks. The
        // full-page hit under the window is what closes it, and it is registered first so every
        // control of the window itself still wins -- clicks resolve in reverse order.
        g.fill(x(0), y(0), x(WIDTH), y(height()), 0xB4000000);
        screen.hit(x(0), y(0), WIDTH, height(), () -> setOpen(-1));
        Draw.panel(g, wx, wy, WIN_W, h);

        int cursor = wy + WIN_PAD;
        String name = room.name();
        Draw.text(g, screen.font(), name, wx + WIN_PAD, cursor, WIN_W - WIN_PAD * 2 - 20,
            Draw.TEXT);
        // A close button, because "click the dark part" is not a control anybody can see. Mekanism
        // puts one on every window it opens for the same reason.
        int closeX = wx + WIN_W - WIN_PAD - 14;
        int closeY = cursor - 3;
        boolean closeHover = screen.hovered(closeX, closeY, 14, 14, mouseX, mouseY);
        Draw.button(g, closeX, closeY, 14, 14, closeHover, false);
        WBIcons.draw(g, WBIcons.CROSS, closeX + 1, closeY + 1, Draw.TEXT_DIM);
        screen.hit(closeX, closeY, 14, 14, () -> setOpen(-1),
            WorkbayScreen.gui("rooms.settings.close"));
        windowTab(g, mouseX, mouseY, closeX - 36, closeY, WBIcons.DOOR, Tab.ROOM, "room");
        windowTab(g, mouseX, mouseY, closeX - 18, closeY, WBIcons.GUEST, Tab.GUESTS, "guests");
        cursor += 12;

        if (tab == Tab.GUESTS) {
            // Four more pixels than the room tab takes, so the invite button does not sit flush
            // against the close button directly above it and read as one column of two.
            guests(g, mouseX, mouseY, room, wx, cursor + 4);
            return;
        }

        Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.colour.label").getString(),
            wx + WIN_PAD, cursor, WIN_W - WIN_PAD * 2, Draw.TEXT_DIM);
        cursor += 10;
        for (RoomColour colour : RoomColour.values()) {
            int i = colour.ordinal();
            int cx = wx + WIN_PAD + (i % SWATCHES_PER_ROW) * SWATCH_PITCH;
            int cy = cursor + (i / SWATCHES_PER_ROW) * SWATCH_PITCH;
            boolean chosen = colour == room.colour();
            boolean hover = screen.hovered(cx, cy, SWATCH, SWATCH, mouseX, mouseY);
            Draw.button(g, cx, cy, SWATCH, SWATCH, hover, chosen);
            g.fill(cx + 3, cy + 3, cx + SWATCH - 3, cy + SWATCH - 3, 0xFF000000 | colour.tint());
            if (chosen) {
                // A ring, not a shade. Draw.button's active fill sits *behind* a swatch that is
                // ten solid pixels of colour, so the one the room is actually painted could not be
                // picked out of the eleven.
                Draw.ring(g, cx, cy, SWATCH, SWATCH, 4, Draw.SELECT);
            }
            long packed = room.index() | ((long) i << 16);
            screen.hit(cx, cy, SWATCH, SWATCH,
                () -> screen.send(WorkbayAction.SET_ROOM_COLOUR, packed),
                WorkbayScreen.gui("colour." + colour.getSerializedName()));
        }
        cursor += rows * SWATCH_PITCH + 8;

        // Label on the left of the line, search field on the right of it: one line for "what this
        // is" and "how to find one in it", which is the shape every long list in the genre uses.
        int searchX = wx + WIN_W - WIN_PAD - SEARCH_W;
        int labelW = searchX - wx - WIN_PAD - 4;
        Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.biome.label").getString(),
            wx + WIN_PAD, cursor + 2, labelW, Draw.TEXT_DIM);
        // The one place that says what a biome does to a room. It used to be on every row of the
        // list; it belongs on the word the list is under, once. A no-op click, so the label also
        // stops the scrim behind it from closing the window on a miss.
        screen.hit(wx + WIN_PAD, cursor, labelW, SEARCH_H, () -> { },
            WorkbayScreen.gui("rooms.biome.label"), WorkbayScreen.gui("rooms.biome.tip"));
        String query = screen.openFilter(searchX + 4, cursor + 2, SEARCH_W - 8, SEARCH_H - 2);
        Draw.slot(g, searchX, cursor, SEARCH_W, SEARCH_H);
        // The field is a real widget drawn by the screen, so the only thing needed here is a hit
        // that takes the click back off the scrim and gives it to the box.
        screen.hit(searchX, cursor, SEARCH_W, SEARCH_H, screen::focusFilter);
        if (query.isEmpty()) {
            Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.biome.search").getString(),
                searchX + 4, cursor + 2, SEARCH_W - 8, Draw.TEXT_FAINT);
        }
        cursor += SEARCH_H + 2;

        java.util.List<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>>
            matches = matching(query);
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            scroll = 0;
        }
        int listW = WIN_W - WIN_PAD * 2;
        Draw.well(g, wx + WIN_PAD, cursor, listW, listH);
        boolean bar = matches.size() > BIOME_ROWS;
        int rowW = listW - 2 - (bar ? SCROLLBAR_W + 1 : 0);
        scroll = Math.clamp(scroll, 0, Math.max(0, matches.size() - BIOME_ROWS));

        if (matches.isEmpty()) {
            // A list with nothing in it says which of the two reasons it is, because they need
            // different things done about them: an empty tag is a pack's doing, an empty search
            // is the player's.
            Draw.text(g, screen.font(),
                WorkbayScreen.gui(biomeChoices().isEmpty() ? "rooms.biome.none"
                    : "rooms.biome.nomatch").getString(),
                wx + WIN_PAD + 5, cursor + 5, listW - 10, Draw.TEXT_FAINT);
        }
        for (int row = 0; row < BIOME_ROWS && scroll + row < matches.size(); row++) {
            var key = matches.get(scroll + row);
            String id = key.location().toString();
            int bx = wx + WIN_PAD + 1;
            int by = cursor + 1 + row * BIOME_H;
            boolean chosen = id.equals(room.biome());
            boolean hover = screen.hovered(bx, by, rowW, BIOME_H, mouseX, mouseY);
            Draw.button(g, bx, by, rowW, BIOME_H, hover, chosen);
            Draw.text(g, screen.font(), biomeName(id).getString(), bx + 5, by + 3, rowW - 10,
                chosen ? Draw.TEXT : Draw.TEXT_DIM);
            long index = room.index();
            // No tooltip. The row is the name, so one over it repeats the word underneath it, and
            // scanning a list of forty means forty of them opening and closing under the cursor.
            // What the choice *means* is said once, on the label above the list, where it belongs.
            screen.hit(bx, by, rowW, BIOME_H,
                () -> screen.sendText(WorkbayAction.SET_ROOM_BIOME, index, id));
        }
        if (bar) {
            scrollbar(g, wx + WIN_PAD + listW - 1 - SCROLLBAR_W, cursor + 1, listH - 2,
                matches.size(), BIOME_ROWS);
        }
    }

    /**
     * The one scrollbar this page has, wherever it is drawn. Six wide and two flat fills: at four,
     * with a bevel on the knob, the whole bar was three pixels of edge and one of knob -- a sliver
     * that read as a rendering seam down the side of the list rather than as something to drag.
     *
     * <p>Writing the track into the page's own fields is what makes it draggable at all; see
     * {@link #mousePressed}.
     */
    private void scrollbar(GuiGraphics g, int trackX, int trackY, int trackH, int total, int shown) {
        barX = trackX;
        barY = trackY;
        barH = trackH;
        barTotal = total;
        barRows = shown;
        g.fill(barX, barY, barX + SCROLLBAR_W, barY + barH, Draw.TUBE);
        int knobH = knobHeight();
        int knobY = barY + (barH - knobH) * scroll / Math.max(1, total - shown);
        g.fill(barX + 1, knobY, barX + SCROLLBAR_W - 1, knobY + knobH,
            barAt >= 0 ? Draw.SELECT : Draw.EDGE_LIGHT);
    }

    /**
     * Who may be in this room, and at what level. SPEC.md 8.
     *
     * <p><b>Per room, from that room's own window</b>, which is the whole shape of the rule: an
     * invitation is to a room and never to the dimension, so there is deliberately no network-wide
     * guest list anywhere in this mod for somebody to add a name to by mistake.
     */
    private void guests(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot.Room room,
        int wx, int top) {
        int cursor = top;
        int plusX = wx + WIN_W - WIN_PAD - 14;
        int fieldX = plusX - 2 - SEARCH_W;
        int labelW = fieldX - wx - WIN_PAD - 4;
        Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.guests.label").getString(),
            wx + WIN_PAD, cursor + 2, labelW, Draw.TEXT_DIM);
        screen.hit(wx + WIN_PAD, cursor, labelW, SEARCH_H, () -> { },
            WorkbayScreen.gui("rooms.guests.label"), WorkbayScreen.gui("rooms.guests.tip"));

        // The same field the biome list uses, because a page owns one and a window shows one thing
        // at a time. What it means changes with the tab; what it is does not.
        String typed = screen.openFilter(fieldX + 4, cursor + 2, SEARCH_W - 8, SEARCH_H - 2);
        Draw.slot(g, fieldX, cursor, SEARCH_W, SEARCH_H);
        screen.hit(fieldX, cursor, SEARCH_W, SEARCH_H, screen::focusFilter);
        if (typed.isEmpty()) {
            Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.guests.name").getString(),
                fieldX + 4, cursor + 2, SEARCH_W - 8, Draw.TEXT_FAINT);
        }
        boolean canInvite = !typed.isBlank();
        boolean plusHover = canInvite && screen.hovered(plusX, cursor - 1, 14, 14, mouseX, mouseY);
        Draw.button(g, plusX, cursor - 1, 14, 14, plusHover, false, canInvite);
        WBIcons.draw(g, WBIcons.PLUS, plusX + 1, cursor, canInvite ? Draw.TEXT : Draw.TEXT_FAINT);
        int index = room.index();
        screen.hit(plusX, cursor - 1, 14, 14, () -> {
            if (canInvite) {
                screen.sendText(WorkbayAction.INVITE_ROOM_GUEST, index, typed.strip());
                screen.clearFilter();
            }
        }, WorkbayScreen.gui("rooms.guests.invite"), WorkbayScreen.gui("rooms.guests.invite.tip"));
        cursor += SEARCH_H + 2;

        int listW = WIN_W - WIN_PAD * 2;
        int listH = GUEST_ROWS * BIOME_H + 2;
        Draw.well(g, wx + WIN_PAD, cursor, listW, listH);
        java.util.List<com.neryos.workbay.world.RoomRecord.Guest> list = room.guests();
        boolean bar = list.size() > GUEST_ROWS;
        int rowW = listW - 2 - (bar ? SCROLLBAR_W + 1 : 0);
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - GUEST_ROWS));
        if (list.isEmpty()) {
            Draw.text(g, screen.font(), WorkbayScreen.gui("rooms.guests.none").getString(),
                wx + WIN_PAD + 5, cursor + 5, listW - 10, Draw.TEXT_FAINT);
        }
        for (int row = 0; row < GUEST_ROWS && scroll + row < list.size(); row++) {
            int at = scroll + row;
            com.neryos.workbay.world.RoomRecord.Guest guest = list.get(at);
            int gx = wx + WIN_PAD + 1;
            int gy = cursor + 1 + row * BIOME_H;
            Draw.well(g, gx, gy, rowW, BIOME_H);
            com.neryos.workbay.world.RoomGuest level = guest.level();
            boolean builds = level == com.neryos.workbay.world.RoomGuest.BUILD;
            Draw.text(g, screen.font(), guest.name(), gx + 4, gy + 3, rowW - 32, Draw.TEXT);
            long packed = index | ((long) at << 16);
            // The lock, not a new icon. "May not change anything" is exactly what a closed padlock
            // says everywhere else in this screen, and the middle level is the same padlock in the
            // colour the rest of the mod uses for "running, and watch it": the room's shape is
            // still shut, the things standing in it are not.
            int levelX = gx + rowW - 26;
            boolean levelHover = screen.hovered(levelX, gy + 1, 11, 11, mouseX, mouseY);
            Draw.button(g, levelX, gy + 1, 11, 11, levelHover, builds);
            int levelColour = switch (level) {
                case LOOK -> Draw.TEXT_DIM;
                case USE -> Draw.BLUE;
                case BUILD -> Draw.GREEN;
            };
            WBIcons.draw(g, builds ? WBIcons.UNLOCK : WBIcons.LOCK, levelX, gy, levelColour);
            String levelKey = "rooms.guest." + level.getSerializedName();
            screen.hit(levelX, gy + 1, 11, 11,
                () -> screen.send(WorkbayAction.CYCLE_ROOM_GUEST, packed),
                WorkbayScreen.gui(levelKey), WorkbayScreen.gui(levelKey + ".tip"));

            int dropX = gx + rowW - 13;
            boolean dropHover = screen.hovered(dropX, gy + 1, 11, 11, mouseX, mouseY);
            Draw.button(g, dropX, gy + 1, 11, 11, dropHover, false);
            WBIcons.draw(g, WBIcons.CROSS, dropX, gy, Draw.TEXT_DIM);
            screen.hit(dropX, gy + 1, 11, 11,
                () -> screen.send(WorkbayAction.REMOVE_ROOM_GUEST, packed),
                WorkbayScreen.gui("rooms.guests.remove", guest.name()),
                WorkbayScreen.gui("rooms.guests.remove.tip"));
        }
        if (bar) {
            scrollbar(g, wx + WIN_PAD + listW - 1 - SCROLLBAR_W, cursor + 1, listH - 2,
                list.size(), GUEST_ROWS);
        }
    }

    /** One of the window's two tabs. Pressing the one you are on does nothing; it is not a toggle. */
    private void windowTab(GuiGraphics g, int mouseX, int mouseY, int px, int py, String[] icon,
        Tab target, String key) {
        boolean here = tab == target;
        boolean hover = screen.hovered(px, py, 14, 14, mouseX, mouseY);
        Draw.button(g, px, py, 14, 14, hover, here);
        WBIcons.draw(g, icon, px + 1, py + 1, here ? Draw.TEXT : Draw.TEXT_DIM);
        screen.hit(px, py, 14, 14, () -> {
            if (!here) {
                tab = target;
                scroll = 0;
                lastQuery = "";
                barAt = -1;
                // The field means a different thing on each tab, so what was typed for one must
                // not be sitting in it as the other's first move.
                screen.clearFilter();
            }
        }, WorkbayScreen.gui("rooms.tab." + key), WorkbayScreen.gui("rooms.tab." + key + ".tip"));
    }

    /**
     * The choices whose name or id contains the search text, in the tag's own order.
     *
     * <p>Matched against the <b>translated name</b> as well as the id, because a player looking for
     * a cherry grove types "cherry", and a pack whose biome id is {@code terralith:yellowstone}
     * still calls it Yellowstone on screen.
     */
    private static java.util.List<
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> matching(
        String query) {
        var all = biomeChoices();
        if (query.isBlank()) {
            return all;
        }
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        return all.stream().filter(key -> {
            String id = key.location().toString();
            return id.toLowerCase(java.util.Locale.ROOT).contains(needle)
                || biomeName(id).getString().toLowerCase(java.util.Locale.ROOT).contains(needle);
        }).toList();
    }

    /**
     * The tag, read from the <b>client's own</b> registry: tags are synced, so the picker needs
     * nothing on the snapshot -- which is as well, because its codec group is full at sixteen.
     */
    private static java.util.List<
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> biomeChoices() {
        net.minecraft.client.multiplayer.ClientLevel level =
            net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? java.util.List.of()
            : com.neryos.workbay.world.RoomBiomes.choices(level.registryAccess());
    }

    /**
     * A biome's own name, from its own mod's lang file: every biome in the game already has
     * {@code biome.<namespace>.<path>}, so a modded biome added to {@code #workbay:room_biomes}
     * names itself and this mod ships no string for it.
     */
    static Component biomeName(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? Component.literal(id)
            : Component.translatable("biome." + location.getNamespace() + "."
                + location.getPath());
    }
}
