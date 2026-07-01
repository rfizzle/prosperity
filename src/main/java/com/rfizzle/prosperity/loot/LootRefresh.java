package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.util.UUID;

/**
 * Cooldown math for per-player loot refresh (S-016, SPEC §9). After a configurable number of in-game
 * days a player's instance of a container is considered expired; on their next interaction it is
 * cleared and rolled fresh, and the unlooted indicator reappears for them in the meantime.
 *
 * <p>Expiry is computed on demand &mdash; at the open path and at the indicator scan &mdash; from the
 * {@code lastGeneratedTick} the generate path always records (even while the feature is disabled, so a
 * later enable applies retroactively). There is no per-tick processing.
 */
public final class LootRefresh {

    /** Ticks in one in-game day; a refresh cooldown is {@code lootRefreshDays} of these. */
    public static final long TICKS_PER_DAY = 24_000L;

    private LootRefresh() {
    }

    /** The cooldown length in ticks for {@code refreshDays} in-game days. */
    public static long cooldownTicks(int refreshDays) {
        return (long) refreshDays * TICKS_PER_DAY;
    }

    /**
     * Pure cooldown predicate: whether loot generated at {@code lastGeneratedTick} has expired by
     * {@code currentTick}. Always {@code false} when refresh is disabled or the player never generated
     * ({@code lastGeneratedTick < 0}), so a never-visited or feature-off container is never treated as
     * refreshed.
     */
    public static boolean isExpired(long lastGeneratedTick, long currentTick, boolean enabled,
            int refreshDays) {
        if (!enabled || lastGeneratedTick < 0) {
            return false;
        }
        return currentTick - lastGeneratedTick >= cooldownTicks(refreshDays);
    }

    /**
     * Pure edge-trigger predicate for the expiry sweep (S-016): whether loot generated at
     * {@code lastGeneratedTick} crosses its {@code cooldown} inside the half-open window
     * {@code (previous, now]} &mdash; i.e. it was still live at {@code previous} but has expired by
     * {@code now}. Returns {@code false} for the never-generated sentinel ({@code lastGeneratedTick < 0})
     * and whenever time did not advance ({@code previous >= now}), so a first sweep or a stalled clock
     * fires no resend; an instance already expired at chunk-load time was lit by the client's own request.
     */
    public static boolean crossedExpiry(long lastGeneratedTick, long cooldown, long previous, long now) {
        if (lastGeneratedTick < 0) {
            return false;
        }
        long expiry = lastGeneratedTick + cooldown;
        return expiry > previous && expiry <= now;
    }

    /**
     * The roll salt for a (re)generation: the player's {@code refreshCount} when
     * {@code randomizeLootOnRefresh} is on, otherwise {@code 0} for a deterministic roll that
     * regenerates identically. Read after any cooldown clear has advanced the count, so successive
     * refreshes draw distinct loot while staying reproducible across a reload for a given count.
     */
    public static long refreshSalt(boolean randomizeOnRefresh, long refreshCount) {
        return randomizeOnRefresh ? refreshCount : 0L;
    }

    /**
     * Ticks remaining until loot generated at {@code lastGeneratedTick} expires, clamped at {@code 0}
     * (never negative). Does not consult the enabled toggle &mdash; callers gate on it separately; this
     * is pure countdown math for the tooltip refresh timer (§10).
     */
    public static long remainingTicks(long lastGeneratedTick, long currentTick, int refreshDays) {
        if (lastGeneratedTick < 0) {
            return 0L;
        }
        return Math.max(0L, cooldownTicks(refreshDays) - (currentTick - lastGeneratedTick));
    }

    /**
     * Whether {@code player}'s stored loot in {@code data} has passed its refresh cooldown as of
     * {@code currentTick}, reading the live config toggle and cooldown. The convenience overload of
     * {@link #isExpired(long, long, boolean, int)} used by the generate and scan paths.
     */
    public static boolean isExpired(InstancedLootData data, UUID player, long currentTick) {
        ProsperityConfig config = Prosperity.getConfig();
        return isExpired(data.getLastGeneratedTick(player), currentTick, config.enableLootRefresh,
                config.lootRefreshDays);
    }
}
