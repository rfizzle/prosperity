package com.rfizzle.prosperity.loot;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a container's loot table into a fresh per-player inventory. This mirrors vanilla's
 * {@code RandomizableContainer#unpackLootTable} but fills a transient {@link SimpleContainer}
 * instead of the block entity, so the result can be stored per-player in the instanced-loot attachment.
 *
 * <p>The roll seed is the container's preserved seed folded together with the player's UUID
 * ({@link #playerSeed}), so each player gets their own loot from the same container while a
 * given (seed, UUID) pair always rolls the same items &mdash; a return visit after a refresh
 * regenerates identically.
 *
 * <p>This is the seam later stories extend: distance/structure scaling (S-011/S-012) and the
 * loot-modifier event (S-013) hook the luck and post-resolution stacks here.
 */
public final class InstancedLootGenerator {

    private InstancedLootGenerator() {
    }

    /**
     * Generate {@code size} slots of loot from {@code tableKey} with {@code player}'s context.
     * Returns an all-empty list when the table is absent or empty.
     */
    public static NonNullList<ItemStack> generate(ServerLevel level, BlockPos pos,
            @Nullable ResourceKey<LootTable> tableKey, long seed, ServerPlayer player, int size) {
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        if (tableKey == null) {
            return items;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        if (table == LootTable.EMPTY) {
            return items;
        }

        SimpleContainer container = new SimpleContainer(size);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, pos.getCenter())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.CHEST);
        table.fill(container, params, playerSeed(seed, player.getUUID()));

        for (int slot = 0; slot < size; slot++) {
            items.set(slot, container.getItem(slot));
        }
        return items;
    }

    /**
     * Fold a container's base loot seed together with a player's UUID into a stable per-player
     * roll seed. Deterministic for a given (base, UUID) pair and never {@code 0L}, which vanilla
     * treats as "pick a fresh random seed" &mdash; the guard keeps generation reproducible even
     * for naturally-placed chests whose base seed is {@code 0}.
     */
    static long playerSeed(long base, UUID uuid) {
        long mixed = base ^ (uuid.getMostSignificantBits() * 0x9E3779B97F4A7C15L)
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32);
        return mixed == 0L ? 1L : mixed;
    }
}
