package com.rfizzle.prosperity.compat.rei;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.compat.index.LootIndexLabels;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import java.util.ArrayList;
import java.util.List;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The REI "Loot Tables" category for the loot index (S-030, SPEC §11): the REI parallel of
 * {@code ProsperityEmiCategories} + {@code LootTableEmiRecipe}. Each row renders as
 * {@code [Structure Icon] [Output Item] · structure name · tier badge}, mirroring the EMI row;
 * injected entries carry the gold-sparkle marker and an "Added by Prosperity" tooltip. The tab icon
 * reuses the mod brand icon ({@code assets/prosperity/icon.png}) scaled to the 16×16 category slot,
 * the suite convention rather than a bespoke recipe-tab glyph.
 */
public final class LootTableReiDisplayCategory implements DisplayCategory<LootTableReiDisplay> {

    /** The unlooted-indicator sparkle (first animation frame); marks injected rows. */
    private static final ResourceLocation SPARKLE = Prosperity.id("textures/overlay/unlooted_0.png");
    private static final ResourceLocation ICON_TEXTURE = Prosperity.id("icon.png");
    private static final Renderer MOD_ICON =
            (GuiGraphics graphics, Rectangle bounds, int mouseX, int mouseY, float delta) ->
                    graphics.blit(ICON_TEXTURE, bounds.x, bounds.y, 0, 0,
                            bounds.width, bounds.height, bounds.width, bounds.height);

    private static final int WIDTH = 160;
    private static final int HEIGHT = 28;
    private static final int SLOT_Y = 5;
    private static final int TEXT_X = 50;

    @Override
    public CategoryIdentifier<? extends LootTableReiDisplay> getCategoryIdentifier() {
        return LootTableReiDisplay.IDENTIFIER;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("rei.prosperity.category.loot_tables");
    }

    @Override
    public Renderer getIcon() {
        return MOD_ICON;
    }

    @Override
    public int getDisplayWidth(LootTableReiDisplay display) {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return HEIGHT;
    }

    @Override
    public List<Widget> setupDisplay(LootTableReiDisplay display, Rectangle bounds) {
        LootIndexEntry entry = display.entry();
        Component structureName = LootIndexLabels.structureName(entry);
        int x = bounds.x;
        int y = bounds.y;

        List<Widget> widgets = new ArrayList<>();
        widgets.add(Widgets.createRecipeBase(bounds));

        // Structure icon as the recipe input so REI's "uses" tree links it (the structure filter).
        widgets.add(Widgets.withTooltip(
                Widgets.createSlot(new Point(x + 4, y + SLOT_Y))
                        .entries(display.getInputEntries().get(0))
                        .markInput(),
                structureName));

        widgets.add(Widgets.createSlot(new Point(x + 26, y + SLOT_Y))
                .entries(display.getOutputEntries().get(0))
                .markOutput());

        if (entry.origin() == LootIndexEntry.Origin.INJECTED) {
            // Gold-sparkle marker in the output slot's top-right corner.
            widgets.add(Widgets.createDrawableWidget((graphics, mouseX, mouseY, delta) ->
                    graphics.blit(SPARKLE, x + 38, y + SLOT_Y - 2, 8, 8, 0F, 0F, 16, 16, 16, 16)));
        }

        widgets.add(Widgets.createLabel(new Point(x + TEXT_X, y + 4), structureName).leftAligned());
        widgets.add(Widgets.createLabel(new Point(x + TEXT_X, y + 15), LootIndexLabels.tierBadge(entry)).leftAligned());

        // The row-text tooltip carries the source/injection note and the loot-table id (EMI shows the
        // source on the output slot and the id on the text hover; REI surfaces both over the text area).
        Rectangle textArea = new Rectangle(x + TEXT_X - 2, y, bounds.width - (TEXT_X - 2), bounds.height);
        widgets.add(Widgets.createTooltip(textArea, List.of(
                LootIndexLabels.sourceTooltip(entry),
                Component.literal(entry.lootTable().toString()))));

        return widgets;
    }
}
