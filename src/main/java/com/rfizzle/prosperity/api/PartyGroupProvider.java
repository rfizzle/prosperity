package com.rfizzle.prosperity.api;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies the party group key for a player in place of Prosperity's default vanilla scoreboard team,
 * for party loot mode (issue #53). Part of Prosperity's stable API surface (Concord API Standard v1).
 *
 * <p>When party loot mode is enabled ({@code partyLootMode}), players who resolve to the same non-null
 * group key share one loot instance per container. A social/party mod registers a provider through
 * {@link ProsperityAPI#registerPartyGroupProvider(PartyGroupProvider)} so its own parties drive the
 * grouping rather than scoreboard teams. Return {@code null} (or a blank string) to defer to the next
 * provider, and finally to Prosperity's scoreboard-team default — a provider that does not recognize a
 * player must return {@code null}, never a made-up key.
 *
 * <p>The key is opaque: any two players sharing a string share an instance. Keep it stable for a given
 * party (e.g. the party's own id), since a key that changes churns which instance a player resolves to.
 *
 * <p>Queried <strong>server-side only</strong>, on the server thread, once per container open (and per
 * indicator/tooltip resolution). Providers are consulted in registration order and the first non-null,
 * non-blank result wins. A provider that throws is caught and logged by Prosperity and treated as
 * returning {@code null}; it cannot break loot resolution.
 */
@Stable
@FunctionalInterface
public interface PartyGroupProvider {

    /**
     * The party group key for {@code player}, or {@code null} to defer to the next provider (and
     * ultimately to the scoreboard-team default). Must be deterministic for a stable party membership.
     *
     * @param player the player whose group is being resolved
     * @return an opaque group key shared by every member of the player's party, or {@code null}
     */
    @Nullable
    String groupKey(ServerPlayer player);
}
