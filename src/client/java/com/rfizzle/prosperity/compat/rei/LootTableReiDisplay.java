package com.rfizzle.prosperity.compat.rei;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

/**
 * One loot-index row as a REI display (S-030, SPEC §11): the REI parallel of {@code LootTableEmiRecipe}.
 * The structure's representative item is the input (so REI's "uses" tree links the structure filter)
 * and the row's item is the output (so REI search finds the row by item); the original
 * {@link LootIndexEntry} rides along for {@link LootTableReiDisplayCategory} to render the labels and
 * the injected marker.
 */
public class LootTableReiDisplay extends BasicDisplay {

    public static final CategoryIdentifier<LootTableReiDisplay> IDENTIFIER =
            CategoryIdentifier.of(Prosperity.id("loot_tables"));

    private final LootIndexEntry entry;

    public LootTableReiDisplay(LootIndexEntry entry) {
        super(
                List.of(EntryIngredient.of(EntryStacks.of(new ItemStack(StructureIcons.iconFor(entry.structure()))))),
                List.of(EntryIngredient.of(EntryStacks.of(entry.output()))),
                Optional.empty());
        this.entry = entry;
    }

    public LootIndexEntry entry() {
        return entry;
    }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return IDENTIFIER;
    }
}
