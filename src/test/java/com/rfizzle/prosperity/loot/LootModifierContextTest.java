package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.api.LootModifierContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the loot-modifier context's mutators (S-013). The player is irrelevant to luck,
 * stack-multiplier, and customData behavior, so the context is built directly with a {@code null} player.
 */
class LootModifierContextTest {

    private static final ResourceLocation TABLE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "chests/simple_dungeon");

    private static LootModifierContext context(float luck, float stackMultiplier) {
        return new LootModifierContextImpl(null, BlockPos.ZERO, TABLE, luck, stackMultiplier);
    }

    @Test
    void readOnlyFieldsAreExposed() {
        LootModifierContext ctx = context(0.0f, 1.0f);
        assertEquals(BlockPos.ZERO, ctx.containerPos(), "containerPos is the constructed position");
        assertEquals(TABLE, ctx.lootTable(), "lootTable is the constructed table");
    }

    @Test
    void addLuckAccumulatesOnTopOfCurrent() {
        LootModifierContext ctx = context(2.0f, 1.0f);
        ctx.addLuck(1.5f);
        ctx.addLuck(0.5f);
        assertEquals(4.0f, ctx.luck(), "addLuck stacks: 2.0 + 1.5 + 0.5 = 4.0");
    }

    @Test
    void setLuckReplacesOutright() {
        LootModifierContext ctx = context(5.0f, 1.0f);
        ctx.setLuck(1.0f);
        assertEquals(1.0f, ctx.luck(), "setLuck overrides the accumulated value");
    }

    @Test
    void multiplyStacksCompoundsTheMultiplier() {
        LootModifierContext ctx = context(0.0f, 2.0f);
        ctx.multiplyStacks(1.5f);
        assertEquals(3.0f, ctx.stackMultiplier(), "multiplyStacks compounds: 2.0 * 1.5 = 3.0");
    }

    @Test
    void setStackMultiplierReplacesOutright() {
        LootModifierContext ctx = context(0.0f, 3.5f);
        ctx.setStackMultiplier(1.0f);
        assertEquals(1.0f, ctx.stackMultiplier(), "setStackMultiplier overrides the seeded value");
    }

    @Test
    void nonFiniteLuckMutationsAreIgnored() {
        LootModifierContext ctx = context(2.0f, 1.0f);
        ctx.setLuck(Float.NaN);
        assertEquals(2.0f, ctx.luck(), "setLuck ignores NaN, keeping the previous value");
        ctx.setLuck(Float.POSITIVE_INFINITY);
        assertEquals(2.0f, ctx.luck(), "setLuck ignores +Inf");
        ctx.addLuck(Float.NaN);
        assertEquals(2.0f, ctx.luck(), "addLuck ignores a NaN delta");
        ctx.addLuck(Float.NEGATIVE_INFINITY);
        assertEquals(2.0f, ctx.luck(), "addLuck ignores a -Inf delta");
    }

    @Test
    void nonFiniteStackMutationsAreIgnored() {
        LootModifierContext ctx = context(0.0f, 2.0f);
        ctx.setStackMultiplier(Float.NaN);
        assertEquals(2.0f, ctx.stackMultiplier(), "setStackMultiplier ignores NaN");
        ctx.multiplyStacks(Float.POSITIVE_INFINITY);
        assertEquals(2.0f, ctx.stackMultiplier(), "multiplyStacks ignores a factor that would go infinite");
        ctx.multiplyStacks(Float.MAX_VALUE);
        ctx.multiplyStacks(Float.MAX_VALUE);
        assertTrue(Float.isFinite(ctx.stackMultiplier()),
                "multiplyStacks rejects the result when accumulation would overflow to infinity");
    }

    @Test
    void customDataIsIsolatedPerContext() {
        LootModifierContext first = context(0.0f, 1.0f);
        LootModifierContext second = context(0.0f, 1.0f);
        assertNotSame(first.customData(), second.customData(), "each context owns a fresh bag");

        first.customData().putInt("prospecting_level", 7);
        assertTrue(first.customData().contains("prospecting_level"), "writes land in the owner's bag");
        assertFalse(second.customData().contains("prospecting_level"),
                "a second context never sees the first's customData");
    }
}
