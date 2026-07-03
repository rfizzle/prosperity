package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.TrialChamberScaling;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distance-tier scaling for trial spawner ejected rewards (SPEC §16). Vanilla ejects one reward
 * roll per detected player from {@code TrialSpawner#ejectReward} and then removes the head of the
 * detected set &mdash; that head is the player this roll rewards, so the scaling (and the
 * {@code LootModifierCallback} fire) is attributed to them via
 * {@link TrialChamberScaling#rewardedPlayer}.
 *
 * <p>Three coordinated injectors share one decision resolved once at HEAD, mirroring
 * {@link VaultBlockEntityServerMixin}:
 * <ul>
 *   <li>{@code @Inject(HEAD)} resolves {@link TrialChamberScaling#resolve} for the rewarded player,
 *       or {@code null} for a non-scalable roll (player offline, feature off).
 *   <li>{@code @ModifyArg} on {@code LootTable#getRandomItems} replaces the parameter-less
 *       {@code LootParams} with one carrying the event's final luck (vanilla rolls this table with
 *       no luck at all).
 *   <li>{@code @ModifyArg} on {@code DefaultDispenseItemBehavior#spawnItem} scales each dispensed
 *       stack's count.
 * </ul>
 *
 * <p>The field is written at HEAD and cleared at RETURN within the same synchronous server-thread
 * call; a {@code null} decision leaves the ejection byte-identical to vanilla and fires no event.
 */
@Mixin(TrialSpawner.class)
public abstract class TrialSpawnerMixin {

    @Unique
    @Nullable
    private TrialChamberScaling.Mods prosperity$rewardMods;

    @Inject(method = "ejectReward", at = @At("HEAD"))
    private void prosperity$resolveRewardScaling(ServerLevel level, BlockPos pos,
            ResourceKey<LootTable> lootTable, CallbackInfo ci) {
        prosperity$rewardMods = null;
        try {
            TrialSpawner self = (TrialSpawner) (Object) this;
            ServerPlayer player = TrialChamberScaling.rewardedPlayer(level,
                    ((TrialSpawnerDataAccessor) self.getData()).prosperity$detectedPlayers());
            prosperity$rewardMods =
                    TrialChamberScaling.resolve(level, pos, player, lootTable.location());
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to resolve trial spawner reward scaling at {}", pos, e);
        }
    }

    @ModifyArg(method = "ejectReward",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;"
                            + "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)"
                            + "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private LootParams prosperity$injectRewardLuck(LootParams original) {
        TrialChamberScaling.Mods mods = prosperity$rewardMods;
        if (mods == null) {
            return original;
        }
        return new LootParams.Builder(original.getLevel())
                .withLuck(mods.luck())
                .create(LootContextParamSets.EMPTY);
    }

    @ModifyArg(method = "ejectReward",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/dispenser/DefaultDispenseItemBehavior;"
                            + "spawnItem(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/item/ItemStack;I"
                            + "Lnet/minecraft/core/Direction;"
                            + "Lnet/minecraft/core/Position;)V"),
            index = 1)
    private ItemStack prosperity$scaleRewardStack(ItemStack original) {
        TrialChamberScaling.Mods mods = prosperity$rewardMods;
        if (mods != null) {
            TrialChamberScaling.scaleStack(original, mods.stackMultiplier());
        }
        return original;
    }

    @Inject(method = "ejectReward", at = @At("RETURN"))
    private void prosperity$clearRewardScaling(ServerLevel level, BlockPos pos,
            ResourceKey<LootTable> lootTable, CallbackInfo ci) {
        prosperity$rewardMods = null;
    }
}
