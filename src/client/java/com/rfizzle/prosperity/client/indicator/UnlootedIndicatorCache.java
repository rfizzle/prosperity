package com.rfizzle.prosperity.client.indicator;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client cache of the unlooted-container positions the player has not yet generated, keyed by
 * chunk (SPEC §2). Populated from {@code UnlootedContainersS2C}, invalidated per-position on
 * {@code ContainerLootedS2C}/{@code ContainerRemovedS2C} and per-chunk on chunk unload.
 *
 * <p>Touched only on the client thread (network receivers hop through {@code client.execute(...)},
 * the renderer reads it on the render thread, which is the same thread), so plain collections are
 * safe — no synchronization. {@code requested} tracks chunks an in-flight request already covers so
 * a re-fired {@code CHUNK_LOAD} does not spam the server.</p>
 */
public final class UnlootedIndicatorCache {

    private static final Map<ChunkPos, Set<BlockPos>> BY_CHUNK = new HashMap<>();
    private static final Set<ChunkPos> REQUESTED = new HashSet<>();

    private UnlootedIndicatorCache() {
    }

    /** Mark a chunk as requested; returns {@code false} if a request is already outstanding for it. */
    public static boolean markRequested(ChunkPos chunkPos) {
        return REQUESTED.add(chunkPos);
    }

    /** Replace the cached positions for {@code chunkPos} with the server's latest answer. */
    public static void put(ChunkPos chunkPos, Set<BlockPos> positions) {
        if (positions.isEmpty()) {
            BY_CHUNK.remove(chunkPos);
        } else {
            BY_CHUNK.put(chunkPos, new HashSet<>(positions));
        }
    }

    /** Drop a chunk's indicators and its request mark (chunk unload). */
    public static void removeChunk(ChunkPos chunkPos) {
        BY_CHUNK.remove(chunkPos);
        REQUESTED.remove(chunkPos);
    }

    /** Drop a single indicator (container looted by this player, or broken for everyone). */
    public static void removePos(BlockPos pos) {
        Set<BlockPos> positions = BY_CHUNK.get(new ChunkPos(pos));
        if (positions != null && positions.remove(pos.immutable()) && positions.isEmpty()) {
            BY_CHUNK.remove(new ChunkPos(pos));
        }
    }

    /** Forget everything (disconnect / world change). */
    public static void clear() {
        BY_CHUNK.clear();
        REQUESTED.clear();
    }

    /** Read-only view of the cached chunks for the renderer. */
    public static Map<ChunkPos, Set<BlockPos>> view() {
        return Collections.unmodifiableMap(BY_CHUNK);
    }

    public static boolean isEmpty() {
        return BY_CHUNK.isEmpty();
    }
}
