package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload;
import com.rfizzle.prosperity.network.UnlootedContainersS2CPayload.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server-side scan that answers a client's per-chunk indicator request (S-009): every instanced loot
 * container in a chunk that the requesting player has not yet generated, as
 * {@link UnlootedContainersS2CPayload.Entry entries} the client can place and size.
 *
 * <p>A container is unlooted for a player when it is a loot container ({@link InstancedLootInteraction#isLootContainer})
 * and the player has no stored inventory there. It also counts as unlooted once that player's loot has
 * passed its refresh cooldown ({@link LootRefresh}, S-016): the scan treats an expired instance as
 * unlooted again even before the player reopens it, so the sparkle returns with no per-tick work. A
 * double chest yields a single entry anchored at its
 * primary half (the lexicographically smaller position): the generated secondary carries a redirect and
 * is skipped, and a pre-generation secondary is skipped by chest geometry, so only the primary emits —
 * sized to the combined 54 slots.
 *
 * <p>Block entities only: container minecarts are entities the block-keyed protocol cannot address and
 * are covered by the entity-anchored sync (S-038).
 */
public final class UnlootedContainers {

    private UnlootedContainers() {
    }

    /**
     * The unlooted loot containers in {@code chunkPos} for {@code player}, or an empty list when the
     * chunk is not loaded (the request is never allowed to force-load or generate a chunk).
     */
    public static List<Entry> scanChunk(ServerLevel level, ChunkPos chunkPos, UUID player) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
            if (!(be.getValue() instanceof RandomizableContainerBlockEntity container)) {
                continue;
            }
            BlockPos pos = be.getKey();
            InstancedLootData data = ProsperityAttachments.get(container);
            if (!InstancedLootInteraction.isLootContainer(container, data)) {
                continue;
            }
            // Blacklisted containers never show the unlooted sparkle (SPEC §7).
            if (InstancedLootInteraction.isBlacklisted(container.getLootTable())) {
                continue;
            }

            // The generated secondary half of a double chest holds no inventory of its own (it redirects
            // to the primary); never emit an indicator for it.
            if (data != null && data.getRedirect() != null) {
                continue;
            }
            int slots = container.getContainerSize();
            BlockState state = chunk.getBlockState(pos);
            if (isDoubleChest(state)) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                if (!pos.equals(DoubleChestLayout.primary(pos, other))) {
                    // Secondary half: the primary emits the single combined indicator.
                    continue;
                }
                slots = DoubleChestLayout.TOTAL_SLOTS;
            }

            // Looted for this player once they have a stored inventory here, unless the refresh
            // cooldown has elapsed (S-016) — then the sparkle reappears until they regenerate.
            if (data != null && data.hasInventory(player)
                    && !LootRefresh.isExpired(data, player, level.getGameTime())) {
                continue;
            }

            entries.add(Entry.of(pos, slots));
            if (entries.size() >= UnlootedContainersS2CPayload.MAX_ENTRIES) {
                Prosperity.LOGGER.warn("Unlooted-container scan of {} hit the {}-entry cap; truncating",
                        chunkPos, UnlootedContainersS2CPayload.MAX_ENTRIES);
                break;
            }
        }
        return entries;
    }

    private static boolean isDoubleChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }
}
