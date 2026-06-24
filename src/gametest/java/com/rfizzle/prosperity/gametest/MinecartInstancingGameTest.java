package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.MinecartContainerAdapter;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for the container-minecart half of the instanced-loot loop (S-035). Chest and hopper
 * minecarts are entities, so the path runs through {@link UseEntityCallback} and a
 * {@link MinecartContainerAdapter} rather than the block-entity path. These tests drive the callback
 * exactly as a real interaction would, then assert the per-player instancing, table nullification, the
 * {@code unpackChestVehicleLootTable} safety mixin, and adapter parity.
 */
public class MinecartInstancingGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.ABANDONED_MINESHAFT;
    private static final long SEED = 1234L;

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

    /** Spawn a loot-laden container minecart centred on {@code rel} and register it in the world. */
    private <T extends AbstractMinecartContainer> T spawnLootCart(GameTestHelper helper, BlockPos rel,
            T cart) {
        BlockPos abs = helper.absolutePos(rel);
        cart.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        cart.setLootTable(TABLE, SEED);
        helper.getLevel().addFreshEntity(cart);
        return cart;
    }

    private InteractionResult open(GameTestHelper helper, ServerPlayer player,
            AbstractMinecartContainer cart) {
        ServerLevel level = helper.getLevel();
        return UseEntityCallback.EVENT.invoker()
                .interact(player, level, InteractionHand.MAIN_HAND, cart, null);
    }

    private boolean allEmpty(AbstractMinecartContainer cart) {
        for (int slot = 0; slot < cart.getContainerSize(); slot++) {
            if (!cart.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Two players opening one chest minecart each get their own private, independent 27-slot inventory,
     * and the cart's vanilla loot table is severed on the first open.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void chestCartInstancesPerPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartChest cart = spawnLootCart(helper, rel, new MinecartChest(helper.getLevel(), 0, 0, 0));

        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        ServerPlayer playerB = spawnPlayerAt(helper, rel);

        // A never-opened cart must carry no attachment up front; interception gates on the loot table.
        helper.assertTrue(ProsperityAttachments.get(cart) == null,
                "a never-opened loot cart must carry no attachment up front");
        helper.assertTrue(open(helper, playerA, cart) == InteractionResult.SUCCESS,
                "opening a loot cart must be intercepted");
        helper.assertTrue(cart.getLootTable() == null,
                "the vanilla loot table must be nulled on first open");
        helper.assertTrue(cart.getLootTableSeed() == 0L,
                "the vanilla loot seed must be cleared on first open");

        helper.assertTrue(open(helper, playerB, cart) == InteractionResult.SUCCESS,
                "the second player's open must also be intercepted");

        InstancedLootData data = ProsperityAttachments.get(cart);
        helper.assertTrue(data != null, "opening must attach instanced-loot data");
        NonNullList<ItemStack> invA = data.getInventory(playerA.getUUID());
        NonNullList<ItemStack> invB = data.getInventory(playerB.getUUID());
        helper.assertTrue(invA != null && invB != null, "both players must have a generated inventory");
        helper.assertTrue(invA.size() == 27 && invB.size() == 27,
                "a chest minecart instance must be 27 slots");
        helper.assertTrue(data.playerIds().size() == 2, "both players' UUIDs must be stored");
        helper.assertFalse(invA == invB, "each player must own a distinct inventory instance");

        playerA.discard();
        playerB.discard();
        helper.succeed();
    }

    /** A hopper minecart instances the same way, proving the size-5 container path (not just size 27). */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hopperCartInstancesAtSizeFive(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartHopper cart = spawnLootCart(helper, rel, new MinecartHopper(helper.getLevel(), 0, 0, 0));

        helper.assertTrue(cart.getContainerSize() == 5, "a hopper minecart must be 5 slots");

        ServerPlayer player = spawnPlayerAt(helper, rel);
        helper.assertTrue(open(helper, player, cart) == InteractionResult.SUCCESS,
                "opening a loot hopper cart must be intercepted");
        helper.assertTrue(cart.getLootTable() == null,
                "the vanilla loot table must be nulled on first open");

        InstancedLootData data = ProsperityAttachments.get(cart);
        helper.assertTrue(data != null, "opening must attach instanced-loot data");
        NonNullList<ItemStack> inv = data.getInventory(player.getUUID());
        helper.assertTrue(inv != null && inv.size() == 5,
                "a hopper minecart instance must be 5 slots");

        player.discard();
        helper.succeed();
    }

    /**
     * The unpack mixin cancels the hopper-drain path: {@code getChestVehicleItem} routes through
     * {@code unpackChestVehicleLootTable(null)}, which must be a no-op for a generated cart even while
     * its loot table is still set, so no global loot ever materialises into the cart's own contents.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unpackCancelledForGeneratedCart(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartChest cart = spawnLootCart(helper, rel, new MinecartChest(helper.getLevel(), 0, 0, 0));

        // Mark generated without nulling the loot table, so only the mixin can stop the drain here.
        ProsperityAttachments.update(cart, data -> data.markGenerated(TABLE, SEED));

        // The hopper-drain path: probing a slot routes through unpackChestVehicleLootTable(null).
        ItemStack probed = cart.getChestVehicleItem(0);
        cart.removeChestVehicleItem(0, 1);

        helper.assertTrue(cart.getLootTable() == TABLE,
                "the mixin must cancel before vanilla clears the cart's loot table");
        helper.assertTrue(probed.isEmpty(),
                "a generated cart must report an empty slot, never freshly-unpacked loot");
        helper.assertTrue(allEmpty(cart),
                "vanilla must not fill the cart's contents once the mod has generated it");
        helper.succeed();
    }

    /**
     * Adapter parity: the minecart adapter reports the cart's slot count and a display name, and
     * {@code clearLootTable} severs the table and seed — the same contract the block adapter satisfies.
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void adapterParity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartChest cart = spawnLootCart(helper, rel, new MinecartChest(level, 0, 0, 0));

        MinecartContainerAdapter cartAdapter = new MinecartContainerAdapter(level, cart);
        helper.assertTrue(cartAdapter.size() == 27, "a chest minecart adapter must report 27 slots");
        helper.assertTrue(cartAdapter.displayName() != null, "the adapter must expose a display name");
        helper.assertTrue(cartAdapter.lootTable() == TABLE,
                "the adapter must surface the cart's live loot table");

        cartAdapter.clearLootTable();
        helper.assertTrue(cart.getLootTable() == null, "clearLootTable must null the cart's loot table");
        helper.assertTrue(cart.getLootTableSeed() == 0L, "clearLootTable must zero the cart's seed");

        // Side-by-side parity with the block adapter over a placed chest.
        BlockPos chestRel = new BlockPos(1, 1, 2);
        helper.setBlock(chestRel, Blocks.CHEST);
        RandomizableContainerBlockEntity be =
                (RandomizableContainerBlockEntity) helper.getBlockEntity(chestRel);
        be.setLootTable(TABLE);
        be.setLootTableSeed(SEED);
        BlockEntityContainerAdapter blockAdapter =
                new BlockEntityContainerAdapter(level, helper.absolutePos(chestRel), be);
        helper.assertTrue(blockAdapter.size() == cartAdapter.size(),
                "block and minecart chest adapters must report the same slot count");
        blockAdapter.clearLootTable();
        helper.assertTrue(be.getLootTable() == null, "block adapter clearLootTable must null the table");
        helper.assertTrue(be.getLootTableSeed() == 0L, "block adapter clearLootTable must zero the seed");

        helper.succeed();
    }
}
