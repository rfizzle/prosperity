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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;

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

        // A plain storage chest (no loot table, never generated) is not a managed loot container.
        BlockPos plainRel = new BlockPos(3, 1, 1);
        helper.setBlock(plainRel, Blocks.CHEST);
        BlockPos plainAbs = helper.absolutePos(plainRel);

        // A never-opened loot chest: a loot table set, no instance generated yet. Its loot is pending
        // for everyone, so it must be protected even with no players online (the singleplayer case).
        BlockPos lootRel = new BlockPos(5, 1, 1);
        helper.setBlock(lootRel, Blocks.CHEST);
        BlockPos lootAbs = helper.absolutePos(lootRel);
        RandomizableContainerBlockEntity lootContainer =
                (RandomizableContainerBlockEntity) level.getBlockEntity(lootAbs);
        lootContainer.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        lootContainer.setLootTableSeed(0L);

        boolean savedEnabled = Prosperity.getConfig().enableContainerProtection;
        float savedMultiplier = Prosperity.getConfig().protectionBreakMultiplier;
        boolean savedUnbreakable = Prosperity.getConfig().protectionUnbreakable;
        boolean savedRefresh = Prosperity.getConfig().enableLootRefresh;
        ServerPlayer creative = helper.makeMockServerPlayerInLevel();
        creative.setGameMode(GameType.CREATIVE);
        try {
            Prosperity.getConfig().enableContainerProtection = true;
            Prosperity.getConfig().protectionBreakMultiplier = 4.0f;
            Prosperity.getConfig().protectionUnbreakable = false;
            Prosperity.getConfig().enableLootRefresh = false;

            helper.assertTrue(ContainerProtection.isProtectedServer(level, abs, null, List.of(PENDING)),
                    "a generated container with a pending online player must be protected");
            helper.assertFalse(ContainerProtection.isProtectedServer(level, abs, null, List.of(GENERATED)),
                    "protection must lift once every online player has generated");

            // With loot refresh on, an emptied container's loot always returns, so it stays protected
            // even after every online player has looted it — nobody can break it to deny the refresh.
            Prosperity.getConfig().enableLootRefresh = true;
            helper.assertTrue(ContainerProtection.isProtectedServer(level, abs, null, List.of(GENERATED)),
                    "a refreshable container must stay protected even after everyone has generated");
            Prosperity.getConfig().enableLootRefresh = false;

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
                    "a plain storage chest (no loot table) must never be protected");

            helper.assertTrue(
                    ContainerProtection.isProtectedServer(level, lootAbs, null, List.of(GENERATED)),
                    "a never-opened loot chest must be protected regardless of who has looted elsewhere");
            helper.assertFalse(
                    ContainerProtection.isProtectedServer(level, lootAbs, creative, List.of()),
                    "a creative breaker must bypass protection on an unopened loot chest too");

            // protectionUnbreakable: a protected container reports an infinite multiplier (the mixin
            // zeroes getDestroyProgress), while an unprotected one is untouched at vanilla speed.
            Prosperity.getConfig().protectionUnbreakable = true;
            helper.assertTrue(
                    Float.isInfinite(
                            ContainerProtection.protectionMultiplierFor(level, abs, null, List.of(PENDING))),
                    "an unbreakable protected container must report an infinite break multiplier");
            helper.assertTrue(
                    ContainerProtection.protectionMultiplierFor(level, abs, null, List.of(GENERATED)) == 1.0f,
                    "an unprotected container must stay at 1.0 even with the unbreakable flag on");
            helper.assertFalse(
                    ContainerProtection.protectionMessage(true).getString()
                            .equals(ContainerProtection.protectionMessage(false).getString()),
                    "the unbreakable warning must differ from the slow-break warning");

            // Explosion immunity rides the unbreakable hard lock: a protected loot source is blast-proof
            // only with the flag on, and only when it is actually a managed loot container.
            helper.assertTrue(ContainerProtection.isExplosionProof(level, lootAbs),
                    "an unbreakable protected container must be explosion-proof");
            helper.assertFalse(ContainerProtection.isExplosionProof(level, plainAbs),
                    "a plain storage chest must never be explosion-proof");
            Prosperity.getConfig().protectionUnbreakable = false;
            helper.assertFalse(ContainerProtection.isExplosionProof(level, lootAbs),
                    "slow-break mode must leave protected containers vulnerable to explosions");

            Prosperity.getConfig().enableContainerProtection = false;
            helper.assertFalse(ContainerProtection.isProtectedServer(level, lootAbs, null, List.of()),
                    "disabled protection must leave even an unopened loot chest unprotected");
            helper.assertFalse(ContainerProtection.isProtectedServer(level, abs, null, List.of(PENDING)),
                    "disabled protection must leave even a pending container unprotected");
        } finally {
            Prosperity.getConfig().enableContainerProtection = savedEnabled;
            Prosperity.getConfig().protectionBreakMultiplier = savedMultiplier;
            Prosperity.getConfig().protectionUnbreakable = savedUnbreakable;
            Prosperity.getConfig().enableLootRefresh = savedRefresh;
            creative.discard();
        }
        helper.succeed();
    }

    /**
     * End-to-end: under the unbreakable hard lock a real explosion spares a protected loot container
     * while still destroying an unprotected chest beside it. Runs in its own batch so it cannot race
     * the global flags it toggles. A sourceless blast exercises the base damage calculator; the
     * identical injection on {@code EntityBasedExplosionDamageCalculator} (TNT/creepers) is the same
     * multi-target mixin, load-validated by {@code defaultRequire}.
     */
    @GameTest(batch = "container_explosion", template = FabricGameTest.EMPTY_STRUCTURE)
    public void unbreakableContainerSurvivesExplosion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        BlockPos protectedRel = new BlockPos(1, 1, 1);
        helper.setBlock(protectedRel, Blocks.CHEST);
        RandomizableContainerBlockEntity protectedChest =
                (RandomizableContainerBlockEntity) level.getBlockEntity(helper.absolutePos(protectedRel));
        protectedChest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        protectedChest.setLootTableSeed(0L);

        // An identical chest with no loot table is unprotected — the control proving the blast is lethal.
        BlockPos plainRel = new BlockPos(3, 1, 1);
        helper.setBlock(plainRel, Blocks.CHEST);

        boolean savedEnabled = Prosperity.getConfig().enableContainerProtection;
        boolean savedUnbreakable = Prosperity.getConfig().protectionUnbreakable;
        try {
            Prosperity.getConfig().enableContainerProtection = true;
            Prosperity.getConfig().protectionUnbreakable = true;

            Vec3 center = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 1)));
            level.explode(null, center.x, center.y, center.z, 4.0f, Level.ExplosionInteraction.TNT);

            helper.assertBlockPresent(Blocks.CHEST, protectedRel);
            helper.assertBlockNotPresent(Blocks.CHEST, plainRel);
        } finally {
            Prosperity.getConfig().enableContainerProtection = savedEnabled;
            Prosperity.getConfig().protectionUnbreakable = savedUnbreakable;
        }
        helper.succeed();
    }
}
