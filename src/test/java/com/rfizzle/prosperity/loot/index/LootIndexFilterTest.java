package com.rfizzle.prosperity.loot.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rfizzle.prosperity.config.DistanceTier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tier-1 (plain JUnit, no Minecraft bootstrap) coverage of the loot-index tier filter logic (S-042):
 * the "available at" reachability that decides which distance tiers can reach a given row. Operates on
 * {@code Optional<String>} min tiers and synthetic {@link DistanceTier} lists, so it needs no registry.
 */
class LootIndexFilterTest {

    private static final List<DistanceTier> TIERS = List.of(
            new DistanceTier("local", 0, 1.0, 0),
            new DistanceTier("frontier", 1000, 1.5, 1),
            new DistanceTier("wilderness", 3000, 2.0, 2),
            new DistanceTier("outlands", 6000, 2.75, 3),
            new DistanceTier("depths", 10000, 3.5, 4));

    @Test
    void vanillaRowReachableFromEveryTier() {
        assertEquals(List.of("local", "frontier", "wilderness", "outlands", "depths"),
                LootIndexFilter.reachableTierNames(Optional.empty(), TIERS));
    }

    @Test
    void gatedRowReachableFromItsTierAndEveryDeeperOne() {
        assertEquals(List.of("wilderness", "outlands", "depths"),
                LootIndexFilter.reachableTierNames(Optional.of("wilderness"), TIERS));
    }

    @Test
    void firstGatedTierReachableFromAllButLocal() {
        assertEquals(List.of("frontier", "wilderness", "outlands", "depths"),
                LootIndexFilter.reachableTierNames(Optional.of("frontier"), TIERS));
    }

    @Test
    void deepestGatedRowReachableOnlyFromDeepest() {
        assertEquals(List.of("depths"), LootIndexFilter.reachableTierNames(Optional.of("depths"), TIERS));
    }

    @Test
    void unknownTierIsPermissiveAcrossAllTiers() {
        assertEquals(List.of("local", "frontier", "wilderness", "outlands", "depths"),
                LootIndexFilter.reachableTierNames(Optional.of("mythic"), TIERS));
    }

    @Test
    void emptyTierListYieldsNoReachableTiers() {
        assertEquals(List.of(), LootIndexFilter.reachableTierNames(Optional.of("wilderness"), List.of()));
        assertEquals(List.of(), LootIndexFilter.reachableTierNames(Optional.empty(), List.of()));
    }

    @Test
    void indexOfResolvesTierPositionOrMinusOne() {
        assertEquals(0, LootIndexFilter.indexOf("local", TIERS));
        assertEquals(2, LootIndexFilter.indexOf("wilderness", TIERS));
        assertEquals(4, LootIndexFilter.indexOf("depths", TIERS));
        assertEquals(-1, LootIndexFilter.indexOf("mythic", TIERS));
    }
}
