package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.TrialChamberScaling;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Runtime checks for trial chamber reward scaling (SPEC §16). The gate, the resolved modifiers, the
 * rewarded-player attribution, and the shared {@code LootModifierCallback} fire are asserted through
 * {@link TrialChamberScaling} against a mock player; the stack-scaling math itself is unit-tested via
 * {@link com.rfizzle.prosperity.loot.LootScaling#scaledCount} and the live vault/spawner mixin wiring
 * showing scaled ejections in-world is the manual check (the S-011 deterministic-vs-statistical
 * split).
 *
 * <p>All assertions live in one method in their own batch: the test forces {@code distanceTiers} and
 * toggles {@code enableTrialChamberScaling}/{@code enableDistanceScaling}, so a unique batch keeps
 * that mutation from racing the default batch and a single method keeps the on/off assertions from
 * racing each other. The capturing loot-modifier listener is registered once in a static block and
 * gated on a flag that defaults off, so it is inert for every other generation in the run (Fabric
 * events cannot be unregistered).
 */
public class TrialChamberScalingGameTest implements FabricGameTest {

    private static final String BATCH = "trial_chamber_scaling";
    private static final double TIER_MULTIPLIER = 4.0;
    private static final int TIER_QUALITY = 3;

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
    public void resolveGatesTrialChamberRewards(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        List<DistanceTier> savedTiers = cfg.distanceTiers;
        boolean savedChamber = cfg.enableTrialChamberScaling;
        boolean savedDistance = cfg.enableDistanceScaling;
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos vaultPos = helper.absolutePos(new BlockPos(1, 1, 1));
        try {
            // A single tier covering every distance makes the resolved multiplier deterministic
            // regardless of where the gametest structure is placed in the world.
            cfg.distanceTiers = List.of(new DistanceTier("test", 0, TIER_MULTIPLIER, TIER_QUALITY));
            cfg.enableTrialChamberScaling = true;
            cfg.enableDistanceScaling = true;

            capturing = true;
            capturedPos = null;
            TrialChamberScaling.Mods mods = TrialChamberScaling.resolve(helper.getLevel(), vaultPos,
                    player, BuiltInLootTables.TRIAL_CHAMBERS_REWARD.location());
            capturing = false;

            helper.assertTrue(mods != null, "an enabled vault roll must scale");
            helper.assertTrue(mods.stackMultiplier() == TIER_MULTIPLIER,
                    "the stack multiplier must be the resolved tier's multiplier");
            // Mock-player luck attribute is 0, so the final luck is the tier's quality alone.
            helper.assertTrue(mods.luck() == (float) TIER_QUALITY,
                    "the luck must be the tier quality plus the player's (zero) vanilla luck");
            helper.assertTrue(vaultPos.equals(capturedPos),
                    "the loot-modifier event must fire with the vault position as containerPos");

            ItemStack stack = new ItemStack(Items.EMERALD, 3);
            TrialChamberScaling.scaleStacks(List.of(stack), mods.stackMultiplier());
            helper.assertTrue(stack.getCount() == 12,
                    "a rolled stack must be multiplied by the tier's stack multiplier");

            helper.assertTrue(TrialChamberScaling.rewardedPlayer(helper.getLevel(),
                            java.util.Set.of(player.getUUID())) == player,
                    "the rewarded player must resolve from the detected set");
            helper.assertTrue(TrialChamberScaling.rewardedPlayer(helper.getLevel(),
                            java.util.Set.of()) == null,
                    "an empty detected set must resolve no player");

            helper.assertTrue(TrialChamberScaling.resolve(helper.getLevel(), vaultPos, null,
                            BuiltInLootTables.TRIAL_CHAMBERS_REWARD.location()) == null,
                    "a roll with no player must not scale");

            cfg.enableTrialChamberScaling = false;
            helper.assertTrue(TrialChamberScaling.resolve(helper.getLevel(), vaultPos, player,
                            BuiltInLootTables.TRIAL_CHAMBERS_REWARD.location()) == null,
                    "disabled trial chamber scaling must pass through to vanilla");

            cfg.enableTrialChamberScaling = true;
            cfg.enableDistanceScaling = false;
            helper.assertTrue(TrialChamberScaling.resolve(helper.getLevel(), vaultPos, player,
                            BuiltInLootTables.TRIAL_CHAMBERS_REWARD.location()) == null,
                    "disabled distance scaling must pass through to vanilla");
        } finally {
            capturing = false;
            cfg.distanceTiers = savedTiers;
            cfg.enableTrialChamberScaling = savedChamber;
            cfg.enableDistanceScaling = savedDistance;
            player.discard();
        }
        helper.succeed();
    }
}
