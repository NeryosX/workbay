package com.neryos.workbay.compat;

import com.neryos.workbay.client.screen.WorkbayScreen;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * The same filter slots, for the other recipe viewer. SPEC.md §5.
 *
 * <p>EMI and JEI are alternatives, not companions - a player has one of them - so this is a second
 * eight-line adapter over {@link WorkbayScreen#ghostTargets()} rather than an abstraction over
 * both. Two adapters onto one list is smaller than any interface that could unify them, and
 * neither mod's API is ours to change.
 *
 * <p><b>Never loaded without EMI</b>: {@code @EmiEntrypoint} is found by EMI's own scan and nothing
 * else, which is why its API is {@code compileOnly}.
 */
@EmiEntrypoint
public class WorkbayEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(WorkbayScreen.class, new FilterSlots());
        // OPEN_ISSUES #123, EMI's half: the same whole-window exclusion as JEI's, for the same
        // screens, and lifted the same way while a filter slot can take a drag.
        registry.addExclusionArea(WorkbayScreen.class, (screen, out) -> {
            if (!WorkbayGuide.wantsIngredientList(screen)) {
                out.accept(new dev.emi.emi.api.widget.Bounds(0, 0, screen.width, screen.height));
            }
        });
        registry.addExclusionArea(com.neryos.workbay.client.screen.RoomDoorScreen.class,
            (screen, out) -> out.accept(
                new dev.emi.emi.api.widget.Bounds(0, 0, screen.width, screen.height)));
        registry.addExclusionArea(com.neryos.workbay.client.screen.ConnectorScreen.class,
            (screen, out) -> out.accept(
                new dev.emi.emi.api.widget.Bounds(0, 0, screen.width, screen.height)));
        // The same guide pages JEI shows, from the same list. EMI has no "add an info page" call:
        // an info page *is* a recipe in a built-in category, so this constructs one per item.
        for (WorkbayGuide.Page page : WorkbayGuide.pages()) {
            net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(page.item().get());
            registry.addRecipe(new dev.emi.emi.api.recipe.EmiInfoRecipe(
                java.util.List.of(dev.emi.emi.api.stack.EmiStack.of(stack)),
                page.lines(),
                com.neryos.workbay.Workbay.rl("info/"
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        stack.getItem()).getPath())));
        }
    }

    private static final class FilterSlots implements EmiDragDropHandler<WorkbayScreen> {

        @Override
        public boolean dropStack(WorkbayScreen screen, EmiIngredient dragged, int x, int y) {
            ItemStack stack = itemOf(dragged);
            if (stack.isEmpty()) {
                return false;
            }
            for (WorkbayScreen.Ghost slot : screen.ghostTargets()) {
                if (x >= slot.x() && x < slot.x() + slot.w()
                    && y >= slot.y() && y < slot.y() + slot.h()) {
                    slot.accept().accept(stack);
                    return true;
                }
            }
            return false;
        }

        /** Outlines every slot that would take the drag, which is what EMI does elsewhere. */
        @Override
        public void render(WorkbayScreen screen, EmiIngredient dragged, GuiGraphics graphics,
            int mouseX, int mouseY, float partial) {
            if (itemOf(dragged).isEmpty()) {
                return;
            }
            for (WorkbayScreen.Ghost slot : screen.ghostTargets()) {
                graphics.renderOutline(slot.x() - 1, slot.y() - 1, slot.w() + 2, slot.h() + 2,
                    0xFF5AA9E6);
            }
        }

        /**
         * An EMI ingredient may be a tag, which is several stacks. A filter holding one item takes
         * the first rather than refusing, because that is what the player saw under the cursor.
         */
        private static ItemStack itemOf(EmiIngredient dragged) {
            return dragged.getEmiStacks().isEmpty()
                ? ItemStack.EMPTY
                : dragged.getEmiStacks().get(0).getItemStack();
        }
    }
}
