package com.rfizzle.prosperity.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

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

    // RequestUnlootedC2S triggers a per-chunk block-entity scan (wired in S-009). Gate it
    // per player so a spammed client cannot queue repeated scans on the server thread.
    private static final Map<UUID, Long> LAST_UNLOOTED_REQUEST_MS = new ConcurrentHashMap<>();
    private static final long REQUEST_UNLOOTED_COOLDOWN_MS = 2000;

    private ProsperityNetworking() {
    }

    public static void register() {
        registerPayloadTypes();
        registerServerHandlers();
        registerDisconnectCleanup();
    }

    private static void registerPayloadTypes() {
        PayloadTypeRegistry.playC2S().register(RequestUnlootedC2SPayload.TYPE, RequestUnlootedC2SPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(ConfigSyncS2CPayload.TYPE, ConfigSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlootedContainersS2CPayload.TYPE, UnlootedContainersS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerLootedS2CPayload.TYPE, ContainerLootedS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ContainerRemovedS2CPayload.TYPE, ContainerRemovedS2CPayload.CODEC);
    }

    private static void registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(RequestUnlootedC2SPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!checkCooldown(LAST_UNLOOTED_REQUEST_MS, player.getUUID(), REQUEST_UNLOOTED_COOLDOWN_MS)) {
                return;
            }
            player.server.execute(() -> handleRequestUnlooted(player, payload));
        });
    }

    private static void registerDisconnectCleanup() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LAST_UNLOOTED_REQUEST_MS.remove(handler.getPlayer().getUUID()));
    }

    private static void handleRequestUnlooted(ServerPlayer player, RequestUnlootedC2SPayload payload) {
        if (player.connection == null) {
            return;
        }
        // TODO(S-009): scan the chunk's block entities for instanced containers the player has
        // not generated and reply with UnlootedContainersS2CPayload. Transport only for now.
    }

    private static boolean checkCooldown(Map<UUID, Long> map, UUID id, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = map.get(id);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        map.put(id, now);
        return true;
    }
}
