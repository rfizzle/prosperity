package com.rfizzle.prosperity.compat.emi;

import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.compat.index.LootIndexFilterMarkers;
import com.rfizzle.prosperity.loot.index.LootIndexDataSource;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * EMI integration for the loot index (S-029, SPEC §11). Registers the "Loot Tables" category and
 * one {@link LootTableEmiRecipe} per loot-index row from {@link LootIndexDataSource#snapshot()},
 * then exposes each structure's representative item as a workstation so EMI's recipe tree gives a
 * per-structure view (the structure filter). The tier and source marker items (S-042) are registered
 * as workstations the same way, so the tier and source filter chips sit beside the structure chips.
 *
 * <p>Discovered via the {@code emi} entrypoint; absent EMI, this class is never loaded. The snapshot
 * is empty until the integrated/dedicated server builds it, so on a loot-less remote client the
 * category simply shows no recipes (the limitation shared by every recipe-viewer loot plugin).
 */
public class ProsperityEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(ProsperityEmiCategories.LOOT_TABLES);

        Set<ResourceLocation> seenStructures = new LinkedHashSet<>();
        Map<String, Integer> dupCounts = new HashMap<>();

        for (LootIndexEntry entry : LootIndexDataSource.snapshot()) {
            String key = entry.lootTable() + "|" + entry.origin();
            int suffix = dupCounts.merge(key, 0, (oldV, ignored) -> oldV + 1);
            registry.addRecipe(new LootTableEmiRecipe(entry, suffix));
            seenStructures.add(entry.structure());
        }

        // Surface every mapped structure as a workstation (not only those with rows) so the
        // structure grid is complete; the icon item links the category to that structure's loot.
        seenStructures.addAll(StructureIcons.mappedStructures());
        for (ResourceLocation structure : seenStructures) {
            Item icon = StructureIcons.iconFor(structure);
            registry.addWorkstation(ProsperityEmiCategories.LOOT_TABLES, EmiStack.of(icon));
        }

        // The tier + source filter chips (S-042): each marker links the category like a structure does.
        List<ItemStack> chips = new ArrayList<>(LootIndexFilterMarkers.tierChips(ClientProsperityData.config().distanceTiers));
        chips.addAll(LootIndexFilterMarkers.sourceChips());
        for (ItemStack chip : chips) {
            registry.addWorkstation(ProsperityEmiCategories.LOOT_TABLES, EmiStack.of(chip));
        }
    }
}
