package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.InstancedLootMenu;
import com.rfizzle.prosperity.loot.PartyLootKeys;
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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Runtime checks for party loot mode (issue #53): two players on one scoreboard team share a single
 * loot instance per container, teamless players keep individual instances, a shared instance already
 * open is refused rather than desynced, and a player who leaves the team still resolves to the shared
 * instance for containers they opened with it. Driven through {@link UseBlockCallback} exactly as a
 * real interaction fires it, so the resolver, lock, and shared storage are exercised end to end.
 *
 * <p>Runs in its own batch because it mutates the global {@code partyLootMode} config and a shared
 * scoreboard team; batches run sequentially, so neither can race tests in the default batch, and every
 * assertion lives in one synchronous method (with a {@code finally} that restores the config and drops
 * the team) so tests within this batch cannot race each other either.
 */
public class PartyLootGameTest implements FabricGameTest {

    private static final String BATCH = "party_loot";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4242L;
    private static final String TEAM = "raiders";

    private RandomizableContainerBlockEntity placeLootContainer(GameTestHelper helper, BlockPos rel,
            Block block) {
        helper.setBlock(rel, block);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        return be;
    }

    // makeMockServerPlayerInLevel is the only factory that yields a connected ServerPlayer, which
    // serveInstance needs to open the menu; every such player shares the name "test-mock-player" but
    // has a distinct UUID, so two on one team both resolve to the team key by name.
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

    private PlayerTeam joinTeam(GameTestHelper helper, ServerPlayer player) {
        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM);
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
        return team;
    }

    // Test teardown restores every piece of shared, cross-test state this batch touches: the placed
    // container (so a generated instance cannot leak into another batch's per-chunk indicator scan, which
    // reuses the same world region), the scoreboard team (mock players all share the name
    // "test-mock-player", so a lingering membership would follow the next test's player), and the config.
    private void cleanup(GameTestHelper helper, boolean savedMode) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM);
        if (team != null) {
            scoreboard.removePlayerTeam(team);
        }
        Prosperity.getConfig().partyLootMode = savedMode;
    }

    /** The team-key derivation the sharing hinges on: deterministic and collision-safe (type 3). */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void teamKeyIsDeterministicAndTypeThree(GameTestHelper helper) {
        helper.assertTrue(PartyLootKeys.teamKey(TEAM).equals(PartyLootKeys.teamKey(TEAM)),
                "the same team must always map to the same loot key");
        helper.assertFalse(PartyLootKeys.teamKey(TEAM).equals(PartyLootKeys.teamKey("miners")),
                "distinct teams must map to distinct loot keys");
        helper.assertTrue(PartyLootKeys.teamKey(TEAM).version() == 3,
                "a team key must be a name-based (type 3) UUID so it cannot alias a player's type-4 UUID");
        helper.succeed();
    }

    /**
     * Two players on one team share a single inventory per container: what one takes is gone for the
     * other, the instance is keyed on the team (not either UUID), and the cooldown tick is shared.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void teammatesShareOneInventory(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = true;
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer alice = spawnPlayerAt(helper, rel);
        ServerPlayer bob = spawnPlayerAt(helper, rel);
        joinTeam(helper, alice);
        joinTeam(helper, bob);
        UUID teamKey = PartyLootKeys.teamKey(TEAM);

        try {
            // Alice opens, drops a marker item into slot 0, and closes so it persists to the team pot.
            helper.assertTrue(rightClick(helper, alice, rel) == InteractionResult.SUCCESS,
                    "opening a loot chest on a team must be intercepted");
            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data != null, "opening must attach instanced-loot data");
            helper.assertTrue(data.getInventory(teamKey) != null,
                    "the instance must be stored under the shared team key");
            helper.assertTrue(data.getInventory(alice.getUUID()) == null,
                    "no per-player instance may be stored for a team member");
            helper.assertTrue(data.getLastGeneratedTick(teamKey) >= 0
                            && data.getLastGeneratedTick(alice.getUUID()) < 0,
                    "the refresh cooldown tick must be per-team, not per-member");
            ((ChestMenu) alice.containerMenu).getContainer().setItem(0, new ItemStack(Items.DIAMOND, 7));
            alice.closeContainer();

            // Bob opens the same chest and sees Alice's marker — one shared pot, not his own roll.
            helper.assertTrue(rightClick(helper, bob, rel) == InteractionResult.SUCCESS,
                    "a teammate opening the shared chest must be intercepted");
            ChestMenu bobMenu = (ChestMenu) bob.containerMenu;
            helper.assertTrue(ItemStack.matches(bobMenu.getContainer().getItem(0),
                            new ItemStack(Items.DIAMOND, 7)),
                    "a teammate must see the shared pot exactly as the last member left it");
            // Bob takes the marker and closes; the take must be gone for Alice too.
            bobMenu.getContainer().setItem(0, ItemStack.EMPTY);
            bob.closeContainer();

            helper.assertTrue(rightClick(helper, alice, rel) == InteractionResult.SUCCESS,
                    "reopening the shared chest must be intercepted");
            helper.assertTrue(((ChestMenu) alice.containerMenu).getContainer().getItem(0).isEmpty(),
                    "an item a teammate took must be gone for the whole team");
        } finally {
            alice.discard();
            bob.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }

    /** A second teammate opening a shared instance already in use is refused, not shown a desynced copy. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void concurrentOpenIsRefused(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = true;
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer alice = spawnPlayerAt(helper, rel);
        ServerPlayer bob = spawnPlayerAt(helper, rel);
        joinTeam(helper, alice);
        joinTeam(helper, bob);

        try {
            // Alice opens and keeps the screen open (no close), holding the in-use lock.
            helper.assertTrue(rightClick(helper, alice, rel) == InteractionResult.SUCCESS,
                    "the first opener must be served");
            helper.assertTrue(alice.containerMenu instanceof InstancedLootMenu,
                    "the first opener must have the instanced screen open");

            // Bob's open is refused: no instanced screen opens for him while Alice holds it.
            rightClick(helper, bob, rel);
            helper.assertFalse(bob.containerMenu instanceof InstancedLootMenu,
                    "a teammate must be refused a shared instance already in use, not shown a copy");

            // Alice closes, releasing the lock; Bob can now open it.
            alice.closeContainer();
            helper.assertTrue(rightClick(helper, bob, rel) == InteractionResult.SUCCESS,
                    "the lock must release on close so a teammate can then open");
            helper.assertTrue(bob.containerMenu instanceof InstancedLootMenu,
                    "the teammate must be served once the instance is free");
        } finally {
            alice.closeContainer();
            bob.closeContainer();
            alice.discard();
            bob.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }

    /** With the mode on, a teamless player still gets their own individual instance. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void teamlessPlayerStaysIndividual(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = true;
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        try {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "a teamless player's open must still be intercepted with the mode on");
            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data != null && data.getInventory(player.getUUID()) != null,
                    "a teamless player must get an individual instance under their own UUID");
            helper.assertTrue(data.teamKeys().isEmpty(),
                    "a teamless open must record no team membership snapshot");
        } finally {
            player.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }

    /**
     * A player who leaves the team still resolves to team instances generated while they were a member
     * (snapshot resolution, not migration), so they cannot leave and re-loot the same chest fresh.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void leavingTeamStillResolvesToSharedInstance(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = true;
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);
        PlayerTeam team = joinTeam(helper, player);
        UUID teamKey = PartyLootKeys.teamKey(TEAM);

        try {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "opening on a team must be intercepted");
            player.closeContainer();
            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data != null && data.isTeamMember(teamKey, player.getUUID()),
                    "opening on a team must record the member in the snapshot");

            // Leave the team. The snapshot must still bind this player to the team instance here.
            helper.getLevel().getScoreboard().removePlayerFromTeam(player.getScoreboardName(), team);
            helper.assertTrue(player.getTeam() == null, "the player must be teamless after leaving");
            helper.assertTrue(PartyLootKeys.resolve(player, data).equals(teamKey),
                    "a former member must still resolve to the team instance they opened");

            // Re-opening must not mint a fresh individual instance (the leave-and-re-loot loop).
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "re-opening after leaving must still be intercepted");
            player.closeContainer();
            helper.assertTrue(data.getInventory(player.getUUID()) == null,
                    "a former member must not get a fresh individual roll from a team-looted chest");
        } finally {
            player.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }

    /**
     * A container looted individually stays individual after the player joins a team: joining never
     * re-rolls a chest they already looted solo (the join-side counterpart of the snapshot rule).
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void joiningTeamDoesNotRerollAlreadyLootedChest(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = true;
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        try {
            // Loot it solo (teamless → individual instance under the player's own UUID).
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "a teamless open must be intercepted");
            player.closeContainer();
            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data != null && data.hasGenerated(player.getUUID()),
                    "the solo open must store an individual instance");

            // Now join a team. Resolution must keep the player on their own individual instance here,
            // never mint a fresh team roll for a chest they already looted.
            joinTeam(helper, player);
            helper.assertTrue(PartyLootKeys.resolve(player, data).equals(player.getUUID()),
                    "after joining a team, an already-looted chest must still resolve to the player's UUID");
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "re-opening after joining must still be intercepted");
            player.closeContainer();
            helper.assertTrue(data.teamKeys().isEmpty(),
                    "joining a team must not create a team instance for an already-looted chest");
        } finally {
            player.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }

    /** With the mode off, a player on a team still gets an individual instance (the config gate). */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void modeOffKeepsIndividualInstances(GameTestHelper helper) {
        boolean saved = Prosperity.getConfig().partyLootMode;
        Prosperity.getConfig().partyLootMode = false;
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        ServerPlayer player = spawnPlayerAt(helper, rel);
        joinTeam(helper, player);

        try {
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                    "opening a loot chest must be intercepted regardless of the mode");
            InstancedLootData data = ProsperityAttachments.get(be);
            helper.assertTrue(data != null && data.getInventory(player.getUUID()) != null,
                    "with the mode off a team member must still store under their own UUID");
            helper.assertTrue(data.teamKeys().isEmpty(),
                    "the mode being off must record no team snapshot even for a team member");
        } finally {
            player.discard();
            cleanup(helper, saved);
        }
        helper.succeed();
    }
}
