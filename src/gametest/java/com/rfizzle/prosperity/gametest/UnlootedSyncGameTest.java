package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload.Entry;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for the server-side indicator scan (S-009). {@link UnlootedContainers#scanChunk}
 * answers a player's per-chunk request with exactly the loot containers that player has not generated;
 * these drive the scan directly (a headless mock player's {@code canSend} is false, so the
 * {@code UnlootedContainersS2C}/{@code ContainerLootedS2C} packets cannot be observed in-test).
 */
public class UnlootedSyncGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 909L;

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

    private List<Entry> scan(GameTestHelper helper, BlockPos rel, UUID player) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunkPos = new ChunkPos(helper.absolutePos(rel));
        return UnlootedContainers.scanChunk(level, chunkPos, player);
    }

    private boolean entryAt(List<Entry> entries, GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ChunkPos chunkPos = new ChunkPos(abs);
        return entries.stream().anyMatch(e -> e.toBlockPos(chunkPos).equals(abs));
    }

    /** The reported slot count of the entry at {@code rel}, or {@code -1} if there is none. */
    private int slotsAt(List<Entry> entries, GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ChunkPos chunkPos = new ChunkPos(abs);
        return entries.stream().filter(e -> e.toBlockPos(chunkPos).equals(abs))
                .findFirst().map(Entry::slots).orElse(-1);
    }

    /** One player generating loot shrinks only their own unlooted set; the other still sees the chest. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void scanIsPerPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        UUID b = UUID.randomUUID();

        // Assert per position rather than whole-chunk counts: gametest structures pack several into
        // one chunk and run concurrently, so a neighbour's loot chest can share this chunk.
        List<Entry> beforeA = scan(helper, rel, playerA.getUUID());
        helper.assertTrue(entryAt(beforeA, helper, rel),
                "player A should see the unlooted chest before opening it");
        helper.assertTrue(slotsAt(beforeA, helper, rel) == 27, "a single chest should report 27 slots");
        helper.assertTrue(entryAt(scan(helper, rel, b), helper, rel),
                "player B should also see the unlooted chest");

        InstancedLootInteraction.generateAndStore(
                new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be), playerA);

        helper.assertFalse(entryAt(scan(helper, rel, playerA.getUUID()), helper, rel),
                "player A should no longer see the chest after generating");
        helper.assertTrue(entryAt(scan(helper, rel, b), helper, rel),
                "player B's unlooted set should be unaffected by player A generating");
        helper.succeed();
    }

    /** A double chest yields a single entry, anchored at the primary half and sized to 54 slots. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleChestYieldsOnePrimaryEntry(GameTestHelper helper) {
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
        ((ChestBlockEntity) helper.getBlockEntity(leftRel)).setLootTable(TABLE);
        ((ChestBlockEntity) helper.getBlockEntity(rightRel)).setLootTable(TABLE);

        // leftRel (smaller X) is the primary half, so the single indicator anchors there. Assert per
        // position: a neighbouring test's chest may share this chunk, so a whole-chunk count is unsafe.
        List<Entry> entries = scan(helper, leftRel, UUID.randomUUID());
        helper.assertTrue(entryAt(entries, helper, leftRel),
                "the double chest's indicator should anchor at the primary (left) half");
        helper.assertFalse(entryAt(entries, helper, rightRel),
                "the secondary (right) half should not emit its own indicator");
        helper.assertTrue(slotsAt(entries, helper, leftRel) == 54, "a double chest should report 54 slots");
        helper.succeed();
    }

    /** Player-placed storage (no loot table) is never instanced and never shows an indicator. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void plainStorageIsExcluded(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        helper.assertFalse(entryAt(scan(helper, rel, UUID.randomUUID()), helper, rel),
                "a player-placed chest with no loot table should never show an indicator");
        helper.succeed();
    }
}
