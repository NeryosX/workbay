package com.neryos.workbay.client.screen;

import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbaySnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Screen 5 - Networks. SPEC.md §0's network model.
 *
 * <p><b>One Workbay block is one network</b>, and this is the page that says which. It is the only
 * page a Workbay holding <em>no</em> network has: placing one while every network you own already
 * has a block is never refused, so the block stands there empty and opens here, on the list, with a
 * Transfer beside each row. It is also reachable from a working Workbay, because "which network is
 * this block" is a question with an answer that can be changed.
 *
 * <p>A row is a network: its name, where its block stands, and what is in it. <b>Never its code</b>
 * - SPEC.md §0 mints one so a lost network can be recovered by typing it to an operator and shows
 * it nowhere else. A network with no block reads <em>Asleep</em>, which is the whole of what that
 * state is: everything kept, nothing ticking.
 */
class NetworksPage extends WorkbayPage {

    private static final int WIDTH = 320;

    private static final int ROW_X = 12;
    private static final int ROW_W = WIDTH - 2 * ROW_X;
    private static final int ROW_H = 34;
    private static final int ROW_PITCH = 38;

    /** The right-hand strip the two buttons own, which no text may reach into. */
    private static final int CONTROLS_W = 56;

    /**
     * The banner a Workbay holding nothing draws, and the room it takes off the top of the list.
     * Deep enough for a title and <b>two</b> wrapped lines: the sentence under it is the only
     * instruction this screen gives, and at 280 pixels one line cut it at "Pick one below and ...".
     * Photographed cut, in play.
     */
    private static final int BANNER_H = 46;

    /** The "New network" button in the banner, when the player is under the limit. */
    private static final int NEW_W = 84;

    NetworksPage(WorkbayScreen screen) {
        super(screen);
    }

    @Override
    int width() {
        return WIDTH;
    }

    @Override
    int height() {
        int natural = rowsTop() + Math.max(1, snapshot().networks().size()) * ROW_PITCH + 22;
        // A bound Workbay's pages share one height (#89); an empty block has this page alone and
        // no tab strip to keep still, so it stays as tall as its list.
        return snapshot().bound() ? Math.max(natural, screen.panelHeight()) : natural;
    }

    private int rowsTop() {
        return HEADER_H + 7 + (snapshot().bound() ? 0 : BANNER_H + 6);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        header(g, mouseX, mouseY, "NETWORKS");
        WorkbaySnapshot snap = snapshot();
        if (!snap.bound()) {
            banner(g, mouseX, mouseY);
        }
        if (snap.networks().isEmpty()) {
            Draw.well(g, x(ROW_X), y(rowsTop()), ROW_W, ROW_H);
            text(g, WorkbayScreen.gui("networks.none"), x(ROW_X + 8), y(rowsTop() + 12),
                ROW_W - 16, Draw.TEXT_FAINT);
        }
        for (int i = 0; i < snap.networks().size(); i++) {
            row(g, mouseX, mouseY, snap.networks().get(i), rowsTop() + i * ROW_PITCH);
        }
        // <b>The count and the limit, and nothing else on the line.</b> It is the one number that
        // explains why a Workbay ever stands empty, and a rule a player only meets as a surprise is
        // a rule nobody can plan around -- but the sentence that goes with it does not fit in 296
        // pixels, and a cut sentence says less than no sentence. Photographed cut, in play.
        textCentre(g, WorkbayScreen.gui("networks.footer", snap.networks().size(),
                snap.maxNetworks()).getString(),
            x(WIDTH / 2), y(height() - 16), WIDTH - 24, Draw.TEXT_FAINT);
        screen.hit(x(ROW_X), y(height() - 18), ROW_W, 12, () -> { },
            WorkbayScreen.gui("networks.footer", snap.networks().size(), snap.maxNetworks()),
            WorkbayScreen.gui("networks.footer.tip"));
    }

    /**
     * What a Workbay holding nothing says, before the list it is asking the player to use.
     *
     * <p><b>Two states, because there are two reasons to be empty.</b> It said "Workbay quota
     * reached" for both, and the second one made it a lie the screen could disprove on its own
     * line: a Transfer leaves the block it moved a network out of standing empty, and that player
     * is now <em>under</em> the limit -- so the footer read "1 of 2" over a banner saying the quota
     * was reached. Found in the photograph. Under the limit the banner is neutral, not
     * amber (amber is a problem everywhere in this mod, and this is not one), and carries the
     * button that makes the block a network.
     */
    private void banner(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();
        // <b>Two independent facts, not one.</b> Whether a network can be *minted* here is the
        // limit; whether one can be *moved* here is whether you own any at all -- Transfer takes a
        // network out of a live block as readily as it wakes a sleeping one. The banner was amber
        // whenever the count reached the limit, which called a block "quota reached" while every
        // row under it offered a Transfer that works (OPEN_ISSUES #106). Amber is this mod's
        // colour for a problem, and the only real problem is having neither.
        boolean canMint = snap.networks().size() < snap.maxNetworks();
        boolean canTake = snap.networks().stream().anyMatch(net -> !net.here());
        boolean stuck = !canMint && !canTake;
        int room = ROW_W - 16 - (canMint ? NEW_W + 6 : 0);
        Draw.notice(g, x(ROW_X), y(HEADER_H + 5), ROW_W, BANNER_H);
        text(g, WorkbayScreen.gui(stuck ? "networks.quota" : "networks.empty"),
            x(ROW_X + 8), y(HEADER_H + 11), room, stuck ? Draw.AMBER : Draw.TEXT);
        wrapped(g, WorkbayScreen.gui(stuck ? "networks.quota.tip"
                : canMint ? "networks.empty.tip" : "networks.empty.take.tip"),
            x(ROW_X + 8), y(HEADER_H + 22), room, Draw.TEXT_DIM);
        if (!canMint) {
            return;
        }
        int bx = x(ROW_X + ROW_W - NEW_W - 6);
        int by = y(HEADER_H + 15);
        boolean hover = screen.hovered(bx, by, NEW_W, 20, mouseX, mouseY);
        Draw.button(g, bx, by, NEW_W, 20, hover, false, true);
        textCentre(g, WorkbayScreen.gui("networks.new").getString(), bx + NEW_W / 2, by + 6,
            NEW_W - 6, Draw.TEXT);
        screen.hit(bx, by, NEW_W, 20, () -> screen.send(WorkbayAction.NEW_NETWORK),
            WorkbayScreen.gui("networks.new"), WorkbayScreen.gui("networks.new.tip"));
    }

