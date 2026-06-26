package com.rfizzle.prosperity.mixin;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the item tag a {@link TagEntry} draws from so the loot index (S-025) can expand it. */
@Mixin(TagEntry.class)
public interface TagEntryAccessor {
    @Accessor("tag")
    TagKey<Item> prosperity$getTag();
}
