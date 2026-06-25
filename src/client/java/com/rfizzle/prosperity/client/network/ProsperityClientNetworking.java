package com.rfizzle.prosperity.client.network;

import com.rfizzle.prosperity.client.indicator.UnlootedIndicatorCache;
import com.rfizzle.prosperity.client.indicator.UnlootedMinecartIndicatorCache;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.network.ConfigSyncS2CPayload;
import com.rfizzle.prosperity.network.ContainerLootedS2CPayload;
import com.rfizzle.prosperity.network.ContainerRemovedS2CPayload;
import com.rfizzle.prosperity.network.MinecartLootedS2CPayload;
import com.rfizzle.prosperity.network.MinecartRemovedS2CPayload;
import com.rfizzle.prosperity.network.ProtectionResultS2CPayload;
import com.rfizzle.prosperity.network.QueryProtectionC2SPayload;
import com.rfizzle.prosperity.network.RequestUnlootedC2SPayload;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload;
import com.rfizzle.prosperity.network.UnlootedMinecartsS2CPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Client half of the visual-indicator protocol (SPEC §2 / S-010): receive the server config and
 * per-chunk unlooted answers, request a chunk's data on load, and invalidate the cache on loot,
 * removal, chunk unload, and disconnect.
 */
public final class ProsperityClientNetworking {

    private ProsperityClientNetworking() {
    }

    public static void init() {
        registerReceivers();
        registerChunkRequests();
        registerCleanup();
    }

    private static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        ClientProsperityData.setServerConfig(ProsperityConfig.fromJson(payload.configJson()))));

        ClientPlayNetworking.registerGlobalReceiver(UnlootedContainersS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    ChunkPos chunkPos = payload.chunkPos();
                    Set<BlockPos> positions = new HashSet<>(payload.entries().size());
                    for (UnlootedContainersS2CPayload.Entry entry : payload.entries()) {
                        positions.add(entry.toBlockPos(chunkPos));
                    }
                    UnlootedIndicatorCache.put(chunkPos, positions);
                }));

        ClientPlayNetworking.registerGlobalReceiver(ContainerLootedS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> UnlootedIndicatorCache.removePos(payload.pos())));

        ClientPlayNetworking.registerGlobalReceiver(ContainerRemovedS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> UnlootedIndicatorCache.removePos(payload.pos())));

        ClientPlayNetworking.registerGlobalReceiver(UnlootedMinecartsS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> UnlootedMinecartIndicatorCache.addAll(payload.entityIds())));

        ClientPlayNetworking.registerGlobalReceiver(MinecartLootedS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> UnlootedMinecartIndicatorCache.remove(payload.entityId())));

        ClientPlayNetworking.registerGlobalReceiver(MinecartRemovedS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> UnlootedMinecartIndicatorCache.remove(payload.entityId())));

        ClientPlayNetworking.registerGlobalReceiver(ProtectionResultS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        ClientProtectionState.get().setResult(payload.pos(), payload.multiplier())));
    }

    private static void registerChunkRequests() {
        // Ask the server for the chunk's unlooted containers once per load. The server gates on
        // enableVisualIndicators (replying empty when off) and rate-limits per player, so an
        // unconditional request here is safe; markRequested suppresses duplicate in-flight asks.
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            ChunkPos pos = chunk.getPos();
            if (UnlootedIndicatorCache.markRequested(pos)
                    && ClientPlayNetworking.canSend(RequestUnlootedC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new RequestUnlootedC2SPayload(pos));
            }
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                UnlootedIndicatorCache.removeChunk(chunk.getPos()));

        // A cart that unloads (leaves view) drops out of the flat id set; its chunk request re-adds it
        // when the chunk reloads if it is still unlooted. Keeps the set bounded as carts come and go.
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof AbstractMinecartContainer) {
                UnlootedMinecartIndicatorCache.remove(entity.getId());
            }
        });

        // Ask the server whether the block we just started breaking is protected, so the cracking
        // animation can be slowed to match (S-017). Fires once per target acquisition; the server
        // rate-limits and replies with the multiplier the mixin then applies.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide && ClientPlayNetworking.canSend(QueryProtectionC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new QueryProtectionC2SPayload(pos));
            }
            return InteractionResult.PASS;
        });
    }

    private static void registerCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            UnlootedIndicatorCache.clear();
            UnlootedMinecartIndicatorCache.clear();
            ClientProtectionState.get().clear();
        });
    }
}
