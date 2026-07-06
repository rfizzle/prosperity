package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.config.DistanceTier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the action-bar notification's value and text shaping (S-021, SPEC §8): when
 * the modifier clause is shown, how the multiplier and quality render, structure-name humanization,
 * and the structure-suffix gate. The assembled {@link net.minecraft.network.chat.Component} and the
 * config-gated send are exercised by {@code LootNotificationGameTest}.
 */
class LootNotificationTest {

    private static final DistanceTier LOCAL = new DistanceTier("local", 0, 1.0, 0);
    private static final DistanceTier FRONTIER = new DistanceTier("frontier", 1000, 1.5, 1);
    private static final DistanceTier WILDERNESS = new DistanceTier("wilderness", 3000, 2.0, 2);
    private static final DistanceTier DEPTHS = new DistanceTier("depths", 10000, 3.5, 4);

    @Test
    void modifierClauseShownOnlyOffBaseline() {
        // Local with no API contribution: bare "✦ Local", no clause.
        assertFalse(LootNotification.hasModifiers(1.0, 0));
        // Either value off its default surfaces the clause.
        assertTrue(LootNotification.hasModifiers(2.0, 2));
        assertTrue(LootNotification.hasModifiers(1.0, 1));
        assertTrue(LootNotification.hasModifiers(1.5, 0));
    }

    @Test
    void multiplierRendersInNaturalDecimal() {
        assertEquals("1.0", LootNotification.multiplierText(LOCAL.stackMultiplier()));
        assertEquals("1.5", LootNotification.multiplierText(FRONTIER.stackMultiplier()));
        assertEquals("2.0", LootNotification.multiplierText(WILDERNESS.stackMultiplier()));
        assertEquals("2.75", LootNotification.multiplierText(2.75));
        assertEquals("3.5", LootNotification.multiplierText(DEPTHS.stackMultiplier()));
    }

    @Test
    void qualityRoundsFinalLuckToInt() {
        assertEquals(2, LootNotification.qualityValue(2.0f));
        assertEquals(4, LootNotification.qualityValue(4.0f));
        // A fractional API contribution rounds to the nearest whole quality.
        assertEquals(3, LootNotification.qualityValue(3.4f));
        assertEquals(4, LootNotification.qualityValue(3.6f));
        assertEquals(0, LootNotification.qualityValue(0.0f));
    }

    @Test
    void humanizeTitleCasesPath() {
        assertEquals("Ancient City", LootNotification.humanize("ancient_city"));
        assertEquals("Trail Ruins", LootNotification.humanize("trail_ruins"));
        assertEquals("Monument", LootNotification.humanize("monument"));
        // Subpaths split on '/' too, so a nested modded id still reads sensibly.
        assertEquals("Some Cool Ruin", LootNotification.humanize("some_cool/ruin"));
    }

    @Test
    void firstOpenMessageMatchesLangFallback() {
        // The fallback text must match the en_us.json value so headless renders read identically to a
        // localized client (issue #86).
        assertEquals("This loot was rolled just for you — other players get their own.",
                LootNotification.buildFirstOpen().getString());
    }

    @Test
    void structureSuffixOnlyWhenOverrideChangedTier() {
        ResourceLocation monument = ResourceLocation.parse("minecraft:monument");
        // Structure present and the tier was raised/replaced → show the suffix.
        assertTrue(LootNotification.overrideChangedTier(
                new LootScaling.ScaledTier(WILDERNESS, monument), FRONTIER));
        // Structure present but the override left the tier unchanged (e.g. a no-op minimum) → hide it.
        assertFalse(LootNotification.overrideChangedTier(
                new LootScaling.ScaledTier(WILDERNESS, monument), WILDERNESS));
        // No structure detected → never show the suffix even though the tier differs.
        assertFalse(LootNotification.overrideChangedTier(
                new LootScaling.ScaledTier(WILDERNESS, null), FRONTIER));
    }
}
