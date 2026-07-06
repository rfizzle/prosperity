package com.rfizzle.prosperity.compat.opac;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The party-loot group-key derivation for the Open Parties and Claims (OPAC) adapter. Pure and free of
 * any OPAC reference so it can be unit-tested without OPAC on the classpath; {@link OpacPartyCompat}
 * holds the OPAC-touching glue.
 *
 * <p>The key is derived from the party's persistent {@link UUID} under a fixed {@code opac:} prefix —
 * never the party's display name, which players can change. Keying on the id keeps a party resolving to
 * the same loot instance across a rename and across a server restart (the id is stable; the name is
 * not), satisfying party loot mode's requirement (issue #53) that a group key stay stable for a party.
 * The {@code opac:} prefix namespaces the key so it cannot collide with a scoreboard-team name or with
 * another party mod's future adapter.
 */
public final class OpacPartyKeys {

    /** Prefix namespacing every OPAC party key against team names and other providers' keys. */
    static final String PREFIX = "opac:";

    private OpacPartyKeys() {
    }

    /**
     * The group key for the OPAC party with id {@code partyId}: {@code "opac:" + partyId}, or
     * {@code null} when {@code partyId} is {@code null} (the player is in no party) so the provider
     * defers to Prosperity's scoreboard-team default. Deterministic for a given id, so a party keeps
     * one key across renames and restarts.
     */
    @Nullable
    public static String keyFor(@Nullable UUID partyId) {
        return partyId == null ? null : PREFIX + partyId;
    }
}
