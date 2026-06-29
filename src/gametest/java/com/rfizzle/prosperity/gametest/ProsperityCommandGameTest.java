package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.command.ProsperityCommand;
import com.rfizzle.prosperity.config.DistanceTier;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Runtime checks for the {@code /prosperity} command core (S-004): the dimension-aware tier
 * resolver and the container-clearing operation that {@code reset}/{@code refresh} drive. Both
 * are exercised through their exposed entry points, away from Brigadier plumbing.
 */
public class ProsperityCommandGameTest implements FabricGameTest {

    private static final UUID PLAYER_A = new UUID(0xAAAAL, 0x1111L);
    private static final UUID PLAYER_B = new UUID(0xBBBBL, 0x2222L);

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void resolveTierScalesWithDistance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(ProsperityCommand.resolveTier(level, 0, 0).name().equals("local"),
                "origin must resolve to the local tier");
        helper.assertTrue(ProsperityCommand.resolveTier(level, 3000, 4000).name().equals("wilderness"),
                "5,000 blocks must resolve to the wilderness tier");
        helper.assertTrue(ProsperityCommand.resolveTier(level, 10_000, 0).name().equals("depths"),
                "10,000 blocks must resolve to the depths tier");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void resolveTierForcesMaxTierInTheEnd(GameTestHelper helper) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "the End dimension must be loaded");

        DistanceTier atOrigin = ProsperityCommand.resolveTier(end, 0, 0);
        helper.assertTrue(atOrigin.name().equals("depths"),
                "the End must force the max (depths) tier regardless of distance");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void clearContainerRemovesPerPlayerAndAll(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockEntity chest = helper.getBlockEntity(rel);
        BlockPos abs = helper.absolutePos(rel);
        ServerLevel level = helper.getLevel();

        ProsperityAttachments.update(chest, data -> {
            data.markGenerated(null, 0L);
            data.getOrCreateInventory(PLAYER_A, 27);
            data.getOrCreateInventory(PLAYER_B, 27);
        });

        // Clearing one player removes exactly that instance and leaves the other intact.
        int removedOne = ProsperityCommand.clearContainer(level, abs, List.of(PLAYER_A));
        helper.assertTrue(removedOne == 1, "clearing one player must report one instance removed");
        helper.assertFalse(ProsperityAttachments.get(chest).hasInventory(PLAYER_A), "player A's instance must be gone");
        helper.assertTrue(ProsperityAttachments.get(chest).hasInventory(PLAYER_B), "player B's instance must remain");

        // Clearing all wipes the remaining instance and reports the count.
        int removedAll = ProsperityCommand.clearContainer(level, abs, null);
        helper.assertTrue(removedAll == 1, "clearing all must report the one remaining instance");
        helper.assertTrue(ProsperityAttachments.get(chest).playerIds().isEmpty(),
                "no instances may remain after a full clear");

        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void clearRadiusClearsWithinAndSparesOutside(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos nearRel = new BlockPos(1, 1, 1);
        BlockPos farRel = new BlockPos(6, 1, 1);
        helper.setBlock(nearRel, Blocks.CHEST);
        helper.setBlock(farRel, Blocks.CHEST);
        BlockEntity near = helper.getBlockEntity(nearRel);
        BlockEntity far = helper.getBlockEntity(farRel);

        ProsperityAttachments.update(near, data -> {
            data.markGenerated(null, 0L);
            data.getOrCreateInventory(PLAYER_A, 27);
        });
        ProsperityAttachments.update(far, data -> {
            data.markGenerated(null, 0L);
            data.getOrCreateInventory(PLAYER_A, 27);
        });

        // A radius of 3 around the near container reaches it (distance 0) but not the far one (distance 5).
        int removed = ProsperityCommand.clearRadius(level, helper.absolutePos(nearRel), 3, null);

        helper.assertTrue(removed == 1, "only the in-radius container's instance must be removed");
        helper.assertFalse(ProsperityAttachments.get(near).hasInventory(PLAYER_A),
                "the in-radius container must be cleared");
        helper.assertTrue(ProsperityAttachments.get(far).hasInventory(PLAYER_A),
                "the out-of-radius container must be spared");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void clearContainerReportsMissingContainer(GameTestHelper helper) {
        BlockPos abs = helper.absolutePos(new BlockPos(1, 1, 1));
        int result = ProsperityCommand.clearContainer(helper.getLevel(), abs, null);
        helper.assertTrue(result == -1, "an empty position must report no instanced container");
        helper.succeed();
    }
}
