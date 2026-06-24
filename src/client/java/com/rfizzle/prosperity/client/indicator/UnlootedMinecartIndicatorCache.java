package com.rfizzle.prosperity.client.indicator;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Client cache of the container-minecart network ids the player has not yet generated (S-038). The
 * entity parallel of {@link UnlootedIndicatorCache} &mdash; a flat id set rather than a chunk map,
 * because carts move and so are not stably owned by a chunk.
 *
 * <p>Populated by {@code UnlootedMinecartsS2C} (which adds, never replaces, since each chunk request
 * only reports the carts currently in that chunk); pruned per-id by {@code MinecartLootedS2C},
 * {@code MinecartRemovedS2C}, and client-side entity unload. The set is self-correcting: a cart that
 * unloads is dropped and re-added when its chunk reloads if still unlooted, and the renderer skips any
 * id whose entity it can no longer resolve.</p>
 *
 * <p>Touched only on the client thread (receivers hop through {@code client.execute(...)}; the
 * renderer reads it on the render thread, which is the same thread), so a plain set is safe.</p>
 */
public final class UnlootedMinecartIndicatorCache {

    private static final Set<Integer> IDS = new HashSet<>();

    private UnlootedMinecartIndicatorCache() {
    }

    /** Add the unlooted cart ids the server reported for a just-requested chunk. */
    public static void addAll(Iterable<Integer> entityIds) {
        for (int id : entityIds) {
            IDS.add(id);
        }
    }

    /** Drop a single cart's indicator (looted by this player, destroyed for everyone, or unloaded). */
    public static void remove(int entityId) {
        IDS.remove(entityId);
    }

    /** Forget everything (disconnect / world change). */
    public static void clear() {
        IDS.clear();
    }

    /** Read-only view of the cached cart ids for the renderer. */
    public static Set<Integer> view() {
        return Collections.unmodifiableSet(IDS);
    }

    public static boolean isEmpty() {
        return IDS.isEmpty();
    }
}
