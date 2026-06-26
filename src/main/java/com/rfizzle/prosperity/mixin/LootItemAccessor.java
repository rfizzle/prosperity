package com.rfizzle.prosperity.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the item a {@link LootItem} yields so the loot index (S-025) can record it. */
@Mixin(LootItem.class)
public interface LootItemAccessor {
    @Accessor("item")
    Holder<Item> prosperity$getItem();
}
