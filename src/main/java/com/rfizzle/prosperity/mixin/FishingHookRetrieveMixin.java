package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.FishingLootScaling;
import com.rfizzle.prosperity.loot.LootScaling;
import java.util.List;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Distance-tier scaling for fishing catches (SPEC §18). Hooks the one method every fishing catch
 * funnels through &mdash; {@code FishingHook#retrieve} &mdash; and reuses the container scaling
 * primitives so fishing loot scales identically to containers and mob drops.
 *
 * <p>Two coordinated injectors share one decision:
 * <ul>
 *   <li>{@code @ModifyArg} on {@code LootParams.Builder#withLuck} resolves
 *       {@link FishingLootScaling#resolve} into {@link #prosperity$fishingMods} (the gate + a single
 *       {@code LootModifierCallback} fire) and replaces vanilla's {@code this.luck + player.getLuck()}
 *       with {@code this.luck} plus the event's final luck (tier quality + vanilla luck + any API
 *       listener) &mdash; Luck of the Sea's contribution is preserved untouched.
 *   <li>{@code @ModifyVariable} on the rolled {@code List<ItemStack>} applies
 *       {@link LootScaling#scaledCount} to each catch in place.
 * </ul>
 *
 * <p>The resolve lives in the {@code withLuck} injector (not HEAD) because {@code retrieve} also
 * handles reeling in hooked entities and empty reels; the {@code withLuck} call sits inside the
 * caught-something branch, so the event fires exactly once per actual loot roll. A {@code null}
 * {@link #prosperity$fishingMods} (no player owner, feature off, resolve threw) leaves the roll
 * byte-identical to vanilla. The field is written and read within the same synchronous server-thread
 * call (and the hook entity is discarded at the end of {@code retrieve}), so it carries no cross-call
 * or cross-thread state.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookRetrieveMixin {

    @Shadow
    @Final
    private int luck;

    @Shadow
    @Nullable
    public abstract net.minecraft.world.entity.player.Player getPlayerOwner();

    @Unique
    @Nullable
    private FishingLootScaling.Mods prosperity$fishingMods;

    @ModifyArg(method = "retrieve",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"
                            + "withLuck(F)Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"))
    private float prosperity$scaleLuck(float original) {
        prosperity$fishingMods = null;
        try {
            prosperity$fishingMods =
                    FishingLootScaling.resolve((FishingHook) (Object) this, this.getPlayerOwner());
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to resolve fishing loot scaling", e);
        }
        FishingLootScaling.Mods mods = prosperity$fishingMods;
        // The event luck already folds in the player's generic.luck (default listener), so only
        // Luck of the Sea's rod contribution (this.luck) is added back from the vanilla operands.
        return mods != null ? this.luck + mods.luck() : original;
    }

    @ModifyVariable(method = "retrieve", at = @At("STORE"))
    private List<ItemStack> prosperity$scaleStacks(List<ItemStack> catches) {
        FishingLootScaling.Mods mods = prosperity$fishingMods;
        if (mods == null || mods.stackMultiplier() == 1.0) {
            return catches;
        }
        double multiplier = mods.stackMultiplier();
        for (ItemStack stack : catches) {
            if (!stack.isEmpty()) {
                stack.setCount(
                        LootScaling.scaledCount(stack.getCount(), stack.getMaxStackSize(), multiplier));
            }
        }
        return catches;
    }
}
