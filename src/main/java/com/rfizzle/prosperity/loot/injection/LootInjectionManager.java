package com.rfizzle.prosperity.loot.injection;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Datapack-driven additive loot injection (S-014, SPEC §5): the generation-time entry point that
 * places one weighted-random eligible bonus item into a freshly rolled container. The datapack
 * loading and registry state live in {@link InjectionRegistry}; the eligibility and weighted-draw
 * logic in {@link InjectionSelector}. This class owns the deterministic seed mixing, the two draw
 * salts, and the pluggable {@link InjectedStackFinalizer}.
 *
 * <p>Injection is purely additive and orthogonal to vanilla loot: it never replaces a rolled item,
 * only fills one empty slot with a tier-exclusive reward. A group carries a {@code chance} (default
 * {@code 1.0}): at generation time each eligible group rolls it independently from the injection
 * draw's deterministic {@link RandomSource} <em>before</em> the weighted draw, and a failed roll
 * drops the group from the pool — so a bonus item appears in only a fraction of generations. When
 * every group fails, nothing is placed.
 */
public final class LootInjectionManager {

    /** Decorrelates the injection draw from the loot-roll seed so it does not mirror the rolled items. */
    private static final long INJECTION_SALT = 0x9E3779B97F4A7C15L;
    /** Decorrelates the structure-completion draw from the per-container injection draw. */
    private static final long COMPLETION_SALT = 0xC2B2AE3D27D4EB4FL;
    /** Log-once latch for a failing finalizer, so a persistent failure cannot warn-spam per generation. */
    private static final AtomicBoolean FINALIZER_FAILURE_LOGGED = new AtomicBoolean(false);

    /**
     * The last-writer-wins finalizer slot applied to the drawn stack before placement; defaults to
     * the identity. Lets a compat integration rewrite an injected prototype at generation time (e.g.
     * roll dynamic enchantments) without the manager referencing the sibling mod.
     */
    private static volatile InjectedStackFinalizer finalizer = (level, stack, tier, random) -> stack;

    private LootInjectionManager() {
    }

    /**
     * Rewrites a drawn injected stack before it is placed. Receives the generating level, a private
     * copy of the prototype stack, the container's resolved tier, and the injection draw's
     * deterministic {@link RandomSource} (consume freely; the draw is already made). Return the stack
     * to place — the input itself to leave the prototype as authored.
     */
    @FunctionalInterface
    public interface InjectedStackFinalizer {
        ItemStack apply(ServerLevel level, ItemStack stack, DistanceTier tier, RandomSource random);
    }

    /** Install {@code finalizer} as the injected-stack finalizer (last writer wins; null ignored). */
    public static void setInjectedStackFinalizer(@Nullable InjectedStackFinalizer finalizer) {
        if (finalizer != null) {
            LootInjectionManager.finalizer = finalizer;
        }
    }

