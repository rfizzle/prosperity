package com.rfizzle.prosperity.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the public distance-tier conversion: {@code index} assignment, the
 * {@code local} sentinel mapping, and the shape and immutability of {@link ProsperityAPI#getDistanceTiers}.
 * These exercise the real package-private conversion ({@link ProsperityAPI#toInfo},
 * {@link ProsperityAPI#distanceTiers}) against a hand-built config, so they never touch the server
 * singleton or a {@code ServerLevel}; the dimension rules are covered by {@code DistanceTierApiGameTest}.
 */
class DistanceTierInfoTest {

    private static final DistanceTier LOCAL = new DistanceTier("local", 0, 1.0, 0);
    private static final DistanceTier FRONTIER = new DistanceTier("frontier", 1000, 1.5, 1);
    private static final DistanceTier WILDERNESS = new DistanceTier("wilderness", 3000, 2.0, 2);
    private static final DistanceTier OUTLANDS = new DistanceTier("outlands", 6000, 2.75, 3);
    private static final DistanceTier DEPTHS = new DistanceTier("depths", 10000, 3.5, 4);

    private static ProsperityConfig configWith(List<DistanceTier> tiers) {
        ProsperityConfig cfg = new ProsperityConfig();
        cfg.distanceTiers = tiers;
        return cfg;
    }

    @Test
    void indexIsZeroBasedAndMonotonicWithDistance() {
        // Supplied out of order: distanceTiers() must sort ascending by minDistance before indexing.
        ProsperityConfig cfg = configWith(List.of(DEPTHS, LOCAL, OUTLANDS, FRONTIER, WILDERNESS));
        List<DistanceTierInfo> tiers = ProsperityAPI.distanceTiers(cfg);

        assertEquals(5, tiers.size(), "one info per configured tier");
        int prevDistance = Integer.MIN_VALUE;
        for (int i = 0; i < tiers.size(); i++) {
            DistanceTierInfo info = tiers.get(i);
            assertEquals(i, info.index(), "index is the 0-based ladder position");
            assertEquals(true, info.minDistance() > prevDistance, "minDistance strictly ascends");
            prevDistance = info.minDistance();
        }
        assertEquals("local", tiers.get(0).name(), "the nearest band sorts first");
        assertEquals("depths", tiers.get(4).name(), "the farthest band sorts last");
    }

    @Test
    void eachInfoCarriesItsTiersModifiers() {
        ProsperityConfig cfg = configWith(List.of(LOCAL, FRONTIER, WILDERNESS, OUTLANDS, DEPTHS));
        DistanceTierInfo frontier = ProsperityAPI.toInfo(FRONTIER, cfg);

        assertEquals("frontier", frontier.name());
        assertEquals(1, frontier.index(), "frontier is the second band");
        assertEquals(1000, frontier.minDistance());
        assertEquals(1.5, frontier.stackMultiplier());
        assertEquals(1, frontier.qualityModifier());
    }

    @Test
    void localSentinelMapsToIndexZeroWithNoScaling() {
        // A ladder that does not include a "local" tier: the sentinel still resolves to index 0.
        ProsperityConfig cfg = configWith(List.of(FRONTIER, WILDERNESS, DEPTHS));
        DistanceTierInfo sentinel = ProsperityAPI.toInfo(ProsperityConfig.LOCAL_SENTINEL, cfg);

        assertEquals("local", sentinel.name());
        assertEquals(0, sentinel.index(), "the off-ladder sentinel maps to index 0");
        assertEquals(0, sentinel.minDistance());
        assertEquals(1.0, sentinel.stackMultiplier(), "the sentinel carries no quantity scaling");
        assertEquals(0, sentinel.qualityModifier(), "the sentinel carries no quality bonus");
    }

    @Test
    void emptyLadderYieldsNoTiers() {
        assertEquals(List.of(), ProsperityAPI.distanceTiers(configWith(List.of())),
                "an empty tier list produces an empty ladder");
    }

    @Test
    void getDistanceTiersIsUnmodifiable() {
        List<DistanceTierInfo> tiers = ProsperityAPI.distanceTiers(configWith(List.of(LOCAL, FRONTIER)));
        assertThrows(UnsupportedOperationException.class,
                () -> tiers.add(new DistanceTierInfo("x", 9, 0, 1.0, 0)),
                "the returned ladder must be unmodifiable");
    }
}
