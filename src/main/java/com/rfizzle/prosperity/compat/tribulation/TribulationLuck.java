package com.rfizzle.prosperity.compat.tribulation;

/**
 * Pure math for the Tribulation loot bias: maps a Tribulation difficulty level to a tier via the
 * config-driven thresholds, and a tier to the luck bonus and stack factor applied on top of
 * Prosperity's own distance scaling. No Minecraft or Tribulation types &mdash; fully unit-testable.
 *
 * <p>The curve is deliberately modest and capped: distance scaling already seeds the context with
 * up to +4 luck, and the Tribulation bonus stacks on top of it, so early tiers add little and the
 * top tier tops out at {@link #MAX_BONUS_LUCK}. Stack counts are only nudged at the highest tiers.
 */
final class TribulationLuck {

    /** Ceiling on the luck a Tribulation tier can add, whatever the curve says. */
    static final float MAX_BONUS_LUCK = 2.0f;

    /** Luck added per Tribulation tier 0-5. Flat early game, half of distance's +4 max at tier 5. */
    private static final float[] TIER_LUCK = {0.0f, 0.25f, 0.5f, 1.0f, 1.5f, 2.0f};

    /** Stack-multiplier factor per Tribulation tier 0-5. Only the two highest tiers nudge counts. */
    private static final float[] TIER_STACK_FACTOR = {1.0f, 1.0f, 1.0f, 1.0f, 1.1f, 1.2f};

    private TribulationLuck() {
    }

    /**
     * Resolve a Tribulation level to its tier by counting the thresholds the level has reached.
     * Thresholds are inclusive and ascending ({@code [tier1..tier5]}, from
     * {@code TribulationAPI.getTierThresholds()}); a level at exactly a threshold is in that tier.
     * A {@code null} or empty array yields tier 0.
     */
    static int tierForLevel(int level, int[] thresholds) {
        if (thresholds == null) {
            return 0;
        }
        int tier = 0;
        for (int threshold : thresholds) {
            if (level >= threshold) {
                tier++;
            }
        }
        return Math.min(tier, TIER_LUCK.length - 1);
    }

    /** Luck bonus for {@code tier}, clamped to the curve's range and to {@link #MAX_BONUS_LUCK}. */
    static float luckForTier(int tier) {
        return Math.min(TIER_LUCK[clampTier(tier)], MAX_BONUS_LUCK);
    }

    /** Stack-multiplier factor for {@code tier} ({@code 1.0} for tiers that leave counts alone). */
    static float stackFactorForTier(int tier) {
        return TIER_STACK_FACTOR[clampTier(tier)];
    }

    private static int clampTier(int tier) {
        return Math.max(0, Math.min(tier, TIER_LUCK.length - 1));
    }
}
