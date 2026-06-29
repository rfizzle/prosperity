package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.ContainerProtection;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.loot.UnlootedMinecarts;
import com.rfizzle.prosperity.loot.index.LootIndexDataSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Typed transport layer for Prosperity: registers every {@link net.minecraft.network.protocol.common.custom.CustomPacketPayload}
 * type and the server-side receiver for the indicator chunk request. Feature logic
 * (the chunk scan, sync emission, config push) lands in the stories that consume these
 * payloads — this class only stands up the wire.
 */
public final class ProsperityNetworking {

    // RequestUnlootedC2S triggers a per-chunk block-entity scan. The client sends one request per
    // chunk it loads, so a single player legitimately bursts many requests at login or while moving;
    // gate with a per-player sliding window that admits those bursts but throttles a flood far above
    // any real chunk-load rate (anti-DoS on the server-thread scan). Each window holds
    // [windowStartMs, count] and is touched only on the player's single netty receiver thread.
    private static final Map<UUID, long[]> UNLOOTED_REQUEST_WINDOW = new ConcurrentHashMap<>();
    private static final long REQUEST_WINDOW_MS = 1000;
    private static final int MAX_REQUESTS_PER_WINDOW = 512;

    // QueryProtectionC2S is sent once per block the client starts breaking (S-017), so the rate is far
    // lower than the per-chunk indicator request; a tight window suffices to throttle a spammer.
    private static final Map<UUID, long[]> PROTECTION_QUERY_WINDOW = new ConcurrentHashMap<>();
    private static final int MAX_PROTECTION_QUERIES_PER_WINDOW = 64;

    private ProsperityNetworking() {
    }

    public static void register() {
        registerPayloadTypes();
        registerServerHandlers();
        registerJoinSync();
        registerDisconnectCleanup();
    }

    private static void registerPayloadTypes() {
        PayloadTypeRegistry.playC2S().register(RequestUnlootedC2SPayload.TYPE, RequestUnlootedC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(QueryProtectionC2SPayload.TYPE, QueryProtectionC2SPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(ConfigSyncS2CPayload.TYPE, ConfigSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProtectionResultS2CPayload.TYPE, ProtectionResultS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlootedContainersS2CPayload.TYPE, UnlootedContainersS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerLootedS2CPayload.TYPE, ContainerLootedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerRemovedS2CPayload.TYPE, ContainerRemovedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlootedMinecartsS2CPayload.TYPE, UnlootedMinecartsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MinecartLootedS2CPayload.TYPE, MinecartLootedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MinecartRemovedS2CPayload.TYPE, MinecartRemovedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LootIndexS2CPayload.TYPE, LootIndexS2CPayload.CODEC);
    }

    /**
     * Re-broadcast the loot index to every connected player after a {@code /reload} rebuilds it (S-047).
     * Registered from {@link com.rfizzle.prosperity.Prosperity#onInitialize()} <b>after</b>
     * {@link LootIndexDataSource#init()} so this {@code END_DATA_PACK_RELOAD} listener runs after the
     * index's own rebuild listener — Fabric fires lifecycle listeners in registration order, so the
     * broadcast always sends the freshly-rebuilt snapshot, not the previous one.
     */
    public static void registerLootIndexReloadSync() {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> syncLootIndexToAll(server));
    }

    private static void registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(RequestUnlootedC2SPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!allowRequest(UNLOOTED_REQUEST_WINDOW, MAX_REQUESTS_PER_WINDOW, player.getUUID())) {
                return;
            }
            player.server.execute(() -> handleRequestUnlooted(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(QueryProtectionC2SPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!allowRequest(PROTECTION_QUERY_WINDOW, MAX_PROTECTION_QUERIES_PER_WINDOW, player.getUUID())) {
                return;
            }
            player.server.execute(() -> handleProtectionQuery(player, payload));
        });
    }

