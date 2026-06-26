package com.rfizzle.prosperity.compat;

/**
 * Formats a remaining-until-refresh duration for the container probe tooltip (SPEC §10). Kept free of
 * any Minecraft imports so the boundary behavior is unit-testable without bootstrapping the game, and
 * shared by every probe integration (Jade, WTHIT) so their timers read identically.
 *
 * <p>Durations are expressed in in-game time: one day is {@value #TICKS_PER_DAY} ticks, split into 24
 * hours of {@value #TICKS_PER_HOUR} ticks. The format follows the cooldown's own granularity &mdash;
 * {@code Xd Yh} once a day or more remains, {@code Yh} under a day but at least an hour, {@code Xm}
 * under an hour &mdash; matching the in-game-day unit the refresh cooldown is configured in.
 */
public final class RefreshTimerFormat {

    static final long TICKS_PER_DAY = 24_000L;
    static final long TICKS_PER_HOUR = 1_000L;

    private RefreshTimerFormat() {
    }

    /**
     * The remaining duration as {@code Xd Yh} / {@code Yh} / {@code Xm}. A non-positive remaining time
     * renders {@code "0m"} (the caller suppresses the line entirely once loot has expired). Minutes are
     * floored so the sub-hour branch never rolls up to {@code "60m"}, and never below {@code "1m"} while
     * any time remains.
     */
    public static String format(long remainingTicks) {
        if (remainingTicks <= 0L) {
            return "0m";
        }
        if (remainingTicks < TICKS_PER_HOUR) {
            long minutes = Math.max(1L, remainingTicks * 60L / TICKS_PER_HOUR);
            return minutes + "m";
        }
        long days = remainingTicks / TICKS_PER_DAY;
        long hoursWithinDay = (remainingTicks % TICKS_PER_DAY) / TICKS_PER_HOUR;
        if (days > 0L) {
            return days + "d " + hoursWithinDay + "h";
        }
        return hoursWithinDay + "h";
    }
}
