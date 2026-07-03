package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.FishingLootScaling;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * Runtime checks for fishing loot scaling (SPEC §18). The gate, the resolved modifiers, and the shared
 * {@code LootModifierCallback} fire are asserted through {@link FishingLootScaling#resolve} against a
 * bobber owned by a mock angler; the stack-scaling math itself is unit-tested via
 * {@link com.rfizzle.prosperity.loot.LootScaling#scaledCount} and the live stochastic treasure bias
 * in-world is the manual check (the S-011 deterministic-vs-statistical split) &mdash; the resolved luck
 * asserted here is exactly what the mixin folds into the fishing roll, so a higher tier's higher luck is
 * the treasure bias.
 *
 * <p>All assertions live in one method in their own batch: the test forces {@code distanceTiers} and
 * toggles {@code enableFishingLootScaling}, so a unique batch keeps that mutation from racing the
 * default batch and a single method keeps the on/off assertions from racing each other. The capturing
 * loot-modifier listener is registered once in a static block and gated on a flag that defaults off, so
 * it is inert for every other generation in the run (Fabric events cannot be unregistered).
 */
public class FishingLootScalingGameTest implements FabricGameTest {

    private static final String BATCH = "fishing_loot_scaling";
    private static final double TIER_MULTIPLIER = 3.5;
    private static final int TIER_QUALITY = 4;

    private static volatile boolean capturing;
    private static volatile BlockPos capturedPos;

    static {
        LootModifierCallback.EVENT.register(context -> {
            if (capturing) {
                capturedPos = context.containerPos();
            }
        });
    }

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void resolveGatesFishingCatches(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        List<DistanceTier> savedTiers = cfg.distanceTiers;
        boolean savedEnabled = cfg.enableFishingLootScaling;
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new BlockPos(1, 2, 1).getCenter()));
        FishingHook hook = new FishingHook(player, helper.getLevel(), 0, 0);
        try {
            // A single tier covering every distance makes the resolved modifiers deterministic
            // regardless of where the gametest structure is placed in the world.
            cfg.distanceTiers = List.of(new DistanceTier("test", 0, TIER_MULTIPLIER, TIER_QUALITY));
            cfg.enableFishingLootScaling = true;

            capturing = true;
            capturedPos = null;
            FishingLootScaling.Mods mods = FishingLootScaling.resolve(hook, player);
            capturing = false;

            helper.assertTrue(mods != null, "a player-owned bobber must scale");
            helper.assertTrue(mods.stackMultiplier() == TIER_MULTIPLIER,
                    "the stack multiplier must be the resolved tier's multiplier");
            // Mock-player luck attribute is 0, so the final luck is the tier's quality alone.
            helper.assertTrue(mods.luck() == (float) TIER_QUALITY,
                    "the luck must be the tier quality plus the angler's (zero) vanilla luck");
            helper.assertTrue(hook.blockPosition().equals(capturedPos),
                    "the loot-modifier event must fire with the bobber's position as containerPos");

            helper.assertTrue(FishingLootScaling.resolve(hook, null) == null,
                    "a bobber with no player owner must not scale");

            cfg.enableFishingLootScaling = false;
            helper.assertTrue(FishingLootScaling.resolve(hook, player) == null,
                    "disabled fishing scaling must pass through to vanilla");
        } finally {
            capturing = false;
            cfg.distanceTiers = savedTiers;
            cfg.enableFishingLootScaling = savedEnabled;
            hook.discard();
            player.discard();
        }
        helper.succeed();
    }
}
