package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * End-to-end wiring for the milestone advancement tab (issue #50): a real generation through the same
 * choke point a live open runs fires {@link com.rfizzle.prosperity.advancement.ProsperityCriteria#INSTANCED_LOOT},
 * which reaches the datagen'd advancements and grants them.
 *
 * <p>These tests exercise the volume chain because a mock player spawns near world origin (the Local
 * tier, in no structure), so the tier and variety predicates would never fire here — that gating is
 * covered deterministically by the unit test {@code InstancedLootTriggerTest}. What only a live server
 * can prove is that the trigger is actually fired from generation and that a threshold predicate flips
 * a real advancement to done at the right count. The container count itself (counts once, ignores
 * return visits, one per double chest) is asserted in {@code LootStatsGameTest}; the trigger reads the
 * same running total, so it inherits that behavior.
 */
public class AdvancementGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 5050L;

    private ServerPlayer spawnListeningPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // placeNewPlayer wires advancement listeners, but reload against the live manager guarantees our
        // freshly-registered trigger has a listener for this player before the first generation fires.
        player.getAdvancements().reload(helper.getLevel().getServer().getAdvancements());
        return player;
    }

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.BARREL);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    private void loot(GameTestHelper helper, BlockPos rel, ServerPlayer player) {
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        BlockEntityContainerAdapter adapter =
                new BlockEntityContainerAdapter(helper.getLevel(), helper.absolutePos(rel), be);
        InstancedLootInteraction.generateAndStore(adapter, player);
    }

    private AdvancementHolder advancement(GameTestHelper helper, String name) {
        MinecraftServer server = helper.getLevel().getServer();
        AdvancementHolder holder = server.getAdvancements().get(Prosperity.id(name));
        helper.assertTrue(holder != null,
                "advancement prosperity:" + name + " must be loaded — run ./gradlew runDatagen and commit it");
        return holder;
    }

    private boolean isDone(ServerPlayer player, AdvancementHolder holder) {
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** The first generation grants the root (min_containers 1) but not the deeper volume milestone. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void firstGenerationGrantsRootOnly(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel);
        ServerPlayer player = spawnListeningPlayer(helper);

        loot(helper, rel, player);

        helper.assertTrue(isDone(player, advancement(helper, "root")),
                "the first instanced generation must grant the root advancement");
        helper.assertFalse(isDone(player, advancement(helper, "volume_10")),
                "one generation must not grant the 10-container milestone");

        player.discard();
        helper.succeed();
    }

    /** Crossing the running-total threshold flips the matching volume milestone to done. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void reachingTenGrantsVolumeMilestone(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel);
        ServerPlayer player = spawnListeningPlayer(helper);

        // Ten counted generations: clear the player's instance between opens so each is a fresh re-roll.
        for (int i = 0; i < 10; i++) {
            loot(helper, rel, player);
            ProsperityAttachments.update(be, data -> data.clearForPlayer(player.getUUID()));
        }

        helper.assertTrue(ProsperityAttachments.stats(player).containersLooted() == 10,
                "ten re-rolls must record ten generations");
        helper.assertTrue(isDone(player, advancement(helper, "volume_10")),
                "reaching 10 containers must grant the 10-container milestone");
        helper.assertFalse(isDone(player, advancement(helper, "volume_50")),
                "10 containers must not yet grant the 50-container milestone");

        player.discard();
        helper.succeed();
    }
}