    /**
     * Register the load hooks. The server lifecycle events (rather than a plain resource listener) give
     * us the server's frozen registry access, which the enchantment components on injected items need
     * to deserialize, plus the loaded loot tables to expand the wildcard. {@code SERVER_STARTING} covers
     * the initial load on every server type (the headless gametest server included, which loads its data
     * pack at construction without firing a reload), and {@code END_DATA_PACK_RELOAD} catches a runtime
     * {@code /reload}.
     */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                InjectionRegistry.reload(server.registryAccess(), server.getResourceManager()));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) ->
                InjectionRegistry.reload(server.registryAccess(), resourceManager));
    }

    /**
     * Add one eligible injected item to {@code items} for a generation of {@code table} at {@code tier}
     * in {@code level}, placed in the first empty slot. An entry applies when its {@code min_tier} is
     * at or below {@code tier} and its {@code dimensions} are empty or contain the level's dimension.
     * No-op when injection is disabled, the table is absent, no entry is eligible, or the container is
     * full. The draw is deterministic for a given {@code (seedBase, salt, uuid)} triple, so a
     * regeneration with the same salt regenerates the same bonus item; {@code salt} is the player's
     * refresh count under {@code randomizeLootOnRefresh} (and {@code 0} otherwise), letting the bonus
     * re-roll on a refresh in lockstep with the main loot. Each eligible group's {@code chance} is
     * rolled from the same {@link RandomSource} before the weighted draw, so the whether and the
     * which of the bonus re-roll together. The drawn stack passes through the
     * installed {@link InjectedStackFinalizer} before placement, isolated so a throwing or
     * empty-returning finalizer falls back to the drawn stack unchanged. Returns whether an item
     * was actually placed, so callers can count rewards received rather than attempts (loot stats).
     */
    public static boolean augment(NonNullList<ItemStack> items, @Nullable ResourceKey<LootTable> table,
            DistanceTier tier, ServerLevel level, long seedBase, long salt, UUID uuid) {
        if (!Prosperity.getConfig().enableLootInjection || table == null) {
            return false;
        }
        long mixed = seedBase * INJECTION_SALT
                ^ uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32)
                ^ Long.rotateLeft(salt * INJECTION_SALT, 29);
        RandomSource random = RandomSource.create(mixed == 0L ? 1L : mixed);
        ItemStack injected = InjectionSelector.pick(InjectionRegistry.forTable(table.location()), tier,
                level.dimension().location(), random, level.registryAccess(), true);
        if (injected == null || injected.isEmpty()) {
            return false;
        }
        injected = finalizeInjected(level, injected, tier, random);
        for (int slot = 0; slot < items.size(); slot++) {
            if (items.get(slot).isEmpty()) {
                items.set(slot, injected);
                return true;
            }
        }
        return false;
    }

    /**
     * One extra reward for the structure completion bonus: the same weighted tier-and-dimension
     * eligible draw as {@link #augment}, decorrelated by {@link #COMPLETION_SALT} so it never mirrors
     * the container's regular injected item, and run through the installed finalizer. Deterministic
     * for a given {@code (seedBase, salt, uuid, structureSeed)} tuple; {@code structureSeed}
     * identifies the structure instance, so two structures completed by the same player draw
     * differently even when their final containers share a table and a {@code 0} seed (every
     * template-placed chest does). Returns {@code null} when the table is absent or has no eligible
     * entry. Gated by {@code enableStructureCompletionBonus} at the caller, not by
     * {@code enableLootInjection} &mdash; the completion bonus is its own feature that draws from the
     * injection pool, not an injection. For the same reason it bypasses the per-group {@code chance}
     * gate: the completion is already earned, so the bonus draws over every tier-and-dimension
     * eligible entry rather than paying out only a fraction of the time.
     */
    @Nullable
    public static ItemStack completionBonus(@Nullable ResourceKey<LootTable> table, DistanceTier tier,
            ServerLevel level, long seedBase, long salt, UUID uuid, long structureSeed) {
        if (table == null) {
            return null;
        }
        long mixed = seedBase * INJECTION_SALT
                ^ uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32)
                ^ Long.rotateLeft(salt * INJECTION_SALT, 29)
                ^ Long.rotateLeft(structureSeed * COMPLETION_SALT, 17)
                ^ COMPLETION_SALT;
        RandomSource random = RandomSource.create(mixed == 0L ? 1L : mixed);
        ItemStack drawn = InjectionSelector.pick(InjectionRegistry.forTable(table.location()), tier,
                level.dimension().location(), random, level.registryAccess(), false);
        if (drawn == null || drawn.isEmpty()) {
            return null;
        }
        return finalizeInjected(level, drawn, tier, random);
    }

    /**
     * Run {@code stack} through the installed finalizer with host-side error isolation: a finalizer
     * that throws or returns null/empty must never break loot generation, so any such outcome falls
     * back to the drawn stack unchanged.
     */
    private static ItemStack finalizeInjected(ServerLevel level, ItemStack stack, DistanceTier tier,
            RandomSource random) {
        try {
            ItemStack finalized = finalizer.apply(level, stack, tier, random);
            return finalized == null || finalized.isEmpty() ? stack : finalized;
        } catch (Throwable e) {
            if (FINALIZER_FAILURE_LOGGED.compareAndSet(false, true)) {
                Prosperity.LOGGER.warn("Injected-stack finalizer failed; placing the authored stack"
                        + " for this and any further failing generations", e);
            }
            return stack;
        }
    }
}
