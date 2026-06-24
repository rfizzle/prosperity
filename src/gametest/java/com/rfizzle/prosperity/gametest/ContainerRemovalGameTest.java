package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.InstancedLootMenu;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for container destruction cleanup (S-008). Breaking is driven through
 * {@link ServerLevel#destroyBlock} so the real {@code LevelChunk#removeBlockEntity} path &mdash; and
 * thus the removal mixin &mdash; fires exactly as it would for any in-world destruction. The packet
 * emission itself is unobservable headlessly (a mock player's channel is unknown, so {@code canSend}
 * is false), so these assert the observable contract: the removal gate ({@code isLootContainer}), that
 * destroying generated/ungenerated loot containers and plain storage all clean up without error, and
 * that breaking one half of a double chest leaves the surviving half serving loot as a single chest.
 */
public class ContainerRemovalGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 5678L;

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

    private InteractionResult rightClick(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }

    private void breakBlock(GameTestHelper helper, BlockPos rel) {
        helper.getLevel().destroyBlock(helper.absolutePos(rel), false);
    }

    // GameTestHelper#getBlockEntity throws when none is present, so query the level directly (it
    // returns null) to assert a block entity is gone.
    private void assertNoBlockEntity(GameTestHelper helper, BlockPos rel, String message) {
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(rel)) == null, message);
    }

    /** Build a NORTH-facing double chest at {@code leftRel}/{@code leftRel.east()} (left = primary). */
    private void placeDoubleLootChest(GameTestHelper helper, BlockPos leftRel, BlockPos rightRel) {
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
    }

    /** Breaking a generated loot chest removes its block entity (and its attachment) without error. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakGeneratedLootChestCleansUp(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening the loot chest must generate the instance");
        helper.assertTrue(InstancedLootInteraction.isLootContainer(be, ProsperityAttachments.get(be)),
                "a generated container must read as an instanced loot container before the break");
        player.closeContainer();

        breakBlock(helper, rel);
        assertNoBlockEntity(helper, rel,
                "breaking the chest must remove its block entity (its attachment dies with it)");

        player.discard();
        helper.succeed();
    }

    /** Breaking a loot chest nobody has opened yet is handled safely (loot table still present). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakUngeneratedLootChestIsSafe(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);

        helper.assertTrue(ProsperityAttachments.get(be) == null,
                "a never-opened loot chest carries no attachment");
        helper.assertTrue(InstancedLootInteraction.isLootContainer(be, null),
                "an unopened loot chest still reads as a loot container via its live loot table");

        breakBlock(helper, rel);
        assertNoBlockEntity(helper, rel,
                "breaking an ungenerated loot chest must remove its block entity without error");

        helper.succeed();
    }

    /** Plain storage is not a loot container, so its removal notifies nothing; the break still cleans up. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakPlainStorageIsNotALootContainer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);

        helper.assertFalse(InstancedLootInteraction.isLootContainer(be, ProsperityAttachments.get(be)),
                "player-placed storage must not read as a loot container");

        breakBlock(helper, rel);
        assertNoBlockEntity(helper, rel,
                "breaking plain storage must remove its block entity without error");

        helper.succeed();
    }

    /** Breaking the secondary half leaves the primary serving its instance as a single chest. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakDoubleSecondaryLeavesPrimaryConsistent(GameTestHelper helper) {
        BlockPos leftRel = new BlockPos(1, 1, 1);
        BlockPos rightRel = leftRel.east();
        placeDoubleLootChest(helper, leftRel, rightRel);
        ServerPlayer player = spawnPlayerAt(helper, leftRel);

        helper.assertTrue(rightClick(helper, player, leftRel) == InteractionResult.SUCCESS,
                "opening the double chest must generate the combined instance");
        player.closeContainer();

        breakBlock(helper, rightRel);
        assertNoBlockEntity(helper, rightRel, "breaking the secondary half removes its block entity");
        BlockState primaryState = helper.getLevel().getBlockState(helper.absolutePos(leftRel));
        helper.assertTrue(primaryState.getValue(ChestBlock.TYPE) == ChestType.SINGLE,
                "the surviving primary half must degrade to a single chest");

        // The surviving primary still serves the player's instance, now as a single 27-slot chest.
        helper.assertTrue(rightClick(helper, player, leftRel) == InteractionResult.SUCCESS,
                "the surviving primary half must still serve its instance");
        helper.assertTrue(player.containerMenu instanceof InstancedLootMenu menu
                        && menu.getContainer().getContainerSize() == 27,
                "the surviving primary half must serve a single 27-slot instance");

        player.discard();
        helper.succeed();
    }

    /** Breaking the primary half leaves the secondary regenerating its own loot as a single chest. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakDoublePrimaryLeavesSecondaryConsistent(GameTestHelper helper) {
        BlockPos leftRel = new BlockPos(1, 1, 1);
        BlockPos rightRel = leftRel.east();
        placeDoubleLootChest(helper, leftRel, rightRel);
        ServerPlayer player = spawnPlayerAt(helper, leftRel);

        helper.assertTrue(rightClick(helper, player, leftRel) == InteractionResult.SUCCESS,
                "opening the double chest must generate the combined instance");
        player.closeContainer();

        breakBlock(helper, leftRel);
        assertNoBlockEntity(helper, leftRel, "breaking the primary half removes its block entity");
        BlockState secondaryState = helper.getLevel().getBlockState(helper.absolutePos(rightRel));
        helper.assertTrue(secondaryState.getValue(ChestBlock.TYPE) == ChestType.SINGLE,
                "the surviving secondary half must degrade to a single chest");

        // The surviving secondary regenerates its own loot (it never stored an inventory of its own);
        // its now-dangling redirect is never read on the single-container open path.
        helper.assertTrue(rightClick(helper, player, rightRel) == InteractionResult.SUCCESS,
                "the surviving secondary half must still serve a single instance");
        helper.assertTrue(player.containerMenu instanceof InstancedLootMenu menu
                        && menu.getContainer().getContainerSize() == 27,
                "the surviving secondary half must serve a single 27-slot instance");

        player.discard();
        helper.succeed();
    }
}
