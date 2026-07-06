package com.rfizzle.prosperity.loot.eviction;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.ContainerAdapter;
import com.rfizzle.prosperity.loot.LootRefresh;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Config-gated eviction of residual per-player instanced-loot entries for long-absent players
 * (issue #43). Issue #33 bounded the heavy stored inventories by evicting them once looted clean,
 * but deliberately left the lightweight {@code lastGeneratedTick} (the "has visited" marker) and
 * {@code refreshCount} (the re-roll salt) growing one small entry per distinct visitor forever.
 * When {@code evictAbsentPlayerData} is enabled, a container drops <em>all three</em> entries of any
 * stored player who has not been online for {@code absentPlayerEvictionDays} in-game days, the next
 * time the container is touched.
 *
 * <p><b>Trade-off:</b> an evicted player who does return regenerates from scratch — uncollected
 * loot is forfeit and their refresh count restarts at {@code 0} — the same loot-loss trade a
 * cooldown refresh already makes, applied only to players gone far longer than any cooldown.
 *
 * <p><b>Trigger:</b> pruning runs opportunistically inside the generation choke points
 * ({@code InstancedLootInteraction.generateAndStore} and {@code generateAndStoreDouble}), so it
 * covers block containers and minecarts alike and never scans the world; a container nobody touches
 * is never pruned, which is fine — untouched data has no carrying cost beyond what it had before.
 * Loot refresh cannot subsume this: a refresh clear fires only on the returning player's own open,
 * so it structurally never reaches the absent-player population this targets, and it retains the
 * salt besides.
 *
 * <p>An online player is always treated as present, whatever the ledger says, so the toggling
 * player can never evict themselves mid-session.
 */
public final class AbsentPlayerEviction {

    private AbsentPlayerEviction() {
    }

    /** Keep the {@link PlayerLastSeenState} ledger stamped on every player join and disconnect. */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PlayerLastSeenState.get(server).touch(handler.getPlayer().getUUID(),
                        server.overworld().getGameTime()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PlayerLastSeenState.get(server).touch(handler.getPlayer().getUUID(),
                        server.overworld().getGameTime()));
    }

    /**
     * Pure threshold predicate: whether a player last seen at {@code lastSeenTick} has been gone at
     * least {@code thresholdDays} in-game days as of {@code now}. Reuses the refresh cooldown's
     * {@link LootRefresh#cooldownTicks day-to-ticks conversion}, so the two features share one
     * definition of a day.
     */
    public static boolean isAbsent(long lastSeenTick, long now, int thresholdDays) {
        return now - lastSeenTick >= LootRefresh.cooldownTicks(thresholdDays);
    }

    /**
     * Drop every entry of every over-threshold absent player from {@code adapter}'s attachment.
     * No-ops (and never dirties the source) when the feature is disabled, the source has no
     * attachment, or nobody qualifies.
     */
    public static void prune(ContainerAdapter adapter) {
        ProsperityConfig config = Prosperity.getConfig();
        if (!config.evictAbsentPlayerData) {
            return;
        }
        InstancedLootData data = adapter.data();
        if (data == null) {
            return;
        }
        MinecraftServer server = adapter.level().getServer();
        Set<UUID> evict = evictablePlayers(data, server, adapter.level().getGameTime(),
                config.absentPlayerEvictionDays);
        if (evict.isEmpty()) {
            return;
        }
        adapter.update(d -> evict.forEach(d::evictPlayer));
    }

    /**
     * The players in {@code data} whose last-seen (per the server's {@link PlayerLastSeenState})
     * exceeds {@code thresholdDays}, excluding everyone currently online. Read-only, so callers can
     * skip the attachment write (and its dirtying) entirely when nobody qualifies.
     */
    static Set<UUID> evictablePlayers(InstancedLootData data, MinecraftServer server, long now,
            int thresholdDays) {
        // Candidates are the per-player entry holders plus every team-snapshot member (party loot mode,
        // issue #53). A pure team member's loot state lives under the synthetic team key, so they never
        // appear in trackedPlayerIds; unioning the snapshot members in lets eviction drop a long-departed
        // member from the snapshot too — otherwise it would grow one UUID per historical member forever.
        Set<UUID> candidates = new HashSet<>(data.trackedPlayerIds());
        candidates.addAll(data.allTeamMembers());
        if (candidates.isEmpty()) {
            return Set.of();
        }
        PlayerLastSeenState ledger = PlayerLastSeenState.get(server);
        Set<UUID> evict = new HashSet<>();
        for (UUID player : candidates) {
            // A party loot mode team key is synthetic — no player list entry and no last-seen stamp — so
            // it would always read as absent and the shared instance would be wrongly evicted. Skip team
            // keys; a shared instance is reclaimed only once all its members are individually evicted.
            if (data.isTeamKey(player)) {
                continue;
            }
            if (server.getPlayerList().getPlayer(player) != null) {
                continue;
            }
            if (isAbsent(ledger.lastSeen(player), now, thresholdDays)) {
                evict.add(player);
            }
        }
        return evict;
    }
}
