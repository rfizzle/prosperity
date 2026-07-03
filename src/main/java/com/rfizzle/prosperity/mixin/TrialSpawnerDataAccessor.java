package com.rfizzle.prosperity.mixin;

import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the trial spawner's protected detected-player set so
 * {@link TrialSpawnerMixin} can attribute a reward ejection to the player vanilla is about to
 * reward (SPEC §16).
 */
@Mixin(TrialSpawnerData.class)
public interface TrialSpawnerDataAccessor {

    @Accessor("detectedPlayers")
    Set<UUID> prosperity$detectedPlayers();
}
