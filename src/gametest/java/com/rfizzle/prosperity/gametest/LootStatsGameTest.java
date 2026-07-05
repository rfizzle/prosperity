package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.LootStatsData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.LootScaling;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for per-player loot statistics (issue #52): a generation counts exactly once in
 * the effective tier's bucket, a return visit counts nothing, a refresh re-roll counts again, a
 * double chest is one generation, players count independently, and {@code augment} reports whether
 * an injected reward was actually placed (the value the injected-rewards stat records).
 *
 * <p>Recording is driven through {@link InstancedLootInteraction#generateAndStore} — the same
 * entry point a real open runs — and asserted on the player attachment directly; the command
 * formatting on top is pure and unit tested ({@code ProsperityCommandStatsTest}).
 */
public class LootStatsGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 5252L;
    private static final UUID INJECT_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000034");

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.BARREL);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    @SuppressWarnings("removal")
    private ServerPlayer spawnPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /** Generate the player's instance for the container at {@code rel}, as a real open would. */
    private BlockEntityContainerAdapter loot(GameTestHelper helper, BlockPos rel, ServerPlayer player) {
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        BlockEntityContainerAdapter adapter =
                new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be);
        InstancedLootInteraction.generateAndStore(adapter, player);
        return adapter;
    }

    /** The tier name the generation path resolves for the container at {@code rel}. */
    private String expectedTier(GameTestHelper helper, BlockPos rel) {
        return LootScaling.resolveForGeneration(helper.getLevel(),
                Vec3.atCenterOf(helper.absolutePos(rel))).tier().name();
    }

    /** A first generation counts once in the resolved tier's bucket; a return visit adds nothing. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void generationCountsOnceAndReturnVisitDoesNot(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel);
        ServerPlayer player = spawnPlayer(helper);
        String tier = expectedTier(helper, rel);

        loot(helper, rel, player);
        LootStatsData stats = ProsperityAttachments.stats(player);
        helper.assertTrue(stats != null, "the first generation must attach loot stats");
        helper.assertTrue(stats.containersLooted() == 1,
                "one generation must count one container: got " + stats.containersLooted());
        helper.assertTrue(stats.tierCounts().getOrDefault(tier, 0L) == 1L,
                "the generation must land in the resolved tier bucket (" + tier + ")");
        helper.assertTrue(stats.distinctStructures() == 0,
                "a container outside any structure must record no structure");

        loot(helper, rel, player);
        helper.assertTrue(ProsperityAttachments.stats(player).containersLooted() == 1,
                "a return visit must not count again");

        player.discard();
        helper.succeed();
    }

    /** Clearing the player's instance (the refresh path) makes the next open a counted re-roll. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void refreshRegenerationCountsAgain(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel);
        ServerPlayer player = spawnPlayer(helper);
        String tier = expectedTier(helper, rel);

        loot(helper, rel, player);
        ProsperityAttachments.update(be, data -> data.clearForPlayer(player.getUUID()));
        loot(helper, rel, player);

        LootStatsData stats = ProsperityAttachments.stats(player);
        helper.assertTrue(stats.containersLooted() == 2,
                "a refresh re-roll must count as a new generation: got " + stats.containersLooted());
        helper.assertTrue(stats.tierCounts().getOrDefault(tier, 0L) == 2L,
                "both generations must land in the same tier bucket");

        player.discard();
        helper.succeed();
    }

    /** A double chest is one generation: one container, one tier increment. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleChestCountsOnce(GameTestHelper helper) {
        BlockPos leftRel = new BlockPos(1, 1, 1);
        BlockPos rightRel = leftRel.east();
        BlockState left = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.setBlock(leftRel, left);
        helper.setBlock(rightRel, right);
        ChestBlockEntity leftBe = (ChestBlockEntity) helper.getBlockEntity(leftRel);
        ChestBlockEntity rightBe = (ChestBlockEntity) helper.getBlockEntity(rightRel);
        leftBe.setLootTable(TABLE);
        leftBe.setLootTableSeed(SEED);
        rightBe.setLootTable(TABLE);
        rightBe.setLootTableSeed(SEED);

        ServerPlayer player = spawnPlayer(helper);
        InstancedLootInteraction.generateAndStoreDouble(helper.getLevel(),
                helper.absolutePos(leftRel), leftBe, helper.absolutePos(rightRel), rightBe, player);

        LootStatsData stats = ProsperityAttachments.stats(player);
        helper.assertTrue(stats != null && stats.containersLooted() == 1,
                "a double chest must count as exactly one generation");

        player.discard();
        helper.succeed();
    }

    /** Two players generating from the same container each count their own generation. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playersCountIndependently(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel);
        ServerPlayer first = spawnPlayer(helper);
        ServerPlayer second = spawnPlayer(helper);

        loot(helper, rel, first);
        loot(helper, rel, second);

        helper.assertTrue(ProsperityAttachments.stats(first).containersLooted() == 1,
                "the first player's stats must count only their own generation");
        helper.assertTrue(ProsperityAttachments.stats(second).containersLooted() == 1,
                "the second player's stats must count only their own generation");

        first.discard();
        second.discard();
        helper.succeed();
    }

    /**
     * {@code augment} reports what the injected-rewards stat records: {@code true} only when an
     * eligible entry survived the chance gate, was drawn <em>and</em> placed — {@code false} below
     * every injection tier and {@code false} when the container has no empty slot. The shipped
     * defaults gate Frontier injections behind a per-group {@code chance} (issue #68), so the
     * placing case first scans a bounded seed range for a generation that passes the gate
     * (deterministic: the scan is over fixed seeds).
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void augmentReportsActualPlacement(GameTestHelper helper) {
        long placingSeed = -1L;
        for (long seed = 0; seed < 500 && placingSeed < 0; seed++) {
            NonNullList<ItemStack> probe = NonNullList.withSize(27, ItemStack.EMPTY);
            if (LootInjectionManager.augment(probe, TABLE,
                    LootScaling.tierByName(Prosperity.getConfig(), "frontier"),
                    helper.getLevel(), seed, 0L, INJECT_PLAYER)) {
                placingSeed = seed;
            }
        }
        helper.assertTrue(placingSeed >= 0,
                "some seed in [0, 500) must pass the Frontier chance gate and report placement");

        NonNullList<ItemStack> belowTier = NonNullList.withSize(27, ItemStack.EMPTY);
        helper.assertFalse(LootInjectionManager.augment(belowTier, TABLE,
                        ProsperityConfig.LOCAL_SENTINEL, helper.getLevel(), placingSeed, 0L, INJECT_PLAYER),
                "a Local draw is below every default injection tier and must report no placement");

        NonNullList<ItemStack> full = NonNullList.withSize(27, ItemStack.EMPTY);
        for (int slot = 0; slot < full.size(); slot++) {
            full.set(slot, new ItemStack(Items.COBBLESTONE));
        }
        helper.assertFalse(LootInjectionManager.augment(full, TABLE,
                        LootScaling.tierByName(Prosperity.getConfig(), "frontier"),
                        helper.getLevel(), placingSeed, 0L, INJECT_PLAYER),
                "a full container leaves the drawn reward unplaced and must report no placement");

        helper.succeed();
    }
}
