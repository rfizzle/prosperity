package com.rfizzle.prosperity.loot;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Transient guard that blocks two teammates from opening the same shared loot instance at once, for
 * party loot mode's v1 concurrency rule (issue #53). A shared team instance serves each opener a
 * private working copy of the stored inventory that is written back only on close, so simultaneous
 * opens would let the last close silently clobber the other's take. Rather than live-sync or
 * last-close-wins (a v2 concern), v1 refuses the second concurrent open with feedback.
 *
 * <p>A lock is identified by the (loot key, container id) pair, so the same team can still open
 * <em>different</em> containers at once and different teams never contend. Individual (per-UUID)
 * instances are never locked — one player cannot open one container twice — so callers only acquire
 * for a genuinely shared key.
 *
 * <p><b>Threading:</b> acquire/release run on the server thread (interaction handling and menu close),
 * so the backing {@link HashSet} needs no synchronization. Held locks are released when the menu closes
 * (screen close or disconnect, via the menu's {@code onClose} hook); {@link #clear()} on server stop is
 * the backstop against a leak from an open that never produced a close.
 */
public final class SharedInstanceLocks {

    private static final Set<String> OPEN = new HashSet<>();

    private SharedInstanceLocks() {
    }

    /**
     * Try to mark the shared instance ({@code lootKey}, {@code containerId}) as open. Returns
     * {@code true} and takes the lock when it was free, {@code false} when a teammate already holds it.
     */
    public static boolean tryAcquire(UUID lootKey, String containerId) {
        return OPEN.add(key(lootKey, containerId));
    }

    /** Release a lock taken by {@link #tryAcquire}. No-op if not held. */
    public static void release(UUID lootKey, String containerId) {
        OPEN.remove(key(lootKey, containerId));
    }

    /** Whether the shared instance is currently held open. Exposed for tests. */
    public static boolean isHeld(UUID lootKey, String containerId) {
        return OPEN.contains(key(lootKey, containerId));
    }

    /** Drop every held lock. Called on server stop so a fresh server starts clean. */
    public static void clear() {
        OPEN.clear();
    }

    private static String key(UUID lootKey, String containerId) {
        return lootKey + "@" + containerId;
    }
}
