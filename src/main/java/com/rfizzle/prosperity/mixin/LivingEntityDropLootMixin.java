package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.LootScaling;
import com.rfizzle.prosperity.loot.MobLootScaling;
import java.util.function.Consumer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distance-tier scaling for hostile-mob drops on player kills (S-018, SPEC §13). Hooks the one method
 * all standard mob death loot funnels through &mdash; {@code LivingEntity#dropFromLootTable} &mdash; and
 * reuses the container scaling primitives so mob loot scales identically.
 *
 * <p>Three coordinated injectors share one decision resolved once at HEAD:
 * <ul>
 *   <li>{@code @Inject(HEAD)} resolves {@link MobLootScaling#resolve} into {@link #prosperity$mobMods}
 *       (the gate + a single {@code LootModifierCallback} fire), or {@code null} for a non-scalable kill.
 *   <li>{@code @ModifyArg} on {@code LootParams.Builder#withLuck} replaces vanilla's
 *       {@code player.getLuck()} with the event's final luck (tier quality + vanilla luck + any API
 *       listener), matching the container path.
 *   <li>{@code @ModifyArg} on the {@code LootTable#getRandomItems} drop consumer wraps it to apply
 *       {@link LootScaling#scaledCount} to each rolled stack.
 * </ul>
 *
 * <p>The {@code withLuck} call and the drop consumer both sit inside the player-kill branch of the
 * vanilla method, so a {@code null} {@link #prosperity$mobMods} (passive mob, non-player kill, feature
 * off) leaves the drop byte-identical to vanilla. The field is written at HEAD and read by the two
 * {@code @ModifyArg}s within the same synchronous server-thread call, so it carries no cross-call or
 * cross-thread state.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDropLootMixin {

    @Shadow
    @Nullable
    protected Player lastHurtByPlayer;

    @Unique
    @Nullable
    private MobLootScaling.Mods prosperity$mobMods;

    @Inject(method = "dropFromLootTable", at = @At("HEAD"))
    private void prosperity$resolveMobScaling(DamageSource damageSource, boolean hitByPlayer,
            CallbackInfo ci) {
        prosperity$mobMods = null;
        try {
            Player killer = hitByPlayer ? this.lastHurtByPlayer : null;
            prosperity$mobMods = MobLootScaling.resolve((LivingEntity) (Object) this, killer);
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to resolve mob loot scaling for {}",
                    ((LivingEntity) (Object) this).getType(), e);
        }
    }

    @ModifyArg(method = "dropFromLootTable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"
                            + "withLuck(F)Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"))
    private float prosperity$scaleLuck(float original) {
        MobLootScaling.Mods mods = prosperity$mobMods;
        return mods != null ? mods.luck() : original;
    }

    @ModifyArg(method = "dropFromLootTable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;"
                            + "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;J"
                            + "Ljava/util/function/Consumer;)V"),
            index = 2)
    private Consumer<ItemStack> prosperity$scaleStacks(Consumer<ItemStack> original) {
        MobLootScaling.Mods mods = prosperity$mobMods;
        if (mods == null || mods.stackMultiplier() == 1.0) {
            return original;
        }
        double multiplier = mods.stackMultiplier();
        return stack -> {
            if (!stack.isEmpty()) {
                stack.setCount(
                        LootScaling.scaledCount(stack.getCount(), stack.getMaxStackSize(), multiplier));
            }
            original.accept(stack);
        };
    }
}
