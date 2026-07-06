package com.rfizzle.prosperity.compat.opac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The OPAC party group-key derivation. Pins the {@code opac:<partyId>} contract that party loot mode
 * (issue #53) relies on: the key is derived from the party's persistent id (so it survives a rename and
 * a restart), is deterministic, distinguishes distinct parties, and defers ({@code null}) for a player
 * in no party. OPAC is not on the test classpath — this exercises only the pure helper.
 */
class OpacPartyKeysTest {

    private static final UUID PARTY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void keyIsPrefixedPartyId() {
        assertEquals("opac:" + PARTY, OpacPartyKeys.keyFor(PARTY));
    }

    @Test
    void keyIsDerivedFromIdNotName() {
        // Keying on the id, not a display name, is what lets a party survive a rename: the same id
        // always yields the same key regardless of what the party is called.
        assertEquals(OpacPartyKeys.keyFor(PARTY), OpacPartyKeys.keyFor(PARTY),
                "the same party id must always map to the same key");
        assertTrue(OpacPartyKeys.keyFor(PARTY).contains(PARTY.toString()),
                "the key must carry the party's id, so a rename cannot change it");
    }

    @Test
    void distinctPartiesGetDistinctKeys() {
        assertNotEquals(OpacPartyKeys.keyFor(PARTY),
                OpacPartyKeys.keyFor(UUID.fromString("99999999-8888-7777-6666-555555555555")));
    }

    @Test
    void noPartyDefersWithNull() {
        assertNull(OpacPartyKeys.keyFor(null),
                "a player in no OPAC party must defer to the scoreboard-team default");
    }

    @Test
    void keyIsNamespacedAgainstTeamNames() {
        // The opac: prefix keeps the key from colliding with a bare scoreboard-team name.
        assertTrue(OpacPartyKeys.keyFor(PARTY).startsWith("opac:"));
    }
}
