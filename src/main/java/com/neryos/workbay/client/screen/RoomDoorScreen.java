package com.neryos.workbay.client.screen;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.menu.RoomDoorMenu;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.menu.WorkbaySnapshot;
import com.neryos.workbay.network.ActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * What a room's wall opens. SPEC.md §8.
 *
 * <p>Two things and no more: <b>out</b>, and <b>next door</b>. It is the screen a player opens
 * while standing in a box wanting to be somewhere else, and every control that is not one of those
 * two is a control between them and the door. The room ladder, the biome and the colours all live
 * on the Workbay's ROOMS page, which is where a player is deciding rather than leaving.
 *
 * <p><b>Leaving is the big button and it is on top.</b> The first draft listed the rooms first and
 * put Leave among them at the same weight, and it read as a menu with no obvious answer - which is
 * the wrong thing to hand somebody who clicked a wall because they wanted out. Everything below the
 * rule is the optional half.
 *
 * <p>Every x on this screen is derived from one column table, because the first draft's were not
 * and the rows did not line up with each other or with the panel.
 */
public class RoomDoorScreen extends AbstractContainerScreen<RoomDoorMenu> {

    private static final int WIDTH = 200;
    private static final int MARGIN = 10;
    private static final int ROW_W = WIDTH - MARGIN * 2;

    private static final int TITLE_Y = 10;
    private static final int LEAVE_Y = 26;
    private static final int LEAVE_H = 26;
    /** The rule under the way out, and the label that names what is below it. */
    private static final int RULE_Y = LEAVE_Y + LEAVE_H + 10;
    private static final int LABEL_Y = RULE_Y + 6;
    private static final int FIRST_ROOM = LABEL_Y + 14;
    private static final int ROOM_H = 20;
    private static final int ROOM_PITCH = 22;

    /** Inside a room row: swatch, name, then the button hard against the right edge. */
    private static final int SWATCH_X = 6;
    private static final int SWATCH = 10;
    private static final int NAME_X = SWATCH_X + SWATCH + 6;
    private static final int GO_W = 46;
    private static final int GO_X = ROW_W - GO_W - 4;
    private static final int NAME_W = GO_X - NAME_X - 6;

    public RoomDoorScreen(RoomDoorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        // A door with nowhere else to go is the title and Leave, and no room kept for a list
        // it does not draw -- a room in nobody's bay, or a Workbay holding one room, showed a
        // panel two thirds empty under the one button on it.
        int others = menu.view().rooms().size();
        imageHeight = others <= 1 ? LEAVE_Y + LEAVE_H + MARGIN
            : FIRST_ROOM + others * ROOM_PITCH + MARGIN - 2;
    }

    /** Drawn in {@link #renderBg}, with the width it has. Vanilla's two labels name nothing here. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        Draw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        int px = leftPos + MARGIN;

        // <b>The room's own name is the title.</b> It used to say "Way out", which is what the
        // button underneath it says -- one of the two was furniture. And the fact this screen was
        // missing is the one a player standing in a grey box actually wants: which room this is.
        // The list below says where you could go; nothing said where you were.
        List<WorkbaySnapshot.Room> all = menu.view().rooms();
        int current = menu.view().current();
        String title = current >= 0 && current < all.size()
            ? all.get(current).name() : WorkbayLang.gui("door.title").getString();
        Draw.text(g, font, title, px, topPos + TITLE_Y, ROW_W, Draw.TEXT);

        // Out: the answer to the question that made somebody click a wall, at twice a row's height
        // and the full width of the panel.
        boolean over = isHovering(MARGIN, LEAVE_Y, ROW_W, LEAVE_H, mouseX, mouseY);
        Draw.button(g, px, topPos + LEAVE_Y, ROW_W, LEAVE_H, over, false);
        Draw.textCentre(g, font, WorkbayLang.gui("door.leave").getString(),
            px + ROW_W / 2, topPos + LEAVE_Y + (LEAVE_H - font.lineHeight) / 2 + 1,
            ROW_W - 8, Draw.TEXT);

        List<WorkbaySnapshot.Room> rooms = all;
        if (rooms.size() <= 1) {
            return;
        }
        g.fill(px, topPos + RULE_Y, px + ROW_W, topPos + RULE_Y + 1, Draw.EDGE_DARK);
        Draw.text(g, font, WorkbayLang.gui("door.elsewhere").getString(), px, topPos + LABEL_Y,
            ROW_W, Draw.TEXT_DIM);

        for (int i = 0; i < rooms.size(); i++) {
            WorkbaySnapshot.Room room = rooms.get(i);
            int ry = FIRST_ROOM + i * ROOM_PITCH;
            int ry0 = topPos + ry;
            boolean here = i == menu.view().current();
            Draw.well(g, px, ry0, ROW_W, ROOM_H);

            // The room's own colour, which on this screen is the only thing that tells two rooms
            // with the same name apart before you walk into one. Only a built room has one: four
            // identical default swatches down the column said nothing at all, and said it about
            // rooms that do not exist yet.
            int textY = ry0 + (ROOM_H - font.lineHeight) / 2 + 1;
            if (room.built()) {
                int swatchY = ry0 + (ROOM_H - SWATCH) / 2;
                g.fill(px + SWATCH_X, swatchY, px + SWATCH_X + SWATCH, swatchY + SWATCH,
                    0xFF000000 | room.colour().tint());
            }

            String name = room.name();

            if (here) {
                // "You are here" is 72 pixels and the button column is 46, so on this one row the
                // name gives up what the label needs. It printed "You are he..." otherwise -- the
                // one string on the screen whose whole job is to say where you are standing.
                Draw.text(g, font, name, px + NAME_X, textY, NAME_W - 26, Draw.TEXT_DIM);
                Draw.textRight(g, font, WorkbayLang.gui("door.here").getString(),
                    px + ROW_W - 6, textY, GO_W + 26, Draw.TEXT_FAINT);
                continue;
            }
            Draw.text(g, font, name, px + NAME_X, textY, NAME_W, Draw.TEXT);
            boolean hover = isHovering(MARGIN + GO_X, ry, GO_W, ROOM_H - 4, mouseX, mouseY);
            Draw.button(g, px + GO_X, ry0 + 2, GO_W, ROOM_H - 4, hover, false);
            Draw.textCentre(g, font,
                WorkbayLang.gui(room.built() ? "door.go" : "door.open").getString(),
                px + GO_X + GO_W / 2, textY, GO_W - 4, Draw.TEXT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isHovering(MARGIN, LEAVE_Y, ROW_W, LEAVE_H, mouseX, mouseY)) {
                send(WorkbayAction.LEAVE_ROOM, 0);
                return true;
            }
            List<WorkbaySnapshot.Room> rooms = menu.view().rooms();
            for (int i = 0; i < rooms.size(); i++) {
                if (i != menu.view().current() && isHovering(MARGIN + GO_X,
                        FIRST_ROOM + i * ROOM_PITCH, GO_W, ROOM_H - 4, mouseX, mouseY)) {
                    send(WorkbayAction.ENTER_ROOM, rooms.get(i).index());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void send(WorkbayAction action, int arg) {
        PacketDistributor.sendToServer(new ActionPacket(menu.containerId, action, arg,
            Optional.empty(), Optional.empty(), false));
    }
}
