package com.rfizzle.prosperity.compat.ftbteams;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.PartyGroupProvider;
import com.rfizzle.prosperity.api.ProsperityAPI;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * FTB Teams soft-dependency integration: party loot mode (issue #53) follows the player-managed FTB
 * <em>party teams</em> that servers and modpacks actually use, in place of admin-only ({@code /team},
 * permission level 2) vanilla scoreboard teams. A sibling of the Open Parties and Claims adapter.
 *
 * <p>Registers a {@link PartyGroupProvider} that resolves a player's effective FTB team via
 * {@code FTBTeamsAPI.api().getManager().getTeamForPlayer(player)} and returns a key derived from the
 * team's persistent {@linkplain Team#getId() id} through {@link FtbTeamsPartyKeys#keyFor} —
 * {@code ftbteams:<teamId>}, keyed on the UUID (not the mutable display name) so a party keeps one loot
 * instance across a rename and a server restart. <strong>Only party teams share</strong>: FTB
 * auto-creates a singleton player team for everyone, so the provider yields a key only when the player
 * is in an actual {@linkplain Team#isPartyTeam() party team} and returns {@code null} for a bare player
 * team — otherwise every solo player would resolve to a "team of one", needlessly tripping party loot
 * mode's concurrency lock. A {@code null} return falls through cleanly to the #53 chain (scoreboard
 * team, then individual instancing).
 *
 * <p>This class references {@code dev.ftb.mods.ftbteams.api.*} and must only be class-loaded behind an
 * {@code isModLoaded("ftbteams")} guard (Concord API Standard v1). No FTB-specific error handling is
 * needed: {@link ProsperityAPI#partyGroupOverride} already invokes every provider under host-side
 * {@code Throwable} isolation (a throw — including the {@code LinkageError} a mismatched FTB jar would
 * surface — is logged once and treated as a deferral), so a failing FTB read can never break loot
 * resolution. Because FTB party teams are free-join/leave, the #53 membership-snapshot and
 * {@code teamLeaveGraceMinutes} mitigations apply as-is through the generic key machinery — no
 * FTB-specific anti-cycling logic here.
 */
public final class FtbTeamsPartyCompat {

    private FtbTeamsPartyCompat() {
    }

    /** Register the FTB Teams party group-key provider. Call once at initialization, behind the guard. */
    public static void register() {
        ProsperityAPI.registerPartyGroupProvider(FtbTeamsPartyCompat::groupKey);
        Prosperity.LOGGER.info("FTB Teams detected: party loot mode will follow FTB party teams in"
                + " place of scoreboard teams when enabled");
    }

    /**
     * The party group key for {@code player}: {@code ftbteams:<teamId>} of their FTB party team, or
     * {@code null} when the team manager is not yet loaded, the player has no team, or the player is in
     * only their singleton player team (deferring to the scoreboard-team default). Server-thread only,
     * matching {@link PartyGroupProvider}'s contract.
     */
    @Nullable
    static String groupKey(ServerPlayer player) {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) {
            return null;
        }
        Optional<Team> team = api.getManager().getTeamForPlayer(player);
        return team.map(t -> FtbTeamsPartyKeys.keyFor(t.getId(), t.isPartyTeam())).orElse(null);
    }
}
