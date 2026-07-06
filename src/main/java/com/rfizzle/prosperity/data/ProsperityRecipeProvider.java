package com.rfizzle.prosperity.data;

import com.rfizzle.prosperity.item.ProsperityItems;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Items;

/**
 * Datagen for the one crafting recipe Prosperity ships: a deterministic path to the Prospector's
 * Compass (issue #84) so the item is not gated solely behind its ~1% Frontier+ loot drop, which
 * stays as the lucky-find path.
 *
 * <p>The compass is deliberately expensive. It reveals the exact bearing and distance to the
 * holder's nearest unlooted container, so a cheap recipe would trivialize exploration. The recipe
 * gates on a netherite ingot and frames a vanilla compass in a gold casing with an end rod for the
 * needle — late-game ingredients that read as the "signature tool you earn," not an early staple.
 * Generating it (rather than hand-authoring the JSON) keeps the recipe-book unlock advancement in
 * sync with the recipe automatically.
 */
public final class ProsperityRecipeProvider extends FabricRecipeProvider {

    public ProsperityRecipeProvider(FabricDataOutput output,
            CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ProsperityItems.PROSPECTORS_COMPASS)
                .define('G', Items.GOLD_INGOT)
                .define('N', Items.NETHERITE_INGOT)
                .define('C', Items.COMPASS)
                .define('E', Items.END_ROD)
                .pattern("GNG")
                .pattern("GCG")
                .pattern("GEG")
                .unlockedBy("has_netherite_ingot", has(Items.NETHERITE_INGOT))
                .save(output);
    }
}
