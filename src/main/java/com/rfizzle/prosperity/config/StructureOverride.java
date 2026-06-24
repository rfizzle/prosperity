package com.rfizzle.prosperity.config;

/**
 * A per-structure override of the resolved distance tier. Applied after the base distance
 * tier is computed for a container generated inside the named structure.
 *
 * @param structure the structure id (e.g. {@code "minecraft:monument"})
 * @param mode      how the override combines with the distance tier:
 *                  {@code "fixed"} replaces it, {@code "minimum"} raises it to at least
 *                  {@code tier}, {@code "maximum"} caps it at {@code tier}
 * @param tier      the {@link DistanceTier#name()} this override refers to
 */
public record StructureOverride(String structure, String mode, String tier) {
}
