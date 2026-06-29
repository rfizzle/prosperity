package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.ContainerProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slows (or fully blocks) breaking of protected instanced loot containers (S-017, SPEC §12).
 *
 * <p>Targets {@link BlockBehaviour#getDestroyProgress} &mdash; the per-tick mining progress used both
 * by the client's cracking animation and by the server's break gate. Dividing the returned progress
 * by the protection multiplier makes the container take that many times longer to break; an infinite
 * multiplier ({@code protectionUnbreakable}) zeroes the progress so it never breaks at all, like
 * bedrock. The computation is delegated to {@link ContainerProtection#breakMultiplier}, which
 * evaluates protection authoritatively on the server (the attachment is server-only) and from the
 * client's queried answer on the client, so both the actual break and the visual cracking match.
 *
 * <p>Injected at {@code RETURN} so it scales vanilla's own result; a {@code multiplier <= 1.0}
 * (feature off, creative, unprotected, or not yet synced client-side) leaves the value untouched.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockBehaviourDestroyProgressMixin {

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void prosperity$slowProtectedContainers(BlockState state, Player player, BlockGetter level,
            BlockPos pos, CallbackInfoReturnable<Float> cir) {
        try {
            float multiplier = ContainerProtection.breakMultiplier(level, pos, player);
            if (Float.isInfinite(multiplier)) {
                // Unbreakable: zero per-tick progress never reaches the break threshold (as for bedrock).
                cir.setReturnValue(0.0f);
            } else if (multiplier > 1.0f) {
                cir.setReturnValue(cir.getReturnValue() / multiplier);
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to apply container break protection at {}", pos, e);
        }
    }
}
