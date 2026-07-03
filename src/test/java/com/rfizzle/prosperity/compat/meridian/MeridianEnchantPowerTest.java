package com.rfizzle.prosperity.compat.meridian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The tier&rarr;power curve for Meridian loot enchantment rolls. */
class MeridianEnchantPowerTest {

    /** The default 5-tier ladder: local(0), frontier(1), wilderness(2), outlands(3), depths(4). */
    private static final int DEFAULT_MAX_INDEX = 4;

    @Test
    void localRollsNothing() {
        assertEquals(0, MeridianEnchantPower.powerForTier(0, DEFAULT_MAX_INDEX));
    }

    @Test
    void firstTravelledTierRollsAtMinPower() {
        assertEquals(MeridianEnchantPower.MIN_POWER,
                MeridianEnchantPower.powerForTier(1, DEFAULT_MAX_INDEX));
    }

    @Test
    void deepestTierRollsAtMaxPower() {
        assertEquals(MeridianEnchantPower.MAX_POWER,
                MeridianEnchantPower.powerForTier(DEFAULT_MAX_INDEX, DEFAULT_MAX_INDEX));
    }

    @Test
    void intermediateTiersRampMonotonically() {
        int previous = 0;
        for (int index = 0; index <= DEFAULT_MAX_INDEX; index++) {
            int power = MeridianEnchantPower.powerForTier(index, DEFAULT_MAX_INDEX);
            assertTrue(power >= previous, "power must not decrease with depth at index " + index);
            assertTrue(power <= MeridianEnchantPower.MAX_POWER, "power exceeds cap at index " + index);
            previous = power;
        }
        // The default ladder's exact intermediate values, pinned so a curve change is deliberate.
        assertEquals(15, MeridianEnchantPower.powerForTier(2, DEFAULT_MAX_INDEX));
        assertEquals(23, MeridianEnchantPower.powerForTier(3, DEFAULT_MAX_INDEX));
    }

    @Test
    void indexBeyondLadderClampsToMax() {
        assertEquals(MeridianEnchantPower.MAX_POWER,
                MeridianEnchantPower.powerForTier(9, DEFAULT_MAX_INDEX));
    }

    @Test
    void twoTierLadderTravelledTierIsDeepest() {
        assertEquals(0, MeridianEnchantPower.powerForTier(0, 1));
        assertEquals(MeridianEnchantPower.MAX_POWER, MeridianEnchantPower.powerForTier(1, 1));
        assertTrue(MeridianEnchantPower.treasureAllowed(1, 1));
    }

    @Test
    void degenerateLaddersRollNothing() {
        assertEquals(0, MeridianEnchantPower.powerForTier(0, 0));
        assertEquals(0, MeridianEnchantPower.powerForTier(3, 0));
        assertEquals(0, MeridianEnchantPower.powerForTier(-1, DEFAULT_MAX_INDEX));
    }

    @Test
    void treasureOnlyAtDeepestTier() {
        assertFalse(MeridianEnchantPower.treasureAllowed(0, DEFAULT_MAX_INDEX));
        assertFalse(MeridianEnchantPower.treasureAllowed(DEFAULT_MAX_INDEX - 1, DEFAULT_MAX_INDEX));
        assertTrue(MeridianEnchantPower.treasureAllowed(DEFAULT_MAX_INDEX, DEFAULT_MAX_INDEX));
        assertFalse(MeridianEnchantPower.treasureAllowed(0, 0));
    }
}
