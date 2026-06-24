package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.config.StructureOverride;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the scaling math: stack-count and effective-luck (S-011) and structure
 * override resolution (S-012).
 */
class LootScalingTest {

    /** Named tiers ordered by minDistance, matching the shipped default band names. */
    private static final DistanceTier LOCAL = new DistanceTier("local", 0, 1.0, 0);
    private static final DistanceTier FRONTIER = new DistanceTier("frontier", 1000, 1.5, 1);
    private static final DistanceTier WILDERNESS = new DistanceTier("wilderness", 3000, 2.0, 2);
    private static final DistanceTier OUTLANDS = new DistanceTier("outlands", 6000, 2.75, 3);
    private static final DistanceTier DEPTHS = new DistanceTier("depths", 10000, 3.5, 4);

    private static ProsperityConfig configWith(StructureOverride... overrides) {
        ProsperityConfig cfg = new ProsperityConfig();
        cfg.distanceTiers = List.of(LOCAL, FRONTIER, WILDERNESS, OUTLANDS, DEPTHS);
        cfg.structureOverrides = List.of(overrides);
        return cfg;
    }

    // ---- scaledCount ----

    @Test
    void scalesAndFloorsStackableCounts() {
        assertEquals(16, LootScaling.scaledCount(8, 64, 2.0), "8 x 2.0 = 16");
        assertEquals(8, LootScaling.scaledCount(3, 64, 2.75), "floor(3 x 2.75) = floor(8.25) = 8");
    }

    @Test
    void capsAtTheItemsMaxStackSize() {
        assertEquals(64, LootScaling.scaledCount(24, 64, 3.5), "floor(24 x 3.5) = 84 caps at 64");
        assertEquals(16, LootScaling.scaledCount(10, 16, 3.5),
                "an item that stacks to 16 (e.g. ender pearls) caps at 16, not 64");
    }

    @Test
    void leavesNonStackableItemsUnchanged() {
        assertEquals(1, LootScaling.scaledCount(1, 1, 3.5),
                "a max-stack-1 item (tool, weapon, enchanted book) is never scaled");
    }

    @Test
    void neverReducesBelowTheOriginalCount() {
        assertEquals(10, LootScaling.scaledCount(10, 64, 0.5),
                "a sub-1.0 multiplier must not shrink the rolled count");
        assertEquals(8, LootScaling.scaledCount(8, 64, 1.0), "the baseline 1.0x is a no-op");
    }

    // ---- applyStructureOverride ----

    @Test
    void fixedModeReplacesTheTierRegardlessOfDistance() {
        ProsperityConfig cfg = configWith(new StructureOverride("minecraft:monument", "fixed", "wilderness"));
        assertSame(WILDERNESS, LootScaling.applyStructureOverride(LOCAL, "minecraft:monument", cfg),
                "fixed override replaces a lower distance tier");
        assertSame(WILDERNESS, LootScaling.applyStructureOverride(DEPTHS, "minecraft:monument", cfg),
                "fixed override replaces a higher distance tier too");
    }

    @Test
    void minimumModeRaisesButNeverLowers() {
        ProsperityConfig cfg = configWith(new StructureOverride("minecraft:stronghold", "minimum", "outlands"));
        assertSame(OUTLANDS, LootScaling.applyStructureOverride(LOCAL, "minecraft:stronghold", cfg),
                "minimum raises a lower distance tier up to the override");
        assertSame(DEPTHS, LootScaling.applyStructureOverride(DEPTHS, "minecraft:stronghold", cfg),
                "minimum leaves an already-higher distance tier untouched");
    }

    @Test
    void maximumModeCapsButNeverRaises() {
        ProsperityConfig cfg = configWith(new StructureOverride("minecraft:village_plains", "maximum", "frontier"));
        assertSame(FRONTIER, LootScaling.applyStructureOverride(DEPTHS, "minecraft:village_plains", cfg),
                "maximum caps a higher distance tier down to the override");
        assertSame(LOCAL, LootScaling.applyStructureOverride(LOCAL, "minecraft:village_plains", cfg),
                "maximum leaves an already-lower distance tier untouched");
    }

    @Test
    void noStructureOrNoMatchLeavesTheBaseTier() {
        ProsperityConfig cfg = configWith(new StructureOverride("minecraft:monument", "fixed", "wilderness"));
        assertSame(OUTLANDS, LootScaling.applyStructureOverride(OUTLANDS, null, cfg),
                "a container in no structure uses pure distance scaling");
        assertSame(OUTLANDS, LootScaling.applyStructureOverride(OUTLANDS, "minecraft:mansion", cfg),
                "a structure with no configured override uses pure distance scaling");
    }

    @Test
    void unknownModeOrTierDegradesToTheBaseTier() {
        ProsperityConfig badMode = configWith(new StructureOverride("minecraft:monument", "sideways", "wilderness"));
        assertSame(LOCAL, LootScaling.applyStructureOverride(LOCAL, "minecraft:monument", badMode),
                "an unrecognized mode is ignored");
        ProsperityConfig badTier = configWith(new StructureOverride("minecraft:monument", "fixed", "mythic"));
        assertSame(LOCAL, LootScaling.applyStructureOverride(LOCAL, "minecraft:monument", badTier),
                "an override naming a tier the config does not define is ignored");
    }

    @Test
    void overlappingStructuresAreResolvedByTheCallerNotTheModeMath() {
        // applyStructureOverride takes a single structure id (resolveStructure picks the most specific);
        // the most-specific match's mode is what applies. Here the smaller stronghold won upstream.
        ProsperityConfig cfg = configWith(
                new StructureOverride("minecraft:monument", "fixed", "wilderness"),
                new StructureOverride("minecraft:stronghold", "minimum", "outlands"));
        assertSame(OUTLANDS, LootScaling.applyStructureOverride(LOCAL, "minecraft:stronghold", cfg),
                "the resolved structure's own override applies");
    }

    @Test
    void shippedDefaultOverridesResolveAsDocumented() {
        ProsperityConfig defaults = new ProsperityConfig();
        DistanceTier monument =
                LootScaling.applyStructureOverride(ProsperityConfig.LOCAL_SENTINEL, "minecraft:monument", defaults);
        assertEquals("wilderness", monument.name(), "the default monument override fixes the tier to wilderness");
        DistanceTier village = LootScaling.applyStructureOverride(
                defaults.tierFor(20_000), "minecraft:village_plains", defaults);
        assertEquals("frontier", village.name(), "the default plains-village override caps a far village at frontier");
        DistanceTier stronghold = LootScaling.applyStructureOverride(
                ProsperityConfig.LOCAL_SENTINEL, "minecraft:stronghold", defaults);
        assertEquals("outlands", stronghold.name(), "the default stronghold override raises a near stronghold to outlands");
    }
}
