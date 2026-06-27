package com.rfizzle.prosperity.api;

/**
 * An immutable, server-authoritative snapshot of one distance-scaling tier (Concord API Standard v1):
 * the stable, public handle a consumer reads instead of touching Prosperity's internal tier record.
 * The reward-axis counterpart to a difficulty tier — it answers "which loot band does this position
 * occupy" without the consumer hardcoding distance thresholds or tier names.
 *
 * <p>{@link #index()} is the comparison key: {@code 0} for the nearest band and monotonically
 * increasing with distance, so consumers can rank tiers without knowing their names. The
 * {@code local} sentinel returned off the tier ladder carries {@code index 0}, {@code minDistance 0},
 * {@code stackMultiplier 1.0}, and {@code qualityModifier 0}.
 *
 * <p>This describes the <em>geographic</em> tier only — structure overrides (SPEC §6) are not folded
 * in. Carries no Minecraft types so it is safe to reference from a soft-dependency consumer compiled
 * with {@code modCompileOnly}.
 *
 * @param name            lowercase tier id (e.g. {@code local}, {@code frontier}, {@code wilderness},
 *                        {@code outlands}, {@code depths})
 * @param index           0-based position in the configured ladder, monotonic with distance
 * @param minDistance     inclusive lower bound, in blocks from origin, of this tier's distance band
 * @param stackMultiplier the multiplier the loot pipeline applies to each stackable item's count
 * @param qualityModifier the luck the loot pipeline adds before loot resolution
 */
@Stable
public record DistanceTierInfo(String name, int index, int minDistance, double stackMultiplier,
        int qualityModifier) {
}
