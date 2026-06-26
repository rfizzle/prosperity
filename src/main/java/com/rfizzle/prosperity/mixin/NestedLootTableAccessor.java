package com.rfizzle.prosperity.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a {@link NestedLootTable}'s contents (either a referenced loot-table key or an inline
 * table) so the loot index (S-025) can recurse into it.
 */
@Mixin(NestedLootTable.class)
public interface NestedLootTableAccessor {
    @Accessor("contents")
    Either<ResourceKey<LootTable>, LootTable> prosperity$getContents();
}
