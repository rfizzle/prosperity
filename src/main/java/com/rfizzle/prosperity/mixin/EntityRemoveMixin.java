package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.MinecartLootInteraction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the unlooted indicator when an instanced container minecart is destroyed (S-038) &mdash; the
 * entity parallel of {@link LevelChunkRemoveBlockEntityMixin}, which only covers block entities.
 *
 * <p>Targets {@link Entity#setRemoved} &mdash; the single {@code final} sink every removal funnels
 * through ({@code discard}/{@code kill}/unload/dimension change all call it). The
 * {@link Entity.RemovalReason#shouldDestroy()} gate keeps only genuine destruction (killed/discarded)
 * and skips the unload reasons, so a cart merely scrolling out of view keeps its indicator rather than
 * being reported as gone &mdash; exactly the distinction the block mixin draws by targeting
 * {@code removeBlockEntity} instead of the unload path.
 *
 * <p>Injected at {@code HEAD}; the {@link ServerLevel} guard skips client entities and the off-thread
 * {@code WorldGenRegion}.</p>
 */
@Mixin(Entity.class)
public abstract class EntityRemoveMixin {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void prosperity$dropMinecartIndicatorOnRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        try {
            if (!reason.shouldDestroy()) {
                return;
            }
            Entity self = (Entity) (Object) this;
            if (self instanceof AbstractMinecartContainer cart && self.level() instanceof ServerLevel serverLevel) {
                MinecartLootInteraction.onMinecartRemoved(serverLevel, cart);
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to drop instanced-loot indicator on minecart removal", e);
        }
    }
}
