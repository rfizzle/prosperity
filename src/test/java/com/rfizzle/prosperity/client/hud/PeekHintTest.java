package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the peek discovery hint's eligibility and once-per-five-sessions cadence (S-082). */
class PeekHintTest {

    @Test
    void eligibleOnlyBeforeFirstPeekAndWhileBound() {
        // Fresh install, key bound: this join counts toward the cadence.
        assertTrue(PeekHint.eligible(false, false));
    }

    @Test
    void ineligibleOnceDismissed() {
        assertFalse(PeekHint.eligible(true, false));
    }

    @Test
    void ineligibleWhenKeyUnbound() {
        // A player who cleared the binding would get a message naming no key — skip the join entirely.
        assertFalse(PeekHint.eligible(false, true));
        // Dismissed and unbound stays ineligible.
        assertFalse(PeekHint.eligible(true, true));
    }

    @Test
    void showsOnFirstJoinThenEveryFifth() {
        // Fires on joins 1, 6, 11, 16 — the first eligible join and every SESSIONS_PER_HINT after.
        assertTrue(PeekHint.showsOnJoin(1));
        assertTrue(PeekHint.showsOnJoin(1 + PeekHint.SESSIONS_PER_HINT));
        assertTrue(PeekHint.showsOnJoin(1 + 2 * PeekHint.SESSIONS_PER_HINT));
    }

    @Test
    void staysSilentOnTheInterveningJoins() {
        for (int n = 2; n <= PeekHint.SESSIONS_PER_HINT; n++) {
            assertFalse(PeekHint.showsOnJoin(n), "join " + n + " should be silent");
        }
    }

    @Test
    void neverShowsForNonPositiveCount() {
        assertFalse(PeekHint.showsOnJoin(0));
        assertFalse(PeekHint.showsOnJoin(-3));
    }
}
