package com.neryos.workbay.content.connector;

import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.init.WBDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The Connector in the hand. Its tooltip always names the Workbay it is paired to, because an
 * unpaired one and a paired one are the same block otherwise, and placing the wrong one is a silent
 * mistake the player only discovers later.
 */
public class ConnectorItem extends BlockItem {

    public ConnectorItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
        List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        ConnectorPairing pairing = stack.get(WBDataComponents.PAIRING.get());
        lines.add(pairing == null
            ? WorkbayLang.tooltip("connector_unpaired").withStyle(ChatFormatting.GRAY)
            : WorkbayLang.tooltip("connector_paired", pairing.network())
                .withStyle(ChatFormatting.AQUA));
    }
}
