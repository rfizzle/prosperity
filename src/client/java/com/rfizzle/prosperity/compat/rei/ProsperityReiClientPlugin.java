package com.rfizzle.prosperity.compat.rei;

import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.compat.index.LootIndexFilterMarkers;
import com.rfizzle.prosperity.loot.index.LootIndexDataSource;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * REI integration for the loot index (S-030, SPEC §11). Registers the "Loot Tables" category and one
 * {@link LootTableReiDisplay} per loot-index row from {@link LootIndexDataSource#snapshot()}, then
 * exposes each structure's representative item as a workstation so REI's recipe tree gives a
 * per-structure view (the structure filter) — the REI parallel of {@code ProsperityEmiPlugin}. The
 * tier and source marker items (S-042) are registered as workstations the same way, surfacing the
 * tier and source filter chips beside the structure chips.
 *
 * <p>Discovered via the {@code rei_client} entrypoint; absent REI, this class is never loaded. The
 * snapshot is empty until the integrated/dedicated server builds it, so on a loot-less remote client
 * the category simply shows no displays (the limitation shared by every recipe-viewer loot plugin).
 */
public class ProsperityReiClientPlugin implements REIClientPlugin {

    @Override
    public void registerCategories(CategoryRegistry registry) {
        registry.add(new LootTableReiDisplayCategory());

        // Surface every mapped structure as a workstation (not only those with rows) so the structure
        // grid is complete; the icon item links the category to that structure's loot.
        Set<ResourceLocation> structures = new LinkedHashSet<>();
        for (LootIndexEntry entry : LootIndexDataSource.snapshot()) {
            structures.add(entry.structure());
        }
        structures.addAll(StructureIcons.mappedStructures());

        List<EntryIngredient> workstations = new ArrayList<>(structures.size());
        for (ResourceLocation structure : structures) {
            workstations.add(EntryIngredient.of(EntryStacks.of(new ItemStack(StructureIcons.iconFor(structure)))));
        }

        // The tier + source filter chips (S-042): each marker links the category like a structure does.
        List<ItemStack> chips = new ArrayList<>(LootIndexFilterMarkers.tierChips(ClientProsperityData.config().distanceTiers));
        chips.addAll(LootIndexFilterMarkers.sourceChips());
        for (ItemStack chip : chips) {
            workstations.add(EntryIngredient.of(EntryStacks.of(chip)));
        }

        registry.addWorkstations(LootTableReiDisplay.IDENTIFIER, workstations.toArray(new EntryIngredient[0]));
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        for (LootIndexEntry entry : LootIndexDataSource.snapshot()) {
            registry.add(new LootTableReiDisplay(entry));
        }
    }
}
