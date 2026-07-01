package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Periodic server-side sweep that re-lights the unlooted indicator when a player's loot crosses its
 * refresh cooldown (S-016) while the container's chunk stays loaded. The indicator scan
 * ({@link UnlootedContainers}) already treats an expired instance as unlooted, so a chunk the client
 * requests <em>after</em> expiry lights up on its own; this sweep covers only the gap where a chunk was
 * already loaded when the cooldown elapsed. It does no per-tick work — it runs once every
 * {@link #SWEEP_INTERVAL_TICKS} ticks and resends a chunk to a player only when that player's loot
 * there transitioned to expired since the previous sweep, so a standing player triggers one resend per
 * container, not one per sweep.
 *
 * <p>Gated on both {@code enableLootRefresh} (nothing expires otherwise) and
 * {@code enableVisualIndicators} (nothing to re-light otherwise), so a server using neither pays
 * nothing. When active the cost per sweep is bounded by online players × their tracked chunk square ×
 * the containers therein — cheap map lookups, with a real packet sent only on an actual transition.
 */
public final class LootRefreshSweep {

    /** Cadence of the expiry sweep. Day-scale cooldowns make a half-minute granularity ample. */
    static final int SWEEP_INTERVAL_TICKS = 600;

    private static final Map<ResourceKey<Level>, Long> PREVIOUS_SWEEP_TICK = new HashMap<>();
    private static int tickCounter;

    private LootRefreshSweep() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(LootRefreshSweep::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PREVIOUS_SWEEP_TICK.clear();
            tickCounter = 0;
        });
    }

    private static void onServerTick(MinecraftServer server) {
        if (++tickCounter < SWEEP_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        ProsperityConfig config = Prosperity.getConfig();
        if (!config.enableLootRefresh || !config.enableVisualIndicators) {
            return;
        }
        long cooldown = LootRefresh.cooldownTicks(config.lootRefreshDays);
        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerLevel level : server.getAllLevels()) {
            sweepLevel(level, cooldown, viewDistance);
        }
    }

    private static void sweepLevel(ServerLevel level, long cooldown, int viewDistance) {
        long now = level.getGameTime();
        long previous = PREVIOUS_SWEEP_TICK.getOrDefault(level.dimension(), now);
        PREVIOUS_SWEEP_TICK.put(level.dimension(), now);
        if (previous >= now) {
            // First sweep for this level, or time did not advance: nothing can have just crossed. An
            // instance already expired at chunk-load time was lit by the client's own request.
            return;
        }
        for (ServerPlayer player : level.players()) {
            sweepPlayer(level, player, cooldown, viewDistance, previous, now);
        }
    }

    private static void sweepPlayer(ServerLevel level, ServerPlayer player, long cooldown,
            int viewDistance, long previous, long now) {
        UUID uuid = player.getUUID();
        ChunkPos origin = player.chunkPosition();
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = origin.x + dx;
                int cz = origin.z + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                if (chunkCrossedExpiry(chunk, uuid, cooldown, previous, now)) {
                    ProsperityNetworking.sendUnlootedChunk(player, level, new ChunkPos(cx, cz));
                }
            }
        }
    }

    /**
     * Whether any container in {@code chunk} holds loot for {@code player} whose cooldown elapsed in
     * {@code (previous, now]}. A player's {@code lastGeneratedTick} only exists once they have generated
     * here (it is set and cleared in lockstep with their inventory), so this is precise enough to gate
     * the resend; the resend itself runs the full per-player scan that filters blacklist/redirect cases.
     */
    private static boolean chunkCrossedExpiry(LevelChunk chunk, UUID player, long cooldown,
            long previous, long now) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof RandomizableContainerBlockEntity container)) {
                continue;
            }
            InstancedLootData data = ProsperityAttachments.get(container);
            if (data == null) {
                continue;
            }
            if (LootRefresh.crossedExpiry(data.getLastGeneratedTick(player), cooldown, previous, now)) {
                return true;
            }
        }
        return false;
    }
}
