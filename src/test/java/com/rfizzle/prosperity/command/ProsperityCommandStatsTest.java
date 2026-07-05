package com.rfizzle.prosperity.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rfizzle.prosperity.config.DistanceTier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the stats readout's tier ordering: configured ladder order first, then
 * any recorded-but-unconfigured names alphabetically, so every recorded bucket is always shown
 * and the rows sum to the container total. The command plumbing and live recording are covered
 * by {@code LootStatsGameTest}.
 */
class ProsperityCommandStatsTest {

    private static final List<DistanceTier> LADDER = List.of(
            new DistanceTier("local", 0, 1.0, 0),
            new DistanceTier("frontier", 1000, 1.5, 1),
            new DistanceTier("wilderness", 3000, 2.0, 2));

    @Test
    void configuredTiersDisplayInLadderOrder() {
        assertEquals(List.of("local", "frontier", "wilderness"),
                ProsperityCommand.statsTierOrder(LADDER, Set.of("wilderness", "local", "frontier")),
                "recorded configured tiers follow the configured ladder, not alphabetical order");
    }

    @Test
    void unrecordedTiersAreOmitted() {
        assertEquals(List.of("frontier"),
                ProsperityCommand.statsTierOrder(LADDER, Set.of("frontier")),
                "tiers with no recorded count produce no row");
    }

    @Test
    void staleRecordedNamesAppendAlphabetically() {
        assertEquals(List.of("local", "frontier", "badlands", "outback"),
                ProsperityCommand.statsTierOrder(LADDER, Set.of("outback", "frontier", "badlands", "local")),
                "names recorded under an older config still display, after the current ladder");
    }

    @Test
    void nullConfigFallsBackToAlphabetical() {
        assertEquals(List.of("frontier", "local"),
                ProsperityCommand.statsTierOrder(null, Set.of("local", "frontier")),
                "with no configured tiers every recorded name displays alphabetically");
    }

    @Test
    void emptyRecordedYieldsNoRows() {
        assertEquals(List.of(), ProsperityCommand.statsTierOrder(LADDER, Set.of()),
                "a player with no stats gets no tier rows");
    }
}
