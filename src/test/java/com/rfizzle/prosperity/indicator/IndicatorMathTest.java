package com.rfizzle.prosperity.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the overlay bob/fade/animation curves (no game bootstrap). */
class IndicatorMathTest {

    private static final double EPS = 1e-9;

    @Test
    void bobIsZeroAtCycleStartAndHalf() {
        assertEquals(0.0, IndicatorMath.bobOffset(0.0), EPS);
        // Half a period (20 ticks) is the descending zero crossing.
        assertEquals(0.0, IndicatorMath.bobOffset(IndicatorMath.BOB_PERIOD_TICKS / 2.0), EPS);
        // A full period returns to the start.
        assertEquals(0.0, IndicatorMath.bobOffset(IndicatorMath.BOB_PERIOD_TICKS), EPS);
    }

    @Test
    void bobReachesAmplitudeAtQuarterPeriods() {
        assertEquals(IndicatorMath.BOB_AMPLITUDE,
                IndicatorMath.bobOffset(IndicatorMath.BOB_PERIOD_TICKS / 4.0), EPS);
        assertEquals(-IndicatorMath.BOB_AMPLITUDE,
                IndicatorMath.bobOffset(3.0 * IndicatorMath.BOB_PERIOD_TICKS / 4.0), EPS);
    }

    @Test
    void bobStaysWithinAmplitudeAcrossTheCycle() {
        for (double t = 0.0; t <= IndicatorMath.BOB_PERIOD_TICKS; t += 0.37) {
            double offset = IndicatorMath.bobOffset(t);
            assertTrue(Math.abs(offset) <= IndicatorMath.BOB_AMPLITUDE + EPS,
                    "bob out of band at t=" + t + ": " + offset);
        }
    }

    @Test
    void fadeIsFullyOpaqueWellInsideRange() {
        assertEquals(1.0, IndicatorMath.fadeAlpha(0.0, 48.0), EPS);
        assertEquals(1.0, IndicatorMath.fadeAlpha(40.0, 48.0), EPS); // exactly at the ramp start
    }

    @Test
    void fadeRampsLinearlyOverTheFinalBand() {
        // Halfway through the 8-block band → half opacity.
        assertEquals(0.5, IndicatorMath.fadeAlpha(44.0, 48.0), EPS);
    }

    @Test
    void fadeIsZeroAtAndBeyondRenderDistance() {
        assertEquals(0.0, IndicatorMath.fadeAlpha(48.0, 48.0), EPS);
        assertEquals(0.0, IndicatorMath.fadeAlpha(60.0, 48.0), EPS);
    }

    @Test
    void fadeIsZeroWhenRenderDistanceDisabled() {
        assertEquals(0.0, IndicatorMath.fadeAlpha(0.0, 0.0), EPS);
    }

    @Test
    void animationFrameAdvancesEveryFrameTimeAndWraps() {
        assertEquals(0, IndicatorMath.animationFrame(0));
        assertEquals(0, IndicatorMath.animationFrame(IndicatorMath.FRAME_TIME_TICKS - 1));
        assertEquals(1, IndicatorMath.animationFrame(IndicatorMath.FRAME_TIME_TICKS));
        assertEquals(3, IndicatorMath.animationFrame(3 * IndicatorMath.FRAME_TIME_TICKS));
        // Wraps back to frame 0 after the full strip.
        assertEquals(0, IndicatorMath.animationFrame(IndicatorMath.FRAME_COUNT * IndicatorMath.FRAME_TIME_TICKS));
    }
}
