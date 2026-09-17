package com.neryos.workbay.client.screen;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.menu.ConnectorMenu;
import com.neryos.workbay.menu.WorkbayAction;
import com.neryos.workbay.network.ActionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * The rename panel a placed Connector opens. OPEN_ISSUES #77.
 *
 * <p>One field and one button, because the gesture means one thing. Everything a Connector used to
 * do from the world -- pick a resource, pick a bay, make another row -- is on the bay screen, and
 * the reason is that those are questions about a <em>bay</em>: the answer depends on which bay you
 * are standing in, and a block in the world cannot know that.
 *
 * <p>The line under the field is what the Connector is called when the field is empty: <b>its own
 * coordinates</b>. Said rather than pre-filled, so pressing the button without typing does not
 * silently freeze the coordinates into a stored name that stops following the block.
 */
public class ConnectorScreen extends AbstractContainerScreen<ConnectorMenu> {

    private static final int WIDTH = 190;
    private static final int MARGIN = 10;
    private static final int ROW_W = WIDTH - MARGIN * 2;

    private static final int TITLE_Y = 10;
    private static final int FIELD_Y = 26;
    private static final int FIELD_H = 16;
    private static final int HINT_Y = FIELD_Y + FIELD_H + 6;
    private static final int USE_Y = HINT_Y + 13;
    private static final int SAVE_Y = USE_Y + 16;
    private static final int SAVE_H = 20;
    private static final int HEIGHT = SAVE_Y + SAVE_H + MARGIN;

    private WBTextField field;

    public ConnectorScreen(ConnectorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        field = new WBTextField(font, leftPos + MARGIN + 4, topPos + FIELD_Y + 4, ROW_W - 8, 12);
        field.setMaxLength(32);
        field.setBordered(false);
        field.setValue(menu.view().name());
        field.moveCursorToEnd();
        addRenderableWidget(field);
        setFocused(field);
        field.setFocused(true);
    }

    /** Drawn in {@link #renderBg} with the width it has; vanilla's two labels name nothing here. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        Draw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        int px = leftPos + MARGIN;

        Draw.text(g, font, WorkbayLang.gui("connector.title").getString(), px, topPos + TITLE_Y,
            ROW_W, Draw.TEXT);
        Draw.slot(g, px, topPos + FIELD_Y, ROW_W, FIELD_H);

        // <b>A Connector belongs to a Workbay, never to a bay.</b> This line used to end
        // "* on bay 2", which was wrong even when a Connector had one row and is now unanswerable:
        // a Connector carries a row for every time it was pulled into a bay, and those rows can sit
        // on different bays at once. What a bay owns is a row. What this panel names is the block.
        Component hint = WorkbayLang.gui("connector.hint", menu.view().fallback());
        Draw.text(g, font, hint.getString(), px, topPos + HINT_Y, ROW_W, Draw.TEXT_DIM);

        // <b>What this block is doing</b>, which is the one other thing it can answer for itself.
        // A Connector carries a channel for every time a bay added it, and none at all until one
        // does -- so a freshly placed one says where to go rather than leaving the player holding
        // a block that appears to do nothing. The panel was a title, a box and a button before,
        // which is the emptiness OPEN_ISSUES #92 objected to on the room door.
        // Never amber: amber is this mod's colour for a problem, and a Connector nobody has
        // added to a bay yet is the normal state of one that was placed a second ago.
        int carried = menu.view().channels();
        Draw.text(g, font, (carried == 0 ? WorkbayLang.gui("connector.unused")
            : carried == 1 ? WorkbayLang.gui("connector.channel")
            : WorkbayLang.gui("connector.channels", carried)).getString(),
            px, topPos + USE_Y, ROW_W, Draw.TEXT_DIM);

        boolean over = isHovering(MARGIN, SAVE_Y, ROW_W, SAVE_H, mouseX, mouseY);
        Draw.button(g, px, topPos + SAVE_Y, ROW_W, SAVE_H, over, false);
        Draw.textCentre(g, font, WorkbayLang.gui("connector.save").getString(),
            px + ROW_W / 2, topPos + SAVE_Y + (SAVE_H - font.lineHeight) / 2 + 1,
            ROW_W - 8, Draw.TEXT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isHovering(MARGIN, SAVE_Y, ROW_W, SAVE_H, mouseX, mouseY)) {
            save();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Enter saves, and the field takes every other key.
     *
     * <p>Falling through to {@code super} on a letter is what closes a screen while somebody is
     * typing: a letter reaches the box through {@code charTyped}, so {@code keyPressed} declines
     * it and vanilla's inventory-key check runs instead. {@code canConsumeInput} is the guard
     * vanilla uses for exactly this, and it is why an <b>e</b> in a name no longer shuts the box.
     */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            // Before the field's own guard, or {@code canConsumeInput} swallows it and the one
            // key everybody presses to back out of a box does nothing at all.
            onClose();
            return true;
        }
        if (field != null && field.isFocused()) {
            return field.keyPressed(key, scan, modifiers) || field.canConsumeInput()
                || super.keyPressed(key, scan, modifiers);
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void save() {
        PacketDistributor.sendToServer(new ActionPacket(menu.containerId,
            WorkbayAction.SET_CONNECTOR_NAME, 0, Optional.empty(),
            Optional.of(field == null ? "" : field.getValue()), false));
        onClose();
    }
}
