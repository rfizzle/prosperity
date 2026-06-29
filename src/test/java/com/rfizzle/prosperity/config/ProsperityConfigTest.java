package com.rfizzle.prosperity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProsperityConfigTest {

    @Test
    void defaultValuesMatchSpec() {
        ProsperityConfig c = new ProsperityConfig();

        // Server toggles + scalars (SPEC §Configuration "Server Config").
        assertTrue(c.enableInstancedLoot);
        assertTrue(c.enableVisualIndicators);
        assertEquals(48, c.indicatorRenderDistance);
        assertEquals(8, c.indicatorXrayDistance);
        assertTrue(c.enableDistanceScaling);
        assertTrue(c.lootTableBlacklist.isEmpty());
        assertTrue(c.enableLootInjection);
        assertTrue(c.enableLootNotifications);
        assertFalse(c.enableLootRefresh);
        assertEquals(7, c.lootRefreshDays);
        assertFalse(c.enableContainerProtection);
        assertEquals(4.0f, c.protectionBreakMultiplier);
        assertFalse(c.protectionUnbreakable);
        assertTrue(c.enableMobLootScaling);
        assertTrue(c.endAlwaysMaxTier);

        // Client block (SPEC §Configuration "Client Config").
        assertNotNull(c.client);
        assertTrue(c.client.showIndicators);
        assertTrue(c.client.enableTierHud);
        assertEquals(ProsperityConfig.Anchor.TOP_LEFT, c.client.hudAnchor);
        assertEquals(4, c.client.hudOffsetX);
        assertEquals(4, c.client.hudOffsetY);
    }

    @Test
    void defaultDistanceTiersMatchSpec() {
        List<DistanceTier> tiers = new ProsperityConfig().distanceTiers;
        assertEquals(List.of(
                new DistanceTier("local", 0, 1.0, 0),
                new DistanceTier("frontier", 1000, 1.5, 1),
                new DistanceTier("wilderness", 3000, 2.0, 2),
                new DistanceTier("outlands", 6000, 2.75, 3),
                new DistanceTier("depths", 10000, 3.5, 4)
        ), tiers);
    }

    @Test
    void defaultStructureOverridesMatchSpec() {
        List<StructureOverride> overrides = new ProsperityConfig().structureOverrides;
        assertEquals(List.of(
                new StructureOverride("minecraft:monument", "fixed", "wilderness"),
                new StructureOverride("minecraft:stronghold", "minimum", "outlands"),
                new StructureOverride("minecraft:village_plains", "maximum", "frontier"),
                new StructureOverride("minecraft:village_desert", "maximum", "frontier"),
                new StructureOverride("minecraft:village_savanna", "maximum", "frontier"),
                new StructureOverride("minecraft:village_snowy", "maximum", "frontier"),
                new StructureOverride("minecraft:village_taiga", "maximum", "frontier"),
                new StructureOverride("minecraft:ancient_city", "minimum", "outlands"),
                new StructureOverride("minecraft:trail_ruins", "minimum", "frontier")
        ), overrides);
    }

    @Test
    void jsonRoundTripIsLossless() {
        ProsperityConfig original = new ProsperityConfig();
        original.enableInstancedLoot = false;
        original.indicatorRenderDistance = 64;
        original.lootTableBlacklist = new ArrayList<>(List.of("minecraft:chests/abandoned_mineshaft", "examplemod:*"));
        original.client.hudAnchor = ProsperityConfig.Anchor.BOTTOM_RIGHT;
        original.client.hudOffsetX = 12;

        ProsperityConfig copy = ProsperityConfig.fromJson(original.toJson());

        assertEquals(original.enableInstancedLoot, copy.enableInstancedLoot);
        assertEquals(original.indicatorRenderDistance, copy.indicatorRenderDistance);
        assertEquals(original.lootTableBlacklist, copy.lootTableBlacklist);
        assertEquals(original.distanceTiers, copy.distanceTiers);
        assertEquals(original.structureOverrides, copy.structureOverrides);
        assertEquals(ProsperityConfig.Anchor.BOTTOM_RIGHT, copy.client.hudAnchor);
        assertEquals(12, copy.client.hudOffsetX);
    }

    @Test
    void missingKeysLoadAsDefaults() {
        // Only one server key present; everything else must default, including the client block.
        ProsperityConfig c = ProsperityConfig.fromJson("{\"enableInstancedLoot\": false}");

        assertFalse(c.enableInstancedLoot);
        assertEquals(48, c.indicatorRenderDistance);
        assertEquals(ProsperityConfig.defaultDistanceTiers(), c.distanceTiers);
        assertEquals(ProsperityConfig.defaultStructureOverrides(), c.structureOverrides);
        assertNotNull(c.client);
        assertEquals(ProsperityConfig.Anchor.TOP_LEFT, c.client.hudAnchor);
    }

    @Test
    void emptyObjectLoadsFullDefaults() {
        ProsperityConfig c = ProsperityConfig.fromJson("{}");
        ProsperityConfig defaults = new ProsperityConfig();

        assertEquals(defaults.indicatorRenderDistance, c.indicatorRenderDistance);
        assertEquals(defaults.lootRefreshDays, c.lootRefreshDays);
        assertEquals(defaults.distanceTiers, c.distanceTiers);
        assertEquals(defaults.structureOverrides, c.structureOverrides);
        assertEquals(defaults.client.hudAnchor, c.client.hudAnchor);
    }

    @Test
    void unknownEnumValueFallsBackToDefault() {
        // Gson leaves an unparseable enum null; clamp() must restore the default.
        ProsperityConfig c = ProsperityConfig.fromJson("{\"client\": {\"hudAnchor\": \"NONSENSE\"}}");
        assertEquals(ProsperityConfig.Anchor.TOP_LEFT, c.client.hudAnchor);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        ProsperityConfig c = ProsperityConfig.fromJson(
                "{\"indicatorRenderDistance\": -5, \"lootRefreshDays\": 0, \"protectionBreakMultiplier\": 0.1}");
        assertEquals(0, c.indicatorRenderDistance);
        assertEquals(1, c.lootRefreshDays);
        assertEquals(1.0f, c.protectionBreakMultiplier);
    }

    @Test
    void tierForBoundaryValues() {
        ProsperityConfig c = new ProsperityConfig();
        assertEquals("local", c.tierFor(0).name());
        assertEquals("local", c.tierFor(999).name());
        assertEquals("frontier", c.tierFor(1000).name());
        assertEquals("wilderness", c.tierFor(3000).name());
        assertEquals("outlands", c.tierFor(9999).name());
        assertEquals("depths", c.tierFor(10000).name());
        assertEquals("depths", c.tierFor(50000).name());
    }

    @Test
    void tierForEmptyListReturnsSentinel() {
        ProsperityConfig c = new ProsperityConfig();
        c.distanceTiers = new ArrayList<>();
        assertSame(ProsperityConfig.LOCAL_SENTINEL, c.tierFor(5000));
    }

    @Test
    void tierForSingleTier() {
        ProsperityConfig c = new ProsperityConfig();
        c.distanceTiers = new ArrayList<>(List.of(new DistanceTier("only", 500, 2.0, 1)));
        // Below the single tier's bound → sentinel; at/above → that tier.
        assertSame(ProsperityConfig.LOCAL_SENTINEL, c.tierFor(0));
        assertEquals("only", c.tierFor(500).name());
        assertEquals("only", c.tierFor(99999).name());
    }

    @Test
    void firstLaunchWritesCompleteConfig(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        assertFalse(Files.exists(path));

        ProsperityConfig loaded = ProsperityConfig.load(path);

        assertTrue(Files.exists(path), "first load must write defaults to disk");
        // The written file round-trips back to the same defaults.
        ProsperityConfig reread = ProsperityConfig.fromJson(Files.readString(path));
        assertEquals(loaded.distanceTiers, reread.distanceTiers);
        assertEquals(loaded.structureOverrides, reread.structureOverrides);
        assertEquals(loaded.indicatorRenderDistance, reread.indicatorRenderDistance);
    }

    @Test
    void corruptedFileLoadsDefaultsAndIsLeftUntouched(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        String garbage = "{ this is not valid json ";
        Files.writeString(path, garbage);

        ProsperityConfig c = ProsperityConfig.load(path);

        // Defaults returned, and the bad file is preserved (not overwritten) for the user.
        assertEquals(48, c.indicatorRenderDistance);
        assertEquals(garbage, Files.readString(path));
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        ProsperityConfig original = new ProsperityConfig();
        original.enableMobLootScaling = false;
        original.lootRefreshDays = 14;
        original.client.hudOffsetY = 20;
        original.save(path);

        ProsperityConfig loaded = ProsperityConfig.load(path);
        assertFalse(loaded.enableMobLootScaling);
        assertEquals(14, loaded.lootRefreshDays);
        assertEquals(20, loaded.client.hudOffsetY);
    }
}
