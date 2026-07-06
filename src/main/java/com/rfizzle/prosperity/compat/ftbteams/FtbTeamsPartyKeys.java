package com.rfizzle.prosperity.compat.ftbteams;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The party-loot group-key derivation for the FTB Teams adapter. Pure and free of any FTB reference so
 * it can be unit-tested without FTB Teams on the classpath; {@link FtbTeamsPartyCompat} holds the
 * FTB-touching glue.
 *
 * <p>The key is derived from the team's persistent {@link UUID} under a fixed {@code ftbteams:} prefix —
 * never the party's display name, which players can change. Keying on the id keeps a party resolving to
 * the same loot instance across a rename and across a server restart (the id is stable; the name is
 * not), satisfying party loot mode's requirement (issue #53) that a group key stay stable for a party.
 * The {@code ftbteams:} prefix namespaces the key so it cannot collide with a scoreboard-team name or
 * with another party mod's adapter.
 *
 * <p><strong>Only party teams share.</strong> FTB Teams auto-creates a one-person <em>player team</em>
 * for everyone and only promotes to a <em>party team</em> when players group up. The key is produced
 * only for a party team; a bare singleton player team defers ({@code null}) so a solo player is never
 * stored under a "team of one" key — which would needlessly trip party loot mode's concurrency lock —
 * and instead falls through to the #53 chain (scoreboard team, then individual instancing).
 */
public final class FtbTeamsPartyKeys {

    /** Prefix namespacing every FTB Teams party key against team names and other providers' keys. */
    static final String PREFIX = "ftbteams:";

    private FtbTeamsPartyKeys() {
    }

    /**
     * The group key for the FTB team with id {@code teamId}: {@code "ftbteams:" + teamId} when it is a
     * party team, or {@code null} when the player is in no team ({@code teamId == null}) or only their
     * singleton player team ({@code partyTeam == false}) — so the provider defers to Prosperity's
     * scoreboard-team default. Deterministic for a given id, so a party keeps one key across renames and
     * restarts.
     *
     * @param teamId    the team's persistent id, or {@code null} if the player has no team
     * @param partyTeam whether that team is an FTB <em>party</em> team (not a singleton player team)
     */
    @Nullable
    public static String keyFor(@Nullable UUID teamId, boolean partyTeam) {
        return (teamId == null || !partyTeam) ? null : PREFIX + teamId;
    }
}
