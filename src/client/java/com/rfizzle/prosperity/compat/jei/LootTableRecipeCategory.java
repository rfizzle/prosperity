package com.rfizzle.prosperity.compat.jei;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.compat.index.LootIndexFilterMarkers;
import com.rfizzle.prosperity.compat.index.LootIndexLabels;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The JEI "Loot Tables" category for the loot index (S-031, SPEC §11): the JEI parallel of
 * {@code ProsperityEmiCategories} + {@code LootTableEmiRecipe} and {@code LootTableReiDisplayCategory}.
 * Each row renders {@code [Structure Icon] [Output Item] · structure name · tier badge}, mirroring the
 * EMI/REI rows; injected entries carry the gold-sparkle marker and an "Added by Prosperity" tooltip.
 * The tab icon reuses the mod brand icon ({@code assets/prosperity/icon.png}) scaled to the 16×16
 * category slot, the suite convention rather than a bespoke recipe-tab glyph.
 *
 * <p>Unlike EMI (one recipe object per row) JEI keeps the category stateless: the {@link LootIndexEntry}
 * is passed back to {@link #setRecipe}/{@link #draw}/{@link #getTooltip} per row, so all row text comes
 * from the shared {@link LootIndexLabels} with no per-instance state.
 */
public final class LootTableRecipeCategory extends AbstractRecipeCategory<LootIndexEntry> {

    public static final RecipeType<LootIndexEntry> RECIPE_TYPE =
            RecipeType.create(Prosperity.MOD_ID, "loot_tables", LootIndexEntry.class);

    /** The unlooted-indicator sprite (16×16 frame on a 16×64 strip); frame 0 marks injected rows. */
    private static final ResourceLocation SPARKLE = Prosperity.id("textures/overlay/unlooted.png");

    private static final int WIDTH = 160;
    private static final int HEIGHT = 28;
    private static final int SLOT_Y = 6;
    private static final int TEXT_X = 50;
    private static final int TEXT_COLOR = 0x404040;

    public LootTableRecipeCategory(IDrawable icon) {
        super(RECIPE_TYPE, Component.translatable("category.prosperity.loot_tables"), icon, WIDTH, HEIGHT);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, LootIndexEntry entry, IFocusGroup focuses) {
        // Structure icon as the INPUT so JEI's "uses" tree links it (the structure filter); the row's
        // item as the OUTPUT so search finds the row by item ("mending" → every source of Mending).
        builder.addSlot(RecipeIngredientRole.INPUT, 5, SLOT_Y)
                .addItemStack(new ItemStack(StructureIcons.iconFor(entry.structure())))
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(LootIndexLabels.structureName(entry)));

        builder.addSlot(RecipeIngredientRole.OUTPUT, 27, SLOT_Y)
                .addItemStack(entry.output())
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(LootIndexLabels.sourceTooltip(entry)));

        // Tier + source markers (S-042) as invisible catalysts: part of JEI's "uses" tree so the filter
        // chips link the row, but not drawn into the row layout.
        builder.addInvisibleIngredients(RecipeIngredientRole.CATALYST)
                .addItemStacks(LootIndexFilterMarkers.markersFor(entry, ClientProsperityData.config().distanceTiers));
    }

    @Override
    public void draw(LootIndexEntry entry, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, LootIndexLabels.structureName(entry), TEXT_X, 4, TEXT_COLOR, false);
        guiGraphics.drawString(font, LootIndexLabels.tierBadge(entry), TEXT_X, 15, TEXT_COLOR, false);

        if (entry.origin() == LootIndexEntry.Origin.INJECTED) {
            // Gold-sparkle marker (frame 0 of the 16×64 strip) in the output slot's top-right corner.
            guiGraphics.blit(SPARKLE, 39, SLOT_Y - 2, 8, 8, 0F, 0F, 16, 16, 16, 64);
        }
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip, LootIndexEntry entry, IRecipeSlotsView recipeSlotsView, double mouseX, double mouseY) {
        // The loot-table id over the text area (the slots carry the structure/source lines themselves).
        if (mouseX >= TEXT_X - 2 && mouseY >= 0 && mouseY <= HEIGHT) {
            tooltip.add(Component.literal(entry.lootTable().toString()));
        }
    }
}
