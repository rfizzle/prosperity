package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.LootNotification;
import com.rfizzle.prosperity.loot.LootScaling;
import com.rfizzle.prosperity.loot.completion.StructureCompletion;
import com.rfizzle.prosperity.loot.completion.StructureCompletionState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for the structure completion bonus. The gametest world generates no structures, so
 * the live {@code StructureStart} resolution in {@code StructureCompletion#onLootGenerated} is the
 * documented manual in-world check (the same split as structure tier overrides, S-012); everything
 * downstream — the census over explicit piece boxes, the one-time ledger, the bonus placement, and
 * the fanfare — is driven here through {@link StructureCompletion#checkAndAward} against placed
 * containers, mirroring the acceptance criteria of the feature issue: exactly one award in the final
 * container, per-player independence, broken containers shrinking the remaining set, refresh never
 * re-earning, and single-container / blacklisted containers never qualifying.
 *
 * <p>The bonus draw needs an injection-pool entry eligible for the container's table, so awards are
 * checked at the {@code frontier} tier, where the built-in defaults inject into every chest table.
 */
public class StructureCompletionGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 9001L;

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

    /** One box spanning the given relative corners, in absolute coordinates. */
    private List<BoundingBox> boxes(GameTestHelper helper, BlockPos minRel, BlockPos maxRel) {
        return List.of(BoundingBox.fromCorners(helper.absolutePos(minRel), helper.absolutePos(maxRel)));
    }

    private DistanceTier frontier(GameTestHelper helper) {
        DistanceTier tier = LootScaling.tierByName(Prosperity.getConfig(), "frontier");
        helper.assertTrue(tier != null, "the default config must define the frontier tier");
        return tier;
    }

    private boolean award(GameTestHelper helper, BlockEntityContainerAdapter adapter,
            ServerPlayer player, String key, List<BoundingBox> boxes, ResourceLocation structureId) {
        return StructureCompletion.checkAndAward(helper.getLevel(), adapter, player, TABLE,
                frontier(helper), SEED, 0L, key, boxes, structureId);
    }

    private static int nonEmptySlots(NonNullList<ItemStack> inventory) {
        int filled = 0;
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /**
     * Looting every container awards exactly once, in the final container: no award while any
     * container remains, one bonus item on completion, and no re-award afterwards.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void lastContainerAwardsExactlyOnce(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        BlockPos c = new BlockPos(3, 1, 1);
        placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        placeLootContainer(helper, b, Blocks.BARREL, TABLE);
        placeLootContainer(helper, c, Blocks.BARREL, TABLE);
        List<BoundingBox> boxes = boxes(helper, a, c);
        ResourceLocation id = Prosperity.id("test_award_once");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ServerPlayer player = spawnPlayer(helper);
        helper.assertFalse(award(helper, loot(helper, a, player), player, key, boxes, id),
                "no award while two containers remain unlooted");
        helper.assertFalse(award(helper, loot(helper, b, player), player, key, boxes, id),
                "no award while one container remains unlooted");

        BlockEntityContainerAdapter last = loot(helper, c, player);
        NonNullList<ItemStack> inventory = last.data().getInventory(player.getUUID());
        int before = nonEmptySlots(inventory);
        helper.assertTrue(award(helper, last, player, key, boxes, id),
                "looting the final container must award the completion bonus");
        helper.assertTrue(nonEmptySlots(inventory) == before + 1,
                "the award must place exactly one bonus item in the final container");
        helper.assertTrue(StructureCompletionState.get(helper.getLevel())
                        .isCompleted(key, player.getUUID()),
                "the award must be recorded in the completion ledger");

        helper.assertFalse(award(helper, last, player, key, boxes, id),
                "a recorded completion must never award again");
        helper.assertTrue(nonEmptySlots(inventory) == before + 1,
                "a repeat check must not place a second bonus item");

        player.discard();
        helper.succeed();
    }

    /** A second player completing the same structure instance earns their own bonus independently. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void secondPlayerEarnsIndependently(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        placeLootContainer(helper, b, Blocks.BARREL, TABLE);
        List<BoundingBox> boxes = boxes(helper, a, b);
        ResourceLocation id = Prosperity.id("test_second_player");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ServerPlayer first = spawnPlayer(helper);
        loot(helper, a, first);
        helper.assertTrue(award(helper, loot(helper, b, first), first, key, boxes, id),
                "the first player must earn the bonus");

        ServerPlayer second = spawnPlayer(helper);
        loot(helper, a, second);
        helper.assertTrue(award(helper, loot(helper, b, second), second, key, boxes, id),
                "the second player must earn their own bonus for the same structure");
        StructureCompletionState state = StructureCompletionState.get(helper.getLevel());
        helper.assertTrue(state.isCompleted(key, first.getUUID())
                        && state.isCompleted(key, second.getUUID()),
                "both players' completions must be recorded");

        first.discard();
        second.discard();
        helper.succeed();
    }

    /** Breaking a never-looted container shrinks the remaining set instead of blocking completion. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void brokenContainerShrinksRemainingSet(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        BlockPos c = new BlockPos(3, 1, 1);
        placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        placeLootContainer(helper, b, Blocks.BARREL, TABLE);
        placeLootContainer(helper, c, Blocks.BARREL, TABLE);
        List<BoundingBox> boxes = boxes(helper, a, c);
        ResourceLocation id = Prosperity.id("test_broken_container");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ServerPlayer player = spawnPlayer(helper);
        helper.assertFalse(award(helper, loot(helper, a, player), player, key, boxes, id),
                "no award while two containers remain unlooted");
        helper.setBlock(c, Blocks.AIR);
        helper.assertTrue(award(helper, loot(helper, b, player), player, key, boxes, id),
                "a broken unlooted container must no longer count toward completion");

        player.discard();
        helper.succeed();
    }

    /** A refresh clearing per-player container state does not allow re-earning the bonus. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void refreshDoesNotReearnBonus(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        RandomizableContainerBlockEntity beA = placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        RandomizableContainerBlockEntity beB = placeLootContainer(helper, b, Blocks.BARREL, TABLE);
        List<BoundingBox> boxes = boxes(helper, a, b);
        ResourceLocation id = Prosperity.id("test_no_reearn");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ServerPlayer player = spawnPlayer(helper);
        UUID uuid = player.getUUID();
        loot(helper, a, player);
        helper.assertTrue(award(helper, loot(helper, b, player), player, key, boxes, id),
                "the first completion must award");

        // A refresh expiry clears the player's per-container state on reopen; regenerate both.
        ProsperityAttachments.update(beA, data -> data.clearForPlayer(uuid));
        ProsperityAttachments.update(beB, data -> data.clearForPlayer(uuid));
        loot(helper, a, player);
        BlockEntityContainerAdapter relooted = loot(helper, b, player);
        helper.assertFalse(award(helper, relooted, player, key, boxes, id),
                "re-looting the whole structure after a refresh must not re-earn the bonus");

        player.discard();
        helper.succeed();
    }

    /** A lone container is never a "structure completion" — the census total must reach two. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void singleContainerNeverAwards(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        List<BoundingBox> boxes = boxes(helper, a, a);
        ResourceLocation id = Prosperity.id("test_single_container");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ServerPlayer player = spawnPlayer(helper);
        helper.assertFalse(award(helper, loot(helper, a, player), player, key, boxes, id),
                "a single-container structure must never award a completion bonus");
        helper.assertFalse(StructureCompletionState.get(helper.getLevel()).isCompleted(key, player.getUUID()),
                "no completion may be recorded for a non-qualifying structure");

        player.discard();
        helper.succeed();
    }

    /** A double chest counts once (via its primary half), not as two containers. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleChestCountsOnce(GameTestHelper helper) {
        // A NORTH-facing LEFT/RIGHT pair: the west half (smaller x) is the primary.
        BlockPos left = new BlockPos(1, 1, 1);
        BlockPos right = left.east();
        BlockState leftState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState rightState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.setBlock(left, leftState);
        helper.setBlock(right, rightState);
        ChestBlockEntity leftBe = (ChestBlockEntity) helper.getBlockEntity(left);
        ChestBlockEntity rightBe = (ChestBlockEntity) helper.getBlockEntity(right);
        leftBe.setLootTable(TABLE);
        leftBe.setLootTableSeed(SEED);
        rightBe.setLootTable(TABLE);
        rightBe.setLootTableSeed(SEED);

        ServerPlayer player = spawnPlayer(helper);
        // The unopened pair reads as two candidates; census only needs "any unlooted?" so the
        // qualifying count is asserted after the combined instance collapses them onto the primary.
        ServerLevel level = helper.getLevel();
        InstancedLootInteraction.generateAndStoreDouble(level,
                helper.absolutePos(left), leftBe, helper.absolutePos(right), rightBe, player);
        StructureCompletion.Census census = StructureCompletion.census(level, player.getUUID(),
                boxes(helper, left, right), new ChunkPos(helper.absolutePos(left)), null);
        helper.assertTrue(census.complete(), "the looted double chest must read as fully looted");
        helper.assertTrue(census.total() == 1,
                "a double chest must count once, not per half (counted " + census.total() + ")");

        player.discard();
        helper.succeed();
    }

    /** The ledger round-trips through NBT: completions survive a save/load cycle. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void ledgerRoundTripsThroughNbt(GameTestHelper helper) {
        // Regression guard: DimensionDataStorage calls DataFixTypes#update unconditionally on read,
        // so a null type would NPE inside the storage's swallowed load and silently hand back an
        // empty ledger on every restart — re-arming every completion.
        helper.assertTrue(StructureCompletionState.FACTORY.type() != null,
                "the ledger factory must carry a non-null DataFixTypes or reads NPE after restart");

        UUID completed = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String key = "minecraft:stronghold@12,-34";

        StructureCompletionState state = new StructureCompletionState();
        helper.assertTrue(state.markCompleted(key, completed), "a first award must report newly added");
        helper.assertFalse(state.markCompleted(key, completed), "a repeat award must report already present");

        CompoundTag tag = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        StructureCompletionState loaded = StructureCompletionState.load(tag, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.isCompleted(key, completed), "a completion must survive save/load");
        helper.assertFalse(loaded.isCompleted(key, other), "other players must stay un-completed");
        helper.assertFalse(loaded.isCompleted("minecraft:stronghold@0,0", completed),
                "other structure instances must stay un-completed");
        helper.succeed();
    }

    /** The completion fanfare renders headless via its fallback text. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fanfareBuildsClearedMessage(GameTestHelper helper) {
        Component message = LootNotification.buildStructureCleared(
                ResourceLocation.withDefaultNamespace("stronghold"));
        helper.assertTrue(message.getString().equals("✦ Stronghold cleared!"),
                "unexpected fanfare text: " + message.getString());
        helper.succeed();
    }

    /**
     * Blacklisted containers are excluded from the census entirely: they neither hold up completion
     * nor count toward the two-container qualification. Own batch — it mutates the global blacklist.
     */
    @GameTest(batch = "structure_completion_blacklist", template = FabricGameTest.EMPTY_STRUCTURE)
    public void blacklistedContainersAreExcluded(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        placeLootContainer(helper, a, Blocks.BARREL, TABLE);
        placeLootContainer(helper, b, Blocks.BARREL, BuiltInLootTables.ABANDONED_MINESHAFT);
        List<BoundingBox> boxes = boxes(helper, a, b);
        ResourceLocation id = Prosperity.id("test_blacklist");
        String key = StructureCompletion.instanceKey(id, new ChunkPos(helper.absolutePos(a)));

        ProsperityConfig cfg = Prosperity.getConfig();
        List<String> saved = cfg.lootTableBlacklist;
        cfg.lootTableBlacklist = new ArrayList<>(List.of("minecraft:chests/abandoned_mineshaft"));
        cfg.clamp();
        ServerPlayer player = spawnPlayer(helper);
        try {
            StructureCompletion.Census census = StructureCompletion.census(
                    helper.getLevel(), player.getUUID(), boxes,
                    new ChunkPos(helper.absolutePos(a)), null);
            helper.assertFalse(census.complete(),
                    "before looting, the one non-blacklisted container must read unlooted");
            helper.assertFalse(award(helper, loot(helper, a, player), player, key, boxes, id),
                    "with the second container blacklisted, only one qualifies — no award");
        } finally {
            cfg.lootTableBlacklist = saved;
            cfg.clamp();
            player.discard();
        }
        helper.succeed();
    }

    /** The fanfare honors {@code enableLootNotifications}. Own batch — it toggles the global flag. */
    @GameTest(batch = "structure_completion_notify", template = FabricGameTest.EMPTY_STRUCTURE)
    public void fanfareHonorsNotificationGate(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper);
        boolean saved = Prosperity.getConfig().enableLootNotifications;
        try {
            Prosperity.getConfig().enableLootNotifications = false;
            helper.assertTrue(LootNotification.sendStructureCleared(player,
                            ResourceLocation.withDefaultNamespace("stronghold")) == null,
                    "disabled notifications must suppress the completion fanfare");
            Prosperity.getConfig().enableLootNotifications = true;
            helper.assertTrue(LootNotification.sendStructureCleared(player,
                            ResourceLocation.withDefaultNamespace("stronghold")) != null,
                    "enabled notifications must send the completion fanfare");
        } finally {
            Prosperity.getConfig().enableLootNotifications = saved;
        }
        player.discard();
        helper.succeed();
    }
}
