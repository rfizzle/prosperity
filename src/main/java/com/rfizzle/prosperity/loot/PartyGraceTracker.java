package com.rfizzle.prosperity.loot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Transient, in-memory record of the group each player was last seen in, for party loot mode's
 * optional leave-grace window (issue #53, {@code teamLeaveGraceMinutes}). While a player resolves to a
 * group (scoreboard team or API-supplied), {@link #remember} stamps that group with the current game
 * time; once they leave, {@link #formerGroup} keeps returning it for a configurable window so a fresh
 * container opened inside the window still generates against the former team's key — making
 * leave/loot/rejoin cycling more hassle than it is worth.
 *
 * <p><b>Deliberately not persisted.</b> The grace window is a soft anti-cycling deterrent, not
 * enforcement (the design accepts that airtight prevention is impossible); a window that does not
 * survive a server restart is an acceptable and documented gap. The membership <em>snapshot</em> that
 * closes the leave-and-re-loot loop for existing instances rides the persistent attachment instead —
 * only this last-group memory is ephemeral. Cleared on server stop.
 *
 * <p><b>Threading.</b> The map is a {@link ConcurrentHashMap} because indicator-scan resolution can run
 * off the main thread's exact ordering relative to opens; each entry is a small immutable snapshot, so
 * a lost update at most shortens one player's grace by one resolution — harmless for a deterrent.
 */
public final class PartyGraceTracker {

    /** One player's last-seen group and the game time it was last confirmed. */
    private record Memory(String group, long tick) {
    }

    private static final Map<UUID, Memory> LAST_GROUP = new ConcurrentHashMap<>();

    private PartyGraceTracker() {
    }

    /** Record that {@code player} was resolved into {@code group} at game time {@code now}. */
    public static void remember(UUID player, String group, long now) {
        LAST_GROUP.put(player, new Memory(group, now));
    }

    /**
     * The group {@code player} recently left, if they were last seen in one within {@code graceTicks}
     * of {@code now}; otherwise {@code null}. Returns {@code null} when the window is disabled
     * ({@code graceTicks <= 0}) or the memory has aged out — an aged-out entry is dropped so the map
     * does not retain long-departed players. A negative elapsed span (clock rewound by a reload) is
     * treated as still inside the window.
     */
    @Nullable
    public static String formerGroup(UUID player, long now, long graceTicks) {
        if (graceTicks <= 0L) {
            return null;
        }
        Memory memory = LAST_GROUP.get(player);
        if (memory == null) {
            return null;
        }
        long elapsed = now - memory.tick();
        if (elapsed >= 0L && elapsed >= graceTicks) {
            LAST_GROUP.remove(player, memory);
            return null;
        }
        return memory.group();
    }

    /** Ticks in {@code minutes} real minutes ({@code 20} ticks/second); {@code 0} for a disabled window. */
    public static long graceTicks(int minutes) {
        return minutes <= 0 ? 0L : (long) minutes * 60L * 20L;
    }

    /** Forget every remembered group. Called on server stop so a fresh server starts clean. */
    public static void clear() {
        LAST_GROUP.clear();
    }
}
