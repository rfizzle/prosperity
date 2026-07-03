package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.TrialChamberScaling;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distance-tier scaling for vault reward rolls (SPEC §16). Hooks the one method every vault unlock
 * funnels through &mdash; {@code VaultBlockEntity.Server#resolveItemsToEject}, called only from
 * {@code tryInsertKey} with the opening player &mdash; so normal and ominous vaults (each a
 * {@link VaultConfig} with its own loot table) are both covered, and the display-item cycling roll
 * ({@code getRandomDisplayItemFromLootTable}) stays untouched.
 *
 * <p>Three coordinated injectors share one decision resolved once at HEAD, mirroring
 * {@link LivingEntityDropLootMixin}:
 * <ul>
 *   <li>{@code @Inject(HEAD)} resolves {@link TrialChamberScaling#resolve} (the gate + a single
 *       {@code LootModifierCallback} fire), or {@code null} for a non-scalable roll.
 *   <li>{@code @ModifyArg} on {@code LootParams.Builder#withLuck} replaces vanilla's
 *       {@code player.getLuck()} with the event's final luck (tier quality + vanilla luck + any API
 *       listener), matching the container path.
 *   <li>{@code @Inject(RETURN)} scales the rolled stacks in place and shows the tier action-bar
 *       notification on a successful (non-empty) roll.
 * </ul>
 *
 * <p>Key consumption and the per-player rewarded set live in {@code tryInsertKey}, outside this
 * method, so vanilla's per-player vault gating is untouched. The field is written at HEAD and
 * cleared at RETURN within the same synchronous server-thread call, so it carries no cross-call
 * state; a {@code null} decision leaves the roll byte-identical to vanilla.
 */
@Mixin(VaultBlockEntity.Server.class)
public abstract class VaultBlockEntityServerMixin {

    @Unique
    @Nullable
    private static TrialChamberScaling.Mods prosperity$vaultMods;

    @Inject(method = "resolveItemsToEject", at = @At("HEAD"))
    private static void prosperity$resolveVaultScaling(ServerLevel level, VaultConfig config,
            BlockPos pos, Player player, CallbackInfoReturnable<List<ItemStack>> cir) {
        prosperity$vaultMods = null;
        try {
            prosperity$vaultMods =
                    TrialChamberScaling.resolve(level, pos, player, config.lootTable().location());
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to resolve vault loot scaling at {}", pos, e);
        }
    }

    @ModifyArg(method = "resolveItemsToEject",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"
                            + "withLuck(F)Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"))
    private static float prosperity$scaleVaultLuck(float original) {
        TrialChamberScaling.Mods mods = prosperity$vaultMods;
        return mods != null ? mods.luck() : original;
    }

    @Inject(method = "resolveItemsToEject", at = @At("RETURN"))
    private static void prosperity$scaleVaultStacks(ServerLevel level, VaultConfig config,
            BlockPos pos, Player player, CallbackInfoReturnable<List<ItemStack>> cir) {
        TrialChamberScaling.Mods mods = prosperity$vaultMods;
        prosperity$vaultMods = null;
        if (mods == null) {
            return;
        }
        try {
            List<ItemStack> items = cir.getReturnValue();
            TrialChamberScaling.scaleStacks(items, mods.stackMultiplier());
            if (!items.isEmpty()) {
                TrialChamberScaling.notifyVaultOpen(player, level, pos, mods);
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to scale vault loot at {}", pos, e);
        }
    }
}
