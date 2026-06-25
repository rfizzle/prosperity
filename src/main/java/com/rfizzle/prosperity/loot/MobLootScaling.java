package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.config.DistanceTier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Mob loot scaling (S-018, SPEC §13): applies the distance-tier system to hostile-mob drops on player
 * kills, the entity parallel of the container scaling in {@link LootScaling}. The decision and the
 * loot-modifier fire live here so {@link com.rfizzle.prosperity.mixin.LivingEntityDropLootMixin} stays
 * pure plumbing &mdash; it reads the returned {@link Mods} to inject the final luck into the mob's
 * {@code LootParams} and to scale the rolled stack counts.
 *
 * <p>Scope mirrors SPEC §13: hostile mobs only ({@link MobCategory#MONSTER}, so modded hostiles and the
 * Wither are included automatically; the Ender Dragon uses a bespoke death-drop path that never reaches
 * {@code dropFromLootTable}, so it is excluded), player kills only (a {@link ServerPlayer} dealt the last
 * hit), and only when {@code enableMobLootScaling} is set. The tier is the ungated geographic
 * {@link LootScaling#resolveTier} &mdash; mob scaling is its own feature with its own toggle, independent
 * of {@code enableDistanceScaling} &mdash; so the Nether's raw coordinates and the End's max tier carry
 * over for free.
 *
 * <p>Firing {@link LootModifiers} keeps mob loot on the same {@link LootModifierContext} path as
 * containers: the death position is the context's {@code containerPos()} (reused as the loot-source
 * position), and the default vanilla-luck listener folds in the killer's {@code generic.luck} just as it
 * does for containers, so a co-installed mod's listener affects both alike.
 */
public final class MobLootScaling {

    private MobLootScaling() {
    }

    /**
     * The finalized modifiers for one mob drop: the post-listener luck folded into the mob's
     * {@code LootParams} and the stack multiplier applied to each rolled stack.
     */
    public record Mods(float luck, double stackMultiplier) {
    }

    /**
     * Resolve the scaling for a mob about to drop its loot, or {@code null} when the kill is not a
     * scalable one (passive mob, non-player or environmental kill, client level, or the feature is
     * disabled) &mdash; in which case the mixin leaves the drop byte-identical to vanilla and fires no
     * event. {@code killer} is the player credited with the last hit (the mixin's
     * {@code lastHurtByPlayer}, or {@code null} when the death was not a player hit).
     */
    @Nullable
    public static Mods resolve(LivingEntity mob, @Nullable Player killer) {
        if (!(killer instanceof ServerPlayer player)) {
            return null;
        }
        if (!Prosperity.getConfig().enableMobLootScaling) {
            return null;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        if (mob.getType().getCategory() != MobCategory.MONSTER) {
            return null;
        }
        DistanceTier tier = LootScaling.resolveTier(level, mob.getX(), mob.getZ());
        ResourceKey<LootTable> table = mob.getLootTable();
        LootModifierContext context =
                LootModifiers.fire(player, mob.blockPosition(), table.location(), tier);
        return new Mods(context.luck(), context.stackMultiplier());
    }
}
