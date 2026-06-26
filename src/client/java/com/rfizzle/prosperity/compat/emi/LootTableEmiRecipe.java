package com.rfizzle.prosperity.compat.emi;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.compat.index.LootIndexFilterMarkers;
import com.rfizzle.prosperity.compat.index.LootIndexLabels;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * One loot-index row (SPEC §11, S-029): a single item source from a structure loot table, rendered
 * as {@code [Structure Icon] [Output Item] · loot table · tier badge}. The structure icon is a
 * catalyst so EMI's "uses" tree links it (the structure filter); the output item is the recipe
 * output so EMI's search finds the row by item. Injected entries carry the gold-sparkle marker and
 * an "Added by Prosperity at [tier]+ tier" tooltip.
 *
 * <p>The row's tier and source filter markers (S-042) ride along as {@link #getCatalysts() catalysts}
 * — found by EMI's "uses" tree but not drawn — so clicking a tier or source chip surfaces the matching
 * rows without changing the row layout.
 */
public class LootTableEmiRecipe implements EmiRecipe {

    /** The unlooted-indicator sprite (16×16 frame on a 16×64 strip); frame 0 marks injected rows. */
    private static final ResourceLocation SPARKLE = Prosperity.id("textures/overlay/unlooted.png");

    private static final int WIDTH = 154;
    private static final int HEIGHT = 26;
    private static final int SLOT_Y = 4;
    private static final int TEXT_X = 46;
    private static final int TEXT_COLOR = 0x404040;

    private final LootIndexEntry entry;
    private final ResourceLocation id;
    private final EmiIngredient structure;
    private final List<EmiStack> outputs;
    private final List<EmiIngredient> catalysts;

    public LootTableEmiRecipe(LootIndexEntry entry, int suffix) {
        this.entry = entry;
        this.structure = EmiStack.of(StructureIcons.iconFor(entry.structure()));
        this.outputs = List.of(EmiStack.of(entry.output()));
        this.id = Prosperity.id("loot_index/" + signature(entry, suffix));
        this.catalysts = buildCatalysts(entry);
    }

    private static List<EmiIngredient> buildCatalysts(LootIndexEntry entry) {
        List<ItemStack> markers =
                LootIndexFilterMarkers.markersFor(entry, ClientProsperityData.config().distanceTiers);
        List<EmiIngredient> out = new ArrayList<>(markers.size());
        for (ItemStack marker : markers) {
            out.add(EmiStack.of(marker));
        }
        return out;
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return ProsperityEmiCategories.LOOT_TABLES;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        // The structure icon as a recipe input so EMI's recipe/uses tree links it: right-clicking
        // (or searching) the structure's representative item surfaces every loot row for it.
        return List.of(structure);
    }

    @Override
    public List<EmiIngredient> getCatalysts() {
        // Tier + source markers (S-042): part of EMI's "uses" tree so the filter chips link the row,
        // but not drawn into the custom row layout.
        return catalysts;
    }

    @Override
    public List<EmiStack> getOutputs() {
        return outputs;
    }

    @Override
    public int getDisplayWidth() {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return HEIGHT;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        Component structureName = LootIndexLabels.structureName(entry);

        widgets.addSlot(structure, 0, SLOT_Y)
                .drawBack(false)
                .catalyst(true)
                .appendTooltip(structureName);

        var output = widgets.addSlot(outputs.get(0), 22, SLOT_Y).recipeContext(this);
        output.appendTooltip(LootIndexLabels.sourceTooltip(entry));

        if (entry.origin() == LootIndexEntry.Origin.INJECTED) {
            // Gold-sparkle marker (frame 0 of the 16×64 strip) in the output slot's top-right corner.
            widgets.addTexture(SPARKLE, 32, SLOT_Y - 2, 8, 8, 0, 0, 16, 16, 16, 64);
        }

        widgets.addText(structureName, TEXT_X, 2, TEXT_COLOR, false);
        widgets.addText(LootIndexLabels.tierBadge(entry), TEXT_X, 13, TEXT_COLOR, false);
        widgets.addTooltipText(List.of(Component.literal(entry.lootTable().toString())), TEXT_X, 0, WIDTH - TEXT_X, HEIGHT);
    }

    private static String signature(LootIndexEntry entry, int suffix) {
        ResourceLocation item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.output().getItem());
        return sanitize(entry.lootTable().toString())
                + "_" + sanitize(item.toString())
                + "_" + entry.origin().name().toLowerCase(java.util.Locale.ROOT)
                + "_" + suffix;
    }

    private static String sanitize(String s) {
        return s.replace(':', '_').replace('/', '_');
    }
}
