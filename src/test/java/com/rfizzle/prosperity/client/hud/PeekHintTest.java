package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the one-time peek discovery hint's send/suppress rule (S-082). */
class PeekHintTest {

    @Test
    void hintSendsOnlyBeforeFirstPeekAndWhileBound() {
        // Fresh install, key bound: the join hint sends.
        assertTrue(PeekHint.shouldSend(false, false));
    }

    @Test
    void hintSuppressedOnceDismissed() {
        assertFalse(PeekHint.shouldSend(true, false));
    }

    @Test
    void hintSuppressedWhenKeyUnbound() {
        // A player who cleared the binding would get a message naming no key — stay silent.
        assertFalse(PeekHint.shouldSend(false, true));
        // Dismissed and unbound stays silent.
        assertFalse(PeekHint.shouldSend(true, true));
    }
}
