package com.neryos.workbay.client.screen;

import com.neryos.workbay.content.workbay.WorkbayUpgrade;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbaySnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Screen 3 - Upgrades. SPEC.md §4 and §1.
 *
 * <p>Upgrades are <b>consumed on install</b> and recorded as counters, so there is no slot grid and
 * no removal path: an Add button takes the item out of the player's inventory and the count goes up
 * by one, for good. Rows the player cannot afford draw at 45% rather than disappearing, because the
 * ladder is the progression and hiding its rungs hides the game.
 *
 * <p>Taller than SPEC.md §4's 206: three upgrade rows at 34px plus the header
 * do not fit in 206 without the rows losing their one-line description, which is the only thing on
 * the row that says what the upgrade does.
 */
class UpgradesPage extends WorkbayPage {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 212;

    private static final int ROW_X = 118;
    private static final int ROW_W = 194;
    private static final int ROW_Y = 32;
    private static final int ROW_H = 34;
    private static final int ROW_PITCH = 38;

    /**
     * The row's three columns, fixed rather than derived from what happens to be in them.
     *
     * <p>Both the count and the price used to be right-aligned against the Add button and the text
     * clamped to whatever they left over, so a figure that grew a digit ate the description's last
     * characters - the row that told you the least was the one about to charge you the most. The
     * price column is gone and the description has its width, and the
     * text gets everything to its left whatever the number turns out to be.
     */
    private static final int TEXT_X = 28;
    private static final int RIGHT_EDGE = ROW_W - 36;
    private static final int COUNT_W = 32;
    private static final int PRICE_W = 52;
    private static final int NAME_W = RIGHT_EDGE - COUNT_W - TEXT_X - 4;
    private static final int DESC_W = RIGHT_EDGE - PRICE_W - TEXT_X - 4;

    /** The block preview turns as the player drags it, since it is the only art on the screen. */
    private static final BlockPreview PREVIEW = new BlockPreview();

    private static final int WELL_X = 12;
    private static final int WELL_Y = 32;
    private static final int WELL_W = 96;
    private static final int WELL_H = 74;

    UpgradesPage(WorkbayScreen screen) {
        super(screen);
    }

    @Override
    int width() {
        return WIDTH;
    }

    @Override
    int height() {
        return Math.max(HEIGHT, screen.panelHeight());
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        header(g, mouseX, mouseY, "UPGRADES");
        preview(g, mouseX, mouseY);
        rows(g, mouseX, mouseY);
    }

    /** Left: the block, its power bar at full size with exact figures, and the incoming rate. */
    private void preview(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();

        Draw.well(g, x(WELL_X), y(WELL_Y), WELL_W, WELL_H);
        // The Workbay's own block, rendered by the game rather than drawn: this screen is where a
        // player looks at what they are upgrading.
        PREVIEW.render(g, BlockPreview.facingCamera(
                com.neryos.workbay.init.WBBlocks.WORKBAY.get().defaultBlockState()),
            x(WELL_X + WELL_W / 2), y(WELL_Y + WELL_H / 2), 34);
        textCentre(g, WorkbayScreen.gui("faces.drag").getString(), x(WELL_X + WELL_W / 2),
            y(WELL_Y + WELL_H + 3), WELL_W, Draw.TEXT_FAINT);

        // <b>Only where running actually draws power.</b> Both knobs ship at zero, which means
        // nothing ever spends this buffer -- so the bar never moves, the figure never changes and
        // the intake rate prices a thing that is free. Three readings about a charge nobody makes,
        // on the screen a player opens to decide what to buy. The owner's call: when the value is
        // zero the row is not there at all. A pack that turns either knob on gets all three back.
        boolean charged = snap.charged();
        if (charged) {
            Draw.bar(g, x(WELL_X), y(WELL_Y + WELL_H + 16), WELL_W, 12,
                snap.energy(), snap.energyCapacity(), Draw.ENERGY);
            // Compact, like every other energy figure in the mod: "0 / 100000 FE" is 74 pixels in
            // a column 96 wide before the capacity grows a digit; the exact one is in the tooltip.
            text(g, Draw.compact(snap.energy()) + " / " + Draw.compact(snap.energyCapacity()) + " FE",
                x(WELL_X), y(WELL_Y + WELL_H + 32), WELL_W, Draw.TEXT_DIM);
            screen.hit(x(WELL_X), y(WELL_Y + WELL_H + 16), WELL_W, 26, () -> { },
                WorkbayScreen.gui("power", Draw.exact(snap.energy()),
                    Draw.exact(snap.energyCapacity())),
                WorkbayScreen.gui("power.tip"));
            text(g, WorkbayScreen.gui("upgrades.rate",
                    com.neryos.workbay.content.workbay.WorkbayBlockEntity.MAX_FE_PER_TICK),
                x(WELL_X), y(WELL_Y + WELL_H + 44), WELL_W, Draw.TEXT_FAINT);
        }
        // <b>"Placed: 1 / 1" is gone, and so is the model it counted.</b> It said how many Workbay
        // blocks could be doors onto one network, drawn amber -- this mod's colour for a problem --
        // on the ordinary state of every network that has ever existed (OPEN_ISSUES #88). One
        // block is one network now, and which network this block is belongs on NETWORKS, beside
        // the others, where it can be changed.
    }

