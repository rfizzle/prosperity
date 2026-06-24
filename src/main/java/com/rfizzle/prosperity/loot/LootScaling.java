package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.config.StructureOverride;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Distance-based loot scaling (S-011, SPEC §3). Resolves the geographic distance tier for a
 * position and applies the tier's quality (luck) and quantity (stack-multiplier) modifiers to a
 * generation. This is the single source of truth for tier resolution: {@code /prosperity info}
 * ({@link com.rfizzle.prosperity.command.ProsperityCommand}) and the generation path
 * ({@link InstancedLootGenerator}) both call {@link #resolveTier}, so the two never drift.
 *
 * <p>Structure-specific overrides (S-012, SPEC §6) refine the distance tier per structure:
 * {@link #resolveForGeneration} resolves the distance tier, detects the structure the container sits
 * in via {@link #resolveStructure}, and applies the override with {@link #applyStructureOverride}. The
 * override math is MC-free and unit tested; structure detection needs a {@link ServerLevel} and is
 * covered by a gametest.
 *
 * <p>The pure scaling math ({@link #scaledCount}, {@link #applyStructureOverride})
 * is MC-free and unit tested; {@link #resolveTier} and {@link #resolveStructure} need a
 * {@link ServerLevel} for the dimension and structure rules and are covered by gametests.
 */
public final class LootScaling {

    private LootScaling() {
    }

    /**
     * The tier and structure resolved for one generation: the effective {@link DistanceTier} after the
     * distance band and any structure override, plus the structure id the container sits in (cached on
     * the attachment for notifications and tooltips), or {@code null} when outside any structure or
     * scaling is disabled.
     */
    public record ScaledTier(DistanceTier tier, @Nullable ResourceLocation structure) {
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

    /**
     * The full tier to scale a generation by (S-011 + S-012): the {@link #effectiveTier distance tier}
     * refined by any structure override (SPEC §6), paired with the structure the container sits in.
     * Structure overrides are part of distance scaling, so when {@code enableDistanceScaling} is off
     * this returns the {@link ProsperityConfig#LOCAL_SENTINEL} with no structure and skips detection
     * entirely; detection is likewise skipped when no overrides are configured (nothing to apply).
     */
    public static ScaledTier resolveForGeneration(ServerLevel level, Vec3 origin) {
        ProsperityConfig cfg = Prosperity.getConfig();
        if (!cfg.enableDistanceScaling) {
            return new ScaledTier(ProsperityConfig.LOCAL_SENTINEL, null);
        }
        DistanceTier base = resolveTier(level, origin.x, origin.z);
        if (cfg.structureOverrides == null || cfg.structureOverrides.isEmpty()) {
            return new ScaledTier(base, null);
        }
        ResourceLocation structure = resolveStructure(level, BlockPos.containing(origin));
        DistanceTier effective =
                applyStructureOverride(base, structure == null ? null : structure.toString(), cfg);
        return new ScaledTier(effective, structure);
    }

    /**
     * Apply the structure override (if any) for {@code structureId} on top of {@code base} (SPEC §6).
     * {@code fixed} replaces the tier, {@code minimum} raises it to at least the override tier, and
     * {@code maximum} caps it at the override tier; tiers are ordered by {@link DistanceTier#minDistance}.
     * Returns {@code base} unchanged when the container is in no structure, no override matches the
     * structure, the override names a tier the config does not define, or the mode is unrecognized
     * &mdash; so a malformed or modded-but-unconfigured entry degrades gracefully to pure distance
     * scaling. Pure (no MC state) and unit tested.
     */
    public static DistanceTier applyStructureOverride(DistanceTier base, @Nullable String structureId,
            ProsperityConfig cfg) {
        if (structureId == null || cfg.structureOverrides == null) {
            return base;
        }
        StructureOverride override = null;
        for (StructureOverride candidate : cfg.structureOverrides) {
            if (candidate != null && structureId.equals(candidate.structure())) {
                override = candidate;
                break;
            }
        }
        if (override == null) {
            return base;
        }
        DistanceTier target = tierByName(cfg, override.tier());
        if (target == null) {
            return base;
        }
        return switch (override.mode() == null ? "" : override.mode()) {
            case "fixed" -> target;
            case "minimum" -> target.minDistance() > base.minDistance() ? target : base;
            case "maximum" -> target.minDistance() < base.minDistance() ? target : base;
            default -> base;
        };
    }

    /**
     * The configured tier named {@code name}, the {@link ProsperityConfig#LOCAL_SENTINEL} when the
     * config has no {@code "local"} tier of its own, or {@code null} when the name resolves to nothing.
     * The configured list wins over the sentinel so a custom {@code "local"} tier is honored.
     */
    @Nullable
    private static DistanceTier tierByName(ProsperityConfig cfg, @Nullable String name) {
        if (name == null) {
            return null;
        }
        if (cfg.distanceTiers != null) {
            for (DistanceTier tier : cfg.distanceTiers) {
                if (tier != null && name.equals(tier.name())) {
                    return tier;
                }
            }
        }
        return name.equals(ProsperityConfig.LOCAL_SENTINEL.name()) ? ProsperityConfig.LOCAL_SENTINEL : null;
    }

    /**
     * The structure the container at {@code pos} sits in, or {@code null} if it is in none (SPEC §6).
     * Walks the structures referencing the position, confirms each actually has a piece at {@code pos},
     * and returns the most specific one &mdash; the smallest bounding-box volume, ties broken
     * deterministically by structure id. Needs the {@link ServerLevel}'s {@link StructureManager}, so
     * this is covered by a gametest rather than a unit test.
     */
    @Nullable
    public static ResourceLocation resolveStructure(ServerLevel level, BlockPos pos) {
        StructureManager manager = level.structureManager();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResolvedStructure best = null;
        for (Structure structure : manager.getAllStructuresAt(pos).keySet()) {
            StructureStart start = manager.getStructureWithPieceAt(pos, structure);
            if (!start.isValid()) {
                continue;
            }
            ResourceLocation id = registry.getKey(structure);
            if (id == null) {
                continue;
            }
            BoundingBox box = start.getBoundingBox();
            long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
            if (best == null || volume < best.volume()
                    || (volume == best.volume() && id.compareTo(best.id()) < 0)) {
                best = new ResolvedStructure(id, volume);
            }
        }
        return best == null ? null : best.id();
    }

    private record ResolvedStructure(ResourceLocation id, long volume) {
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
