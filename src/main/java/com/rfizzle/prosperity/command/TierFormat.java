package com.rfizzle.prosperity.command;

import java.util.Locale;

/**
 * Pure formatting helpers for {@code /prosperity info} feedback. Kept free of any Minecraft
 * imports so the formatting can be unit-tested without bootstrapping the game.
 */
final class TierFormat {

    private TierFormat() {
    }

    /** A block distance as a grouped integer, e.g. {@code 4521.7 -> "4,521"}. */
    static String distance(double blocks) {
        return String.format(Locale.US, "%,d", Math.round(blocks));
    }

    /**
     * A stack multiplier in its natural decimal form, e.g. {@code 2.0 -> "2.0"},
     * {@code 2.75 -> "2.75"}. Trailing-zero behavior matches {@link Double#toString} so a
     * whole multiplier still reads as {@code "2.0"} per SPEC §15.
     */
    static String multiplier(double stackMultiplier) {
        return Double.toString(stackMultiplier);
    }
}
