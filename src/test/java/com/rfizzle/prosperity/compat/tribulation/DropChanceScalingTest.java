package com.rfizzle.prosperity.compat.tribulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropChanceScalingTest {

    // Default ladders: distance tiers local..depths (0-4), Tribulation tiers 0-5.
    private static final int MAX_DISTANCE_TIER = 4;
    private static final int MAX_MOB_TIER = 5;
    private static final float DEF = 0.085f;

    @Test
    void zeroDistanceDropsNothingWhateverTheMobTier() {
        for (int mobTier = 0; mobTier <= MAX_MOB_TIER; mobTier++) {
            assertEquals(0.0f,
                    DropChanceScaling.compute(0, MAX_DISTANCE_TIER, mobTier, MAX_MOB_TIER, DEF));
        }
    }

    @Test
    void tierZeroMobAtMaxDistanceYieldsLowButNonZeroChance() {
        float chance = DropChanceScaling.compute(
                MAX_DISTANCE_TIER, MAX_DISTANCE_TIER, 0, MAX_MOB_TIER, DEF);
        assertTrue(chance > 0.0f, "distance alone must yield a small chance");
        assertTrue(chance < DropChanceScaling.MAX_CHANCE / 2.0f, "tier-0 mob must stay well under the ceiling");
    }

    @Test
    void maxTiersHitTheCeilingExactly() {
        assertEquals(DropChanceScaling.MAX_CHANCE, DropChanceScaling.compute(
                MAX_DISTANCE_TIER, MAX_DISTANCE_TIER, MAX_MOB_TIER, MAX_MOB_TIER, DEF));
    }

    @Test
    void chanceIsMonotonicInBothTiersAndAlwaysInUnitRange() {
        for (int mobTier = 0; mobTier <= MAX_MOB_TIER; mobTier++) {
            float previous = -1.0f;
            for (int distanceTier = 0; distanceTier <= MAX_DISTANCE_TIER; distanceTier++) {
                float chance = DropChanceScaling.compute(
                        distanceTier, MAX_DISTANCE_TIER, mobTier, MAX_MOB_TIER, DEF);
                assertTrue(Float.isFinite(chance));
                assertTrue(chance >= 0.0f && chance <= 1.0f, "chance must stay in [0, 1]");
                assertTrue(chance >= previous, "chance must not decrease with distance tier");
                previous = chance;
            }
        }
        for (int distanceTier = 0; distanceTier <= MAX_DISTANCE_TIER; distanceTier++) {
            float previous = -1.0f;
            for (int mobTier = 0; mobTier <= MAX_MOB_TIER; mobTier++) {
                float chance = DropChanceScaling.compute(
                        distanceTier, MAX_DISTANCE_TIER, mobTier, MAX_MOB_TIER, DEF);
                assertTrue(chance >= previous, "chance must not decrease with mob tier");
                previous = chance;
            }
        }
    }

    @Test
    void outOfRangeInputsReturnTheDefaultUnchanged() {
        // Empty or single-band ladders.
        assertEquals(DEF, DropChanceScaling.compute(0, 0, 3, MAX_MOB_TIER, DEF));
        assertEquals(DEF, DropChanceScaling.compute(2, -1, 3, MAX_MOB_TIER, DEF));
        assertEquals(DEF, DropChanceScaling.compute(2, MAX_DISTANCE_TIER, 3, 0, DEF));
        // Tiers outside [0, max].
        assertEquals(DEF, DropChanceScaling.compute(-1, MAX_DISTANCE_TIER, 3, MAX_MOB_TIER, DEF));
        assertEquals(DEF, DropChanceScaling.compute(9, MAX_DISTANCE_TIER, 3, MAX_MOB_TIER, DEF));
        assertEquals(DEF, DropChanceScaling.compute(2, MAX_DISTANCE_TIER, -1, MAX_MOB_TIER, DEF));
        assertEquals(DEF, DropChanceScaling.compute(2, MAX_DISTANCE_TIER, 9, MAX_MOB_TIER, DEF));
        // Both-zero ladders return def without throwing.
        assertEquals(DEF, DropChanceScaling.compute(0, 0, 0, 0, DEF));
    }

    @Test
    void nonFiniteValuesCannotEscapeForInRangeInputs() {
        for (int distanceTier = 0; distanceTier <= MAX_DISTANCE_TIER; distanceTier++) {
            for (int mobTier = 0; mobTier <= MAX_MOB_TIER; mobTier++) {
                float chance = DropChanceScaling.compute(distanceTier, MAX_DISTANCE_TIER,
                        mobTier, MAX_MOB_TIER, Float.NaN);
                assertTrue(Float.isFinite(chance), "in-range inputs must never surface the default");
            }
        }
    }
}
