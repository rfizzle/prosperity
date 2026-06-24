package com.rfizzle.prosperity.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the {@code /prosperity info} formatting helpers (S-004). No Minecraft
 * bootstrap: {@link TierFormat} deliberately has no game imports.
 */
class TierFormatTest {

    @Test
    void distanceGroupsThousands() {
        assertEquals("4,521", TierFormat.distance(4521));
        assertEquals("0", TierFormat.distance(0));
        assertEquals("999", TierFormat.distance(999));
        assertEquals("1,000,000", TierFormat.distance(1_000_000));
    }

    @Test
    void distanceRoundsToNearestBlock() {
        assertEquals("4,521", TierFormat.distance(4521.4));
        assertEquals("4,522", TierFormat.distance(4521.5));
    }

    @Test
    void multiplierKeepsNaturalDecimal() {
        assertEquals("2.0", TierFormat.multiplier(2.0));
        assertEquals("1.5", TierFormat.multiplier(1.5));
        assertEquals("2.75", TierFormat.multiplier(2.75));
        assertEquals("3.5", TierFormat.multiplier(3.5));
    }
}
