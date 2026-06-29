package com.rfizzle.prosperity.loot;

import java.util.UUID;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a container's loot table into a fresh per-player inventory. This mirrors vanilla's
 * {@code RandomizableContainer#unpackLootTable} but fills a transient {@link SimpleContainer}
 * instead of the block entity, so the result can be stored per-player in the instanced-loot attachment.
 *
 * <p>The roll seed is the container's preserved seed folded together with the player's UUID and a
 * refresh salt ({@link #playerSeed}), so each player gets their own loot from the same container while
 * a given (seed, UUID, salt) triple always rolls the same items. With a {@code 0} salt — the salt the
 * caller passes unless {@code randomizeLootOnRefresh} is on — a return visit after a refresh
 * regenerates identically; a non-zero salt (the player's refresh count) re-rolls fresh-but-reproducible
 * items on each refresh.
 *
 * <p>Distance and structure scaling (S-011/S-012) and the loot-modifier event (S-013) are resolved
 * upstream by the caller, which fires {@link LootModifiers} and passes the final {@code luck} and
 * {@code stackMultiplier} in: the luck biases the {@code LootParams} before resolution and the
 * multiplier scales the rolled counts afterwards.
 */
public final class InstancedLootGenerator {

    private InstancedLootGenerator() {
    }

    /**
     * Generate {@code size} slots of loot from {@code tableKey} with {@code player}'s context.
     * {@code origin} is the loot source's world position (a block's center or a minecart's live
     * position). {@code salt} decorrelates a refresh re-roll from earlier ones (the player's refresh
     * count under {@code randomizeLootOnRefresh}; {@code 0} for a deterministic roll). {@code luck} is
     * folded into the {@code LootParams} before resolution and {@code stackMultiplier} scales the rolled
     * counts afterwards (both already finalized by the caller's scaling + loot-modifier pass; {@code 0}
     * luck and a {@code 1.0} multiplier are no-ops). Returns an all-empty list when the table is absent
     * or empty.
     */
    public static NonNullList<ItemStack> generate(ServerLevel level, Vec3 origin,
            @Nullable ResourceKey<LootTable> tableKey, long seed, long salt, ServerPlayer player, int size,
            float luck, double stackMultiplier) {
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
                .withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(luck)
                .create(LootContextParamSets.CHEST);
        table.fill(container, params, playerSeed(seed, player.getUUID(), salt));

        double multiplier = stackMultiplier;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                stack.setCount(LootScaling.scaledCount(stack.getCount(), stack.getMaxStackSize(), multiplier));
            }
            items.set(slot, stack);
        }
        return items;
    }

    /**
     * Decorrelates successive refresh re-rolls from each other; a {@code 0} salt contributes nothing,
     * so it leaves the unsalted derivation byte-for-byte unchanged.
     */
    private static final long REFRESH_SALT_MIX = 0xD1B54A32D192ED03L;

    /**
     * Fold a container's base loot seed together with a player's UUID and a refresh {@code salt} into a
     * stable per-player roll seed. Deterministic for a given (base, UUID, salt) triple and never
     * {@code 0L}, which vanilla treats as "pick a fresh random seed" &mdash; the guard keeps generation
     * reproducible even for naturally-placed chests whose base seed is {@code 0}. A {@code 0} salt
     * reproduces the unsalted seed exactly, so deterministic refreshes are unaffected.
     */
    static long playerSeed(long base, UUID uuid, long salt) {
        long mixed = base ^ (uuid.getMostSignificantBits() * 0x9E3779B97F4A7C15L)
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 32)
                ^ Long.rotateLeft(salt * REFRESH_SALT_MIX, 29);
        return mixed == 0L ? 1L : mixed;
    }
}
