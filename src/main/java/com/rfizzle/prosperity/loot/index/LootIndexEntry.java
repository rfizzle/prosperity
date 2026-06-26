package com.rfizzle.prosperity.loot.index;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * One row of the loot index (S-025, SPEC §11): a single item source from a loot table, mapped to
 * the structure it belongs to, the minimum distance tier it is available at, and whether it is a
 * vanilla loot-table entry or a Prosperity injection (SPEC §5).
 *
 * @param output    the item this row represents (count is illustrative — loot rolls vary)
 * @param lootTable the loot table this item is sourced from
 * @param structure the structure id this loot table belongs to (see {@link StructureIcons});
 *                  {@link LootTableStructures#OTHER} for unmapped tables
 * @param minTier   the minimum tier name this entry is available at; empty for vanilla entries,
 *                  which carry no tier restriction ("Any tier")
 * @param origin    whether this entry comes from the vanilla loot table or a Prosperity injection
 */
public record LootIndexEntry(
        ItemStack output,
        ResourceLocation lootTable,
        ResourceLocation structure,
        Optional<String> minTier,
        Origin origin) {

    /** Whether a loot-index row is a base loot-table entry or a Prosperity injection. */
    public enum Origin {
        VANILLA,
        INJECTED
    }
}
