package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit cooldown-boundary tests for {@link LootRefresh}. The primitive {@code isExpired} overload
 * takes no Minecraft types, so the just-before / at / after boundaries, the disabled and retroactive
 * cases, and the never-generated sentinel are all exercised without bootstrapping the game.
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
}
