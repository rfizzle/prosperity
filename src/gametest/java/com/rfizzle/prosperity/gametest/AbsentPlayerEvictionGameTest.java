package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.eviction.PlayerLastSeenState;
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
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for absent-player eviction (issue #43): when enabled, a container touched after a
 * stored player's last-seen exceeds the threshold drops that player's inventory, generation tick,
 * and refresh count; when disabled (the default), nothing changes from the issue-#33 behavior.
 *
 * <p>An absent player is simulated with a fabricated "ghost" UUID that has never joined the test
 * server, so its last-seen falls back to the ledger's creation epoch — with a zero-day threshold it
 * is immediately over-threshold, without advancing whole in-game days (the exact day-boundary math
 * is covered by the pure-JUnit {@code AbsentPlayerEvictionTest}). The mock opener, by contrast,
 * <em>is</em> placed in the server's player list, which exercises the online-players-are-never-
 * evicted guard under the same zero-day threshold.
 *
 * <p>Runs in its own batch because it mutates the global eviction config; batches run sequentially,
 * so the toggle cannot race tests in the default batch.
 */
public class AbsentPlayerEvictionGameTest implements FabricGameTest {

    private static final String BATCH = "absent_eviction";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4343L;

    private RandomizableContainerBlockEntity placeLootChest(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    @SuppressWarnings("removal")
    private ServerPlayer spawnPlayerAt(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return player;
    }

    /** Seed all three per-player entries — inventory, generation tick, and a nonzero salt. */
    private void seedGhostEntries(RandomizableContainerBlockEntity be, UUID ghost) {
        ProsperityAttachments.update(be, data -> {
            data.setLastGeneratedTick(ghost, 0L);
            data.clearForPlayer(ghost); // refresh count -> 1
            data.setLastGeneratedTick(ghost, 0L);
            NonNullList<ItemStack> inv = NonNullList.withSize(27, ItemStack.EMPTY);
            inv.set(0, new ItemStack(Items.DIAMOND, 2));
            data.setInventory(ghost, inv);
        });
    }

    private void withEviction(boolean enabled, int days, Runnable body) {
        boolean savedEnabled = Prosperity.getConfig().evictAbsentPlayerData;
        int savedDays = Prosperity.getConfig().absentPlayerEvictionDays;
        try {
            Prosperity.getConfig().evictAbsentPlayerData = enabled;
            Prosperity.getConfig().absentPlayerEvictionDays = days;
            body.run();
        } finally {
            Prosperity.getConfig().evictAbsentPlayerData = savedEnabled;
            Prosperity.getConfig().absentPlayerEvictionDays = savedDays;
        }
    }

    /** An over-threshold absent player loses all three entries when the container is next opened. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void overThresholdGhostEvictedOnOpen(GameTestHelper helper) {
        withEviction(true, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootChest(helper, rel);
            UUID ghost = UUID.randomUUID();
            seedGhostEntries(be, ghost);
            ServerPlayer opener = spawnPlayerAt(helper, rel);

            InstancedLootInteraction.generateAndStore(
                    new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be), opener);

            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertFalse(data.trackedPlayerIds().contains(ghost),
                    "an over-threshold absent player must lose every residual entry on the next open");
            helper.assertTrue(data.getInventory(ghost) == null, "the ghost's inventory is dropped");
            helper.assertTrue(data.getLastGeneratedTick(ghost) == -1L, "the ghost's tick is dropped");
            helper.assertTrue(data.getRefreshCount(ghost) == 0L,
                    "the ghost's refresh count resets — a return regenerates from scratch with salt 0");
            helper.assertTrue(data.hasGenerated(opener.getUUID()),
                    "the opener's own fresh instance is stored after the prune");

            opener.discard();
            helper.succeed();
        });
    }

    /** A player under the absence threshold keeps every entry, including the salt. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void underThresholdGhostRetained(GameTestHelper helper) {
        // 1000 in-game days — far beyond any gametest world's age, so the ghost's epoch-fallback
        // last-seen is always under threshold.
        withEviction(true, 1000, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootChest(helper, rel);
            UUID ghost = UUID.randomUUID();
            seedGhostEntries(be, ghost);
            ServerPlayer opener = spawnPlayerAt(helper, rel);

            InstancedLootInteraction.generateAndStore(
                    new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be), opener);

            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data.hasInventory(ghost), "an under-threshold player keeps their inventory");
            helper.assertTrue(data.getRefreshCount(ghost) == 1L, "and keeps their salt");

            opener.discard();
            helper.succeed();
        });
    }

    /** With the feature disabled (the default), an open changes nothing — the #33 behavior. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void disabledDefaultLeavesGhostUntouched(GameTestHelper helper) {
        withEviction(false, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootChest(helper, rel);
            UUID ghost = UUID.randomUUID();
            seedGhostEntries(be, ghost);
            ServerPlayer opener = spawnPlayerAt(helper, rel);

            InstancedLootInteraction.generateAndStore(
                    new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be), opener);

            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data.hasInventory(ghost),
                    "with eviction disabled a stored player is never touched");
            helper.assertTrue(data.getLastGeneratedTick(ghost) == 0L, "the tick is retained");
            helper.assertTrue(data.getRefreshCount(ghost) == 1L, "the salt is retained");

            opener.discard();
            helper.succeed();
        });
    }

    /** Opening a double chest prunes both halves, and the secondary's redirect marker survives. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleChestPruneCoversBothHalves(GameTestHelper helper) {
        withEviction(true, 0, () -> {
            BlockPos leftRel = new BlockPos(1, 1, 1);
            BlockPos rightRel = leftRel.east();
            helper.setBlock(leftRel, Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.LEFT));
            helper.setBlock(rightRel, Blocks.CHEST.defaultBlockState()
                    .setValue(ChestBlock.FACING, Direction.NORTH)
                    .setValue(ChestBlock.TYPE, ChestType.RIGHT));
            ChestBlockEntity left = (ChestBlockEntity) helper.getBlockEntity(leftRel);
            ChestBlockEntity right = (ChestBlockEntity) helper.getBlockEntity(rightRel);
            left.setLootTable(TABLE);
            left.setLootTableSeed(SEED);
            right.setLootTable(TABLE);
            right.setLootTableSeed(SEED);
            // The left half (smaller x) is the primary; seed ghost residuals on BOTH halves — the
            // secondary can carry per-player entries from an earlier life as a single chest.
            UUID ghost = UUID.randomUUID();
            seedGhostEntries(left, ghost);
            seedGhostEntries(right, ghost);
            ServerPlayer opener = spawnPlayerAt(helper, leftRel);

            InstancedLootInteraction.generateAndStoreDouble(helper.getLevel(),
                    helper.absolutePos(leftRel), left, helper.absolutePos(rightRel), right, opener);

            helper.assertFalse(ProsperityAttachments.get(left).trackedPlayerIds().contains(ghost),
                    "the primary half must drop the absent player's entries");
            InstancedLootData secondaryData = ProsperityAttachments.get(right);
            helper.assertFalse(secondaryData.trackedPlayerIds().contains(ghost),
                    "the secondary half must drop the absent player's residual entries too");
            helper.assertTrue(helper.absolutePos(leftRel).equals(secondaryData.getRedirect()),
                    "eviction must not disturb the secondary's redirect to the primary");

            opener.discard();
            helper.succeed();
        });
    }

    /** The ledger wires through the live overworld storage: stamped epoch, touch-then-read. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void lastSeenLedgerRoundTripsThroughLiveStorage(GameTestHelper helper) {
        PlayerLastSeenState ledger = PlayerLastSeenState.get(helper.getLevel().getServer());
        helper.assertTrue(ledger == PlayerLastSeenState.get(helper.getLevel().getServer()),
                "repeat gets must return the same stored instance, not a fresh ledger");
        long now = helper.getLevel().getServer().overworld().getGameTime();
        long unknown = ledger.lastSeen(UUID.randomUUID());
        helper.assertTrue(unknown >= 0 && unknown <= now,
                "an unknown player reads the stamped epoch (a real past game time), never a sentinel");

        UUID player = UUID.randomUUID();
        ledger.touch(player, now);
        helper.assertTrue(ledger.lastSeen(player) == now, "a touch is read back verbatim");

        helper.succeed();
    }

    /** An online player is never evicted, even when the threshold math would call them absent. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void onlinePlayerNeverEvicted(GameTestHelper helper) {
        withEviction(true, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootChest(helper, rel);
            // The mock player is fully registered in the server's player list, so the prune on the
            // second open sees them online — a zero-day threshold would otherwise evict everyone.
            ServerPlayer opener = spawnPlayerAt(helper, rel);
            BlockEntityContainerAdapter adapter =
                    new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be);

            NonNullList<ItemStack> first = InstancedLootInteraction.generateAndStore(adapter, opener);
            NonNullList<ItemStack> second = InstancedLootInteraction.generateAndStore(adapter, opener);

            helper.assertTrue(first == second,
                    "an online player's instance must survive the prune on a return visit");

            opener.discard();
            helper.succeed();
        });
    }
}
