package com.rfizzle.prosperity.compat.meridian;

/**
 * Pure tier&rarr;enchanting-power math for the Meridian integration, kept free of Minecraft and
 * Meridian types so it unit-tests without a game context.
 *
 * <p>The curve maps a distance-tier index on the configured ladder to an effective enchanting
 * power, deliberately conservative: index 0 (the {@code local} band and its off-ladder sentinel)
 * rolls nothing, the first travelled tier rolls at a modest {@value #MIN_POWER}, and only the
 * deepest tier reaches the vanilla-table maximum of {@value #MAX_POWER}. Intermediate tiers
 * interpolate linearly, so a longer configured ladder stretches the same range rather than
 * inflating it. On a two-tier ladder the single travelled tier <em>is</em> the deepest, so it
 * rolls at {@value #MAX_POWER} with treasure allowed. Meridian's own per-enchantment loot caps
 * bound the resulting levels on top.
 */
final class MeridianEnchantPower {

    /** Power at the first tier beyond {@code local} — a modest early-game enchanting table. */
    static final int MIN_POWER = 8;
    /** Power at the deepest tier — a fully shelved vanilla enchanting table. */
    static final int MAX_POWER = 30;

    private MeridianEnchantPower() {
    }

    /**
     * The effective enchanting power for the tier at {@code index} on a ladder whose deepest tier
     * sits at {@code maxIndex}: {@code 0} (no roll) at index 0 or on a degenerate ladder, otherwise
     * a linear ramp from {@link #MIN_POWER} at index 1 to {@link #MAX_POWER} at {@code maxIndex}.
     * An index beyond the ladder clamps to {@code maxIndex}.
     */
    static int powerForTier(int index, int maxIndex) {
        if (index <= 0 || maxIndex <= 0) {
            return 0;
        }
        if (maxIndex == 1 || index >= maxIndex) {
            return MAX_POWER;
        }
        return MIN_POWER + Math.round((float) (MAX_POWER - MIN_POWER) * (index - 1) / (maxIndex - 1));
    }

    /** Whether treasure-tagged enchantments may roll: only at the ladder's deepest tier. */
    static boolean treasureAllowed(int index, int maxIndex) {
        return maxIndex > 0 && index >= maxIndex;
    }
}
