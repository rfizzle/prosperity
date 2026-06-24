package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the unlooted indicator when an instanced loot container is destroyed (S-008).
 *
 * <p>Targets {@link LevelChunk#removeBlockEntity(BlockPos)} &mdash; the single path through which a
 * block entity leaves the world for good (block break, explosion, {@code /setblock}, piston replace,
 * all of which route through {@code setBlockState}). Crucially this is <em>not</em> the chunk-unload
 * path: unload calls {@code clearAllBlockEntities} &rarr; {@code setRemoved()} directly and never
 * touches {@code removeBlockEntity}, so a container that merely scrolls out of view keeps its
 * indicator rather than being reported as gone.
 *
 * <p>Injected at {@code HEAD}, where the entity is still in the chunk's map, so it can be looked up
 * and inspected before removal. The {@link ServerLevel} guard skips client chunks and the off-thread
 * {@code WorldGenRegion} used during world generation.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRemoveBlockEntityMixin {

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void prosperity$dropIndicatorOnRemove(BlockPos pos, CallbackInfo ci) {
        try {
            LevelChunk self = (LevelChunk) (Object) this;
            if (self.getLevel() instanceof ServerLevel serverLevel) {
                BlockEntity be = self.getBlockEntity(pos);
                if (be != null) {
                    InstancedLootInteraction.onContainerRemoved(serverLevel, pos, be);
                }
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to drop instanced-loot indicator on block-entity removal", e);
        }
    }
}