    private void row(GuiGraphics g, int mouseX, int mouseY, WorkbaySnapshot.Net net, int localY) {
        int px = x(ROW_X);
        int py = y(localY);
        int textW = ROW_W - CONTROLS_W - 16;

        Draw.well(g, px, py, ROW_W, ROW_H);
        if (net.here()) {
            // The one the player is standing at, marked on the row itself rather than only by its
            // greyed-out Transfer: a state said by a disabled control is a state said by nothing.
            Draw.round(g, px, py, 3, ROW_H, 1, Draw.SELECT);
        }

        // The name, and clicking it renames it. The same gesture a Connector's name has, for the
        // same reason: one object, one name, changed where you are already looking at it.
        String name = net.name();
        if (!screen.renaming(net.id())) {
            text(g, name, px + 8, py + 6, textW - 62, Draw.TEXT);
        }
        screen.hit(px + 8, py + 5, textW - 62, 12,
            () -> screen.beginRename(net.id(), px + 8, py + 4, textW - 62, 12, name,
                typed -> screen.sendText(WorkbayAction.SET_NETWORK_NAME, typed, net.id())),
            WorkbayScreen.gui("networks.rename"), WorkbayScreen.gui("networks.rename.tip"));

        textRight(g, WorkbayScreen.gui(net.here() ? "networks.here"
                : net.asleep() ? "networks.asleep" : "networks.live").getString(),
            px + 8 + textW, py + 6, 60,
            net.here() ? Draw.SELECT : net.asleep() ? Draw.TEXT_FAINT : Draw.GREEN);

        // Where its block stands, on its own line under the name. A network you can walk to is a
        // network you can check; "Asleep" is the honest answer when there is nowhere to walk.
        text(g, net.where().map(NetworksPage::describe)
                .orElseGet(() -> WorkbayScreen.gui("networks.nowhere").getString()),
            px + 8, py + 19, textW - 96, Draw.TEXT_FAINT);
        textRight(g, WorkbayScreen.gui("networks.holding", net.bays(), net.connectors()).getString(),
            px + 8 + textW, py + 19, 94, Draw.TEXT_FAINT);

        int renameX = px + ROW_W - CONTROLS_W + 4;
        iconButton(g, mouseX, mouseY, renameX, py + 8, WBIcons.RENAME, false,
            () -> screen.beginRename(net.id(), px + 8, py + 4, textW - 62, 12, name,
                typed -> screen.sendText(WorkbayAction.SET_NETWORK_NAME, typed, net.id())),
            WorkbayScreen.gui("networks.rename"), WorkbayScreen.gui("networks.rename.tip"));

        int transferX = px + ROW_W - 26;
        boolean can = !net.here();
        boolean hover = can && screen.hovered(transferX, py + 8, 22, 18, mouseX, mouseY);
        Draw.button(g, transferX, py + 8, 22, 18, hover, false, can);
        WBIcons.draw(g, WBIcons.ENTER, transferX + 5, py + 11, can ? Draw.TEXT : Draw.TEXT_FAINT);
        screen.hit(transferX, py + 8, 22, 18,
            can ? () -> screen.send(WorkbayAction.TRANSFER_NETWORK, net.id()) : () -> { },
            can ? new Component[] {
                WorkbayScreen.gui("networks.transfer"),
                WorkbayScreen.gui(net.asleep() ? "networks.transfer.asleep.tip"
                    : "networks.transfer.live.tip", net.name()) }
                : new Component[] {
                    WorkbayScreen.gui("networks.transfer"),
                    WorkbayScreen.gui("networks.transfer.here.tip") });
    }

    /**
     * Where a block stands, as a player would say it. The dimension by its own translation key, so
     * a modded one reads as its own name rather than {@code somemod:some_dim}.
     */
    private static String describe(net.minecraft.core.GlobalPos where) {
        return net.minecraft.network.chat.Component.translatable(
                "dimension." + where.dimension().location().getNamespace() + "."
                    + where.dimension().location().getPath()).getString()
            + "  " + where.pos().getX() + " " + where.pos().getY() + " " + where.pos().getZ();
    }
}
