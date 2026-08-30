package com.rfizzle.prosperity.api;

import com.rfizzle.prosperity.Prosperity;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>A listener that throws is caught, logged once at {@code WARN} naming the listener class, and
 * skipped &mdash; it can never break loot generation or the listeners registered after it, which
 * still fire against the same context (API-STANDARD §3.1). What the aborted listener leaves behind:
 * <ul>
 *   <li>{@link LootModifierContext#luck() luck} and {@link LootModifierContext#stackMultiplier()
 *       stackMultiplier} are single validated scalar writes, so they hold whatever the listener
 *       last committed &mdash; never a torn value.</li>
 *   <li>{@link LootModifierContext#customData() customData} is a plain {@code CompoundTag}: every
 *       put the listener made before throwing survives, so a later listener may read a record the
 *       earlier one only half wrote. Listeners that publish a multi-key record should assemble it
 *       in a local tag and {@code put} it in one step, and readers should treat a missing sibling
 *       key as "absent" rather than assume a complete record.</li>
 * </ul>
 */
@Stable
@FunctionalInterface
public interface LootModifierCallback {

    /** One-shot gate so a listener that throws on every generation logs its stack trace once. */
    AtomicBoolean LISTENER_FAILURE_LOGGED = new AtomicBoolean(false);

    Event<LootModifierCallback> EVENT = EventFactory.createArrayBacked(LootModifierCallback.class,
            listeners -> context -> {
                for (LootModifierCallback listener : listeners) {
                    try {
                        listener.onModifyLoot(context);
                    } catch (VirtualMachineError e) {
                        throw e; // OOME/SOE: the JVM is gone, not the guest
                    } catch (Throwable t) {
                        // Throwable, not Exception: a listener compiled against an older signature
                        // throws Error (AbstractMethodError, NoClassDefFoundError), which an
                        // Exception catch would let escape and abandon the rest of the chain.
                        if (LISTENER_FAILURE_LOGGED.compareAndSet(false, true)) {
                            Prosperity.LOGGER.warn("A LootModifierCallback listener {} threw; skipping",
                                    listener.getClass().getName(), t);
                        }
                    }
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
