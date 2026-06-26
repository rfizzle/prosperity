package com.rfizzle.prosperity.loot.index;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.mixin.CompositeEntryBaseAccessor;
import com.rfizzle.prosperity.mixin.LootItemAccessor;
import com.rfizzle.prosperity.mixin.LootPoolAccessor;
import com.rfizzle.prosperity.mixin.LootTableAccessor;
import com.rfizzle.prosperity.mixin.NestedLootTableAccessor;
import com.rfizzle.prosperity.mixin.TagEntryAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;

/**
 * Statically enumerates the distinct item sources a loot table can yield, for the loot index
 * (S-025, SPEC §11). Walks the table's pools and entries via accessor mixins rather than rolling it,
 * so the result is deterministic and complete (not a sample):
 *
 * <ul>
 *   <li>{@link LootItem} &mdash; the item it yields.</li>
 *   <li>{@link TagEntry} &mdash; every item in the referenced tag.</li>
 *   <li>{@link NestedLootTable} &mdash; recurse into the referenced (by key) or inline table.</li>
 *   <li>{@link CompositeEntryBase} (alternatives / group / sequence) &mdash; recurse into children.</li>
 *   <li>Everything else ({@code EmptyLootItem}, {@code DynamicLoot}, unknown) yields no fixed item.</li>
 * </ul>
 *
 * <p>Results are deduplicated by item, preserving first-seen order. An identity-based visited set
 * over tables guards against cycles in nested-table references.
 */
public final class LootTableInspector {

    private LootTableInspector() {
    }

    /**
     * The distinct items {@code table} can yield, in first-seen order. {@code resolver} maps a
     * nested-table key to its loot table (typically {@code reloadableRegistries::getLootTable}); a
     * {@code null} resolver simply skips keyed nested references (inline ones still recurse).
     */
    public static List<Item> collect(LootTable table, Function<ResourceKey<LootTable>, LootTable> resolver) {
        Set<Item> items = new LinkedHashSet<>();
        Set<LootTable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectTable(table, resolver, items, visited);
        return new ArrayList<>(items);
    }

    private static void collectTable(LootTable table, Function<ResourceKey<LootTable>, LootTable> resolver,
            Set<Item> items, Set<LootTable> visited) {
        if (table == null || !visited.add(table)) {
            return;
        }
        for (LootPool pool : ((LootTableAccessor) (Object) table).prosperity$getPools()) {
            for (LootPoolEntryContainer entry : ((LootPoolAccessor) (Object) pool).prosperity$getEntries()) {
                collectEntry(entry, resolver, items, visited);
            }
        }
    }

    private static void collectEntry(LootPoolEntryContainer entry,
            Function<ResourceKey<LootTable>, LootTable> resolver, Set<Item> items, Set<LootTable> visited) {
        switch (entry) {
            case LootItem item -> {
                Item value = ((LootItemAccessor) (Object) item).prosperity$getItem().value();
                if (value != Items.AIR) {
                    items.add(value);
                }
            }
            case TagEntry tag -> addTagItems(((TagEntryAccessor) (Object) tag).prosperity$getTag(), items);
            case NestedLootTable nested -> {
                ((NestedLootTableAccessor) (Object) nested).prosperity$getContents().ifLeft(key -> {
                    if (resolver != null) {
                        collectTable(resolver.apply(key), resolver, items, visited);
                    }
                }).ifRight(inline -> collectTable(inline, resolver, items, visited));
            }
            case CompositeEntryBase composite -> {
                for (LootPoolEntryContainer child :
                        ((CompositeEntryBaseAccessor) (Object) composite).prosperity$getChildren()) {
                    collectEntry(child, resolver, items, visited);
                }
            }
            default -> Prosperity.LOGGER.debug("Loot index: no fixed item for entry type {}",
                    entry.getClass().getSimpleName());
        }
    }

    private static void addTagItems(TagKey<Item> tag, Set<Item> items) {
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            items.add(holder.value());
        }
    }
}
