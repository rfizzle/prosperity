package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.MinecartContainerAdapter;
import com.rfizzle.prosperity.loot.MinecartLootInteraction;
import com.rfizzle.prosperity.loot.UnlootedMinecarts;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for the entity-anchored indicator scan (S-038). {@link UnlootedMinecarts#scanChunk}
 * answers a player's per-chunk request with exactly the container minecarts that player has not
 * generated, keyed by network id (carts move, so the block-keyed protocol cannot address them). These
 * drive the scan directly — a headless mock player's {@code canSend} is false, so the
 * {@code UnlootedMinecartsS2C}/{@code MinecartLootedS2C}/{@code MinecartRemovedS2C} packets cannot be
 * observed in-test (the same constraint as {@link UnlootedSyncGameTest}).
 */
public class MinecartIndicatorGameTest implements FabricGameTest {

    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.ABANDONED_MINESHAFT;
    private static final long SEED = 4242L;

    @SuppressWarnings("removal")
    private ServerPlayer spawnPlayerAt(GameTestHelper helper, BlockPos rel) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos abs = helper.absolutePos(rel);
        player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        return player;
    }

    private <T extends AbstractMinecartContainer> T spawnCart(GameTestHelper helper, BlockPos rel, T cart) {
        BlockPos abs = helper.absolutePos(rel);
        cart.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        helper.getLevel().addFreshEntity(cart);
        return cart;
    }

    private List<Integer> scan(GameTestHelper helper, BlockPos rel, UUID player) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunkPos = new ChunkPos(helper.absolutePos(rel));
        return UnlootedMinecarts.scanChunk(level, chunkPos, player);
    }

    /** One player generating loot shrinks only their own unlooted set; the other still sees the cart. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void scanIsPerPlayer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartChest cart = spawnCart(helper, rel, new MinecartChest(helper.getLevel(), 0, 0, 0));
        cart.setLootTable(TABLE, SEED);
        ServerPlayer playerA = spawnPlayerAt(helper, rel);
        UUID b = UUID.randomUUID();

        List<Integer> beforeA = scan(helper, rel, playerA.getUUID());
        helper.assertTrue(beforeA.size() == 1 && beforeA.contains(cart.getId()),
                "player A should see the unlooted chest cart before generating");
        helper.assertTrue(scan(helper, rel, b).contains(cart.getId()),
                "player B should also see the unlooted chest cart");

        InstancedLootInteraction.generateAndStore(
                new MinecartContainerAdapter(helper.getLevel(), cart), playerA);

        helper.assertTrue(scan(helper, rel, playerA.getUUID()).isEmpty(),
                "player A should no longer see the cart after generating");
        helper.assertTrue(scan(helper, rel, b).contains(cart.getId()),
                "player B's unlooted set should be unaffected by player A generating");

        playerA.discard();
        helper.succeed();
    }

    /** A hopper minecart is reported the same way, proving the scan is not chest-cart-specific. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void hopperCartIncluded(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        MinecartHopper cart = spawnCart(helper, rel, new MinecartHopper(helper.getLevel(), 0, 0, 0));
        cart.setLootTable(TABLE, SEED);

        List<Integer> ids = scan(helper, rel, UUID.randomUUID());
        helper.assertTrue(ids.size() == 1 && ids.contains(cart.getId()),
                "an unlooted hopper cart should be reported by the scan");
        helper.succeed();
    }

    /** A player-placed cart (no loot table) is never instanced and never shows an indicator. */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void plainCartExcluded(GameTestHelper helper) {
        BlockPos rel = new BlockPos(1, 1, 1);
        spawnCart(helper, rel, new MinecartChest(helper.getLevel(), 0, 0, 0));
        helper.assertTrue(scan(helper, rel, UUID.randomUUID()).isEmpty(),
                "a player-placed chest cart with no loot table should never show an indicator");
        helper.succeed();
    }

    /**
     * The removal handler gates on the cart being a loot container: a loot cart is classified for the
     * removed broadcast, a plain cart is not, and {@code onMinecartRemoved} runs without error for both
     * (the actual {@code MinecartRemovedS2C} emission is a manual check — {@code canSend} is false here).
     */
    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void removalGateClassifiesCarts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecartChest loot = spawnCart(helper, new BlockPos(1, 1, 1), new MinecartChest(level, 0, 0, 0));
        loot.setLootTable(TABLE, SEED);
        MinecartChest plain = spawnCart(helper, new BlockPos(3, 1, 1), new MinecartChest(level, 0, 0, 0));

        helper.assertTrue(new MinecartContainerAdapter(level, loot).isLootContainer(),
                "a loot cart must be classified as a loot container for removal");
        helper.assertFalse(new MinecartContainerAdapter(level, plain).isLootContainer(),
                "a plain cart must not be classified as a loot container");

        // Both must run without throwing; only the loot cart would broadcast a removed packet.
        MinecartLootInteraction.onMinecartRemoved(level, loot);
        MinecartLootInteraction.onMinecartRemoved(level, plain);
        helper.succeed();
    }
}
