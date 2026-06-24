package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import org.junit.jupiter.api.Test;

/** Pure-JUnit checks for the distance-scaling math (S-011): stack-count and effective-luck. */
class LootScalingTest {

    private static DistanceTier tier(double stackMultiplier, int qualityModifier) {
        return new DistanceTier("test", 0, stackMultiplier, qualityModifier);
    }

    // ---- scaledCount ----

    @Test
    void scalesAndFloorsStackableCounts() {
        assertEquals(16, LootScaling.scaledCount(8, 64, 2.0), "8 x 2.0 = 16");
        assertEquals(8, LootScaling.scaledCount(3, 64, 2.75), "floor(3 x 2.75) = floor(8.25) = 8");
    }

    @Test
    void capsAtTheItemsMaxStackSize() {
        assertEquals(64, LootScaling.scaledCount(24, 64, 3.5), "floor(24 x 3.5) = 84 caps at 64");
        assertEquals(16, LootScaling.scaledCount(10, 16, 3.5),
                "an item that stacks to 16 (e.g. ender pearls) caps at 16, not 64");
    }

    @Test
    void leavesNonStackableItemsUnchanged() {
        assertEquals(1, LootScaling.scaledCount(1, 1, 3.5),
                "a max-stack-1 item (tool, weapon, enchanted book) is never scaled");
    }

    @Test
    void neverReducesBelowTheOriginalCount() {
        assertEquals(10, LootScaling.scaledCount(10, 64, 0.5),
                "a sub-1.0 multiplier must not shrink the rolled count");
        assertEquals(8, LootScaling.scaledCount(8, 64, 1.0), "the baseline 1.0x is a no-op");
    }

    // ---- effectiveLuck ----

    @Test
    void addsTheTierQualityModifierToBaseLuck() {
        assertEquals(4.0f, LootScaling.effectiveLuck(0.0f, tier(3.5, 4)), "0 + 4 quality = 4");
        assertEquals(3.5f, LootScaling.effectiveLuck(2.5f, tier(1.5, 1)), "2.5 base + 1 quality = 3.5");
    }

    @Test
    void localTierLeavesLuckUnchanged() {
        assertEquals(2.5f, LootScaling.effectiveLuck(2.5f, ProsperityConfig.LOCAL_SENTINEL),
                "the local sentinel's +0 quality leaves base luck untouched");
    }
}
