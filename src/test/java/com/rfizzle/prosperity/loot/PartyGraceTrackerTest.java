package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the party loot mode leave-grace window math (no game bootstrap). */
class PartyGraceTrackerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @AfterEach
    void reset() {
        PartyGraceTracker.clear();
    }

    @Test
    void graceTicksConvertsMinutesToTicks() {
        assertEquals(0L, PartyGraceTracker.graceTicks(0), "zero minutes disables the window");
        assertEquals(0L, PartyGraceTracker.graceTicks(-5), "negative minutes disable the window");
        assertEquals(20L * 60L, PartyGraceTracker.graceTicks(1), "one minute is 1200 ticks");
        assertEquals(20L * 60L * 5L, PartyGraceTracker.graceTicks(5));
    }

    @Test
    void formerGroupReturnedInsideWindow() {
        PartyGraceTracker.remember(PLAYER, "raiders", 1_000L);
        // 1000 ticks later, still inside a 1200-tick (1 minute) window.
        assertEquals("raiders", PartyGraceTracker.formerGroup(PLAYER, 2_000L, 1_200L));
    }

    @Test
    void formerGroupExpiresAtWindowEdge() {
        PartyGraceTracker.remember(PLAYER, "raiders", 1_000L);
        // Exactly at the window length the grace has elapsed (>= is expired), and the entry is dropped.
        assertNull(PartyGraceTracker.formerGroup(PLAYER, 2_200L, 1_200L));
        // A subsequent query with a huge window still finds nothing — the entry was pruned.
        assertNull(PartyGraceTracker.formerGroup(PLAYER, 2_201L, Long.MAX_VALUE));
    }

    @Test
    void disabledWindowNeverResolves() {
        PartyGraceTracker.remember(PLAYER, "raiders", 1_000L);
        assertNull(PartyGraceTracker.formerGroup(PLAYER, 1_001L, 0L), "a zero window is off");
    }

    @Test
    void unknownPlayerHasNoFormerGroup() {
        assertNull(PartyGraceTracker.formerGroup(PLAYER, 500L, 1_200L));
    }

    @Test
    void rememberRefreshesTheWindow() {
        PartyGraceTracker.remember(PLAYER, "raiders", 1_000L);
        // Re-confirmed at 2000: the window now measures from there, so 3000 is still inside 1200.
        PartyGraceTracker.remember(PLAYER, "raiders", 2_000L);
        assertEquals("raiders", PartyGraceTracker.formerGroup(PLAYER, 3_000L, 1_200L));
    }

    @Test
    void clockRewoundByReloadKeepsGroup() {
        PartyGraceTracker.remember(PLAYER, "raiders", 5_000L);
        // A world reload can rewind the game clock; a negative elapsed span stays inside the window.
        assertEquals("raiders", PartyGraceTracker.formerGroup(PLAYER, 1_000L, 1_200L));
    }
}
