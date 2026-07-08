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

    /** Lang-key prefix for a tier's display name (DESIGN-SYSTEM §10 surface: notification). */
    public static final String NAME_KEY_PREFIX = "notification.prosperity.tier.";

    /**
     * The translation key for this tier's display name, e.g. {@code notification.prosperity.tier.wilderness}.
     * The single choke point every surface that renders a tier name shares, so a rename can never leave one
     * site formatting a raw key.
     */
    public String translationKey() {
        return translationKey(name);
    }

    /**
     * The translation key for a bare tier name, for the sites that hold a stored/config name rather than a
     * {@link DistanceTier} instance (the stats readout, the HUD badge, the loot-index and probe labels).
     */
    public static String translationKey(String name) {
        return NAME_KEY_PREFIX + name;
    }
}
