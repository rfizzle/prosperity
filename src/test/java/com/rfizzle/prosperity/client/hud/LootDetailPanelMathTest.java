package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.client.hud.LootDetailPanelMath.NearbyEntry;
import com.rfizzle.prosperity.client.hud.LootDetailPanelMath.NearbyGroup;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the loot detail panel math (S-035): tier-ladder ordering and current-tier
 * selection, progress-to-next arithmetic, nearby-container grouping, and page cross-fade timing.
 */
class LootDetailPanelMathTest {

    private static ProsperityConfig defaultConfig() {
        // The no-arg constructor seeds the SPEC §3 default tiers without touching FabricLoader.
        return new ProsperityConfig();
    }

    @Test
    void sortedLadderOrdersByMinDistanceAscending() {
        ProsperityConfig cfg = defaultConfig();
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(cfg);
        assertEquals(List.of("local", "frontier", "wilderness", "outlands", "depths"),
                ladder.stream().map(DistanceTier::name).toList());
    }

    @Test
    void sortedLadderReordersAnUnsortedList() {
        ProsperityConfig cfg = defaultConfig();
        cfg.distanceTiers = new ArrayList<>(List.of(
                new DistanceTier("far", 5000, 3.0, 3),
                new DistanceTier("near", 100, 1.0, 0),
                new DistanceTier("mid", 1000, 2.0, 1)));
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(cfg);
        assertEquals(List.of("near", "mid", "far"), ladder.stream().map(DistanceTier::name).toList());
    }

    @Test
    void sortedLadderCollapsesEmptyToSentinel() {
        ProsperityConfig cfg = defaultConfig();
        cfg.distanceTiers = new ArrayList<>();
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(cfg);
        assertEquals(1, ladder.size());
        assertEquals(ProsperityConfig.LOCAL_SENTINEL.name(), ladder.get(0).name());
    }

    @Test
    void currentIndexMatchesByName() {
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(defaultConfig());
        assertEquals(2, LootDetailPanelMath.currentIndex(ladder, ladder.get(2)));
        // A copy with the same name resolves to the same row (name-matched, not reference-matched).
        assertEquals(1, LootDetailPanelMath.currentIndex(ladder,
                new DistanceTier("frontier", 1000, 1.5, 1)));
        assertEquals(-1, LootDetailPanelMath.currentIndex(ladder,
                new DistanceTier("nowhere", 0, 1.0, 0)));
    }

    @Test
    void isMaxTierOnlyAtTopOfLadder() {
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(defaultConfig());
        assertTrue(LootDetailPanelMath.isMaxTier(ladder, ladder.size() - 1));
        assertFalse(LootDetailPanelMath.isMaxTier(ladder, 0));
        assertFalse(LootDetailPanelMath.isMaxTier(ladder, -1));
    }

    @Test
    void distanceToNextReturnsBoundaryGapOrMinusOneAtMax() {
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(defaultConfig());
        // In "frontier" (index 1, [1000, 3000)) at 1200 blocks: 1800 remain to "wilderness".
        assertEquals(1800.0, LootDetailPanelMath.distanceToNext(ladder, 1, 1200), 1e-9);
        // Past the next boundary (stale resolve) clamps to 0, never negative.
        assertEquals(0.0, LootDetailPanelMath.distanceToNext(ladder, 1, 5000), 1e-9);
        // At the top tier there is no next boundary.
        assertEquals(-1.0, LootDetailPanelMath.distanceToNext(ladder, ladder.size() - 1, 99999), 1e-9);
    }

    @Test
    void progressToNextIsFractionOfTheBand() {
        List<DistanceTier> ladder = LootDetailPanelMath.sortedLadder(defaultConfig());
        // "frontier" band is [1000, 3000); halfway (2000) is 0.5.
        assertEquals(0.5f, LootDetailPanelMath.progressToNext(ladder, 1, 2000), 1e-6);
        assertEquals(0f, LootDetailPanelMath.progressToNext(ladder, 1, 1000), 1e-6);
        assertEquals(1f, LootDetailPanelMath.progressToNext(ladder, 1, 3000), 1e-6);
        // Max tier always reads full.
        assertEquals(1f, LootDetailPanelMath.progressToNext(ladder, ladder.size() - 1, 50000), 1e-6);
    }

    @Test
    void progressToNextHandlesDuplicateThresholds() {
        List<DistanceTier> ladder = List.of(
                new DistanceTier("a", 1000, 1.0, 0),
                new DistanceTier("b", 1000, 2.0, 1));
        // Zero-width band cannot divide — reads full rather than NaN/infinity.
        assertEquals(1f, LootDetailPanelMath.progressToNext(ladder, 0, 1000), 1e-6);
    }

    @Test
    void groupNearbyCountsAndOrders() {
        List<NearbyEntry> entries = List.of(
                new NearbyEntry("minecraft:chest", "wilderness"),
                new NearbyEntry("minecraft:barrel", "frontier"),
                new NearbyEntry("minecraft:chest", "wilderness"),
                new NearbyEntry("minecraft:chest", "frontier"));
        List<NearbyGroup> groups = LootDetailPanelMath.groupNearby(entries);
        assertEquals(3, groups.size());
        // Busiest (chest/wilderness ×2) leads.
        assertEquals(new NearbyGroup("minecraft:chest", "wilderness", 2), groups.get(0));
        // Ties on count (both ×1) order by type key then tier name.
        assertEquals(new NearbyGroup("minecraft:barrel", "frontier", 1), groups.get(1));
        assertEquals(new NearbyGroup("minecraft:chest", "frontier", 1), groups.get(2));
    }

