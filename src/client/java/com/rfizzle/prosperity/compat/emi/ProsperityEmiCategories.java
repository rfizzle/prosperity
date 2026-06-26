package com.rfizzle.prosperity.compat.emi;

import com.rfizzle.prosperity.Prosperity;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;

/**
 * The EMI "Loot Tables" category for the loot index (S-029, SPEC §11). The tab icon reuses the mod
 * brand icon ({@code assets/prosperity/icon.png}, 256×256) scaled to the 16×16 category slot — the
 * suite convention is to surface the mod's identity icon rather than author a bespoke recipe-tab glyph.
 */
public final class ProsperityEmiCategories {

    private static final EmiTexture ICON =
            new EmiTexture(Prosperity.id("icon.png"), 0, 0, 16, 16, 256, 256, 256, 256);

    public static final EmiRecipeCategory LOOT_TABLES =
            new EmiRecipeCategory(Prosperity.id("loot_tables"), ICON);

    private ProsperityEmiCategories() {
    }
}
