package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.ContainerProtection;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Runtime checks for container break protection (S-017). The predicate is asserted through the
 * explicit-online-set seams ({@link ContainerProtection#isProtectedServer(ServerLevel, BlockPos, net.minecraft.world.entity.player.Player, java.util.Collection)}
 * and {@code protectionMultiplierFor}) rather than the live player list: gametest mock players are
 * deliberately absent from {@code getPlayerList()} (that is S-034's fake-player tell), so a real-list
 * scan would always see nobody online. The actual slowed break and the cracking-animation sync are
 * the in-world manual check.
 *
 * <p>Runs in its own batch because it toggles the global {@code enableContainerProtection} flag, and
 * all assertions live in one method so they cannot race that flag (batches run sequentially; tests in
 * a batch run concurrently).
 */
public class ContainerProtectionGameTest implements FabricGameTest {

    private static final String BATCH = "container_protection";
    private static final UUID GENERATED = new UUID(0L, 1L);
    private static final UUID PENDING = new UUID(0L, 2L);

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void protectionGateRespectsStateAndConfig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // A generated instanced chest: GENERATED has opened it, PENDING has not.
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockPos abs = helper.absolutePos(rel);
        RandomizableContainerBlockEntity container =
                (RandomizableContainerBlockEntity) level.getBlockEntity(abs);
        ProsperityAttachments.update(container, data -> {
            data.markGenerated(BuiltInLootTables.SIMPLE_DUNGEON, 0L);
            data.setInventory(GENERATED, NonNullList.create());
        });

        // A plain, never-generated chest is never protected.
        BlockPos plainRel = new BlockPos(3, 1, 1);
        helper.setBlock(plainRel, Blocks.CHEST);
        BlockPos plainAbs = helper.absolutePos(plainRel);

        boolean savedEnabled = Prosperity.getConfig().enableContainerProtection;
        float savedMultiplier = Prosperity.getConfig().protectionBreakMultiplier;
        ServerPlayer creative = helper.makeMockServerPlayerInLevel();
        creative.setGameMode(GameType.CREATIVE);
        try {
            Prosperity.getConfig().enableContainerProtection = true;
            Prosperity.getConfig().protectionBreakMultiplier = 4.0f;

            helper.assertTrue(ContainerProtection.isProtectedServer(level, abs, null, List.of(PENDING)),
                    "a generated container with a pending online player must be protected");
            helper.assertFalse(ContainerProtection.isProtectedServer(level, abs, null, List.of(GENERATED)),
                    "protection must lift once every online player has generated");

            helper.assertTrue(
                    ContainerProtection.protectionMultiplierFor(level, abs, null, List.of(PENDING)) == 4.0f,
                    "a protected container must report the configured break multiplier");
            helper.assertTrue(
                    ContainerProtection.protectionMultiplierFor(level, abs, null, List.of(GENERATED)) == 1.0f,
                    "an unprotected container must report a 1.0 (vanilla) multiplier");

            helper.assertFalse(
                    ContainerProtection.isProtectedServer(level, abs, creative, List.of(PENDING)),
                    "a creative breaker must bypass protection");

            helper.assertFalse(
                    ContainerProtection.isProtectedServer(level, plainAbs, null, List.of(PENDING)),
                    "a plain, never-generated chest must never be protected");

            Prosperity.getConfig().enableContainerProtection = false;
            helper.assertFalse(ContainerProtection.isProtectedServer(level, abs, null, List.of(PENDING)),
                    "disabled protection must leave even a pending container unprotected");
        } finally {
            Prosperity.getConfig().enableContainerProtection = savedEnabled;
            Prosperity.getConfig().protectionBreakMultiplier = savedMultiplier;
            creative.discard();
        }
        helper.succeed();
    }
}
