package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Distance-based loot scaling (S-011, SPEC §3). Resolves the geographic distance tier for a
 * position and applies the tier's quality (luck) and quantity (stack-multiplier) modifiers to a
 * generation. This is the single source of truth for tier resolution: {@code /prosperity info}
 * ({@link com.rfizzle.prosperity.command.ProsperityCommand}) and the generation path
 * ({@link InstancedLootGenerator}) both call {@link #resolveTier}, so the two never drift.
 *
 * <p>The pure scaling math ({@link #scaledCount}, {@link #effectiveLuck}) is MC-free and unit
 * tested; {@link #resolveTier} needs a {@link ServerLevel} for the dimension rules and is covered
 * by gametests.
 */
public final class LootScaling {

    private LootScaling() {
    }

    /**
     * Resolves the geographic distance tier for a position. Euclidean XZ distance from world origin
     * feeds {@link ProsperityConfig#tierFor(double)}; the Nether uses those raw coordinates
     * unchanged (no &times;8), and the End forces the highest-{@code minDistance} tier when
     * {@code endAlwaysMaxTier} is set. This is the pure geographic tier &mdash; it does not consult
     * {@code enableDistanceScaling}; the generation path gates application of the tier separately.
     */
    public static DistanceTier resolveTier(ServerLevel level, double x, double z) {
        ProsperityConfig cfg = Prosperity.getConfig();
        if (cfg.endAlwaysMaxTier && level.dimension() == Level.END) {
            return maxTier(cfg);
        }
        return cfg.tierFor(Math.sqrt(x * x + z * z));
    }

    /**
     * The tier to scale a generation by (S-011): the geographic {@link #resolveTier} when
     * {@code enableDistanceScaling} is set, otherwise {@link ProsperityConfig#LOCAL_SENTINEL} so the
     * generation produces vanilla quantities and quality. The generation path uses this; {@code /info}
     * uses the ungated {@link #resolveTier} so it always reports the player's geographic tier.
     */
    public static DistanceTier effectiveTier(ServerLevel level, double x, double z) {
        if (!Prosperity.getConfig().enableDistanceScaling) {
            return ProsperityConfig.LOCAL_SENTINEL;
        }
        return resolveTier(level, x, z);
    }

    /** The configured tier with the highest {@code minDistance}, or the sentinel if none exist. */
    public static DistanceTier maxTier(ProsperityConfig cfg) {
        DistanceTier best = null;
        if (cfg.distanceTiers != null) {
            for (DistanceTier tier : cfg.distanceTiers) {
                if (tier != null && (best == null || tier.minDistance() > best.minDistance())) {
                    best = tier;
                }
            }
        }
        return best != null ? best : ProsperityConfig.LOCAL_SENTINEL;
    }

    /**
     * The effective luck for a generation: the player's base luck plus the tier's quality modifier
     * (SPEC §3 "Quality Scaling"). The {@link DistanceTier#LOCAL_SENTINEL}'s {@code +0} makes this a
     * no-op at the baseline tier (and when scaling is disabled, the caller passes the sentinel).
     */
    public static float effectiveLuck(float baseLuck, DistanceTier tier) {
        return baseLuck + tier.qualityModifier();
    }

    /**
     * Stack-count scaling for one item (SPEC §3 "Quantity Scaling"): {@code floor(original *
     * multiplier)}, capped at the item's {@code maxStack}, and never reduced below {@code original}.
     * Non-stackable items ({@code maxStack <= 1}: tools, weapons, armor, enchanted books) are
     * returned unchanged.
     *
     * @param original   the rolled stack count
     * @param maxStack   the item's max stack size ({@code ItemStack#getMaxStackSize})
     * @param multiplier the tier's stack multiplier
     * @return the scaled count
     */
    public static int scaledCount(int original, int maxStack, double multiplier) {
        if (maxStack <= 1) {
            return original;
        }
        long scaled = (long) Math.floor(original * multiplier);
        int capped = (int) Math.min(scaled, maxStack);
        return Math.max(capped, original);
    }
}
