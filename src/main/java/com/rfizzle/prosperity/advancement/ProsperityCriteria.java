package com.rfizzle.prosperity.advancement;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Registers Prosperity's custom criterion triggers and holds their singletons.
 *
 * <p>Vanilla registers its triggers through the package-private {@code CriteriaTriggers.register}, so a
 * mod registers into {@link BuiltInRegistries#TRIGGER_TYPES} directly — the same registry that backer
 * codec ({@code CriteriaTriggers.CODEC}) resolves criteria against, so a datagen'd advancement and the
 * live server refer to the same trigger id. Call {@link #register()} once from
 * {@link Prosperity#onInitialize()} (and it also runs during datagen bootstrap, so the provider can
 * reference {@link #INSTANCED_LOOT}).
 */
public final class ProsperityCriteria {

    /** Fired once per real instanced-loot generation; backs every milestone advancement (issue #50). */
    public static final InstancedLootTrigger INSTANCED_LOOT = new InstancedLootTrigger();

    private ProsperityCriteria() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, Prosperity.id("instanced_loot"), INSTANCED_LOOT);
    }
}
