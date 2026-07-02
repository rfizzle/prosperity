package com.rfizzle.prosperity.compat.tribulation;

/**
 * Pure math for the Tribulation tier-gear drop chance: maps Prosperity's distance tier at the mob's
 * position and the mob's Tribulation tier to the chance that a piece of its equipped gear drops on
 * a player kill. No Minecraft or Tribulation types &mdash; fully unit-testable.
 *
 * <p>The curve is the product of the two tier fractions against a modest ceiling: distance is the
 * dominant axis (a mob at spawn drops nothing, whatever its tier), while the mob tier scales the
 * result between {@link #MOB_TIER_FLOOR} and full weight so even a low-tier mob deep in the world
 * yields a small chance. The result never exceeds {@link #MAX_CHANCE} &mdash; this is a reward for
 * ranging far, not an enchanted-gear firehose.
 */
final class DropChanceScaling {

    /** Ceiling on the drop chance, reached only at max distance tier and max mob tier. */
    static final float MAX_CHANCE = 0.35f;

    /** Weight a tier-0 mob keeps, so distance alone still yields a small chance. */
    static final float MOB_TIER_FLOOR = 0.25f;

    private DropChanceScaling() {
    }

    /**
     * The drop chance for a piece of tier gear: {@code MAX_CHANCE} scaled by the distance-tier
     * fraction and the mob-tier fraction (floored at {@link #MOB_TIER_FLOOR}), clamped to
     * {@code [0, 1]}. Monotonically non-decreasing in both tiers. Returns {@code defaultChance}
     * unchanged when either ladder is empty ({@code max <= 0}) or either tier is out of its
     * {@code [0, max]} range; never returns a non-finite value for in-range inputs.
     *
     * @param distanceTier    Prosperity distance-tier index at the mob's position (0-based)
     * @param maxDistanceTier highest configured distance-tier index
     * @param mobTier         the mob's Tribulation tier (0-based)
     * @param maxMobTier      highest Tribulation tier
     * @param defaultChance   Tribulation's configured default, returned for out-of-range inputs
     * @return the drop chance in {@code [0, 1]}, or {@code defaultChance} when out of range
     */
    static float compute(int distanceTier, int maxDistanceTier, int mobTier, int maxMobTier,
            float defaultChance) {
        if (maxDistanceTier <= 0 || maxMobTier <= 0
                || distanceTier < 0 || distanceTier > maxDistanceTier
                || mobTier < 0 || mobTier > maxMobTier) {
            return defaultChance;
        }
        float distanceFraction = distanceTier / (float) maxDistanceTier;
        float mobFraction = mobTier / (float) maxMobTier;
        float chance = MAX_CHANCE * distanceFraction
                * (MOB_TIER_FLOOR + (1.0f - MOB_TIER_FLOOR) * mobFraction);
        return Math.max(0.0f, Math.min(chance, 1.0f));
    }
}
