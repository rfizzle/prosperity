package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.ContainerProtection;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes break-protected loot containers blast-proof under the {@code protectionUnbreakable} hard lock
 * (S-017, SPEC §12).
 *
 * <p>Targets {@code getBlockExplosionResistance} on both the base calculator and
 * {@link EntityBasedExplosionDamageCalculator} (used by every entity-sourced explosion &mdash; TNT,
 * creepers, end crystals), which overrides it. The two share an identical signature, so one
 * multi-target mixin covers both. Reporting {@link Float#MAX_VALUE} for a protected container drives
 * the explosion's running power negative for that ray, so the block is never added to the blast's
 * destroy set &mdash; the same mechanism that keeps obsidian and bedrock standing.
 *
 * <p>Only block destruction is affected (the position is gated to a {@link ServerLevel}); the
 * gate reuses {@link ContainerProtection#isExplosionProof}, which is a no-op unless both
 * {@code enableContainerProtection} and {@code protectionUnbreakable} are on.
 */
@Mixin({ExplosionDamageCalculator.class, EntityBasedExplosionDamageCalculator.class})
public abstract class ExplosionDamageCalculatorMixin {

    @Inject(method = "getBlockExplosionResistance", at = @At("RETURN"), cancellable = true)
    private void prosperity$protectContainers(Explosion explosion, BlockGetter level, BlockPos pos,
            BlockState state, FluidState fluid, CallbackInfoReturnable<Optional<Float>> cir) {
        try {
            if (level instanceof ServerLevel serverLevel
                    && ContainerProtection.isExplosionProof(serverLevel, pos)) {
                cir.setReturnValue(Optional.of(Float.MAX_VALUE));
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to apply container explosion protection at {}", pos, e);
        }
    }
}