    @Override
    boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || mouseX < x(WELL_X) || mouseX >= x(WELL_X + WELL_W)
            || mouseY < y(WELL_Y) || mouseY >= y(WELL_Y + WELL_H)) {
            return false;
        }
        PREVIEW.press();
        return true;
    }

    @Override
    boolean mouseDragged(double dragX, double dragY) {
        PREVIEW.drag(dragX, dragY);
        return false;
    }

    @Override
    boolean mouseReleased(double mouseX, double mouseY) {
        PREVIEW.release();
        return false;
    }

    private void rows(GuiGraphics g, int mouseX, int mouseY) {
        WorkbaySnapshot snap = snapshot();

        int row = -1;
        for (WorkbayUpgrade upgrade : WorkbayUpgrade.values()) {
            row++;
            int index = upgrade.ordinal();
            int px = x(ROW_X);
            int py = y(ROW_Y + row * ROW_PITCH);
            int installed = snap.upgrades().installed(upgrade);
            boolean maxed = installed >= upgrade.max();
            // <b>One gate per rung.</b> An upgrade costs its item and nothing else now: the Levy
            // was a second gate on the same decision and neither its author nor a player could say
            // in one sentence what it was for. Whether the player is holding the item is the
            // server's question, so the row cannot answer it -- the click either installs or says
            // why, which is the same shape every other refusal on these screens has.
            boolean canInstall = !maxed && snap.owned();

            Draw.well(g, px, py, ROW_W, ROW_H);
            // The whole row, registered before the + so the + still wins the click: hits dispatch
            // in reverse registration order. A row carried a name and a two-word line and had no
            // hover at all, so the only way to the sentence explaining it was to point at a 22px
            // button whose own tooltip is about buying it. OPEN_ISSUES #68.
            // The recipe is the row's last line (#130): without a recipe viewer this row is where a
            // player learns an upgrade exists, and it never said what one is made of.
            net.minecraft.network.chat.Component recipe = recipeLine(upgrade);
            screen.hit(px, py, ROW_W, ROW_H, () -> { },
                WorkbayScreen.gui("upgrade." + upgrade.getSerializedName()),
                WorkbayScreen.gui("upgrade." + upgrade.getSerializedName() + ".long"), recipe);

            ItemStack icon = new ItemStack(upgrade.item());
            g.renderItem(icon, px + 6, py + 9);

            String key = "upgrade." + upgrade.getSerializedName();
            // Name over description on the left, count over price on the right. Both right-hand
            // figures are right-aligned inside one fixed column, so neither can reach back into
            // the text.
            String count = installed + " / " + upgrade.max();
            // A name is never faint. It says what the thing <em>is</em>, and that does not change
            // with what is in the bank: on a network that cannot afford anything the whole page
            // came out greyed, four dead rows with nothing for the eye to land on. Whether you
            // can have it is said by the + being drawn disabled, and that is not the name's job.
            // Same rule on ROOMS.
            text(g, WorkbayScreen.gui(key), px + TEXT_X, py + 6, NAME_W, Draw.TEXT);
            textRight(g, count, px + RIGHT_EDGE, py + 6, COUNT_W,
                maxed ? Draw.GREEN : Draw.TEXT_DIM);

            text(g, WorkbayScreen.gui(key + ".desc"), px + TEXT_X, py + 19, DESC_W + PRICE_W,
                Draw.TEXT_FAINT);

            int addX = px + ROW_W - 30;
            int addY = py + 8;
            boolean hover = canInstall && screen.hovered(addX, addY, 22, 18, mouseX, mouseY);
            Draw.button(g, addX, addY, 22, 18, hover, maxed, canInstall);
            WBIcons.draw(g, WBIcons.PLUS, addX + 5, addY + 3,
                canInstall ? Draw.TEXT : Draw.TEXT_FAINT);
            screen.hit(addX, addY, 22, 18,
                canInstall ? () -> screen.send(WorkbayAction.INSTALL_UPGRADE, index) : () -> { },
                // One line, not two. A maxed row printed "as many as a Workbay takes" twice, and an
                maxed ? new net.minecraft.network.chat.Component[] {
                    WorkbayScreen.gui(key), WorkbayScreen.gui("upgrades.maxed") }
                    : !canInstall ? new net.minecraft.network.chat.Component[] {
                        WorkbayScreen.gui(key),
                        com.neryos.workbay.WorkbayLang.message("owner_only") }
                    : new net.minecraft.network.chat.Component[] {
                        WorkbayScreen.gui(key),
                        WorkbayScreen.gui(key + ".long"),
                        WorkbayScreen.gui("upgrades.add", WorkbayScreen.gui(key)), recipe });
        }
    }

    /** What the upgrade is made of and where its recipe comes from, as one tooltip paragraph. */
    private static net.minecraft.network.chat.Component recipeLine(WorkbayUpgrade upgrade) {
        return com.neryos.workbay.WorkbayLang.tooltip("recipe." + upgrade.getSerializedName())
            .append(" ").append(WorkbayScreen.gui("upgrades.unlock"))
            .withStyle(net.minecraft.ChatFormatting.GRAY);
    }

}
