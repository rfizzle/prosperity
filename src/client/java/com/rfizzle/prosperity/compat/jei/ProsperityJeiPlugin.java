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
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
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
 *
 * <p>On a remote dedicated server the index arrives over the network after JEI first registers, so the
 * static {@link #registerRecipes} pass sees an empty snapshot. The runtime is captured in
 * {@link #onRuntimeAvailable} and {@link #refresh()} re-publishes the synced rows through JEI's public
 * {@link IRecipeManager} (S-047) — the deterministic JEI parallel of EMI's reflective reload. The
 * refresh is idempotent: it hides the previously-published rows before adding the current snapshot, and
 * JEI keys its hidden set by identity, so the fresh instances from a re-sync are never suppressed.
 */
@JeiPlugin
public final class ProsperityJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID = Prosperity.id("jei");

    /** The live recipe manager, captured in {@link #onRuntimeAvailable}; {@code null} until JEI is ready. */
    private static volatile IRecipeManager recipeManager;
    /** Rows currently published to JEI, hidden before re-adding so a re-sync replaces rather than stacks. */
    private static volatile List<LootIndexEntry> published = List.of();
    /** Latches the refresh off after an unexpected failure, falling back to rejoin-refresh like REI. */
    private static volatile boolean refreshFailed = false;

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        recipeManager = jeiRuntime.getRecipeManager();
        JeiIndexRefresh.bind(ProsperityJeiPlugin::refresh);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiIndexRefresh.unbind();
        recipeManager = null;
        published = List.of();
    }

    /**
     * Re-publish the synced loot index to the live recipe manager (S-047). Called on the client thread
     * from {@link com.rfizzle.prosperity.compat.index.LootIndexViewerRefresh} after a remote server's
     * index lands. {@code addRecipes} is additive, so the previously-published rows are hidden first;
     * JEI's hidden set is identity-based, so the new snapshot instances are added cleanly even when they
     * are value-equal to the hidden ones.
     */
    private static void refresh() {
        IRecipeManager manager = recipeManager;
        if (manager == null || refreshFailed) {
            return;
        }
        try {
            List<LootIndexEntry> previous = published;
            if (!previous.isEmpty()) {
                manager.hideRecipes(LootTableRecipeCategory.RECIPE_TYPE, previous);
            }
            List<LootIndexEntry> current = LootIndexDataSource.snapshot();
            manager.addRecipes(LootTableRecipeCategory.RECIPE_TYPE, current);
            published = current;
        } catch (RuntimeException e) {
            refreshFailed = true;
            Prosperity.LOGGER.warn("JEI loot-index refresh failed; its loot tab will refresh on rejoin", e);
        }
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new LootTableRecipeCategory(modIcon()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // Snapshot the rows published at register time so a later runtime refresh hides exactly these
        // before re-adding. On a remote dedicated server this is empty (the index syncs after JEI
        // registers); an integrated host already holds the full snapshot here and never force-refreshes.
        List<LootIndexEntry> rows = LootIndexDataSource.snapshot();
        registration.addRecipes(LootTableRecipeCategory.RECIPE_TYPE, rows);
        published = rows;
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
