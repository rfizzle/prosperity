package com.rfizzle.prosperity.compat.opac;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.PartyGroupProvider;
import com.rfizzle.prosperity.api.ProsperityAPI;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;

/**
 * Open Parties and Claims (OPAC) soft-dependency integration: party loot mode (issue #53) follows the
 * player-managed OPAC parties players actually use, in place of admin-only ({@code /team}, permission
 * level 2) vanilla scoreboard teams. The first of the party-mod adapter siblings.
 *
 * <p>Registers a {@link PartyGroupProvider} that resolves a player's OPAC party via
 * {@code OpenPACServerAPI.get(server).getPartyManager().getPartyByMember(uuid)} and returns a key
 * derived from the party's persistent {@linkplain IServerPartyAPI#getId() id} through
 * {@link OpacPartyKeys#keyFor} — {@code opac:<partyId>}, keyed on the UUID (not the mutable display
 * name) so a party keeps one loot instance across a rename and a server restart. A player in no OPAC
 * party resolves to {@code null}, so Prosperity falls back through the #53 chain (scoreboard team, then
 * individual instancing). OPAC alliances are deliberately ignored: they are directional and do not
 * partition players, so they cannot form a consistent shared instance key — party membership only.
 *
 * <p>This class references {@code xaero.pac.common.server.*.api} and must only be class-loaded behind an
 * {@code isModLoaded("openpartiesandclaims")} guard (Concord API Standard v1). No OPAC-specific error
 * handling is needed: {@link ProsperityAPI#partyGroupOverride} already invokes every provider under
 * host-side {@code Throwable} isolation (a throw — including the {@code LinkageError} a mismatched OPAC
 * jar would surface — is logged once and treated as a deferral), so a failing OPAC read can never break
 * loot resolution. Because OPAC parties are free-join/leave, the #53 membership-snapshot and
 * {@code teamLeaveGraceMinutes} mitigations apply as-is through the generic key machinery — no
 * OPAC-specific anti-cycling logic here.
 */
public final class OpacPartyCompat {

    private OpacPartyCompat() {
    }

    /** Register the OPAC party group-key provider. Call once at initialization, behind the guard. */
    public static void register() {
        ProsperityAPI.registerPartyGroupProvider(OpacPartyCompat::groupKey);
        Prosperity.LOGGER.info("Open Parties and Claims detected: party loot mode will follow OPAC"
                + " parties in place of scoreboard teams when enabled");
    }

    /**
     * The party group key for {@code player}: {@code opac:<partyId>} of their OPAC party, or
     * {@code null} when the server is unavailable or the player is in no party (deferring to the
     * scoreboard-team default). Server-thread only, matching {@link PartyGroupProvider}'s contract.
     */
    @Nullable
    static String groupKey(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        IServerPartyAPI party =
                OpenPACServerAPI.get(server).getPartyManager().getPartyByMember(player.getUUID());
        return party == null ? null : OpacPartyKeys.keyFor(party.getId());
    }
}
