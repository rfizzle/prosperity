package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.config.DistanceTier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.jetbrains.annotations.Nullable;

/**
 * Fishing loot scaling (SPEC §18): applies the distance-tier system to fishing catches, the third
 * vanilla loot source alongside containers ({@link LootScaling}) and mob drops ({@link MobLootScaling}).
 * The decision and the loot-modifier fire live here so
 * {@link com.rfizzle.prosperity.mixin.FishingHookRetrieveMixin} stays pure plumbing &mdash; it reads the
 * returned {@link Mods} to inject the final luck into the fishing roll's {@code LootParams} and to scale
 * the rolled stack counts.
 *
 * <p>The tier is resolved from the <em>bobber's</em> position (not the angler's) via the ungated
 * geographic {@link LootScaling#resolveTier} &mdash; fishing scaling is its own feature with its own
 * toggle, independent of {@code enableDistanceScaling} and {@code enableMobLootScaling} &mdash; so the
 * Nether's raw coordinates and the End's max tier carry over for free. Higher tiers add the quality
 * modifier to the roll's luck, which biases vanilla's quality-weighted fishing table toward the treasure
 * category; Luck of the Sea is untouched and stacks on top in the mixin.
 *
 * <p>Firing {@link LootModifiers} keeps fishing on the same {@link LootModifierContext} path as
 * containers and mobs: the bobber position is the context's {@code containerPos()} (reused as the
 * loot-source position), and the default vanilla-luck listener folds in the angler's {@code generic.luck}
 * just as it does for the other sources, so a co-installed mod's listener affects all three alike.
 */
public final class FishingLootScaling {

    private FishingLootScaling() {
    }

    /**
     * The finalized modifiers for one fishing catch: the post-listener luck (tier quality + vanilla
     * {@code generic.luck} + any API listener; Luck of the Sea is added back by the mixin) and the stack
     * multiplier applied to each rolled stack.
     */
    public record Mods(float luck, double stackMultiplier) {
    }

    /**
     * Resolve the scaling for a fishing catch about to be rolled, or {@code null} when the catch is not
     * a scalable one (no player owner, client level, or the feature is disabled) &mdash; in which case
     * the mixin leaves the roll byte-identical to vanilla and fires no event.
     */
    @Nullable
    public static Mods resolve(FishingHook hook, @Nullable Player owner) {
        if (!(owner instanceof ServerPlayer player)) {
            return null;
        }
        if (!Prosperity.getConfig().enableFishingLootScaling) {
            return null;
        }
        if (!(hook.level() instanceof ServerLevel level)) {
            return null;
        }
        DistanceTier tier = LootScaling.resolveTier(level, hook.getX(), hook.getZ());
        LootModifierContext context = LootModifiers.fire(player, hook.blockPosition(),
                BuiltInLootTables.FISHING.location(), tier);
        return new Mods(context.luck(), context.stackMultiplier());
    }
}
