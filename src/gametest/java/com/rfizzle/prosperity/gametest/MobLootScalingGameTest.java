package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.MobLootScaling;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Runtime checks for mob loot scaling (S-018, SPEC §13). The gate, the resolved modifiers, and the
 * shared {@code LootModifierCallback} fire are asserted through {@link MobLootScaling#resolve} against a
 * spawned hostile mob and a mock killer; the stack-scaling math itself is unit-tested via
 * {@link com.rfizzle.prosperity.loot.LootScaling#scaledCount} and the live stochastic mob roll showing
 * larger stacks in-world is the manual check (the S-011 deterministic-vs-statistical split).
 *
 * <p>All assertions live in one method in their own batch: the test forces {@code distanceTiers} and
 * toggles {@code enableMobLootScaling}, so a unique batch keeps that mutation from racing the default
 * batch and a single method keeps the on/off assertions from racing each other. The capturing
 * loot-modifier listener is registered once in a static block and gated on a flag that defaults off, so
 * it is inert for every other generation in the run (Fabric events cannot be unregistered).
 */
public class MobLootScalingGameTest implements FabricGameTest {

    private static final String BATCH = "mob_loot_scaling";
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
    public void resolveGatesHostilePlayerKills(GameTestHelper helper) {
        ProsperityConfig cfg = Prosperity.getConfig();
        List<DistanceTier> savedTiers = cfg.distanceTiers;
        boolean savedEnabled = cfg.enableMobLootScaling;
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(3, 1, 1));
        try {
            // A single tier covering every distance makes the resolved multiplier deterministic
            // regardless of where the gametest structure is placed in the world.
            cfg.distanceTiers = List.of(new DistanceTier("test", 0, TIER_MULTIPLIER, TIER_QUALITY));
            cfg.enableMobLootScaling = true;

            capturing = true;
            capturedPos = null;
            MobLootScaling.Mods mods = MobLootScaling.resolve(zombie, player);
            capturing = false;

            helper.assertTrue(mods != null, "a hostile player kill must scale");
            helper.assertTrue(mods.stackMultiplier() == TIER_MULTIPLIER,
                    "the stack multiplier must be the resolved tier's multiplier");
            // Mock-player luck attribute is 0, so the final luck is the tier's quality alone.
            helper.assertTrue(mods.luck() == (float) TIER_QUALITY,
                    "the luck must be the tier quality plus the killer's (zero) vanilla luck");
            helper.assertTrue(zombie.blockPosition().equals(capturedPos),
                    "the loot-modifier event must fire with the mob's death position as containerPos");

            helper.assertTrue(MobLootScaling.resolve(cow, player) == null,
                    "a passive mob must not scale");
            helper.assertTrue(MobLootScaling.resolve(zombie, null) == null,
                    "a non-player (environmental) kill must not scale");

            cfg.enableMobLootScaling = false;
            helper.assertTrue(MobLootScaling.resolve(zombie, player) == null,
                    "disabled mob scaling must pass through to vanilla");
        } finally {
            capturing = false;
            cfg.distanceTiers = savedTiers;
            cfg.enableMobLootScaling = savedEnabled;
            player.discard();
            zombie.discard();
            cow.discard();
        }
        helper.succeed();
    }
}
