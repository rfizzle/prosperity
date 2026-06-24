package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.api.LootModifierCallback;
import com.rfizzle.prosperity.api.LootModifierContext;
import com.rfizzle.prosperity.config.DistanceTier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Fires {@link LootModifierCallback} once per loot generation and registers Prosperity's own default
 * listeners (SPEC section 4). The context is seeded with the post-scaling values &mdash; the resolved
 * tier's quality modifier as luck and its stack multiplier &mdash; then handed to every listener; the
 * caller reads the final {@link LootModifierContext#luck()} and {@link LootModifierContext#stackMultiplier()}
 * back out to drive resolution and stack scaling.
 *
 * <p>Vanilla luck participates via a default listener (registered at init) that adds the player's
 * {@code generic.luck} attribute, so the context starts from the scaling contribution alone and the
 * attribute is folded in like any other listener.
 */
public final class LootModifiers {

    private LootModifiers() {
    }

    /** Register Prosperity's own loot-modifier listeners. Call once at initialization. */
    public static void registerDefaults() {
        LootModifierCallback.EVENT.register(context ->
                context.addLuck((float) context.player().getAttributeValue(Attributes.LUCK)));
    }

    /**
     * Build a fresh context seeded from {@code tier}, fire {@link LootModifierCallback#EVENT}, and
     * return the context carrying the listeners' cumulative result. A listener that throws is caught
     * and logged so it cannot break generation, though it stops later listeners for this generation.
     */
    public static LootModifierContext fire(ServerPlayer player, BlockPos containerPos,
            ResourceLocation lootTable, DistanceTier tier) {
        LootModifierContext context = new LootModifierContextImpl(player, containerPos, lootTable,
                tier.qualityModifier(), (float) tier.stackMultiplier());
        try {
            LootModifierCallback.EVENT.invoker().onModifyLoot(context);
        } catch (Exception e) {
            Prosperity.LOGGER.error("A loot modifier listener threw during loot generation at {}",
                    containerPos, e);
        }
        return context;
    }
}
