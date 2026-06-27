package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.DistanceTierInfo;
import com.rfizzle.prosperity.api.ProsperityAPI;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Runtime checks for the public geographic distance-tier API ({@link ProsperityAPI#getDistanceTier},
 * {@link ProsperityAPI#getTierForPlayer}). Asserts the band boundaries (0/999/1000/2999/3000) and the
 * two dimension rules that need a real {@link ServerLevel}: the Nether reads raw coordinates (no
 * &times;8) and the End forces the max tier when {@code endAlwaysMaxTier} is set. The pure
 * {@code index}/sentinel conversion is covered by {@code DistanceTierInfoTest}.
 *
 * <p>Runs in its own batch and toggles {@code endAlwaysMaxTier} inside one method (restored in a
 * {@code finally}) so the mutation cannot race the default batch.
 */
public class DistanceTierApiGameTest implements FabricGameTest {

    private static final String BATCH = "distance_tier_api";

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void distanceTierApiResolvesBandsAndDimensions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        ServerLevel end = level.getServer().getLevel(Level.END);
        helper.assertTrue(nether != null && end != null, "the Nether and End must be loaded");

        // Overworld band boundaries. getDistanceTier is geographic only and independent of where the
        // test structure sits, so absolute coordinates are passed directly.
        assertTier(helper, ProsperityAPI.getDistanceTier(level, new BlockPos(0, 64, 0)), "local", 0);
        assertTier(helper, ProsperityAPI.getDistanceTier(level, new BlockPos(999, 64, 0)), "local", 0);
        assertTier(helper, ProsperityAPI.getDistanceTier(level, new BlockPos(1000, 64, 0)), "frontier", 1);
        assertTier(helper, ProsperityAPI.getDistanceTier(level, new BlockPos(2999, 64, 0)), "frontier", 1);
        assertTier(helper, ProsperityAPI.getDistanceTier(level, new BlockPos(3000, 64, 0)), "wilderness", 2);

        // 5,000 raw Nether blocks falls in the wilderness band; an x8 conversion would land in depths,
        // so wilderness proves the coordinates are used unscaled.
        assertTier(helper, ProsperityAPI.getDistanceTier(nether, new BlockPos(5000, 64, 0)), "wilderness", 2);

        // getTierForPlayer reads the player's own position; teleport to a known wilderness coordinate
        // and assert it agrees with getDistanceTier at the same block.
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.teleportTo(5000.5, 64.0, 0.5);
        DistanceTierInfo forPlayer = ProsperityAPI.getTierForPlayer(player);
        DistanceTierInfo atBlock = ProsperityAPI.getDistanceTier(level, player.blockPosition());
        assertTier(helper, forPlayer, "wilderness", 2);
        helper.assertTrue(forPlayer.name().equals(atBlock.name()) && forPlayer.index() == atBlock.index(),
                "getTierForPlayer must match getDistanceTier at the player's position");
        player.discard();

        boolean savedEnd = Prosperity.getConfig().endAlwaysMaxTier;
        try {
            Prosperity.getConfig().endAlwaysMaxTier = true;
            assertTier(helper, ProsperityAPI.getDistanceTier(end, new BlockPos(0, 64, 0)), "depths", 4);

            Prosperity.getConfig().endAlwaysMaxTier = false;
            assertTier(helper, ProsperityAPI.getDistanceTier(end, new BlockPos(0, 64, 0)), "local", 0);
        } finally {
            Prosperity.getConfig().endAlwaysMaxTier = savedEnd;
        }

        helper.succeed();
    }

    private static void assertTier(GameTestHelper helper, DistanceTierInfo info, String name, int index) {
        helper.assertTrue(info.name().equals(name),
                "expected tier '" + name + "' but got '" + info.name() + "'");
        helper.assertTrue(info.index() == index,
                "expected tier '" + name + "' at index " + index + " but got index " + info.index());
    }
}
