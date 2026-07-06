package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.InstancedHopperMenu;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.UnlootedContainers;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload.Entry;
import java.util.List;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the block-hopper half of the instanced-loot loop (S-045). A block
 * {@code HopperBlockEntity} is a 5-slot {@link RandomizableContainerBlockEntity}, so a modded loot
 * table can target it; it is admitted into the {@link UseBlockCallback} gate alongside the
 * multiple-of-nine sizes and served through the same {@link InstancedHopperMenu} the hopper minecart
 * uses. These tests drive the callback exactly as a real interaction would, then assert per-player
 * instancing, table nullification, the served menu, the unlooted-indicator scan, and the destruction
 * cleanup gate — the same surface the chest and minecart paths cover.
 */
public class BlockHopperInstancingGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 1234L;

    private RandomizableContainerBlockEntity placeLootHopper(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.HOPPER);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    // makeMockServerPlayerInLevel is the only factory that yields a connected ServerPlayer, which
    // serveInstance needs to open the menu; makeMockPlayer returns a connection-less Player. The
    // method is marked for removal in a later version but is the supported headless factory here.
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

    /**
     * Two players opening one loot-bearing block hopper each get their own private, independent 5-slot
     * inventory served through a hopper menu, and the vanilla loot table is severed on the first open.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void blockHopperInstancesPerPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootHopper(helper, rel);
        helper.assertTrue(be.getContainerSize() == 5, "a block hopper must be 5 slots");

        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        ServerPlayer playerB = spawnPlayerAt(helper, rel);

        helper.assertTrue(ProsperityAttachments.get(be) == null,
                "a never-opened loot hopper must carry no attachment up front");
        helper.assertTrue(rightClick(helper, playerA, rel) == InteractionResult.SUCCESS,
                "opening a loot hopper must be intercepted");
        helper.assertTrue(playerA.containerMenu instanceof InstancedHopperMenu,
                "a 5-slot block hopper must be served through a hopper menu");
        helper.assertTrue(be.getLootTable() == null,
                "the vanilla loot table must be nulled on first open");
        helper.assertTrue(be.getLootTableSeed() == 0L,
                "the vanilla loot seed must be cleared on first open");

        helper.assertTrue(rightClick(helper, playerB, rel) == InteractionResult.SUCCESS,
                "the second player's open must also be intercepted");

        InstancedLootData data = ProsperityAttachments.get(be);
        helper.assertTrue(data != null, "opening must attach instanced-loot data");
        NonNullList<ItemStack> invA = data.getInventory(playerA.getUUID());
        NonNullList<ItemStack> invB = data.getInventory(playerB.getUUID());
        helper.assertTrue(invA != null && invB != null, "both players must have a generated inventory");
        helper.assertTrue(invA.size() == 5 && invB.size() == 5,
                "a block hopper instance must be 5 slots");
        helper.assertTrue(data.playerIds().size() == 2, "both players' UUIDs must be stored");
        helper.assertFalse(invA == invB, "each player must own a distinct inventory instance");

        playerA.discard();
        playerB.discard();
        helper.succeed();
    }

    /**
     * The unlooted-indicator scan (S-009) includes an ungenerated loot hopper sized to 5 slots, and
     * drops it for a player once they have generated their instance.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void indicatorScanCoversBlockHopper(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootHopper(helper, rel);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        ChunkPos chunk = new ChunkPos(abs);
        // Assert per position rather than whole-chunk counts: gametest structures pack several into one
        // chunk and run concurrently, so a neighbour's loot container can share this chunk (same pattern
        // as UnlootedSyncGameTest).
        List<Entry> before = UnlootedContainers.scanChunk(level, chunk, player.getUUID());
        helper.assertTrue(entryAt(before, chunk, abs), "an ungenerated loot hopper must show its indicator");
        helper.assertTrue(slotsAt(before, chunk, abs) == 5, "the hopper indicator must be sized to 5 slots");

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening the loot hopper must be intercepted");
        List<Entry> after = UnlootedContainers.scanChunk(level, chunk, player.getUUID());
        helper.assertFalse(entryAt(after, chunk, abs), "a generated hopper must drop the player's indicator");

        player.discard();
        helper.succeed();
    }

    /** Whether {@code entries} contains an entry at the absolute position {@code abs}. */
    private boolean entryAt(List<Entry> entries, ChunkPos chunk, BlockPos abs) {
        return entries.stream().anyMatch(e -> e.toBlockPos(chunk).equals(abs));
    }

    /** The reported slot count of the entry at {@code abs}, or {@code -1} if there is none. */
    private int slotsAt(List<Entry> entries, ChunkPos chunk, BlockPos abs) {
        return entries.stream().filter(e -> e.toBlockPos(chunk).equals(abs))
                .findFirst().map(Entry::slots).orElse(-1);
    }

    /**
     * Breaking a generated loot hopper removes its block entity (and its attachment) through the real
     * {@code LevelChunk#removeBlockEntity} mixin path (S-008), and the removal gate classifies it as a
     * loot container so its indicator is dropped.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void breakGeneratedLootHopperCleansUp(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootHopper(helper, rel);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening the loot hopper must generate the instance");
        helper.assertTrue(
                InstancedLootInteraction.isLootContainer(be, ProsperityAttachments.get(be)),
                "a generated loot hopper must read as a loot container before the break");
        player.closeContainer();

        // destroyBlock drives the real removeBlockEntity path, so the removal mixin fires as it would
        // for any in-world destruction; the packet emission is unobservable headlessly (canSend false).
        helper.getLevel().destroyBlock(helper.absolutePos(rel), false);
        helper.assertTrue(
                helper.getLevel().getBlockEntity(helper.absolutePos(rel)) == null,
                "breaking the hopper must remove its block entity (its attachment dies with it)");

        player.discard();
        helper.succeed();
    }
}
