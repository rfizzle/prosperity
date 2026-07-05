package com.rfizzle.prosperity.loot.injection;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * An injection group reduced to what the registry needs: the gating tier name, the dimensions it is
 * restricted to (empty means any), the per-generation survival chance, and its entries.
 */
public record Tiered(String minTier, List<ResourceLocation> dimensions, float chance,
        List<Entry> entries) {

    /** An always-injecting group (chance {@code 1.0}) — the pre-gate shape, kept for tests. */
    public Tiered(String minTier, List<ResourceLocation> dimensions, List<Entry> entries) {
        this(minTier, dimensions, 1.0f, entries);
    }
}
