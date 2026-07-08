package com.rfizzle.prosperity.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Prospector's Compass. Its needle logic is entirely client-side (see {@code
 * ProspectorsCompassClient}); the item itself is behaviorless on the server. This subclass exists
 * only to describe that behavior in the tooltip so a player who finds one in a chest knows what it
 * does without consulting the website.
 *
 * <p>The description lines go through {@link #appendHoverText} rather than an {@code
 * ItemTooltipCallback} so they render above lore, advanced info, and any recipe-viewer mod-name
 * footer (which are appended after the vanilla tooltip is assembled).
 */
public class ProspectorsCompassItem extends Item {

    public ProspectorsCompassItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.prosperity.prospectors_compass.points")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.prosperity.prospectors_compass.spins")
                .withStyle(ChatFormatting.GRAY));
    }
}
