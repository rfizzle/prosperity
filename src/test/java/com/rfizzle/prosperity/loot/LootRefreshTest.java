package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit tests for the {@link LootRefresh} primitives. They take no Minecraft types, so the
 * cooldown boundaries ({@code isExpired}: just-before / at / after, disabled, retroactive, and the
 * never-generated sentinel), the sweep edge-trigger ({@code crossedExpiry}: the {@code (previous, now]}
 * window and the first-sweep/stalled-clock guard), and the {@code randomizeLootOnRefresh} salt gate
 * ({@code refreshSalt}) are all exercised without bootstrapping the game.
 */
class LootRefreshTest {

    private static final int DAYS = 7;
    private static final long COOLDOWN = (long) DAYS * LootRefresh.TICKS_PER_DAY; // 168_000

    @Test
    void cooldownTicksIsDaysTimesDayLength() {
        assertEquals(24_000L, LootRefresh.cooldownTicks(1));
        assertEquals(168_000L, LootRefresh.cooldownTicks(7));
    }

    @Test
    void notExpiredJustBeforeCooldown() {
        assertFalse(LootRefresh.isExpired(0L, COOLDOWN - 1, true, DAYS),
                "loot one tick short of the cooldown is not yet expired");
    }

    @Test
    void expiredExactlyAtCooldown() {
        assertTrue(LootRefresh.isExpired(0L, COOLDOWN, true, DAYS),
                "loot is expired the tick the cooldown is reached");
    }

    @Test
    void expiredAfterCooldown() {
        assertTrue(LootRefresh.isExpired(0L, COOLDOWN + 5_000, true, DAYS),
                "loot well past the cooldown is expired");
    }

    @Test
    void disabledNeverExpires() {
        assertFalse(LootRefresh.isExpired(0L, COOLDOWN * 10, false, DAYS),
                "with refresh disabled, loot never expires no matter how old");
    }

    @Test
    void neverGeneratedNeverExpires() {
        assertFalse(LootRefresh.isExpired(-1L, COOLDOWN * 10, true, DAYS),
                "the -1 never-generated sentinel is never treated as expired");
    }

    @Test
    void retroactiveEnableUsesTheRecordedTick() {
        // A tick recorded while the feature was off (the generate path always records it) is measured
        // against the cooldown once the feature is turned on — the container expires retroactively.
        long recordedWhileDisabled = 5_000L;
        assertFalse(LootRefresh.isExpired(recordedWhileDisabled, recordedWhileDisabled + COOLDOWN - 1,
                true, DAYS), "just before the cooldown after a retroactive enable, not expired");
        assertTrue(LootRefresh.isExpired(recordedWhileDisabled, recordedWhileDisabled + COOLDOWN,
                true, DAYS), "at the cooldown after a retroactive enable, expired");
    }

    @Test
    void shorterCooldownExpiresSooner() {
        assertFalse(LootRefresh.isExpired(0L, LootRefresh.TICKS_PER_DAY - 1, true, 1),
                "a one-day cooldown is not met just before a day passes");
        assertTrue(LootRefresh.isExpired(0L, LootRefresh.TICKS_PER_DAY, true, 1),
                "a one-day cooldown is met exactly at one day");
    }

    // --- crossedExpiry: the edge-trigger for the loot-refresh sweep (S-016) ------------------------

    @Test
    void crossedExpiryFiresOnlyInsideTheWindow() {
        // Loot generated at 0 expires at COOLDOWN. It crosses only in a window whose end is at/after the
        // expiry tick and whose start is strictly before it.
        long expiry = COOLDOWN; // lastGen = 0
        assertTrue(LootRefresh.crossedExpiry(0L, COOLDOWN, expiry - 1, expiry),
                "expiry landing on the window end (previous < expiry <= now) is a crossing");
        assertTrue(LootRefresh.crossedExpiry(0L, COOLDOWN, expiry - 100, expiry + 50),
                "expiry strictly inside the window is a crossing");
    }

    @Test
    void crossedExpiryFalseWhenAlreadyExpiredBeforeTheWindow() {
        long expiry = COOLDOWN;
        assertFalse(LootRefresh.crossedExpiry(0L, COOLDOWN, expiry, expiry + 600),
                "expiry at the window start (already expired at previous) did not cross this window");
        assertFalse(LootRefresh.crossedExpiry(0L, COOLDOWN, expiry + 1, expiry + 600),
                "expiry before the window is long past — no crossing");
    }

    @Test
    void crossedExpiryFalseWhenNotYetExpired() {
        long expiry = COOLDOWN;
        assertFalse(LootRefresh.crossedExpiry(0L, COOLDOWN, expiry - 600, expiry - 1),
                "expiry one tick past the window end has not been reached yet");
    }

    @Test
    void crossedExpiryFalseOnStalledOrFirstSweep() {
        // previous >= now (first sweep seeds previous = now, or the clock did not advance): the window is
        // empty, so nothing can cross regardless of how old the loot is.
        assertFalse(LootRefresh.crossedExpiry(0L, COOLDOWN, COOLDOWN * 10, COOLDOWN * 10),
                "an empty (previous == now) window never fires");
        assertFalse(LootRefresh.crossedExpiry(0L, COOLDOWN, COOLDOWN * 10, COOLDOWN),
                "a backwards (previous > now) window never fires");
    }

    @Test
    void crossedExpiryFalseForNeverGenerated() {
        assertFalse(LootRefresh.crossedExpiry(-1L, COOLDOWN, 0L, COOLDOWN * 10),
                "the -1 never-generated sentinel never crosses a cooldown it never entered");
    }

    // --- refreshSalt: the randomizeLootOnRefresh gate ----------------------------------------------

    @Test
    void refreshSaltIsZeroWhenRandomizeDisabled() {
        assertEquals(0L, LootRefresh.refreshSalt(false, 0L),
                "with the toggle off, a first generation rolls at salt 0");
        assertEquals(0L, LootRefresh.refreshSalt(false, 5L),
                "with the toggle off, even a refreshed instance rolls at salt 0 (regenerates identically)");
    }

    @Test
    void refreshSaltTracksRefreshCountWhenEnabled() {
        assertEquals(0L, LootRefresh.refreshSalt(true, 0L),
                "with the toggle on, an unrefreshed instance still rolls at salt 0");
        assertEquals(1L, LootRefresh.refreshSalt(true, 1L),
                "with the toggle on, the first refresh re-rolls at a non-zero salt");
        assertEquals(7L, LootRefresh.refreshSalt(true, 7L),
                "with the toggle on, the salt tracks the refresh count so each re-roll differs");
    }
}
