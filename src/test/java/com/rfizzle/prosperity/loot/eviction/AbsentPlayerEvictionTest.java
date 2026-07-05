package com.rfizzle.prosperity.loot.eviction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.loot.LootRefresh;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the absent-player eviction threshold math (issue #43). The ledger, the
 * online-player exclusion, and the prune-on-open integration need a live server and are covered by
 * {@code AbsentPlayerEvictionGameTest}; the ledger's NBT round-trip is covered by
 * {@link PlayerLastSeenStateTest}.
 */
class AbsentPlayerEvictionTest {

    private static final long DAY = LootRefresh.TICKS_PER_DAY;

    @Test
    void underThresholdIsPresent() {
        assertFalse(AbsentPlayerEviction.isAbsent(0L, 7 * DAY - 1, 7),
                "one tick short of the threshold is still present");
    }

    @Test
    void exactThresholdIsAbsent() {
        assertTrue(AbsentPlayerEviction.isAbsent(0L, 7 * DAY, 7),
                "exactly the threshold counts as absent, mirroring the refresh cooldown's >=");
    }

    @Test
    void overThresholdIsAbsent() {
        assertTrue(AbsentPlayerEviction.isAbsent(100L, 100L + 7 * DAY + 1, 7));
    }

    @Test
    void zeroDayThresholdFiresImmediately() {
        // Not reachable from a loaded config (clamp() floors the knob at 1) but used by gametests
        // to exercise eviction without advancing whole in-game days.
        assertTrue(AbsentPlayerEviction.isAbsent(500L, 500L, 0));
    }

    @Test
    void largeThresholdDoesNotOverflow() {
        // One tick under the max threshold: if the days-to-ticks product overflowed (int math), the
        // threshold would wrap negative and this would spuriously read as absent.
        assertFalse(AbsentPlayerEviction.isAbsent(0L, Integer.MAX_VALUE * DAY - 1, Integer.MAX_VALUE));
        assertTrue(AbsentPlayerEviction.isAbsent(0L, Integer.MAX_VALUE * DAY, Integer.MAX_VALUE));
    }
}
