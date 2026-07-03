package com.rfizzle.prosperity.compat.tribulation;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.api.ProsperityAPI;
import com.rfizzle.tribulation.api.TribulationAPI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tribulation soft-dependency integration: a dangerous world makes for richer loot. Registers a
 * {@link LootModifierCallback} listener that biases each loot-modifier generation &mdash; instanced
 * containers and scaled hostile-mob drops alike, matching distance scaling's own reach &mdash; by
 * the local Tribulation difficulty. The bonus stacks on top of Prosperity's distance-tier luck
 * already seeded into the context, so distance is never double-counted.
 *
 * <p>Also registers Tribulation's armor and weapon drop-chance providers so a scaled mob's equipped
 * tier gear becomes loot out where it's dangerous: the chance follows Prosperity's distance tier at
 * the mob's position (crossed with the mob's own Tribulation tier through
 * {@link DropChanceScaling}), so a high-tier brute in the Depths reliably yields its kit while the
 * same mob near spawn rarely does. The providers are the only lever Tribulation exposes over that
 * gear &mdash; without them it never drops. Last-writer-wins is Tribulation's slot contract; no
 * teardown is needed.
 *
 * <p>The read is place-based: {@code TribulationAPI.getEffectiveLevel} resolves the difficulty
 * around an entity (nearby players' levels folded per Tribulation's scaling mode, plus the
 * dimension offset), and the opening player &mdash; standing at the container &mdash; is that
 * entity, so "this area feels deadly &rarr; this chest is good" holds literally. The level maps to
 * a tier through Tribulation's config-driven thresholds and {@link TribulationLuck}'s capped curve.
 *
 * <p>This class references {@code com.rfizzle.tribulation.api} and must only be class-loaded behind
 * an {@code isModLoaded("tribulation")} guard (Concord API Standard v1). A Tribulation read that
 * throws is swallowed (logged once) so the integration can never break loot generation.
 */
public final class TribulationCompat {

    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean GEAR_FAILURE_LOGGED = new AtomicBoolean(false);

    private TribulationCompat() {
    }

    /**
     * Register the loot-bias listener and the tier-gear drop-chance providers. Call once at
     * initialization, behind the mod-loaded guard.
     */
    public static void register() {
        TribulationAPI.setArmorDropChanceProvider(
                (mob, tier, slot, stack, defaultChance) -> gearDropChance(mob, tier, defaultChance));
        TribulationAPI.setWeaponDropChanceProvider(
                (mob, tier, stack, defaultChance) -> gearDropChance(mob, tier, defaultChance));
        LootModifierCallback.EVENT.register(context -> {
            int tier;
            try {
                int level = TribulationAPI.getEffectiveLevel(context.player());
                tier = TribulationLuck.tierForLevel(level, TribulationAPI.getTierThresholds());
            } catch (Throwable e) {
                // Throwable, not Exception: an older Tribulation jar missing one of these API
                // methods surfaces as a LinkageError, which must not escape into generation.
                if (FAILURE_LOGGED.compareAndSet(false, true)) {
                    Prosperity.LOGGER.warn(
                            "Tribulation difficulty read failed; skipping loot bias for this and any"
                                    + " further failing generations", e);
                }
                return;
            }
            float bonus = TribulationLuck.luckForTier(tier);
            if (bonus > 0.0f) {
                context.addLuck(bonus);
            }
            float stackFactor = TribulationLuck.stackFactorForTier(tier);
            if (stackFactor != 1.0f) {
                context.multiplyStacks(stackFactor);
            }
        });
        Prosperity.LOGGER.info("Tribulation detected: local difficulty tier now biases instanced loot"
                + " and tier gear drops scale with distance");
    }

    /**
     * The drop chance for one piece of {@code mob}'s tier gear: Prosperity's distance tier at the
     * mob's position crossed with its Tribulation tier through {@link DropChanceScaling}. Falls back
     * to {@code defaultChance} off a server level or on any failure (logged once) &mdash; Tribulation
     * isolates a throwing provider too, but only against {@code Exception}, and this must never
     * break mob spawning.
     */
    private static float gearDropChance(Entity mob, int tier, float defaultChance) {
        try {
            if (!(mob.level() instanceof ServerLevel level)) {
                return defaultChance;
            }
            int distanceTier = ProsperityAPI.getDistanceTier(level, mob.blockPosition()).index();
            int maxDistanceTier = ProsperityAPI.getDistanceTiers().size() - 1;
            int[] thresholds = TribulationAPI.getTierThresholds();
            int maxMobTier = thresholds == null ? 0 : thresholds.length;
            return DropChanceScaling.compute(distanceTier, maxDistanceTier, tier, maxMobTier,
                    defaultChance);
        } catch (Throwable e) {
            if (GEAR_FAILURE_LOGGED.compareAndSet(false, true)) {
                Prosperity.LOGGER.warn("Tribulation gear drop-chance resolution failed; using the"
                        + " Tribulation default for this and any further failing resolutions", e);
            }
            return defaultChance;
        }
    }
}
