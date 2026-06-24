package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for structure-specific scaling (S-012, SPEC §6). The override mode math
 * (fixed/minimum/maximum, missing structure, unknown mode/tier) is asserted exhaustively in
 * {@code LootScalingTest}; this covers the parts that need a live {@link ServerLevel}: structure
 * detection and the {@link LootScaling#resolveForGeneration} wiring (distance-only when in no
 * structure, the {@code enableDistanceScaling} gate, and a {@code null} cached structure).
 *
 * <p>The gametest world has no generated structures at the test position, so detection resolves to
 * {@code null} here — the in-structure override path (a real monument/stronghold yielding its
 * override tier) is the documented manual in-world check, the same split S-010's renderer uses.
 *
 * <p>Runs in its own batch because it toggles the global {@code enableDistanceScaling} flag; batches
 * run sequentially while tests within one run concurrently, so isolating the mutation keeps it from
 * racing the default batch.
 */
public class StructureScalingGameTest implements FabricGameTest {

    private static final String BATCH = "structure_scaling";

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void resolutionOutsideAnyStructureUsesPureDistance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        Vec3 origin = Vec3.atCenterOf(pos);

        helper.assertTrue(LootScaling.resolveStructure(level, pos) == null,
                "a position outside any structure must resolve to no structure");

        boolean saved = Prosperity.getConfig().enableDistanceScaling;
        try {
            Prosperity.getConfig().enableDistanceScaling = true;
            LootScaling.ScaledTier on = LootScaling.resolveForGeneration(level, origin);
            helper.assertTrue(on.structure() == null,
                    "no structure here means no structure is cached");
            String distanceTier = LootScaling.resolveTier(level, origin.x, origin.z).name();
            helper.assertTrue(on.tier().name().equals(distanceTier),
                    "with no structure the effective tier is the pure distance tier");

            Prosperity.getConfig().enableDistanceScaling = false;
            LootScaling.ScaledTier off = LootScaling.resolveForGeneration(level, origin);
            helper.assertTrue(off.tier().name().equals(ProsperityConfig.LOCAL_SENTINEL.name()),
                    "disabled scaling yields the local sentinel");
            helper.assertTrue(off.structure() == null,
                    "disabled scaling skips structure detection entirely");
        } finally {
            Prosperity.getConfig().enableDistanceScaling = saved;
        }
        helper.succeed();
    }
}