    private static void registerJoinSync() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendJoinSync(handler.getPlayer()));
    }

    /**
     * Push the server config to a player's client (S-010). The client's indicator renderer reads
     * the synced {@code enableVisualIndicators}/{@code indicatorRenderDistance}/{@code indicatorXrayDistance}
     * to gate and size overlays, so it must land before the client requests per-chunk data. Public so
     * gametests can drive the emission without the real {@code JOIN} event, and so {@code /prosperity reload}
     * can resync without a reconnect.
     */
    public static void sendJoinSync(ServerPlayer player) {
        if (player.connection == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, ConfigSyncS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new ConfigSyncS2CPayload(Prosperity.getConfig().toSyncJson()));
        }
        // Then the loot index (S-047), so a remote client's recipe viewers can browse instanced loot
        // they have no datapack for. The client receiver ignores it on an integrated server, where the
        // viewers already read the full in-JVM snapshot. Config-before-data: the index's tier chips
        // resolve against the synced distanceTiers above.
        if (ServerPlayNetworking.canSend(player, LootIndexS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, LootIndexS2CPayload.of(LootIndexDataSource.snapshot()));
        }
    }

    /**
     * Push the current server config to every connected player, returning the number of clients that
     * received it. Used by {@code /prosperity reload} (S-004) and the Mod Menu config screen's save
     * runnable (S-028) so an in-game config change reaches clients without a reconnect. The
     * {@code canSend} guard skips clients (e.g. vanilla) that have not registered the receiver.
     */
    public static int syncConfigToAll(MinecraftServer server) {
        ConfigSyncS2CPayload payload = new ConfigSyncS2CPayload(Prosperity.getConfig().toSyncJson());
        int sent = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ConfigSyncS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
                sent++;
            }
        }
        return sent;
    }

    /**
     * Push the current loot index to every connected player, returning the number of clients that
     * received it (S-047). Used by {@link #registerLootIndexReloadSync()} after a {@code /reload}
     * rebuild so remote clients mirror the server's current index without a reconnect. The payload is
     * built once and reused; the {@code canSend} guard skips clients that have not registered the
     * receiver (e.g. vanilla).
     */
    public static int syncLootIndexToAll(MinecraftServer server) {
        LootIndexS2CPayload payload = LootIndexS2CPayload.of(LootIndexDataSource.snapshot());
        int sent = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, LootIndexS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
                sent++;
            }
        }
        return sent;
    }

    private static void registerDisconnectCleanup() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            UNLOOTED_REQUEST_WINDOW.remove(id);
            PROTECTION_QUERY_WINDOW.remove(id);
        });
    }

    private static void handleRequestUnlooted(ServerPlayer player, RequestUnlootedC2SPayload payload) {
        if (player.connection == null || !Prosperity.getConfig().enableVisualIndicators) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (ServerPlayNetworking.canSend(player, UnlootedContainersS2CPayload.TYPE)) {
            List<UnlootedContainersS2CPayload.Entry> entries =
                    UnlootedContainers.scanChunk(level, payload.chunkPos(), player.getUUID());
            // Reply even when empty so the client can clear any stale indicators it cached for the chunk.
            ServerPlayNetworking.send(player, new UnlootedContainersS2CPayload(payload.chunkPos(), entries));
        }
        if (ServerPlayNetworking.canSend(player, UnlootedMinecartsS2CPayload.TYPE)) {
            List<Integer> minecarts = UnlootedMinecarts.scanChunk(level, payload.chunkPos(), player.getUUID());
            ServerPlayNetworking.send(player, new UnlootedMinecartsS2CPayload(minecarts));
        }
    }

    private static void handleProtectionQuery(ServerPlayer player, QueryProtectionC2SPayload payload) {
        if (player.connection == null) {
            return;
        }
        float multiplier =
                ContainerProtection.protectionMultiplierFor(player.serverLevel(), payload.pos(), player);
        sendProtectionResult(player, payload.pos(), multiplier);
    }

    /**
     * Answer a player's break-protection query for {@code pos} with the {@code multiplier} their client
     * should slow the cracking animation by ({@code 1.0} = unprotected). The {@code canSend} guard skips
     * clients (e.g. vanilla) that have not registered the receiver.
     */
    public static void sendProtectionResult(ServerPlayer player, BlockPos pos, float multiplier) {
        if (ServerPlayNetworking.canSend(player, ProtectionResultS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new ProtectionResultS2CPayload(pos, multiplier));
        }
    }

    /**
     * Tell a single player that they have just generated loot from the container at {@code pos}, so
     * their client drops its unlooted indicator there. The {@code canSend} guard skips the send for a
     * client that has not registered the receiver (e.g. a vanilla client).
     */
    public static void sendContainerLooted(ServerPlayer player, BlockPos pos) {
        if (ServerPlayNetworking.canSend(player, ContainerLootedS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new ContainerLootedS2CPayload(pos));
        }
    }

    /**
     * Send {@code player} the current unlooted-container set for {@code chunkPos} as a full-chunk
     * replace ({@link UnlootedContainersS2CPayload}) — the same answer they would get from a fresh
     * {@code RequestUnlootedC2S}. Used to re-light an indicator that should reappear without a chunk
     * reload: a command reset/refresh (S-004) leaves the container unlooted again, and the refresh
     * cooldown sweep (S-016) re-lights an instance that expired while its chunk stayed loaded. The
     * scan never force-loads, so an unloaded chunk yields an empty (harmless) replace.
     */
    public static void sendUnlootedChunk(ServerPlayer player, ServerLevel level, ChunkPos chunkPos) {
        if (player.connection == null || !ServerPlayNetworking.canSend(player, UnlootedContainersS2CPayload.TYPE)) {
            return;
        }
        List<UnlootedContainersS2CPayload.Entry> entries =
                UnlootedContainers.scanChunk(level, chunkPos, player.getUUID());
        ServerPlayNetworking.send(player, new UnlootedContainersS2CPayload(chunkPos, entries));
    }

    /**
     * Resend the unlooted-container set for {@code chunkPos} to the players tracking it after a command
     * reset/refresh (S-004), so the indicator for a container that is unlooted again is re-added rather
     * than dropped. When {@code only} is non-null the resend is limited to those players (a per-player
     * reset); {@code null} sends to every tracking player. The scan is per-player, so each receives
     * their own current set. No-op when indicators are disabled.
     */
    public static void resendUnlootedChunk(ServerLevel level, ChunkPos chunkPos, @Nullable Collection<UUID> only) {
        if (!Prosperity.getConfig().enableVisualIndicators) {
            return;
        }
        for (ServerPlayer player : PlayerLookup.tracking(level, chunkPos)) {
            if (only != null && !only.contains(player.getUUID())) {
                continue;
            }
            sendUnlootedChunk(player, level, chunkPos);
        }
    }

    /**
     * Tell every client tracking {@code pos} that the loot container there is gone, so each drops
     * its unlooted indicator. Used on container break (S-008). The {@code canSend} guard skips
     * clients that have not registered the receiver.
     */
    public static void sendContainerRemoved(ServerLevel level, BlockPos pos) {
        ContainerRemovedS2CPayload payload = new ContainerRemovedS2CPayload(pos);
        for (ServerPlayer player : PlayerLookup.tracking(level, pos)) {
            if (ServerPlayNetworking.canSend(player, ContainerRemovedS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /**
     * Tell a single player that they have just generated loot from the container minecart with network
     * id {@code entityId}, so their client drops its unlooted indicator there (S-038). The minecart
     * parallel of {@link #sendContainerLooted}.
     */
    public static void sendMinecartLooted(ServerPlayer player, int entityId) {
        if (ServerPlayNetworking.canSend(player, MinecartLootedS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, new MinecartLootedS2CPayload(entityId));
        }
    }

    /**
     * Tell every client tracking {@code cart} that the container minecart is gone, so each drops its
     * unlooted indicator. Used on cart destruction/death (S-038); the block-removal mixin (S-008) does
     * not cover entities. The minecart parallel of {@link #sendContainerRemoved}.
     */
    public static void sendMinecartRemoved(ServerLevel level, AbstractMinecartContainer cart) {
        MinecartRemovedS2CPayload payload = new MinecartRemovedS2CPayload(cart.getId());
        for (ServerPlayer player : PlayerLookup.tracking(cart)) {
            if (ServerPlayNetworking.canSend(player, MinecartRemovedS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /** Admit up to {@code maxPerWindow} requests per player per {@link #REQUEST_WINDOW_MS} in {@code windows}. */
    private static boolean allowRequest(Map<UUID, long[]> windows, int maxPerWindow, UUID id) {
        long now = System.currentTimeMillis();
        long[] window = windows.computeIfAbsent(id, k -> new long[]{now, 0});
        if (now - window[0] >= REQUEST_WINDOW_MS) {
            window[0] = now;
            window[1] = 0;
        }
        if (window[1] >= maxPerWindow) {
            return false;
        }
        window[1]++;
        return true;
    }
}
