package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.config.ProsperityConfig;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Trial chamber reward scaling (SPEC §16): applies the distance-tier system to vault loot (normal
 * and ominous) and trial spawner ejected rewards &mdash; scaling only, no instancing, since vanilla
 * vaults already gate rewards per player. The decision and the loot-modifier fire live here so the
 * two mixins ({@code VaultBlockEntityServerMixin}, {@code TrialSpawnerMixin}) stay pure plumbing:
 * they read the returned {@link Mods} to inject the final luck into the roll's {@code LootParams}
 * and to scale the rolled stack counts.
 *
 * <p>Both rolls run through the standard tier pipeline: {@link LootScaling#resolveForGeneration}
 * resolves the distance tier refined by any structure override (the shipped default raises
 * {@code minecraft:trial_chambers} to at least the wilderness tier), and {@link LootModifiers} fires
 * {@link com.rfizzle.prosperity.api.LootModifierCallback} so API listeners compose exactly as they
 * do for containers and mob drops. Gated on {@code enableTrialChamberScaling} <em>and</em>
 * {@code enableDistanceScaling} (this is an extension of distance scaling, not an independent loot
 * source like mob scaling) &mdash; with either off, the rolls stay byte-identical to vanilla and no
 * event fires. Vanilla per-player vault gating (key consumption, rewarded-players set) is untouched.
 */
public final class TrialChamberScaling {

    private TrialChamberScaling() {
    }

    /**
     * The finalized modifiers for one vault or spawner reward roll: the resolved tier (for the vault
     * open notification), the post-listener luck folded into the roll's {@code LootParams}, and the
     * stack multiplier applied to each rolled stack.
     */
    public record Mods(LootScaling.ScaledTier scaled, float luck, double stackMultiplier) {
    }

    /**
     * Resolve the scaling for a reward roll at {@code pos} for {@code player}, or {@code null} when
     * the roll is not a scalable one (no {@link ServerPlayer}, or either toggle is off) &mdash; in
     * which case the mixin leaves the roll byte-identical to vanilla and fires no event.
     */
    @Nullable
    public static Mods resolve(ServerLevel level, BlockPos pos, @Nullable Player player,
            ResourceLocation lootTable) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        ProsperityConfig cfg = Prosperity.getConfig();
        if (!cfg.enableTrialChamberScaling || !cfg.enableDistanceScaling) {
            return null;
        }
        LootScaling.ScaledTier scaled = LootScaling.resolveForGeneration(level, Vec3.atCenterOf(pos));
        LootModifierContext context = LootModifiers.fire(serverPlayer, pos, lootTable, scaled.tier());
        return new Mods(scaled, context.luck(), context.stackMultiplier());
    }

    /**
     * The player a trial spawner reward ejection is credited to: the head of the spawner's detected
     * set, the same element vanilla removes right after ejecting (the set is not mutated in between,
     * so the two iterations agree). {@code null} when the set is empty or the player is offline.
     */
    @Nullable
    public static ServerPlayer rewardedPlayer(ServerLevel level, Set<UUID> detectedPlayers) {
        var iterator = detectedPlayers.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        return level.getPlayerByUUID(iterator.next()) instanceof ServerPlayer player ? player : null;
    }

    /**
     * Apply {@link LootScaling#scaledCount} to every rolled stack in place. The vault path mutates
     * the (mutable) list vanilla stores on {@code VaultServerData} for ejection; the spawner path
     * scales each stack as it is dispensed.
     */
    public static void scaleStacks(List<ItemStack> stacks, double multiplier) {
        if (multiplier == 1.0) {
            return;
        }
        for (ItemStack stack : stacks) {
            scaleStack(stack, multiplier);
        }
    }

    /** Scale one rolled stack's count in place (no-op for empty or non-stackable stacks). */
    public static void scaleStack(ItemStack stack, double multiplier) {
        if (!stack.isEmpty()) {
            stack.setCount(LootScaling.scaledCount(stack.getCount(), stack.getMaxStackSize(), multiplier));
        }
    }

    /**
     * Show the tier action-bar notification on a successful vault open (SPEC §8), consistent with
     * container generation. The spawner path stays silent &mdash; rewards eject on a timer with no
     * per-player open moment to anchor a message to.
     */
    public static void notifyVaultOpen(Player player, ServerLevel level, BlockPos pos, Mods mods) {
        if (player instanceof ServerPlayer serverPlayer) {
            LootNotification.send(serverPlayer, level, Vec3.atCenterOf(pos), mods.scaled(),
                    mods.stackMultiplier(), mods.luck());
        }
    }
}
