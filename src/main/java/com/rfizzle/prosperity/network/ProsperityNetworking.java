package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.loot.UnlootedMinecarts;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

        PayloadTypeRegistry.playS2C().register(ConfigSyncS2CPayload.TYPE, ConfigSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlootedContainersS2CPayload.TYPE, UnlootedContainersS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerLootedS2CPayload.TYPE, ContainerLootedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerRemovedS2CPayload.TYPE, ContainerRemovedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlootedMinecartsS2CPayload.TYPE, UnlootedMinecartsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MinecartLootedS2CPayload.TYPE, MinecartLootedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MinecartRemovedS2CPayload.TYPE, MinecartRemovedS2CPayload.CODEC);
    }

    private static void registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(RequestUnlootedC2SPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!allowRequest(player.getUUID())) {
                return;
            }
            player.server.execute(() -> handleRequestUnlooted(player, payload));
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
            ServerPlayNetworking.send(player, new ConfigSyncS2CPayload(Prosperity.getConfig().toJson()));
        }
    }

    private static void registerDisconnectCleanup() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                UNLOOTED_REQUEST_WINDOW.remove(handler.getPlayer().getUUID()));
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
     * Tell every client tracking {@code pos} that the loot container there is gone, so each drops
     * its unlooted indicator. Used on container break (S-008) and on command reset/refresh (S-004).
     * The {@code canSend} guard skips clients that have not registered the receiver.
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

    /** Admit up to {@link #MAX_REQUESTS_PER_WINDOW} chunk requests per player per {@link #REQUEST_WINDOW_MS}. */
    private static boolean allowRequest(UUID id) {
        long now = System.currentTimeMillis();
        long[] window = UNLOOTED_REQUEST_WINDOW.computeIfAbsent(id, k -> new long[]{now, 0});
        if (now - window[0] >= REQUEST_WINDOW_MS) {
            window[0] = now;
            window[1] = 0;
        }
        if (window[1] >= MAX_REQUESTS_PER_WINDOW) {
            return false;
        }
        window[1]++;
        return true;
    }
}
