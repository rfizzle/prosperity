package com.rfizzle.prosperity.loot.injection;

import java.util.Optional;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * A read-only view of one injectable entry for the loot index: its prototype stack, gating tier,
 * and — for a generative entry — the enchantment tag it draws from, so the index can present
 * "random &lt;rarity&gt; enchantment" instead of the blank prototype book.
 */
public record InjectedView(ItemStack stack, String minTier,
        Optional<TagKey<Enchantment>> enchantRandomly) {

    /** A literal entry's view: no generative tag. */
    public InjectedView(ItemStack stack, String minTier) {
        this(stack, minTier, Optional.empty());
    }
}
