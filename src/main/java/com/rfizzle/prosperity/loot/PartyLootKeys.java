package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.ProsperityAPI;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the <em>loot key</em> a player's instanced-loot state is stored under for a given container,
 * for party loot mode (issue #53). Without party loot mode this is always the player's own UUID (the
 * historical behavior). With it enabled, players who resolve to the same group share one key — a stable
 * UUID derived from the group id ({@link #teamKey}) — so a single {@link InstancedLootData} slot backs
 * the whole team's inventory, generation, and refresh cooldown for that container.
 *
 * <p><b>Resolution precedence</b> ({@link #resolve}), first match wins:
 * <ol>
 *   <li><b>Off.</b> Party loot mode disabled &rarr; the player's own UUID.</li>
 *   <li><b>Existing team instance here.</b> If this container already records the player as a member of
 *       a team instance ({@linkplain InstancedLootData#teamKeyForMember snapshot}), that team key —
 *       even after they leave the team. <em>Resolution, not migration</em>: closes the "leave the team,
 *       re-open the same chest for a fresh roll" loop without moving any data.</li>
 *   <li><b>Existing individual instance here.</b> If the player already generated their own instance on
 *       this container ({@link InstancedLootData#hasGenerated} under their UUID), they keep it — joining
 *       a team never re-rolls a chest they already looted solo. The join-side counterpart of the
 *       snapshot rule; likewise resolution, not migration.</li>
 *   <li><b>Current group.</b> An {@linkplain ProsperityAPI#partyGroupOverride API-supplied} group key,
 *       else the player's vanilla scoreboard team, else — within the configured grace window — the group
 *       they just left ({@code teamLeaveGraceMinutes}, 0 = off), so leave/loot/rejoin cycling stays costly.</li>
 *   <li><b>Fallback.</b> No group &rarr; the player's own UUID (individual instance).</li>
 * </ol>
 *
 * <p>Server-thread only. {@link #resolve} is <b>side-effect-free</b> — the open path stamps the
 * leave-grace memory separately via {@link #stampGrace}, so read paths (indicator scan, tooltip, refresh
 * sweep) do not mutate it. The container context flows in as the (nullable) {@link InstancedLootData} so
 * the snapshot/individual checks are against the very container being resolved.
 */
public final class PartyLootKeys {

    private PartyLootKeys() {
    }

    /**
     * The loot key {@code player} resolves to for the container whose state is {@code data} (which may
     * be {@code null} before anyone has opened it). See the class Javadoc for the precedence. Never
     * {@code null}; equals {@code player.getUUID()} whenever the player is individual (mode off,
     * teamless, or outside any grace window). Side-effect-free.
     */
    public static UUID resolve(ServerPlayer player, @Nullable InstancedLootData data) {
        return resolve(player, data, currentGroupKey(player));
    }

    /**
     * {@link #resolve} using a {@code currentGroupKey} already computed for this player (the team key of
     * their live group, or {@code null}). Lets a loop that resolves many containers for one player —
     * the indicator scan and refresh sweep — query the group (and any API provider) once instead of
     * once per container. The per-container snapshot/individual checks still run against each
     * {@code data}. Side-effect-free.
     */
    public static UUID resolve(ServerPlayer player, @Nullable InstancedLootData data,
            @Nullable UUID currentGroupKey) {
        UUID self = player.getUUID();
        if (!Prosperity.getConfig().partyLootMode) {
            return self;
        }
        if (data != null) {
            // Already bound to a team instance on this container — resolution, not migration.
            UUID bound = data.teamKeyForMember(self);
            if (bound != null) {
                return bound;
            }
            // Already has an individual instance here — keep it, so teaming up never re-rolls a chest
            // the player already looted solo (the join-side counterpart of the snapshot rule).
            if (data.hasGenerated(self)) {
                return self;
            }
        }
        return currentGroupKey != null ? currentGroupKey : self;
    }

    /**
     * The team loot key of {@code player}'s current group — an API override, else their scoreboard team,
     * else the grace-window group they just left — or {@code null} when they belong to no group (or the
     * mode is off). Side-effect-free; call {@link #stampGrace} on the open path to keep the grace memory
     * fresh.
     */
    @Nullable
    public static UUID currentGroupKey(ServerPlayer player) {
        ProsperityConfig cfg = Prosperity.getConfig();
        if (!cfg.partyLootMode) {
            return null;
        }
        String group = currentGroup(player, cfg);
        return group != null ? teamKey(group) : null;
    }

    /**
     * The player's current group id: an API override, else their scoreboard team, else — within the
     * configured grace window — the group they just left. {@code null} when the player belongs to no
     * group. Side-effect-free (does not touch the grace memory).
     */
    @Nullable
    private static String currentGroup(ServerPlayer player, ProsperityConfig cfg) {
        String live = liveGroup(player);
        if (live != null) {
            return live;
        }
        // Teamless right now: the grace window keeps a recent leaver on their former group for new
        // instances, so cycling out and back is not a free re-roll.
        long now = player.serverLevel().getGameTime();
        return PartyGraceTracker.formerGroup(player.getUUID(), now,
                PartyGraceTracker.graceTicks(cfg.teamLeaveGraceMinutes));
    }

    /** The player's live group id (API override, else scoreboard team), or {@code null}. No grace. */
    @Nullable
    private static String liveGroup(ServerPlayer player) {
        String override = ProsperityAPI.partyGroupOverride(player);
        if (override != null && !override.isBlank()) {
            return override;
        }
        Team team = player.getTeam();
        return team != null ? team.getName() : null;
    }

    /**
     * Stamp the leave-grace memory with {@code player}'s live group, if any. Called from the open path
     * only (not from read-path resolution), so merely viewing a tooltip or scanning a chunk cannot
     * extend a player's grace window. No-op when the mode or the grace window is off.
     */
    public static void stampGrace(ServerPlayer player) {
        ProsperityConfig cfg = Prosperity.getConfig();
        if (!cfg.partyLootMode || cfg.teamLeaveGraceMinutes <= 0) {
            return;
        }
        String group = liveGroup(player);
        if (group != null) {
            PartyGraceTracker.remember(player.getUUID(), group, player.serverLevel().getGameTime());
        }
    }

    /**
     * The stable loot key for a group id: a deterministic name-based (type 3) UUID over the id under a
     * fixed namespace. Type-3 UUIDs never collide with the type-4 UUIDs the game assigns players, so a
     * team key can safely share the per-player {@link InstancedLootData} maps without aliasing a real
     * player. Deterministic across restarts, so a team resolves to the same instance after a reload.
     */
    public static UUID teamKey(String group) {
        return UUID.nameUUIDFromBytes(("prosperity:party:" + group).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Tell {@code player} their open was refused because a teammate is already looting this shared
     * instance (issue #53 v1 concurrency): an action-bar line and a short "no" cue at the container. No
     * screen opens; the player simply keeps their place until the teammate closes.
     */
    public static void refuseInUse(ServerPlayer player, ServerLevel level, Vec3 origin) {
        player.displayClientMessage(Component.translatable("party.prosperity.container_in_use"), true);
        ContainerFeedback.playSound(level, origin, SoundEvents.VILLAGER_NO);
    }
}
