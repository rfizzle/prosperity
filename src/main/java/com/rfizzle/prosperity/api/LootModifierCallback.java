package com.rfizzle.prosperity.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Callback fired once per instanced-loot generation, after distance and structure scaling
 * (SPEC sections 3/6) but before the loot table is resolved. Part of Prosperity's stable API
 * surface (Concord API Standard v1).
 *
 * <p>Listeners receive a mutable {@link LootModifierContext} and may adjust the loot generation
 * parameters &mdash; biasing luck (quality), scaling stack sizes, or exchanging data through the
 * context's {@link LootModifierContext#customData() customData} bag. The context's final
 * {@code luck} feeds the {@code LootParams}, and its final {@code stackMultiplier} scales the
 * rolled counts.
 *
 * <p>Fired <strong>server-side only</strong>, from the single generation choke point. Listeners
 * fire in registration order; a later listener sees the cumulative state left by earlier ones.
 * Prosperity registers its own {@code generic.luck} listener at initialization, so vanilla luck
 * always participates.
 *
 * <p>A listener that throws is caught and logged by Prosperity; it cannot corrupt loot generation,
 * but it may prevent listeners registered after it from seeing that generation.
 */
@Stable
@FunctionalInterface
public interface LootModifierCallback {

    Event<LootModifierCallback> EVENT = EventFactory.createArrayBacked(LootModifierCallback.class,
            listeners -> context -> {
                for (LootModifierCallback listener : listeners) {
                    listener.onModifyLoot(context);
                }
            });

    /**
     * Called during loot generation with the mutable per-generation context.
     *
     * @param context the loot generation context, populated with post-scaling values; mutations
     *                are cumulative and visible to later listeners
     */
    void onModifyLoot(LootModifierContext context);
}
