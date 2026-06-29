package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.InstancedLootGenerator;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for distance-based loot scaling (S-011). Tier resolution (distance bands, the
 * disabled-scaling gate, Nether raw coordinates, End max tier) is asserted through
 * {@link LootScaling#effectiveTier}; quantity scaling is asserted by generating the same table and
 * seed at two multipliers that share a quality modifier — so the rolled items are identical and only
 * the stack counts differ. The quality (luck) wiring is covered by {@code LootScalingTest}.
 *
 * <p>These run in their own gametest batch: the tier-resolution test toggles the global
 * {@code enableDistanceScaling} flag, and a unique batch keeps that mutation from racing tests in the
 * default batch (batches run sequentially; tests within a batch run concurrently). The two
 * config-toggling assertions live in one method so they cannot race each other on the flag either.
 */
public class LootScalingGameTest implements FabricGameTest {

    private static final String BATCH = "loot_scaling";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4242L;

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    public void tierResolutionGatesAndHonorsDimensions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        ServerLevel end = level.getServer().getLevel(Level.END);
        helper.assertTrue(nether != null && end != null, "the Nether and End must be loaded");

        boolean saved = Prosperity.getConfig().enableDistanceScaling;
        try {
            Prosperity.getConfig().enableDistanceScaling = true;
            helper.assertTrue(LootScaling.effectiveTier(level, 0, 0).name().equals("local"),
                    "origin must resolve to the local tier");
            helper.assertTrue(LootScaling.effectiveTier(level, 10_000, 0).name().equals("depths"),
                    "10,000 blocks must resolve to the depths tier");
            // 5,000 raw Nether blocks falls in the wilderness band; an x8 conversion would land in
            // depths, so wilderness proves the coordinates are used unscaled.
            helper.assertTrue(LootScaling.effectiveTier(nether, 5000, 0).name().equals("wilderness"),
                    "the Nether must scale on raw coordinates (no x8)");
            helper.assertTrue(LootScaling.effectiveTier(end, 0, 0).name().equals("depths"),
                    "the End must force the max (depths) tier regardless of distance");

            Prosperity.getConfig().enableDistanceScaling = false;
            DistanceTier disabled = LootScaling.effectiveTier(level, 10_000, 0);
            helper.assertTrue(disabled.name().equals(ProsperityConfig.LOCAL_SENTINEL.name()),
                    "disabled scaling must yield the local sentinel regardless of distance");
            helper.assertTrue(disabled.stackMultiplier() == 1.0 && disabled.qualityModifier() == 0,
                    "the disabled sentinel must carry no multiplier and no quality");
        } finally {
            Prosperity.getConfig().enableDistanceScaling = saved;
        }
        helper.succeed();
    }

    /** The stack multiplier scales each stackable stack's count, caps at max stack, and leaves
     * non-stackables alone — verified against an identical baseline roll. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void quantityScalingMultipliesStackables(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 origin = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));

        // Identical luck (0) on both rolls keeps the rolled items identical; only the stack
        // multiplier differs.
        double multiplier = 3.5;

        NonNullList<ItemStack> baseLoot =
                InstancedLootGenerator.generate(level, origin, TABLE, SEED, 0L, player, 27, 0.0f, 1.0);
        NonNullList<ItemStack> scaledLoot =
                InstancedLootGenerator.generate(level, origin, TABLE, SEED, 0L, player, 27, 0.0f, multiplier);

        boolean sawStackableScaledUp = false;
        for (int slot = 0; slot < 27; slot++) {
            ItemStack base = baseLoot.get(slot);
            ItemStack big = scaledLoot.get(slot);
            if (base.isEmpty()) {
                helper.assertTrue(big.isEmpty(), "an empty baseline slot must stay empty when scaled");
                continue;
            }
            helper.assertTrue(ItemStack.isSameItemSameComponents(base, big),
                    "scaling must not change which item rolls in a slot");
            int expected = LootScaling.scaledCount(base.getCount(), base.getMaxStackSize(), multiplier);
            helper.assertTrue(big.getCount() == expected,
                    "slot " + slot + " count must be the scaled count");
            if (base.getMaxStackSize() <= 1) {
                helper.assertTrue(big.getCount() == base.getCount(),
                        "a non-stackable item must keep its count");
            } else if (big.getCount() > base.getCount()) {
                sawStackableScaledUp = true;
            }
        }
        helper.assertTrue(sawStackableScaledUp,
                "the loot must contain at least one stackable stack that scaled up");

        player.discard();
        helper.succeed();
    }
}
