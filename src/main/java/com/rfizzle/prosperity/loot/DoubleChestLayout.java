package com.rfizzle.prosperity.loot;

import net.minecraft.core.BlockPos;

/**
 * Pure layout math for a double chest's combined instanced inventory. A double chest is served as
 * one 54-slot instance: the primary half fills slots {@code 0..26} and the secondary half fills
 * {@code 27..53}. The primary half is the lexicographically smaller of the two positions, compared
 * by {@code x}, then {@code z}, then {@code y}, so both halves agree on which one holds the shared
 * inventory regardless of which was clicked.
 *
 * <p>No Minecraft world or Fabric state is touched here, so the selection and offsets are covered by
 * a plain unit test.
 */
public final class DoubleChestLayout {

    /** Slots contributed by each half (one chest's worth). */
    public static final int PRIMARY_SLOTS = 27;

    /** Total slots in the combined double-chest instance. */
    public static final int TOTAL_SLOTS = 54;

    private DoubleChestLayout() {
    }

    /**
     * The primary half of the pair: the lexicographically smaller position by {@code (x, z, y)}.
     * Ties on every coordinate (the same position passed twice) return {@code a}.
     */
    public static BlockPos primary(BlockPos a, BlockPos b) {
        int byX = Integer.compare(a.getX(), b.getX());
        if (byX != 0) {
            return byX < 0 ? a : b;
        }
        int byZ = Integer.compare(a.getZ(), b.getZ());
        if (byZ != 0) {
            return byZ < 0 ? a : b;
        }
        return a.getY() <= b.getY() ? a : b;
    }

    /** The secondary half: whichever of the pair is not {@link #primary}. */
    public static BlockPos secondary(BlockPos a, BlockPos b) {
        return primary(a, b).equals(a) ? b : a;
    }
}
