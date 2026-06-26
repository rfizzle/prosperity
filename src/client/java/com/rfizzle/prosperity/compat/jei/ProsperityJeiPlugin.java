package com.rfizzle.prosperity.compat.jei;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.compat.index.LootIndexFilterMarkers;
import com.rfizzle.prosperity.loot.index.LootIndexDataSource;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.StructureIcons;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI integration for the loot index (S-031, SPEC §11): the JEI parallel of {@code ProsperityEmiPlugin}
 * (S-029) and {@code ProsperityReiClientPlugin} (S-030). Registers the "Loot Tables" category, adds one
 * recipe per loot-index row from {@link LootIndexDataSource#snapshot()}, and registers each structure's
 * representative item as a catalyst so JEI's recipe tree gives a per-structure view (the structure filter).
 * The tier and source marker items (S-042) are registered as catalysts the same way, surfacing the tier
 * and source filter chips beside the structure chips.
 *
 * <p>Discovered by JEI-Fabric via the {@code jei_mod_plugin} entrypoint in {@code fabric.mod.json}; the
 * {@link JeiPlugin} annotation is load-bearing on Forge/NeoForge only but is kept for cross-loader parity.
 * Absent JEI, this class is never loaded. The snapshot is empty until the integrated/dedicated server
 * builds it, so on a loot-less remote client the category simply shows no recipes (the limitation shared
 * by every recipe-viewer loot plugin).
 */
@JeiPlugin
public final class ProsperityJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID = Prosperity.id("jei");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new LootTableRecipeCategory(modIcon()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(LootTableRecipeCategory.RECIPE_TYPE, LootIndexDataSource.snapshot());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // Surface every mapped structure as a catalyst (not only those with rows) so the structure grid
        // is complete; the icon item links the category to that structure's loot — the JEI parallel of
        // the EMI/REI workstations.
        Set<ResourceLocation> structures = new LinkedHashSet<>();
        for (LootIndexEntry entry : LootIndexDataSource.snapshot()) {
            structures.add(entry.structure());
        }
        structures.addAll(StructureIcons.mappedStructures());
        for (ResourceLocation structure : structures) {
            registration.addRecipeCatalyst(new ItemStack(StructureIcons.iconFor(structure)), LootTableRecipeCategory.RECIPE_TYPE);
        }

        // The tier + source filter chips (S-042): each marker links the category like a structure does.
        List<ItemStack> chips = new ArrayList<>(LootIndexFilterMarkers.tierChips(ClientProsperityData.config().distanceTiers));
        chips.addAll(LootIndexFilterMarkers.sourceChips());
        for (ItemStack chip : chips) {
            registration.addRecipeCatalyst(chip, LootTableRecipeCategory.RECIPE_TYPE);
        }
    }

    /**
     * The category tab icon: the mod brand icon ({@code assets/prosperity/icon.png}, 256×256) blitted
     * into the 16×16 category slot, matching the EMI/REI brand-icon convention.
     */
    private static IDrawable modIcon() {
        return new IDrawable() {
            private static final ResourceLocation TEXTURE = Prosperity.id("icon.png");

            @Override
            public int getWidth() {
                return 16;
            }

            @Override
            public int getHeight() {
                return 16;
            }

            @Override
            public void draw(GuiGraphics guiGraphics, int xOffset, int yOffset) {
                guiGraphics.blit(TEXTURE, xOffset, yOffset, 0, 0, 16, 16, 16, 16);
            }
        };
    }
}
