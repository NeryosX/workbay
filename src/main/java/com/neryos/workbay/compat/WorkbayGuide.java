package com.neryos.workbay.compat;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ItemLike;

import java.util.List;
import java.util.function.Supplier;

/**
 * The guide, as data. SPEC.md §6.
 *
 * <p><b>There was nowhere a player could look.</b> Right-clicking a Workbay in a recipe viewer
 * gave them a crafting grid and nothing else - no statement of what the block is for, and no
 * first move to make once it is placed. That is the exact moment somebody asks what to do next,
 * and every mod in this genre answers it on that tab.
 *
 * <p>It lives here rather than in either plugin because both viewers want the same list and
 * neither's API is ours: JEI hands a page to {@code addIngredientInfo}, EMI wraps one in an
 * {@code EmiInfoRecipe}, and both are three lines over this. The same argument as the ghost slots
 * next door - two adapters onto one list, and no interface pretending to unify two foreign APIs.
 *
 * <p><b>No text is written here.</b> Every line is a key, so the pages are translated with
 * everything else and the copy stays in one file (SPEC.md §6: the lang file is the truth).
 *
 * <p><b>And it is on the item itself, behind Shift.</b> A player on a plain server has neither
 * viewer, and the pages were reachable nowhere else. The choices were a book item (a recipe and
 * a screen to teach before it teaches), a page on the Workbay screen (needs a Workbay placed, and
 * the Workbay's own page is the one read <em>before</em> that) or the tooltip -- which every
 * player already has, in the recipe book, the inventory and the creative tab, on the exact thing
 * they are wondering about. The unshifted tooltip stays four lines (SPEC.md §6); Shift is where a
 * paragraph is allowed.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.neryos.workbay.Workbay.MOD_ID,
    value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class WorkbayGuide {
    private WorkbayGuide() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void onTooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        net.minecraft.world.item.Item item = event.getItemStack().getItem();
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (!id.getNamespace().equals(com.neryos.workbay.Workbay.MOD_ID)) {
            return;
        }
        List<Component> lines = event.getToolTip();
        // OPEN_ISSUES #117: one honest line under the name, for the items that had none. The
        // Connector and the rooms write their own in appendHoverText and have no key here.
        String own = WorkbayLang.tooltipKey("item." + id.getPath());
        if (net.minecraft.client.resources.language.I18n.exists(own)) {
            lines.add(Math.min(1, lines.size()),
                Component.translatable(own).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        // OPEN_ISSUES #130: an upgrade's recipe unlocks on a Housing, and nothing on it said so.
        String recipe = WorkbayLang.tooltipKey("recipe." + id.getPath());
        if (net.minecraft.client.resources.language.I18n.exists(recipe)) {
            lines.add(Math.min(2, lines.size()),
                Component.translatable(recipe).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        Page page = pages().stream().filter(p -> p.item().get().asItem() == item).findFirst()
            .orElse(null);
        if (page == null) {
            return;
        }
        if (!net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            lines.add(WorkbayLang.tooltip("more").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            return;
        }
        // Whole paragraphs: NeoForge wraps a tooltip line to whichever side of the cursor has
        // the room (ClientHooks#gatherTooltipComponents), and wrapping it here first only gave
        // it shorter lines to wrap again.
        for (Component paragraph : page.brief()) {
            lines.add(paragraph.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    /**
     * Whether a recipe viewer's item list is of any use on this screen right now: only while a
     * filter slot is drawn that takes a drag out of it. Both plugins ask this, so JEI and EMI
     * appear and go at the same moment (OPEN_ISSUES #123).
     */
    public static boolean wantsIngredientList(com.neryos.workbay.client.screen.WorkbayScreen screen) {
        return !screen.ghostTargets().isEmpty();
    }

    /**
     * One item's page: the thing it is about, and the paragraphs, in order. {@code brief} is what
     * the item's own tooltip shows behind Shift - the same paragraphs for every page but the
     * Workbay's, whose three numbered steps are about the screen and are taught on it (the empty
     * bay's hint, the Connector's own page); six paragraphs in a tooltip ran off the top of a
     * scale-4 window.
     */
    public record Page(Supplier<? extends ItemLike> item, List<Component> lines,
        List<Component> brief) {
        Page(Supplier<? extends ItemLike> item, List<Component> lines) {
            this(item, lines, lines);
        }

        /** Only the first and last paragraph on the item. */
        Page bookends() {
            return new Page(item, lines, List.of(lines.getFirst(), Component.literal(" "),
                lines.getLast()));
        }
    }

    /**
     * The Workbay's page carries the first three things to do and the others do not, because a
     * player reads exactly one of these before they start and it is the one on the block they just
     * crafted. Everything after it answers "what is this for" in a paragraph.
     */
    public static List<Page> pages() {
        return List.of(
            page(WBBlocks.WORKBAY, "workbay", 6).bookends(),
            page(WBBlocks.CONNECTOR, "connector", 3),
            page(WBItems.EXPANSION_PLATE, "expansion_plate", 1),
            page(WBBlocks.ROOM, "room", 4),
            page(WBBlocks.WIDE_ROOM, "room", 4),
            page(WBBlocks.VAST_ROOM, "room", 4),
            page(WBItems.ANCHOR, "anchor", 2),
            page(WBItems.RESONATOR, "resonator", 1),
            page(WBItems.IMPELLER, "impeller", 1),
            page(WBItems.SHOPSTEEL, "shopsteel", 1),
            page(WBItems.HOUSING, "housing", 1));
    }

    /**
     * Keys are {@code info.workbay.<name>.1} upward, so a page grows by adding a line here.
     *
     * <p>A blank line between paragraphs, because both viewers draw the list straight down a
     * column about twenty-five characters wide: without one, six paragraphs are a wall and the
     * numbered steps stop looking like steps. <b>A space, not {@link Component#empty()}</b> --
     * JEI wraps each component with the font and an empty one splits to no lines at all, so the
     * gap silently was not there.
     */
    private static Page page(Supplier<? extends ItemLike> item, String name, int lines) {
        List<Component> text = new java.util.ArrayList<>();
        for (int n = 1; n <= lines; n++) {
            if (n > 1) {
                text.add(Component.literal(" "));
            }
            text.add(WorkbayLang.info(name + "." + n));
        }
        return new Page(item, List.copyOf(text));
    }
}
