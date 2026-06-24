package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Pure-JUnit checks for double-chest primary-half selection and slot layout (S-007). */
class DoubleChestLayoutTest {

    @Test
    void smallerXWins() {
        BlockPos low = new BlockPos(4, 64, 7);
        BlockPos high = new BlockPos(5, 64, 7);
        assertEquals(low, DoubleChestLayout.primary(low, high), "smaller x is the primary half");
        assertEquals(low, DoubleChestLayout.primary(high, low), "argument order must not matter");
        assertEquals(high, DoubleChestLayout.secondary(low, high), "the other half is secondary");
    }

    @Test
    void tiesXThenSmallerZWins() {
        BlockPos low = new BlockPos(4, 64, 7);
        BlockPos high = new BlockPos(4, 64, 8);
        assertEquals(low, DoubleChestLayout.primary(low, high), "equal x falls to smaller z");
        assertEquals(low, DoubleChestLayout.primary(high, low));
        assertEquals(high, DoubleChestLayout.secondary(high, low));
    }

    @Test
    void tiesXAndZThenSmallerYWins() {
        BlockPos low = new BlockPos(4, 63, 7);
        BlockPos high = new BlockPos(4, 64, 7);
        assertEquals(low, DoubleChestLayout.primary(low, high), "equal x and z falls to smaller y");
        assertEquals(low, DoubleChestLayout.primary(high, low));
    }

    @Test
    void negativeCoordinatesCompareNumerically() {
        BlockPos low = new BlockPos(-9, 64, 0);
        BlockPos high = new BlockPos(-8, 64, 0);
        assertEquals(low, DoubleChestLayout.primary(high, low), "negative x compares numerically, not by magnitude");
    }

    @Test
    void slotLayoutSpansBothHalves() {
        assertEquals(27, DoubleChestLayout.PRIMARY_SLOTS, "each half contributes one chest's slots");
        assertEquals(54, DoubleChestLayout.TOTAL_SLOTS, "the combined instance is a full double chest");
        assertEquals(DoubleChestLayout.TOTAL_SLOTS,
                DoubleChestLayout.PRIMARY_SLOTS * 2, "the two halves tile the combined inventory");
    }
}
