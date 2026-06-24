package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.InstancedLootMenu;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for double-chest instancing (S-007). A NORTH-facing double chest is built from an
 * explicit LEFT/RIGHT pair so the connection is deterministic; the left half (smaller x) is the
 * primary. The handler is driven through {@link UseBlockCallback} exactly as a real interaction
 * fires it, covering the combined 54-slot instance, the primary/secondary split, both halves' loot
 * tables being nulled, per-player independence, and vanilla pass-through for a player-placed double.
 */
public class DoubleChestGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4321L;

    private record DoubleChest(BlockPos leftRel, BlockPos rightRel,
            ChestBlockEntity left, ChestBlockEntity right) {
    }

    /** Build a NORTH-facing double chest at {@code leftRel}/{@code leftRel.east()} (left = primary). */
    private DoubleChest placeDoubleChest(GameTestHelper helper, BlockPos leftRel, boolean withLoot) {
        BlockPos rightRel = leftRel.east();
        BlockState left = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.setBlock(leftRel, left);
        helper.setBlock(rightRel, right);

        // The LEFT half's connected direction must point at the RIGHT half, or the pair never formed.
        BlockState placedLeft = helper.getLevel().getBlockState(helper.absolutePos(leftRel));
        helper.assertTrue(placedLeft.getValue(ChestBlock.TYPE) == ChestType.LEFT,
                "the left half must keep its LEFT chest type after placement");

        ChestBlockEntity leftBe = (ChestBlockEntity) helper.getBlockEntity(leftRel);
        ChestBlockEntity rightBe = (ChestBlockEntity) helper.getBlockEntity(rightRel);
        if (withLoot) {
            leftBe.setLootTable(TABLE);
            leftBe.setLootTableSeed(SEED);
            rightBe.setLootTable(TABLE);
            rightBe.setLootTableSeed(SEED);
        }
        return new DoubleChest(leftRel, rightRel, leftBe, rightBe);
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

    /** Opening either half serves one 54-slot instance stored on the primary; both halves are nulled. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void doubleChestServesCombinedInstance(GameTestHelper helper) {
        DoubleChest chest = placeDoubleChest(helper, new BlockPos(1, 1, 1), true);
        ServerPlayer player = spawnPlayerAt(helper, chest.leftRel);

        helper.assertTrue(rightClick(helper, player, chest.rightRel) == InteractionResult.SUCCESS,
                "opening the secondary half of a loot double chest must be intercepted");

        // The served screen is a full double chest.
        helper.assertTrue(player.containerMenu instanceof InstancedLootMenu,
                "an instanced menu must be open after the interaction");
        InstancedLootMenu menu = (InstancedLootMenu) player.containerMenu;
        helper.assertTrue(menu.getRowCount() == 6, "a double chest must serve six rows");
        helper.assertTrue(menu.getContainer().getContainerSize() == 54, "the instance is 54 slots");

        // Inventory lives on the primary (left) half; the secondary only redirects to it.
        InstancedLootData leftData = ProsperityAttachments.get(chest.left);
        InstancedLootData rightData = ProsperityAttachments.get(chest.right);
        helper.assertTrue(leftData != null && rightData != null, "both halves must attach data");
        NonNullList<ItemStack> combined = leftData.getInventory(player.getUUID());
        helper.assertTrue(combined != null && combined.size() == 54,
                "the primary half must hold the combined 54-slot inventory");
        helper.assertTrue(rightData.getInventory(player.getUUID()) == null,
                "the secondary half must hold no inventory of its own");
        helper.assertTrue(helper.absolutePos(chest.leftRel).equals(rightData.getRedirect()),
                "the secondary half must redirect to the primary's position");

        // Both halves' loot tables are nulled so neither drains via hopper (S-006).
        helper.assertTrue(chest.left.getLootTable() == null, "the primary half's loot table must be nulled");
        helper.assertTrue(chest.right.getLootTable() == null, "the secondary half's loot table must be nulled");

        player.discard();
        helper.succeed();
    }

    /** Both halves open the same instance: a change made through one half persists and is seen via the other. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void eitherHalfOpensSameInstance(GameTestHelper helper) {
        DoubleChest chest = placeDoubleChest(helper, new BlockPos(1, 1, 1), true);
        ServerPlayer player = spawnPlayerAt(helper, chest.leftRel);

        helper.assertTrue(rightClick(helper, player, chest.leftRel) == InteractionResult.SUCCESS,
                "opening the primary half must be intercepted");
        InstancedLootMenu opened = (InstancedLootMenu) player.containerMenu;
        opened.getContainer().setItem(40, new ItemStack(Items.DIAMOND, 7));
        player.closeContainer();

        // Reopening via the other half must retrieve the saved state, not a fresh roll.
        helper.assertTrue(rightClick(helper, player, chest.rightRel) == InteractionResult.SUCCESS,
                "reopening via the secondary half must still be intercepted");
        InstancedLootMenu reopened = (InstancedLootMenu) player.containerMenu;
        helper.assertTrue(reopened.getContainer().getContainerSize() == 54,
                "the secondary half serves the same 54-slot instance");
        helper.assertTrue(ItemStack.matches(reopened.getContainer().getItem(40), new ItemStack(Items.DIAMOND, 7)),
                "a change made via one half must be visible through the other");

        player.discard();
        helper.succeed();
    }

    /** Two players opening one double chest each get an independent combined instance. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void eachPlayerGetsIndependentDoubleInstance(GameTestHelper helper) {
        DoubleChest chest = placeDoubleChest(helper, new BlockPos(1, 1, 1), true);
        ServerPlayer playerA = spawnPlayerAt(helper, chest.leftRel);
        ServerPlayer playerB = spawnPlayerAt(helper, chest.leftRel);

        helper.assertTrue(rightClick(helper, playerA, chest.leftRel) == InteractionResult.SUCCESS,
                "the first player's open must be intercepted");
        helper.assertTrue(rightClick(helper, playerB, chest.rightRel) == InteractionResult.SUCCESS,
                "the second player's open must also be intercepted");

        InstancedLootData leftData = ProsperityAttachments.get(chest.left);
        NonNullList<ItemStack> invA = leftData.getInventory(playerA.getUUID());
        NonNullList<ItemStack> invB = leftData.getInventory(playerB.getUUID());
        helper.assertTrue(invA != null && invB != null, "both players must have a combined inventory");
        helper.assertFalse(invA == invB, "each player must own a distinct instance");
        helper.assertTrue(invA.size() == 54 && invB.size() == 54, "each instance spans the full double chest");

        playerA.discard();
        playerB.discard();
        helper.succeed();
    }

    /** A player-placed double chest (no loot tables) is left to vanilla and never instanced. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playerPlacedDoubleChestOpensVanilla(GameTestHelper helper) {
        DoubleChest chest = placeDoubleChest(helper, new BlockPos(1, 1, 1), false);
        ServerPlayer player = spawnPlayerAt(helper, chest.leftRel);

        helper.assertTrue(rightClick(helper, player, chest.leftRel) == InteractionResult.PASS,
                "a player-placed double chest must fall through to vanilla");
        helper.assertTrue(ProsperityAttachments.get(chest.left) == null
                        && ProsperityAttachments.get(chest.right) == null,
                "a non-loot double chest must not attach an instance on either half");

        player.discard();
        helper.succeed();
    }
}
