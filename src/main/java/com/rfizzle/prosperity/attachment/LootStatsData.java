package com.rfizzle.prosperity.attachment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Per-player loot statistics, stored as a persistent Fabric data attachment on the player (see
 * {@link ProsperityAttachments#LOOT_STATS}). Counts are recorded at loot <em>generation</em> time
 * — a first open or a refresh re-roll — never on a return visit, so they measure how much loot a
 * player has actually been served. Surfaced through {@code /prosperity stats} (SPEC §15).
 *
 * <p><b>Bounding:</b> the maps are keyed by tier name (bounded by the configured tier list) and
 * structure id (bounded by the structure registry), so the serialized footprint stays a few dozen
 * small entries even on a long-lived server; {@link #MAX_TRACKED_KEYS} hard-caps each map far above
 * organic growth so a tampered save cannot bloat playerdata. Both are {@link TreeMap}s so
 * serialization order is deterministic and saves diff cleanly.
 *
 * <p><b>Dirtying/threading:</b> a player entity serializes with its own playerdata on every save,
 * so in-place mutation needs no explicit dirty call; all access stays on the server thread. Every
 * write goes through {@link ProsperityAttachments#updateStats} for consistency with the suite's
 * single-choke-point discipline.
 */
public final class LootStatsData {

    /** Round-trips through the player's own NBT (Fabric serializes the attachment there). */
    public static final Codec<LootStatsData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.LONG.optionalFieldOf("containersLooted", 0L)
                            .forGetter(LootStatsData::containersLooted),
                    Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("tierCounts", Map.of())
                            .forGetter(d -> d.tierCounts),
                    Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("structureCounts", Map.of())
                            .forGetter(d -> d.structureCounts),
                    Codec.LONG.optionalFieldOf("injectedRewards", 0L)
                            .forGetter(LootStatsData::injectedRewards)
            ).apply(instance, LootStatsData::new));

    /**
     * Hard cap on keys per counting map — far above organic growth (a handful of tier names, a
     * few hundred structure ids on a heavily modded server), it only bites on a tampered save or a
     * pathological modpack; a generation whose key would exceed it still counts in the totals but
     * gains no new bucket.
     */
    static final int MAX_TRACKED_KEYS = 512;

    private long containersLooted;
    private final SortedMap<String, Long> tierCounts = new TreeMap<>();
    private final SortedMap<String, Long> structureCounts = new TreeMap<>();
    private long injectedRewards;

    public LootStatsData() {
    }

    /**
     * Codec constructor: clamps tampered negative totals, drops non-positive map entries, and
     * truncates each map to {@link #MAX_TRACKED_KEYS} (keeping the alphabetically-first keys, so
     * the trim is deterministic).
     */
    private LootStatsData(long containersLooted, Map<String, Long> tierCounts,
            Map<String, Long> structureCounts, long injectedRewards) {
        this.containersLooted = Math.max(0L, containersLooted);
        this.injectedRewards = Math.max(0L, injectedRewards);
        copyPositive(tierCounts, this.tierCounts);
        copyPositive(structureCounts, this.structureCounts);
    }

    private static void copyPositive(Map<String, Long> from, SortedMap<String, Long> into) {
        for (Map.Entry<String, Long> entry : from.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                into.put(entry.getKey(), entry.getValue());
            }
        }
        while (into.size() > MAX_TRACKED_KEYS) {
            into.remove(into.lastKey());
        }
    }

    /**
     * Count one loot generation: the container total, the effective tier's bucket, the structure's
     * bucket when the container sits in one, and the injected-reward total when this generation
     * actually placed one. Called once per generation (a double chest is one generation).
     */
    public void recordGeneration(String tierName, @Nullable ResourceLocation structure,
            boolean injectedPlaced) {
        containersLooted++;
        mergeBounded(tierCounts, tierName);
        if (structure != null) {
            mergeBounded(structureCounts, structure.toString());
        }
        if (injectedPlaced) {
            injectedRewards++;
        }
    }

    /** Increment {@code key}'s bucket, admitting a new key only under {@link #MAX_TRACKED_KEYS}. */
    private static void mergeBounded(SortedMap<String, Long> counts, String key) {
        if (counts.containsKey(key) || counts.size() < MAX_TRACKED_KEYS) {
            counts.merge(key, 1L, Long::sum);
        }
    }

    /** Total containers this player has had loot generated for (doubles count once). */
    public long containersLooted() {
        return containersLooted;
    }

    /** Generations per effective tier name, sorted by name. Unmodifiable view. */
    public Map<String, Long> tierCounts() {
        return Collections.unmodifiableSortedMap(tierCounts);
    }

    /** Generations per structure id the container sat in, sorted by id. Unmodifiable view. */
    public Map<String, Long> structureCounts() {
        return Collections.unmodifiableSortedMap(structureCounts);
    }

    /** Distinct structure types this player has looted a container in. */
    public int distinctStructures() {
        return structureCounts.size();
    }

    /** Injected-tier rewards actually received (an eligible entry drawn and placed). */
    public long injectedRewards() {
        return injectedRewards;
    }
}
