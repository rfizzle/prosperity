package com.rfizzle.prosperity.compat.ftbteams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The FTB Teams party group-key derivation. Pins the {@code ftbteams:<teamId>} contract that party loot
 * mode (issue #53) relies on: the key is derived from the team's persistent id (so it survives a rename
 * and a restart), is deterministic, distinguishes distinct teams, and — the FTB-specific rule — is
 * produced only for a party team, deferring ({@code null}) for a bare singleton player team or a
 * teamless player. FTB Teams is not on the test classpath — this exercises only the pure helper.
 */
class FtbTeamsPartyKeysTest {

    private static final UUID TEAM = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void partyKeyIsPrefixedTeamId() {
        assertEquals("ftbteams:" + TEAM, FtbTeamsPartyKeys.keyFor(TEAM, true));
    }

    @Test
    void keyIsDerivedFromIdNotName() {
        // Keying on the id, not a display name, is what lets a party survive a rename: the same id
        // always yields the same key regardless of what the party is called.
        assertEquals(FtbTeamsPartyKeys.keyFor(TEAM, true), FtbTeamsPartyKeys.keyFor(TEAM, true),
                "the same team id must always map to the same key");
        assertTrue(FtbTeamsPartyKeys.keyFor(TEAM, true).contains(TEAM.toString()),
                "the key must carry the team's id, so a rename cannot change it");
    }

    @Test
    void distinctPartiesGetDistinctKeys() {
        assertNotEquals(FtbTeamsPartyKeys.keyFor(TEAM, true),
                FtbTeamsPartyKeys.keyFor(UUID.fromString("99999999-8888-7777-6666-555555555555"), true));
    }

    @Test
    void singletonPlayerTeamDefersWithNull() {
        // FTB auto-creates a one-person player team for everyone; only party teams share. A non-party
        // team must defer so a solo player is never stored under a "team of one" key.
        assertNull(FtbTeamsPartyKeys.keyFor(TEAM, false),
                "a bare player team (not a party) must defer to the scoreboard-team default");
    }

    @Test
    void noTeamDefersWithNull() {
        assertNull(FtbTeamsPartyKeys.keyFor(null, true),
                "a player in no FTB team must defer to the scoreboard-team default");
        assertNull(FtbTeamsPartyKeys.keyFor(null, false),
                "a teamless player defers regardless of the party flag");
    }

    @Test
    void keyIsNamespacedAgainstTeamNames() {
        // The ftbteams: prefix keeps the key from colliding with a bare scoreboard-team name.
        assertTrue(FtbTeamsPartyKeys.keyFor(TEAM, true).startsWith("ftbteams:"));
    }
}
