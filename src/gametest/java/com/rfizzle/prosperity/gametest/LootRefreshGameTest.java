package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload.Entry;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for per-player loot refresh (S-016, SPEC §9). A container's instance expires after a
 * configurable cooldown; on the next interaction it is rolled fresh, and the unlooted indicator
 * reappears for that player in the meantime.
 *
 * <p>The expired cases run with a zero-day cooldown so a just-generated instance is immediately past
 * its cooldown — this exercises the clear-and-regenerate integration without advancing the 24 000+
 * ticks a real cooldown spans (the exact day-boundary math is covered by the pure-JUnit
 * {@code LootRefreshTest}). Backdating the stored tick instead would underflow below zero on a
 * fresh world and collide with the never-generated sentinel.
 *
 * <p>Runs in its own batch because it mutates the global {@code enableLootRefresh}/{@code lootRefreshDays}
 * config; batches run sequentially, so the toggle cannot race tests in the default batch.
 */
public class LootRefreshGameTest implements FabricGameTest {

    private static final String BATCH = "loot_refresh";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4242L;

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel,
            Block block) {
        helper.setBlock(rel, block);
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

    private BlockEntityContainerAdapter adapterFor(GameTestHelper helper, BlockPos rel,
            RandomizableContainerBlockEntity be) {
        return new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be);
    }

    private boolean entryAt(List<Entry> entries, GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ChunkPos chunkPos = new ChunkPos(abs);
        return entries.stream().anyMatch(e -> e.toBlockPos(chunkPos).equals(abs));
    }

    private List<Entry> scan(GameTestHelper helper, BlockPos rel, UUID player) {
        ChunkPos chunkPos = new ChunkPos(helper.absolutePos(rel));
        return UnlootedContainers.scanChunk(helper.getLevel(), chunkPos, player);
    }

    private void withRefresh(boolean enabled, int days, Runnable body) {
        boolean savedEnabled = Prosperity.getConfig().enableLootRefresh;
        int savedDays = Prosperity.getConfig().lootRefreshDays;
        try {
            Prosperity.getConfig().enableLootRefresh = enabled;
            Prosperity.getConfig().lootRefreshDays = days;
            body.run();
        } finally {
            Prosperity.getConfig().enableLootRefresh = savedEnabled;
            Prosperity.getConfig().lootRefreshDays = savedDays;
        }
    }

    /** Once the cooldown elapses, the next open clears the stale instance and rolls a fresh one. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void expiredInstanceRegeneratesOnReopen(GameTestHelper helper) {
        withRefresh(true, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);
            UUID uuid = player.getUUID();

            BlockEntityContainerAdapter adapter = adapterFor(helper, rel, be);
            NonNullList<ItemStack> first = InstancedLootInteraction.generateAndStore(adapter, player);
            helper.assertTrue(ProsperityAttachments.get(be).getInventory(uuid) == first,
                    "first generation must store the rolled inventory");

            NonNullList<ItemStack> second = InstancedLootInteraction.generateAndStore(adapter, player);
            helper.assertFalse(first == second,
                    "an expired instance must be re-rolled into a new inventory, not retained");
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(ProsperityAttachments.get(be).getLastGeneratedTick(uuid) == now,
                    "regeneration must stamp the current game time as the new last-generated tick");

            player.discard();
            helper.succeed();
        });
    }

    /** Before the cooldown elapses, a return visit retrieves the same instance unchanged. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void unexpiredInstanceRetainedOnReopen(GameTestHelper helper) {
        withRefresh(true, 1, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);

            BlockEntityContainerAdapter adapter = adapterFor(helper, rel, be);
            NonNullList<ItemStack> first = InstancedLootInteraction.generateAndStore(adapter, player);
            NonNullList<ItemStack> second = InstancedLootInteraction.generateAndStore(adapter, player);

            helper.assertTrue(first == second,
                    "within the cooldown, a return visit must retrieve the same instance");

            player.discard();
            helper.succeed();
        });
    }

    /**
     * Disabled refresh still records the generation tick (so a later enable applies retroactively) and
     * never expires, even with a zero-day cooldown that would otherwise fire immediately.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void disabledRefreshRecordsTickAndNeverExpires(GameTestHelper helper) {
        withRefresh(false, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);
            UUID uuid = player.getUUID();

            BlockEntityContainerAdapter adapter = adapterFor(helper, rel, be);
            NonNullList<ItemStack> first = InstancedLootInteraction.generateAndStore(adapter, player);
            helper.assertTrue(ProsperityAttachments.get(be).getLastGeneratedTick(uuid) >= 0,
                    "disabled refresh must still record the generation tick for a later enable");

            NonNullList<ItemStack> second = InstancedLootInteraction.generateAndStore(adapter, player);
            helper.assertTrue(first == second,
                    "with refresh disabled, an instance must be retained, never re-rolled");

            player.discard();
            helper.succeed();
        });
    }

    /** A freshly looted container (cooldown not yet elapsed) shows no unlooted sparkle. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void freshInstanceHidesFromScan(GameTestHelper helper) {
        withRefresh(true, 1, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);

            InstancedLootInteraction.generateAndStore(adapterFor(helper, rel, be), player);
            helper.assertFalse(entryAt(scan(helper, rel, player.getUUID()), helper, rel),
                    "a freshly looted container must not show the unlooted sparkle");

            player.discard();
            helper.succeed();
        });
    }

    /** An expired instance reappears in the indicator scan before the player reopens it. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void expiredContainerReappearsInScan(GameTestHelper helper) {
        withRefresh(true, 0, () -> {
            BlockPos rel = new BlockPos(1, 1, 1);
            RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
            ServerPlayer player = spawnPlayerAt(helper, rel);

            InstancedLootInteraction.generateAndStore(adapterFor(helper, rel, be), player);
            helper.assertTrue(entryAt(scan(helper, rel, player.getUUID()), helper, rel),
                    "once the cooldown elapses, the container reappears in the unlooted scan");

            player.discard();
            helper.succeed();
        });
    }
}