    @Test
    void groupNearbySkipsNullAndToleratesEmpty() {
        assertTrue(LootDetailPanelMath.groupNearby(null).isEmpty());
        List<NearbyEntry> entries = new ArrayList<>();
        entries.add(null);
        entries.add(new NearbyEntry(null, "frontier"));
        entries.add(new NearbyEntry("minecraft:chest", null));
        entries.add(new NearbyEntry("minecraft:chest", "local"));
        List<NearbyGroup> groups = LootDetailPanelMath.groupNearby(entries);
        assertEquals(List.of(new NearbyGroup("minecraft:chest", "local", 1)), groups);
    }

    @Test
    void pageCountIsCeilingAndNeverBelowOne() {
        assertEquals(1, LootDetailPanelMath.pageCount(0, 8));
        assertEquals(1, LootDetailPanelMath.pageCount(8, 8));
        assertEquals(2, LootDetailPanelMath.pageCount(9, 8));
        assertEquals(3, LootDetailPanelMath.pageCount(17, 8));
        // A non-positive page size is treated as one row per page.
        assertEquals(5, LootDetailPanelMath.pageCount(5, 0));
    }

    @Test
    void paginateChunksWithoutLosingItems() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);
        List<List<Integer>> pages = LootDetailPanelMath.paginate(items, 2);
        assertEquals(3, pages.size());
        assertEquals(List.of(1, 2), pages.get(0));
        assertEquals(List.of(3, 4), pages.get(1));
        assertEquals(List.of(5), pages.get(2));
        // An empty list is still one (empty) page.
        assertEquals(1, LootDetailPanelMath.paginate(List.of(), 2).size());
    }

    @Test
    void pageIndexCyclesAndWrapsAround() {
        assertEquals(0, LootDetailPanelMath.pageIndex(0, 3, 1000));
        assertEquals(1, LootDetailPanelMath.pageIndex(1500, 3, 1000));
        assertEquals(2, LootDetailPanelMath.pageIndex(2500, 3, 1000));
        // Wraps back to the first page after the last.
        assertEquals(0, LootDetailPanelMath.pageIndex(3500, 3, 1000));
        // A single page never advances.
        assertEquals(0, LootDetailPanelMath.pageIndex(9999, 1, 1000));
    }

    @Test
    void pageAlphaFadesInAndOut() {
        // Fade in over the first FADE window.
        assertEquals(0f, LootDetailPanelMath.pageAlpha(0, 2600, 350), 1e-6);
        assertEquals(0.5f, LootDetailPanelMath.pageAlpha(175, 2600, 350), 1e-6);
        // Full brightness in the middle.
        assertEquals(1f, LootDetailPanelMath.pageAlpha(1300, 2600, 350), 1e-6);
        // Fade out over the last FADE window.
        assertEquals(0f, LootDetailPanelMath.pageAlpha(2600, 2600, 350), 1e-6);
        // A zero fade is always opaque.
        assertEquals(1f, LootDetailPanelMath.pageAlpha(0, 2600, 0), 1e-6);
    }

    @Test
    void bearing8ResolvesAllEightSectorCenters() {
        // Minecraft axes: north is -Z, east is +X.
        assertEquals("n", LootDetailPanelMath.bearing8(0, -10));
        assertEquals("ne", LootDetailPanelMath.bearing8(10, -10));
        assertEquals("e", LootDetailPanelMath.bearing8(10, 0));
        assertEquals("se", LootDetailPanelMath.bearing8(10, 10));
        assertEquals("s", LootDetailPanelMath.bearing8(0, 10));
        assertEquals("sw", LootDetailPanelMath.bearing8(-10, 10));
        assertEquals("w", LootDetailPanelMath.bearing8(-10, 0));
        assertEquals("nw", LootDetailPanelMath.bearing8(-10, -10));
    }

    @Test
    void bearing8SectorBoundariesFallClockwise() {
        // Exactly 22.5 degrees east of north sits on the N/NE boundary; the sector floor puts it in NE.
        double t = Math.tan(Math.toRadians(22.5));
        assertEquals("ne", LootDetailPanelMath.bearing8(t * 10, -10));
        // Just inside north's sector on either side stays N.
        assertEquals("n", LootDetailPanelMath.bearing8(t * 10 - 0.01, -10));
        assertEquals("n", LootDetailPanelMath.bearing8(-t * 10 + 0.01, -10));
        // The NW/N boundary (337.5 degrees) rolls forward into N.
        assertEquals("n", LootDetailPanelMath.bearing8(-t * 10, -10));
    }

    @Test
    void bearing8ZeroOffsetIsWellDefined() {
        assertEquals("n", LootDetailPanelMath.bearing8(0, 0));
    }
}
