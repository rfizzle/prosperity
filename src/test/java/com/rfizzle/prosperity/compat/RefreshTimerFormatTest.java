package com.rfizzle.prosperity.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the refresh-timer formatter (S-026). No Minecraft bootstrap:
 * {@link RefreshTimerFormat} deliberately has no game imports. In-game units: 24,000 ticks/day,
 * 1,000 ticks/hour.
 */
class RefreshTimerFormatTest {

    @Test
    void daysAndHoursWhenAtLeastOneDay() {
        // 2d 14h = 2*24000 + 14*1000
        assertEquals("2d 14h", RefreshTimerFormat.format(2 * 24_000L + 14 * 1_000L));
        assertEquals("7d 0h", RefreshTimerFormat.format(7 * 24_000L));
        assertEquals("1d 0h", RefreshTimerFormat.format(24_000L));
    }

    @Test
    void hoursOnlyWhenUnderADay() {
        // drop the 0d: under a day shows just hours
        assertEquals("23h", RefreshTimerFormat.format(23_999L));
        assertEquals("5h", RefreshTimerFormat.format(5 * 1_000L));
        assertEquals("1h", RefreshTimerFormat.format(1_000L));
        assertEquals("1h", RefreshTimerFormat.format(1_999L));
    }

    @Test
    void minutesWhenUnderAnHour() {
        // floored, never rolls up to 60m
        assertEquals("59m", RefreshTimerFormat.format(999L));
        assertEquals("30m", RefreshTimerFormat.format(500L));
        assertEquals("1m", RefreshTimerFormat.format(16L));
    }

    @Test
    void clampsAtLeastOneMinuteWhileTimeRemains() {
        assertEquals("1m", RefreshTimerFormat.format(1L));
        assertEquals("1m", RefreshTimerFormat.format(15L));
    }

    @Test
    void zeroOrNegativeRendersZeroMinutes() {
        assertEquals("0m", RefreshTimerFormat.format(0L));
        assertEquals("0m", RefreshTimerFormat.format(-1L));
        assertEquals("0m", RefreshTimerFormat.format(-100_000L));
    }
}
