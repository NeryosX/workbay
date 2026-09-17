package com.neryos.workbay.compat;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.client.screen.WorkbayScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Items dragged out of JEI land in the LINKS rows' filter slots. SPEC.md §5.
 *
 * <p><b>Never loaded without JEI.</b> {@code @JeiPlugin} classes are found by JEI's own annotation
 * scan and by nothing else, so with JEI absent this class is never touched and its imports never
 * resolve - which is the whole reason the API is {@code compileOnly}.
 *
 * <p>It knows nothing about the layout. {@link WorkbayScreen} collects its ghost slots while the
 * page draws, exactly as it collects its clickable regions, so a slot's geometry is written once.
 */
@JeiPlugin
public class WorkbayJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return Workbay.rl("jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(WorkbayScreen.class, new FilterSlots());
    }

    /**
     * The guide pages, on each item's Information tab. {@link WorkbayGuide} holds the list; this
     * is the whole of JEI's half of it.
     */
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (WorkbayGuide.Page page : WorkbayGuide.pages()) {
            registration.addIngredientInfo(page.item().get(),
                page.lines().toArray(net.minecraft.network.chat.Component[]::new));
        }
    }

    private static final class FilterSlots implements IGhostIngredientHandler<WorkbayScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(WorkbayScreen screen,
            ITypedIngredient<I> ingredient, boolean doStart) {
            // Only items. A fluid or a foreign ingredient type has nothing to land in here, and
            // offering it a target would highlight slots that cannot take it.
            if (ingredient.getItemStack().isEmpty()) {
                return List.of();
            }
            return screen.ghostTargets().stream()
                .<Target<I>>map(slot -> new Target<I>() {
                    @Override
                    public Rect2i getArea() {
                        return new Rect2i(slot.x(), slot.y(), slot.w(), slot.h());
                    }

                    @Override
                    public void accept(I dropped) {
                        ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY);
                        if (!stack.isEmpty()) {
                            slot.accept().accept(stack);
                        }
                    }
                })
                .toList();
        }

        @Override
        public void onComplete() {
        }
    }
}
