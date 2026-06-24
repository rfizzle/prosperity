package com.rfizzle.prosperity.config;

/**
 * One distance-scaling tier. Tiers are stored in {@link ProsperityConfig#distanceTiers}
 * as an ordered list and resolved by {@link ProsperityConfig#tierFor(double)}, which walks
 * them from highest to lowest {@link #minDistance} and returns the first match.
 *
 * @param name            lowercase id referenced by {@link StructureOverride#tier()}
 *                        (e.g. {@code "wilderness"})
 * @param minDistance     inclusive lower bound, in blocks, of this tier's distance band
 * @param stackMultiplier multiplier applied to each stackable item's count after the loot
 *                        table resolves (floored, capped at the item's max stack size)
 * @param qualityModifier added to the player's effective luck before loot resolution
 */
public record DistanceTier(String name, int minDistance, double stackMultiplier, int qualityModifier) {
}
