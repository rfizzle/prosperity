package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.InstancedLootGenerator;
import com.rfizzle.prosperity.loot.LootModifiers;
import com.rfizzle.prosperity.loot.LootScaling;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * Runtime checks for the loot-modifier API (S-013): the default {@code generic.luck} listener
 * participates, listeners fire in registration order accumulating state, and the context's final stack
 * multiplier flows into generation. Assertions read {@link LootModifiers#fire}'s returned context
 * directly (luck and the multiplier are exact, unlike RNG loot bias), so no statistical sampling is
 * needed.
 *
 * <p>Fabric events cannot be unregistered, so the test listeners are registered once (static block) and
 * gated on flags that default off &mdash; they are no-ops for every other generation in the run. They
 * run in their own batch; the server ticks gametests single-threaded, so toggling the flags within a
 * {@code try/finally} cannot race other tests.
 */
public class LootModifierGameTest implements FabricGameTest {

    private static final String BATCH = "loot_modifier";
    private static final ResourceKey<LootTable> TABLE = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final long SEED = 4242L;

    private static volatile boolean luckListenersActive = false;
    private static volatile boolean stackListenerActive = false;
    private static volatile float luckObservedBySecondListener = Float.NaN;

    static {
        // Registered after Prosperity's default vanilla-luck listener (added at init), so the firing
        // order is [vanilla-luck, A, B, stack]. Each is inert unless its flag is set by a test.
        LootModifierCallback.EVENT.register(ctx -> {
            if (luckListenersActive) {
                ctx.addLuck(1.0f);
            }
        });
        LootModifierCallback.EVENT.register(ctx -> {
            if (luckListenersActive) {
                luckObservedBySecondListener = ctx.luck();
                ctx.addLuck(2.0f);
            }
        });
        LootModifierCallback.EVENT.register(ctx -> {
            if (stackListenerActive) {
                ctx.multiplyStacks(2.0f);
            }
        });
    }

    /** The default listener folds the player's vanilla {@code generic.luck} into the context luck. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void defaultLuckListenerAddsVanillaLuck(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AttributeInstance luck = player.getAttribute(Attributes.LUCK);
        helper.assertTrue(luck != null, "a player must carry the generic.luck attribute");
        luck.setBaseValue(3.0);

        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        DistanceTier tier = new DistanceTier("test", 0, 1.0, 2);
        LootModifierContext ctx = LootModifiers.fire(player, pos, TABLE.location(), tier);

        helper.assertTrue(ctx.luck() == 5.0f,
                "luck must be the tier quality (2) plus vanilla luck (3): got " + ctx.luck());
        helper.succeed();
    }

    /** Listeners fire in registration order; a later listener sees the earlier listener's mutation. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void listenersFireInOrderAndAccumulate(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        // Zero quality and zero vanilla luck so the seed and the default listener contribute nothing,
        // leaving only the two test listeners' additive contributions to observe.
        DistanceTier tier = new DistanceTier("test", 0, 1.0, 0);

        luckObservedBySecondListener = Float.NaN;
        luckListenersActive = true;
        try {
            LootModifierContext ctx = LootModifiers.fire(player, pos, TABLE.location(), tier);
            helper.assertTrue(ctx.luck() == 3.0f,
                    "cumulative luck must be A(+1) then B(+2) = 3: got " + ctx.luck());
            helper.assertTrue(luckObservedBySecondListener == 1.0f,
                    "the second listener must see the first's +1: got " + luckObservedBySecondListener);
        } finally {
            luckListenersActive = false;
        }
        helper.succeed();
    }

    /** A listener's final stack multiplier scales the generated counts; luck is held fixed so the
     * rolled items are identical and only the counts differ. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void finalStackMultiplierFlowsIntoGeneration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 origin = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        DistanceTier tier = new DistanceTier("test", 0, 1.0, 0);

        LootModifierContext baseCtx = LootModifiers.fire(player, pos, TABLE.location(), tier);
        NonNullList<ItemStack> baseLoot = InstancedLootGenerator.generate(
                level, origin, TABLE, SEED, 0L, player, 27, baseCtx.luck(), baseCtx.stackMultiplier());
        helper.assertTrue(baseCtx.stackMultiplier() == 1.0f,
                "the baseline multiplier must be the tier's 1.0");

        stackListenerActive = true;
        NonNullList<ItemStack> scaledLoot;
        float scaledMultiplier;
        try {
            LootModifierContext scaledCtx = LootModifiers.fire(player, pos, TABLE.location(), tier);
            scaledMultiplier = scaledCtx.stackMultiplier();
            scaledLoot = InstancedLootGenerator.generate(
                    level, origin, TABLE, SEED, 0L, player, 27, scaledCtx.luck(), scaledCtx.stackMultiplier());
        } finally {
            stackListenerActive = false;
        }

        helper.assertTrue(scaledMultiplier == 2.0f,
                "the listener must double the multiplier to 2.0: got " + scaledMultiplier);
        for (int slot = 0; slot < 27; slot++) {
            ItemStack base = baseLoot.get(slot);
            ItemStack scaled = scaledLoot.get(slot);
            if (base.isEmpty()) {
                helper.assertTrue(scaled.isEmpty(), "fixed luck must keep the same slots filled");
                continue;
            }
            helper.assertTrue(ItemStack.isSameItemSameComponents(base, scaled),
                    "fixed luck must roll the same item in slot " + slot);
            int expected = LootScaling.scaledCount(base.getCount(), base.getMaxStackSize(), 2.0);
            helper.assertTrue(scaled.getCount() == expected,
                    "slot " + slot + " count must be the listener-scaled count");
        }
        helper.succeed();
    }
}
