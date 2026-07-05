package com.rfizzle.prosperity.loot.completion;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.loot.BlockEntityContainerAdapter;
import com.rfizzle.prosperity.loot.ContainerAdapter;
import com.rfizzle.prosperity.loot.DoubleChestLayout;
import com.rfizzle.prosperity.loot.InstancedLootInteraction;
import com.rfizzle.prosperity.loot.LootNotification;
import com.rfizzle.prosperity.loot.LootScaling;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Per-player structure completion bonus. When a player generates loot for the last remaining
 * instanced container inside a structure instance's piece bounds, they earn a one-time bonus: one
 * extra reward drawn from the tier-appropriate injection pool ({@link LootInjectionManager}), placed
 * in that final container, plus an action-bar fanfare. Awards are recorded per player per structure
 * instance in {@link StructureCompletionState}, so a loot refresh (or the awarding container being
 * broken) can never re-arm the bonus.
 *
 * <p><b>What counts.</b> Block-entity loot containers inside any of the structure's piece bounding
 * boxes, under the same filters as the unlooted-indicator scan: it is a loot container
 * ({@link InstancedLootInteraction#isLootContainer}), it is not blacklisted (SPEC §7), and a double
 * chest counts once via its primary half. Container minecarts neither count toward nor trigger
 * completion &mdash; entities in unvisited chunks cannot be enumerated deterministically, so
 * including them would make completion silently unreliable in minecart-bearing structures. A
 * container broken before being looted no longer exists to be enumerated, so the remaining set
 * shrinks and completion stays reachable. Only structures with more than one qualifying container
 * ever award (no "completion" for a lone buried-treasure chest).
 *
 * <p><b>Cost.</b> The census walks the chunks intersecting the structure's piece boxes via
 * {@code ServerLevel#getChunk}, which loads (and if necessary generates) each chunk &mdash; required
 * for a correct total, since an ungenerated piece still contains future containers. Three bounds
 * keep this off the common path: a player who already completed the structure skips the census
 * entirely; the walk visits chunks nearest the triggering container first and early-exits at the
 * first container the player has not looted, so a partially-looted structure usually stops within
 * the chunks around the player; and the one full sweep a completion requires caches the instance's
 * container positions ({@link #CONTAINER_CACHE}), so every later census of that structure &mdash;
 * another player finishing it &mdash; walks just those positions instead of the span.
 *
 * <p>The census-and-award core ({@link #checkAndAward}) takes explicit piece boxes and an instance
 * key so gametests can drive it against placed containers; the thin production entry point
 * ({@link #onLootGenerated}) resolves the live {@link StructureStart}, which needs real worldgen and
 * is covered by the same documented manual in-world check as structure tier overrides (S-012).
 */
public final class StructureCompletion {

    /** Structures with fewer qualifying containers than this never award a completion bonus. */
    static final int MIN_QUALIFYING_CONTAINERS = 2;

    private StructureCompletion() {
    }

    /**
     * Completion hook for one first-generation of instanced loot. Called from the generation paths
     * after the player's instance (and any injected reward) has been stored on the attachment, so the
     * just-generated container already reads as looted for the player. No-op when the feature is off,
     * the source is not a block-entity container (minecarts neither count nor trigger), or the
     * container sits in no structure.
     */
    public static void onLootGenerated(ContainerAdapter adapter, ServerPlayer player,
            @Nullable ResourceKey<LootTable> table, DistanceTier tier, long seed, long salt) {
        if (!Prosperity.getConfig().enableStructureCompletionBonus) {
            return;
        }
        if (!(adapter instanceof BlockEntityContainerAdapter)) {
            return;
        }
        ServerLevel level = adapter.level();
        BlockPos pos = BlockPos.containing(adapter.origin());
        LootScaling.ResolvedStart resolved = LootScaling.resolveStructureStart(level, pos);
        if (resolved == null) {
            return;
        }
        checkAndAward(level, adapter, player, table, tier, seed, salt,
                instanceKey(resolved.id(), resolved.start().getChunkPos()),
                pieceBoxes(resolved.start()), resolved.id());
    }

    /**
     * The stable per-instance ledger key for a structure start: its registry id and start chunk
     * (deterministic for a given instance; the dimension is implicit in which level's
     * {@link StructureCompletionState} holds the entry).
     */
    public static String instanceKey(ResourceLocation structure, ChunkPos startChunk) {
        return structure + "@" + startChunk.x + "," + startChunk.z;
    }

    /**
     * The structure's piece bounding boxes &mdash; the regions that actually contain its containers.
     * Scanning pieces rather than the overall start box keeps sprawling starts (mineshafts,
     * strongholds) from pulling the whole hull of empty chunks between corridors into the census.
     * Falls back to the start's own box for a pathological start with no pieces.
     */
    static List<BoundingBox> pieceBoxes(StructureStart start) {
        List<StructurePiece> pieces = start.getPieces();
        if (pieces.isEmpty()) {
            return List.of(start.getBoundingBox());
        }
        List<BoundingBox> boxes = new ArrayList<>(pieces.size());
        for (StructurePiece piece : pieces) {
            boxes.add(piece.getBoundingBox());
        }
        return boxes;
    }

    /**
     * The census-and-award core: if {@code player} has not yet been awarded {@code structureKey}'s
     * bonus, count the qualifying containers inside {@code boxes}; when none remain unlooted for the
     * player and at least {@link #MIN_QUALIFYING_CONTAINERS} exist, record the completion, place one
     * bonus reward in the just-generated container ({@code adapter}), and send the fanfare. The
     * ledger is marked before the grant, and the grant is gated on {@code markCompleted}'s "newly
     * added" result, so no interleaving can double-pay. Returns whether the award fired on this call.
     *
     * <p>Public (with explicit boxes and key rather than a {@link StructureStart}) so gametests can
     * drive the full census-and-award loop against placed containers &mdash; the gametest world
     * generates no structures, the same testability split as structure tier overrides (S-012).
     */
    public static boolean checkAndAward(ServerLevel level, ContainerAdapter adapter, ServerPlayer player,
            @Nullable ResourceKey<LootTable> table, DistanceTier tier, long seed, long salt,
            String structureKey, List<BoundingBox> boxes, ResourceLocation structureId) {
        UUID uuid = player.getUUID();
        StructureCompletionState state = StructureCompletionState.get(level);
        if (state.isCompleted(structureKey, uuid)) {
            return false;
        }
        // The container cache is keyed with the dimension: the ledger is per-level, but the cache is
        // a single static map and two dimensions can host the same structure at the same start chunk.
        String cacheKey = level.dimension().location() + "#" + structureKey;
        ChunkPos origin = new ChunkPos(BlockPos.containing(adapter.origin()));
        Census census = census(level, uuid, boxes, origin, cacheKey);
        if (!census.complete() || census.total() < MIN_QUALIFYING_CONTAINERS) {
            return false;
        }
        if (!state.markCompleted(structureKey, uuid)) {
            return false;
        }
        // The instance key seeds the draw so two structures completed by the same player pay
        // different bonuses even when their final containers share a (table, seed, salt) triple.
        placeBonus(level, adapter, player, table, tier, seed, salt, structureKey.hashCode());
        LootNotification.sendStructureCleared(player, structureId);
        return true;
    }

    /**
     * Bounded session cache of an instance's discovered container representatives, keyed by
     * dimension-qualified instance key. A full sweep force-loads every chunk in the structure's piece
     * span; once one has run to completion the container positions are fixed (containers never appear
     * in already-generated chunks), so later censuses of the same instance &mdash; another player
     * finishing the same structure &mdash; walk just those positions instead of the whole span.
     * Positions whose container has since been broken re-qualify as absent, so the cache can never
     * hide a break. Server-thread confined (the loot-generation path), access-ordered, and capped so
     * a long-running server cannot accrue one entry per structure ever visited.
     */
    private static final int CONTAINER_CACHE_LIMIT = 64;
    private static final Map<String, List<BlockPos>> CONTAINER_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<BlockPos>> eldest) {
                    return size() > CONTAINER_CACHE_LIMIT;
                }
            };

    /**
     * Whether every qualifying container inside {@code boxes} has been looted by {@code player}, and
     * how many exist in total. Early-exits on the first unlooted container ({@code total} is then
     * partial &mdash; it is only meaningful when {@code complete}). Chunks intersecting the boxes are
     * loaded (and generated if needed) so never-visited pieces still count; they are walked nearest
     * the triggering container first ({@code origin}), so the early exit finds the unlooted chest
     * beside the player before wandering the far end of a sprawling span. A completed sweep's
     * container positions are cached under {@code cacheKey} ({@code null} skips the cache) and reused
     * by later censuses of the same instance.
     */
    public static Census census(ServerLevel level, UUID player, List<BoundingBox> boxes,
            ChunkPos origin, @Nullable String cacheKey) {
        List<BlockPos> cached = cacheKey == null ? null : CONTAINER_CACHE.get(cacheKey);
        if (cached != null) {
            int total = 0;
            for (BlockPos pos : cached) {
                LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()));
                Candidate candidate = qualify(chunk, pos, boxes);
                if (candidate == null) {
                    // Broken (or otherwise no longer qualifying) since the sweep: the set shrinks.
                    continue;
                }
                total++;
                if (!candidate.lootedBy(player)) {
                    return new Census(false, total);
                }
            }
            return new Census(true, total);
        }

        int total = 0;
        List<BlockPos> representatives = new ArrayList<>();
        // The span is taken over boxes inflated by one block so a double chest straddling a piece-box
        // edge cannot land its primary half in an unscanned chunk; membership below still tests the
        // original boxes.
        for (long chunkKey : orderedChunkSpan(inflated(boxes), origin)) {
            LevelChunk chunk = level.getChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                Candidate candidate = qualify(chunk, pos, boxes);
                if (candidate == null) {
                    continue;
                }
                total++;
                if (!candidate.lootedBy(player)) {
                    // Partial sweep: the representative list is incomplete, so nothing is cached.
                    return new Census(false, total);
                }
                representatives.add(pos);
            }
        }
        if (cacheKey != null) {
            CONTAINER_CACHE.put(cacheKey, List.copyOf(representatives));
        }
        return new Census(true, total);
    }

    /**
     * The container at {@code pos} as a census candidate, or {@code null} when it does not qualify:
     * not a loot container, blacklisted (SPEC §7), a double chest's non-primary half (the primary
     * alone represents the pair, the same rule as the unlooted-indicator scan), or outside
     * {@code boxes} (a straddling pair counts when either half is inside).
     */
    @Nullable
    private static Candidate qualify(LevelChunk chunk, BlockPos pos, List<BoundingBox> boxes) {
        if (!(chunk.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) {
            return null;
        }
        InstancedLootData data = ProsperityAttachments.get(container);
        if (!InstancedLootInteraction.isLootContainer(container, data)) {
            return null;
        }
        if (InstancedLootInteraction.isBlacklisted(container.getLootTable())) {
            return null;
        }
        if (data != null && data.getRedirect() != null) {
            return null;
        }
        BlockState blockState = chunk.getBlockState(pos);
        if (isDoubleChest(blockState)) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(blockState));
            if (!pos.equals(DoubleChestLayout.primary(pos, other))) {
                return null;
            }
            if (!contains(boxes, pos) && !contains(boxes, other)) {
                return null;
            }
        } else if (!contains(boxes, pos)) {
            return null;
        }
        return new Candidate(data);
    }

    /** A qualifying container's per-player state; {@code data} is null until anyone has generated. */
    private record Candidate(@Nullable InstancedLootData data) {

        /**
         * "Looted" is has-ever-generated: a refresh-expired instance keeps its per-player entry until
         * reopened (and regenerating re-records it in the same call), so an expiry can never
         * un-complete a structure mid-census.
         */
        boolean lootedBy(UUID player) {
            return data != null && data.hasGenerated(player);
        }
    }

    /** The census verdict: whether no unlooted container remains, and the qualifying total counted. */
    public record Census(boolean complete, int total) {
    }

    /** The packed {@link ChunkPos} keys of every chunk intersecting any of {@code boxes}, deduplicated. */
    public static Set<Long> chunkSpan(List<BoundingBox> boxes) {
        Set<Long> chunks = new HashSet<>();
        for (BoundingBox box : boxes) {
            int minX = SectionPos.blockToSectionCoord(box.minX());
            int maxX = SectionPos.blockToSectionCoord(box.maxX());
            int minZ = SectionPos.blockToSectionCoord(box.minZ());
            int maxZ = SectionPos.blockToSectionCoord(box.maxZ());
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    chunks.add(ChunkPos.asLong(x, z));
                }
            }
        }
        return chunks;
    }

    /**
     * The {@link #chunkSpan} of {@code boxes} ordered by Chebyshev distance from {@code origin}
     * (ties broken by packed key for determinism), so a census visits the chunks around the
     * triggering container first.
     */
    public static List<Long> orderedChunkSpan(List<BoundingBox> boxes, ChunkPos origin) {
        List<Long> ordered = new ArrayList<>(chunkSpan(boxes));
        ordered.sort(Comparator
                .comparingInt((Long key) -> Math.max(Math.abs(ChunkPos.getX(key) - origin.x),
                        Math.abs(ChunkPos.getZ(key) - origin.z)))
                .thenComparing(Comparator.naturalOrder()));
        return ordered;
    }

    /** Each box grown by one block on every axis. */
    private static List<BoundingBox> inflated(List<BoundingBox> boxes) {
        List<BoundingBox> out = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            out.add(box.inflatedBy(1));
        }
        return out;
    }

    private static boolean contains(List<BoundingBox> boxes, BlockPos pos) {
        for (BoundingBox box : boxes) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDoubleChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * Draw the completion bonus for the final container and place it in the first empty slot of the
     * player's just-stored instance, through the attachment write choke point so the block entity is
     * re-dirtied. The stored list is the same one the caller is about to serve, so the reward shows
     * in the opening screen. A full container or an empty draw (no eligible injection entry for the
     * table) places nothing &mdash; the completion itself, and its fanfare, still stand.
     */
    private static void placeBonus(ServerLevel level, ContainerAdapter adapter, ServerPlayer player,
            @Nullable ResourceKey<LootTable> table, DistanceTier tier, long seed, long salt,
            long structureSeed) {
        ItemStack bonus = LootInjectionManager.completionBonus(table, tier, level, seed, salt,
                player.getUUID(), structureSeed);
        if (bonus == null || bonus.isEmpty()) {
            return;
        }
        adapter.update(data -> {
            NonNullList<ItemStack> inventory = data.getInventory(player.getUUID());
            if (inventory == null) {
                return;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (inventory.get(slot).isEmpty()) {
                    inventory.set(slot, bonus);
                    return;
                }
            }
        });
    }
}
