package com.neryos.workbay.content.room;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.world.RoomGeometry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A room in the hand. SPEC.md §0: it travels as an item carrying everything built inside it, it
 * goes into a bay and nowhere else, and <b>it is never destroyed</b> -- not by fire, lava, cactus,
 * an explosion, despawning or the void.
 *
 * <p>Fire, lava, cactus and explosions all arrive as {@link #canBeHurtBy}; despawning and the
 * void are the item entity's, so a room on the ground is a {@link RoomItemEntity}.
 */
public class RoomItem extends BlockItem {

    public RoomItem(RoomBlock block, Properties properties) {
        super(block, properties.fireResistant());
    }

    public int tier() {
        return ((RoomBlock) getBlock()).tier();
    }

    /**
     * A room goes in a bay, and this says so rather than placing a block that would be a second
     * holder (SPEC.md §0). Right-clicking a Workbay with one still opens the Workbay's screen --
     * the block handles that before the item is asked.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) {
            WorkbaySounds.refuse(player, WorkbayLang.message("room_goes_in_a_bay"));
        }
        return InteractionResult.FAIL;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
        TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        int inside = RoomGeometry.interior(tier());
        lines.add(WorkbayLang.tooltip("room_size", inside).withStyle(ChatFormatting.GRAY));
        RoomStamp stamp = stack.get(WBDataComponents.ROOM.get());
        lines.add((stamp == null ? WorkbayLang.tooltip("room_new")
            : stamp.ticket().isPresent() ? WorkbayLang.tooltip("room_built")
            : WorkbayLang.tooltip("room_copy")).withStyle(
                stamp != null && stamp.ticket().isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA));
    }

    // ------------------------------------------------------- never destroyed

    @Override
    public boolean canBeHurtBy(ItemStack stack, DamageSource source) {
        return false;
    }

    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return true;
    }

    @Override
    public Entity createEntity(Level level, Entity location, ItemStack stack) {
        return new RoomItemEntity(level, location, stack);
    }
}
