package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.FakePlayers;
import net.fabricmc.fabric.api.entity.FakePlayer;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the fake-player open guard (S-034). An automation mod opens containers through a
 * fake {@link ServerPlayer}; the guard must pass those through to vanilla untouched — no generation,
 * no loot-table nullification, no attachment — so a real player visiting later still gets a full
 * first-visit instance. The guard is driven through the real {@link UseBlockCallback} exactly as a
 * live interaction would fire it.
 */
public class FakePlayerGuardGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 1234L;

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel,
            Block block) {
        helper.setBlock(rel, block);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    // makeMockServerPlayerInLevel is the only factory that yields a connected, player-list-registered
    // ServerPlayer — exactly the "real opener" the guard must let through (and openMenu needs it).
    @SuppressWarnings("removal")
    private ServerPlayer spawnRealPlayerAt(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return player;
    }

    private FakePlayer spawnFakePlayerAt(GameTestHelper helper, BlockPos rel) {
        FakePlayer player = FakePlayer.get(helper.getLevel());
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

    /** A fake opener generates nothing: no interception, no attachment, loot table left intact. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void fakeOpenDoesNothing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        FakePlayer fake = spawnFakePlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, fake, rel) == InteractionResult.PASS,
                "a fake-player open must pass through to vanilla");
        helper.assertTrue(ProsperityAttachments.get(be) == null,
                "a fake-player open must not attach instanced-loot data");
        helper.assertTrue(be.getLootTable() == TABLE,
                "a fake-player open must leave the vanilla loot table intact");
        helper.assertTrue(be.getLootTableSeed() == SEED,
                "a fake-player open must leave the vanilla loot-table seed intact");

        fake.discard();
        helper.succeed();
    }

    /** After a fake open did nothing, a real player still gets a full first-visit generation. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void realPlayerAfterFakeStillGenerates(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);

        FakePlayer fake = spawnFakePlayerAt(helper, rel);
        rightClick(helper, fake, rel);
        fake.discard();

        ServerPlayer real = spawnRealPlayerAt(helper, rel);
        helper.assertTrue(rightClick(helper, real, rel) == InteractionResult.SUCCESS,
                "a real player must still get a first-visit generation after a fake open");
        helper.assertTrue(ProsperityAttachments.get(be) != null
                        && ProsperityAttachments.get(be).getInventory(real.getUUID()) != null,
                "a real open must generate and store the player's inventory");
        helper.assertTrue(be.getLootTable() == null,
                "first real generation must null the vanilla loot table (S-006)");

        real.discard();
        helper.succeed();
    }

    /** The predicate classifies a connected, listed player as real and a Fabric fake as fake. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void predicateClassifiesRealVsFake(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        ServerPlayer real = spawnRealPlayerAt(helper, rel);
        FakePlayer fake = spawnFakePlayerAt(helper, rel);

        helper.assertFalse(FakePlayers.isFakePlayer(real),
                "a connected, player-list-registered player must not be treated as fake");
        helper.assertTrue(FakePlayers.isFakePlayer(fake),
                "a Fabric FakePlayer must be treated as fake");

        real.discard();
        fake.discard();
        helper.succeed();
    }
}
