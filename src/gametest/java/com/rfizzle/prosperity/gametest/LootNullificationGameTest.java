package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
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
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for loot-table nullification and the unpack-safety mixin (S-006). After the first
 * generation the block entity's vanilla loot table is severed, and the mixin cancels any leftover
 * {@code unpackLootTable} call, so a hopper or comparator can never drain the global loot.
 */
public class LootNullificationGameTest implements FabricGameTest {

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

    private boolean allEmpty(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** First generation nulls the vanilla loot table and leaves the shared inventory empty. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void firstOpenNullsLootTable(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening a loot chest must be intercepted");

        helper.assertTrue(be.getLootTable() == null, "the vanilla loot table must be nulled on first open");
        helper.assertTrue(be.getLootTableSeed() == 0L, "the vanilla loot seed must be cleared on first open");
        helper.assertTrue(allEmpty(be), "the shared vanilla inventory must stay empty (a hopper sees nothing)");
        helper.assertTrue(AbstractContainerMenu.getRedstoneSignalFromContainer(be) == 0,
                "a comparator must read zero from an instanced container");

        player.discard();
        helper.succeed();
    }

    /** The mixin cancels a direct unpack on a container the mod has generated. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unpackCancelledForGeneratedContainer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);

        // Mark generated without nulling the field, so only the mixin can stop vanilla unpack here.
        ProsperityAttachments.update(be, data -> data.markGenerated(TABLE, SEED));
        be.unpackLootTable(null);

        helper.assertTrue(be.getLootTable() == TABLE,
                "the mixin must cancel before vanilla clears the loot table");
        helper.assertTrue(allEmpty(be), "vanilla must not fill the shared inventory once generated");
        helper.succeed();
    }

    /** The mixin leaves an ungenerated container alone, so vanilla unpack still runs. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unpackRunsForUngeneratedContainer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);

        be.unpackLootTable(null);

        helper.assertTrue(be.getLootTable() == null,
                "vanilla unpack must run (and clear the table) for a container the mod has not taken over");
        helper.succeed();
    }

    /** A hopper beneath a generated container extracts nothing over time. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hopperCannotExtractFromGenerated(GameTestHelper helper) {
        BlockPos chestRel = new BlockPos(1, 2, 1);
        BlockPos hopperRel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, chestRel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, chestRel);
        helper.assertTrue(rightClick(helper, player, chestRel) == InteractionResult.SUCCESS,
                "opening a loot chest must be intercepted");

        helper.setBlock(hopperRel, Blocks.HOPPER);

        // Let the hopper run through several transfer cooldowns; a missing guard would let vanilla
        // unpack refill the chest and the hopper pull from it.
        helper.runAfterDelay(20L, () -> {
            Container chest = (Container) helper.getBlockEntity(chestRel);
            Container hopper = (Container) helper.getBlockEntity(hopperRel);
            helper.assertTrue(allEmpty(chest), "the chest's shared inventory must stay empty");
            helper.assertTrue(allEmpty(hopper), "the hopper must extract nothing from an instanced chest");
            player.discard();
            helper.succeed();
        });
    }

    /** Regenerating after a clear yields the same loot for the same (seed, player). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void regenerationIsDeterministic(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening a loot chest must be intercepted");
        InstancedLootData data = ProsperityAttachments.get(be);
        helper.assertTrue(data != null, "opening must attach instanced-loot data");
        NonNullList<ItemStack> first = copyOf(data.getInventory(player.getUUID()));

        // Refresh: clear the player's entry and reopen. The preserved seed + UUID must roll the same.
        ProsperityAttachments.update(be, d -> d.clearForPlayer(player.getUUID()));
        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "reopening after a clear must regenerate");
        NonNullList<ItemStack> second = data.getInventory(player.getUUID());

        helper.assertTrue(second != null && second.size() == first.size(),
                "regeneration must produce the same number of slots");
        for (int slot = 0; slot < first.size(); slot++) {
            helper.assertTrue(ItemStack.matches(first.get(slot), second.get(slot)),
                    "regeneration must be deterministic for a given (seed, UUID)");
        }

        player.discard();
        helper.succeed();
    }

    private NonNullList<ItemStack> copyOf(NonNullList<ItemStack> source) {
        NonNullList<ItemStack> copy = NonNullList.withSize(source.size(), ItemStack.EMPTY);
        for (int slot = 0; slot < source.size(); slot++) {
            copy.set(slot, source.get(slot).copy());
        }
        return copy;
    }
}
