package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the one-time peek hint chip's show/hide rule and added width (S-082). */
class PeekHintMathTest {

    @Test
    void hintShowsOnlyBeforeFirstPeekAndWhileBound() {
        // Fresh install, key bound: the chip shows.
        assertTrue(PeekHintMath.shouldShowHint(false, false));
    }

    @Test
    void hintHidesOnceDismissed() {
        assertFalse(PeekHintMath.shouldShowHint(true, false));
    }

    @Test
    void hintHidesWhenKeyUnbound() {
        // A player who cleared the binding would see a meaningless [ ] chip — suppress it.
        assertFalse(PeekHintMath.shouldShowHint(false, true));
        // Dismissed and unbound stays hidden.
        assertFalse(PeekHintMath.shouldShowHint(true, true));
    }

    @Test
    void chipWidthIsGapPlusPaddedText() {
        int text = 40;
        int expected = PeekHintMath.CHIP_GAP + PeekHintMath.CHIP_PAD_H + text + PeekHintMath.CHIP_PAD_H;
        assertEquals(expected, PeekHintMath.chipWidth(text));
    }

    @Test
    void chipWidthForEmptyTextIsExactlyGapPlusPadding() {
        // Even a zero-width label reserves the gap and both paddings — pin the exact contract.
        assertEquals(PeekHintMath.CHIP_GAP + 2 * PeekHintMath.CHIP_PAD_H, PeekHintMath.chipWidth(0));
        assertTrue(PeekHintMath.chipWidth(0) > 0);
    }
}
