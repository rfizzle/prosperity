package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the loot-table blacklist (S-015, SPEC §7). A blacklisted loot table is excluded
 * from all Prosperity behavior: its container opens with full vanilla behavior (no instance, no
 * loot-table nullification) and never appears in the unlooted-indicator scan, while a non-blacklisted
 * container alongside it still instances normally.
 *
 * <p>Runs in its own batch because it mutates the global {@code lootTableBlacklist}; batches run
 * sequentially, so the toggle cannot race the default batch.
 */
public class BlacklistGameTest implements FabricGameTest {

    private static final String BATCH = "blacklist";
    private static final ResourceKey<LootTable> BLACKLISTED = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final ResourceKey<LootTable> ALLOWED = BuiltInLootTables.ABANDONED_MINESHAFT;
    private static final long SEED = 1234L;

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel,
            Block block, ResourceKey<LootTable> table) {
        helper.setBlock(rel, block);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(table);
        be.setLootTableSeed(SEED);
        return be;
    }

    @SuppressWarnings("removal")
    private ServerPlayer spawnRealPlayerAt(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return player;
    }

    private InteractionResult rightClick(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
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

    private void withBlacklist(List<String> patterns, Runnable body) {
        List<String> saved = Prosperity.getConfig().lootTableBlacklist;
        try {
            Prosperity.getConfig().lootTableBlacklist = new ArrayList<>(patterns);
            Prosperity.getConfig().clamp(); // rebuilds the cached matcher
            body.run();
        } finally {
            Prosperity.getConfig().lootTableBlacklist = saved;
            Prosperity.getConfig().clamp();
        }
    }

    /** A blacklisted (exact-id) container opens vanilla: PASS, no attachment, loot table intact. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void exactBlacklistOpensVanilla(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST, BLACKLISTED);
        ServerPlayer player = spawnRealPlayerAt(helper, rel);

        withBlacklist(List.of(BLACKLISTED.location().toString()), () -> {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.PASS,
                    "a blacklisted container must pass through to vanilla");
            helper.assertTrue(ProsperityAttachments.get(be) == null,
                    "a blacklisted container must not attach instanced-loot data");
            helper.assertTrue(be.getLootTable() == BLACKLISTED,
                    "a blacklisted container must keep its vanilla loot table");
            helper.assertFalse(entryAt(scan(helper, rel, player.getUUID()), helper, rel),
                    "a blacklisted container must not appear in the unlooted scan");
        });

        player.discard();
        helper.succeed();
    }

    /** A namespace wildcard ({@code minecraft:*}) excludes the container just like an exact id. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void wildcardBlacklistOpensVanilla(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST, BLACKLISTED);
        ServerPlayer player = spawnRealPlayerAt(helper, rel);

        withBlacklist(List.of("minecraft:*"), () -> {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.PASS,
                    "a wildcard-blacklisted container must pass through to vanilla");
            helper.assertTrue(ProsperityAttachments.get(be) == null,
                    "a wildcard-blacklisted container must not attach instanced-loot data");
            helper.assertFalse(entryAt(scan(helper, rel, player.getUUID()), helper, rel),
                    "a wildcard-blacklisted container must not appear in the unlooted scan");
        });

        player.discard();
        helper.succeed();
    }

    /** With a blacklist active, a non-matching container still instances and nulls its table. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void nonBlacklistedContainerStillInstances(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST, ALLOWED);
        ServerPlayer player = spawnRealPlayerAt(helper, rel);

        withBlacklist(List.of(BLACKLISTED.location().toString()), () -> {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "a non-blacklisted container must still instance");
            helper.assertTrue(ProsperityAttachments.get(be) != null
                            && ProsperityAttachments.get(be).getInventory(player.getUUID()) != null,
                    "a non-blacklisted open must generate and store the player's inventory");
            helper.assertTrue(be.getLootTable() == null,
                    "a non-blacklisted first open must null the vanilla loot table (S-006)");
        });

        player.discard();
        helper.succeed();
    }

    /** An empty blacklist (the default) instances everything. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void emptyBlacklistInstancesEverything(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST, BLACKLISTED);
        ServerPlayer player = spawnRealPlayerAt(helper, rel);

        withBlacklist(List.of(), () -> {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "an empty blacklist must instance every loot container");
            helper.assertTrue(ProsperityAttachments.get(be) != null,
                    "an empty blacklist must let the container instance");
        });

        player.discard();
        helper.succeed();
    }
}
