package com.rfizzle.prosperity.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Mutable per-generation context passed to {@link LootModifierCallback} listeners. Created fresh for
 * each instanced-loot generation and populated with the post-scaling values (SPEC section 4). Part of
 * Prosperity's stable API surface (Concord API Standard v1).
 *
 * <p>The container, position, and loot table are read-only; {@code luck} and {@code stackMultiplier}
 * are mutable and accumulate across listeners. After all listeners run, the final {@link #luck()}
 * feeds the {@code LootParams} for table resolution and the final {@link #stackMultiplier()} scales
 * the rolled stack counts.
 */
@Stable
public interface LootModifierContext {

    /** The player receiving the loot. */
    ServerPlayer player();

    /** The container's position (a block position, or the block a container minecart occupies). */
    BlockPos containerPos();

    /** The loot table being resolved. */
    ResourceLocation lootTable();

    /** Current effective luck (distance/structure quality plus any listener contributions). */
    float luck();

    /** Replace the effective luck outright. */
    void setLuck(float luck);

    /** Add {@code bonus} to the current luck. */
    void addLuck(float bonus);

    /** Current stack-size multiplier (seeded from distance/structure scaling). */
    float stackMultiplier();

    /** Replace the stack-size multiplier outright. */
    void setStackMultiplier(float multiplier);

    /** Multiply the current stack multiplier by {@code factor}. */
    void multiplyStacks(float factor);

    /**
     * Unstructured key-value bag for inter-mod communication within a single generation. Prosperity
     * neither reads nor writes it; it exists purely for third-party use and is fresh each event.
     */
    CompoundTag customData();
}
