package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.component.InstancedLootComponent;
import com.rfizzle.prosperity.component.ProsperityComponents;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the instanced-loot interaction loop (S-005). The right-click handler is driven
 * through {@link UseBlockCallback} exactly as a real interaction would fire it, so the config gate,
 * the loot-container gate, generation, and the lid cue are all exercised end to end. The generate
 * and persist steps are also poked directly, since a real screen close cannot be triggered headless.
 */
public class InstancedLootGameTest implements FabricGameTest {

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

    /** Two players opening one loot chest each get their own private, independent inventory. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void lootChestInstancesPerPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.get(be);

        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        ServerPlayer playerB = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, playerA, rel) == InteractionResult.SUCCESS,
                "opening a loot chest must be intercepted");
        helper.assertTrue(rightClick(helper, playerB, rel) == InteractionResult.SUCCESS,
                "the second player's open must also be intercepted");

        NonNullList<ItemStack> invA = component.getInventory(playerA.getUUID());
        NonNullList<ItemStack> invB = component.getInventory(playerB.getUUID());
        helper.assertTrue(invA != null && invB != null, "both players must have a generated inventory");
        helper.assertFalse(invA == invB, "each player must own a distinct inventory instance");

        ItemStack bSlotZero = invB.get(0).copy();
        invA.set(0, new ItemStack(Items.NETHERITE_INGOT, 1));
        helper.assertTrue(ItemStack.matches(invB.get(0), bSlotZero),
                "mutating one player's inventory must not affect the other's");

        playerA.discard();
        playerB.discard();
        helper.succeed();
    }

    /** Rearranging the screen and closing it writes the state back; reopening retrieves it. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void instancePersistsOnClose(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.get(be);

        ServerPlayer player = spawnPlayerAt(helper, rel);
        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening a loot chest must be intercepted");

        // Rearrange the open screen and close it as the player would.
        helper.assertTrue(player.containerMenu instanceof InstancedLootMenu,
                "an instanced menu must be open after the interaction");
        ChestMenu menu = (ChestMenu) player.containerMenu;
        menu.getContainer().setItem(0, new ItemStack(Items.DIAMOND, 5));
        menu.getContainer().setItem(1, ItemStack.EMPTY);
        player.closeContainer();

        NonNullList<ItemStack> stored = component.getInventory(player.getUUID());
        helper.assertTrue(stored.size() == be.getContainerSize(), "persisted inventory keeps its size");
        helper.assertTrue(ItemStack.matches(stored.get(0), new ItemStack(Items.DIAMOND, 5)),
                "the rearranged slot must persist on close");
        helper.assertTrue(stored.get(1).isEmpty(), "cleared slots must persist as empty");

        // Reopening must retrieve the saved state, not regenerate fresh loot.
        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "reopening a looted chest must still be intercepted");
        NonNullList<ItemStack> reopened = component.getInventory(player.getUUID());
        helper.assertTrue(ItemStack.matches(reopened.get(0), new ItemStack(Items.DIAMOND, 5)),
                "a return visit must show the player's prior state, not new loot");

        player.discard();
        helper.succeed();
    }

    /** A player-placed container (no loot table) is left to vanilla and never instanced. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playerPlacedChestOpensVanilla(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(rel);
        InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.get(be);

        ServerPlayer player = spawnPlayerAt(helper, rel);
        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.PASS,
                "a non-loot container must fall through to vanilla");
        helper.assertFalse(component.hasInventory(player.getUUID()),
                "a non-loot container must not generate an instance");

        player.discard();
        helper.succeed();
    }

    /** With instancing disabled, even a loot chest falls through to vanilla. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void disabledConfigOpensVanilla(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        RandomizableContainerBlockEntity be = placeLootContainer(helper, rel, Blocks.CHEST);
        InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.get(be);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        boolean saved = Prosperity.getConfig().enableInstancedLoot;
        try {
            Prosperity.getConfig().enableInstancedLoot = false;
            helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.PASS,
                    "disabled instancing must fall through to vanilla");
            helper.assertFalse(component.hasInventory(player.getUUID()),
                    "disabled instancing must not generate an instance");
        } finally {
            Prosperity.getConfig().enableInstancedLoot = saved;
        }

        player.discard();
        helper.succeed();
    }

    /** Opening an instanced barrel drives the vanilla lid via its {@code OPEN} blockstate. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void barrelLidOpensOnInteract(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        placeLootContainer(helper, rel, Blocks.BARREL);
        ServerPlayer player = spawnPlayerAt(helper, rel);

        helper.assertTrue(rightClick(helper, player, rel) == InteractionResult.SUCCESS,
                "opening a loot barrel must be intercepted");

        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(rel));
        helper.assertTrue(state.getValue(BarrelBlock.OPEN), "the barrel lid must open on interaction");

        player.discard();
        helper.succeed();
    }

    /** The served menu has the row count and slot count of the underlying container. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void menuMatchesContainerSize(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        SimpleContainer chest = new SimpleContainer(27);
        InstancedLootMenu chestMenu = InstancedLootMenu.create(0, player.getInventory(), chest, () -> {
        });
        helper.assertTrue(chestMenu.getRowCount() == 3, "a 27-slot container must serve three rows");
        helper.assertTrue(chestMenu.getContainer().getContainerSize() == 27, "menu keeps the container size");

        SimpleContainer dispenser = new SimpleContainer(9);
        InstancedLootMenu dispenserMenu = InstancedLootMenu.create(0, player.getInventory(), dispenser, () -> {
        });
        helper.assertTrue(dispenserMenu.getRowCount() == 1, "a 9-slot container must serve one row");

        helper.succeed();
    }
}
