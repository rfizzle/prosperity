package com.rfizzle.prosperity.compat.tribulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TribulationLuckTest {

    private static final int[] DEFAULT_THRESHOLDS = {50, 100, 150, 200, 250};

    @Test
    void tierForLevelWalksThresholdsInclusively() {
        assertEquals(0, TribulationLuck.tierForLevel(0, DEFAULT_THRESHOLDS));
        assertEquals(0, TribulationLuck.tierForLevel(49, DEFAULT_THRESHOLDS));
        assertEquals(1, TribulationLuck.tierForLevel(50, DEFAULT_THRESHOLDS));
        assertEquals(1, TribulationLuck.tierForLevel(99, DEFAULT_THRESHOLDS));
        assertEquals(2, TribulationLuck.tierForLevel(100, DEFAULT_THRESHOLDS));
        assertEquals(5, TribulationLuck.tierForLevel(250, DEFAULT_THRESHOLDS));
        assertEquals(5, TribulationLuck.tierForLevel(9999, DEFAULT_THRESHOLDS));
    }

    @Test
    void tierForLevelToleratesDegenerateInputs() {
        assertEquals(0, TribulationLuck.tierForLevel(100, null));
        assertEquals(0, TribulationLuck.tierForLevel(100, new int[0]));
        assertEquals(0, TribulationLuck.tierForLevel(-5, DEFAULT_THRESHOLDS));
        // More thresholds than the curve has tiers still clamps to the top tier.
        assertEquals(5, TribulationLuck.tierForLevel(1000, new int[]{1, 2, 3, 4, 5, 6, 7}));
    }

    @Test
    void luckCurveIsMonotonicFlatEarlyAndCapped() {
        assertEquals(0.0f, TribulationLuck.luckForTier(0));
        float previous = 0.0f;
        for (int tier = 0; tier <= 5; tier++) {
            float luck = TribulationLuck.luckForTier(tier);
            assertTrue(luck >= previous, "luck must not decrease with tier");
            assertTrue(luck <= TribulationLuck.MAX_BONUS_LUCK, "luck must respect the cap");
            previous = luck;
        }
        // Early game adds little: tiers 0-1 together stay under half a luck point.
        assertTrue(TribulationLuck.luckForTier(1) < 0.5f);
    }

    @Test
    void luckAndStackFactorClampOutOfRangeTiers() {
        assertEquals(TribulationLuck.luckForTier(0), TribulationLuck.luckForTier(-3));
        assertEquals(TribulationLuck.luckForTier(5), TribulationLuck.luckForTier(42));
        assertEquals(TribulationLuck.stackFactorForTier(0), TribulationLuck.stackFactorForTier(-1));
        assertEquals(TribulationLuck.stackFactorForTier(5), TribulationLuck.stackFactorForTier(42));
    }

    @Test
    void stackFactorOnlyNudgesHighestTiers() {
        for (int tier = 0; tier <= 3; tier++) {
            assertEquals(1.0f, TribulationLuck.stackFactorForTier(tier));
        }
        assertTrue(TribulationLuck.stackFactorForTier(4) > 1.0f);
        assertTrue(TribulationLuck.stackFactorForTier(5) >= TribulationLuck.stackFactorForTier(4));
        // Kept modest: never more than a 25% bump on top of distance scaling.
        assertTrue(TribulationLuck.stackFactorForTier(5) <= 1.25f);
    }
}
