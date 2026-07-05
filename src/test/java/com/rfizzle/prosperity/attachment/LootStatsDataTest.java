package com.rfizzle.prosperity.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the loot-stats value class: counting semantics and the codec's
 * round-trip, defaults, determinism, and tamper hardening. The recording call sites and the
 * command output are runtime concerns covered by {@code LootStatsGameTest}.
 */
class LootStatsDataTest {

    private static final ResourceLocation STRONGHOLD = ResourceLocation.withDefaultNamespace("stronghold");
    private static final ResourceLocation MONUMENT = ResourceLocation.withDefaultNamespace("monument");

    @Test
    void recordGenerationCountsEveryBucket() {
        LootStatsData stats = new LootStatsData();
        stats.recordGeneration("frontier", STRONGHOLD, true);
        stats.recordGeneration("frontier", null, false);
        stats.recordGeneration("local", MONUMENT, false);

        assertEquals(3, stats.containersLooted(), "each generation counts once");
        assertEquals(Map.of("frontier", 2L, "local", 1L), stats.tierCounts(),
                "tier buckets accumulate per effective tier name");
        assertEquals(Map.of("minecraft:stronghold", 1L, "minecraft:monument", 1L),
                stats.structureCounts(), "structure buckets key on the structure id");
        assertEquals(2, stats.distinctStructures(), "distinct structures is the bucket count");
        assertEquals(1, stats.injectedRewards(), "only a placed injection counts");
    }

    @Test
    void structurelessGenerationsLeaveStructuresUntouched() {
        LootStatsData stats = new LootStatsData();
        stats.recordGeneration("local", null, false);
        assertEquals(1, stats.containersLooted());
        assertEquals(0, stats.distinctStructures(), "a wild chest is in no structure bucket");
    }

    @Test
    void codecRoundTripsAllFields() {
        LootStatsData stats = new LootStatsData();
        stats.recordGeneration("frontier", STRONGHOLD, true);
        stats.recordGeneration("wilderness", STRONGHOLD, false);

        JsonElement json = LootStatsData.CODEC.encodeStart(JsonOps.INSTANCE, stats).getOrThrow();
        LootStatsData back = LootStatsData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(stats.containersLooted(), back.containersLooted());
        assertEquals(stats.tierCounts(), back.tierCounts());
        assertEquals(stats.structureCounts(), back.structureCounts());
        assertEquals(stats.injectedRewards(), back.injectedRewards());
    }

    @Test
    void emptyObjectDecodesToAllZeros() {
        // Every field is optional so a pre-feature (or empty) save reads as zero stats.
        LootStatsData stats = LootStatsData.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
        assertEquals(0, stats.containersLooted());
        assertTrue(stats.tierCounts().isEmpty());
        assertEquals(0, stats.distinctStructures());
        assertEquals(0, stats.injectedRewards());
    }

    @Test
    void encodeOrderIsDeterministic() {
        // Two objects built in opposite insertion order serialize identically (TreeMap-backed).
        LootStatsData first = new LootStatsData();
        first.recordGeneration("frontier", STRONGHOLD, false);
        first.recordGeneration("local", MONUMENT, false);
        LootStatsData second = new LootStatsData();
        second.recordGeneration("local", MONUMENT, false);
        second.recordGeneration("frontier", STRONGHOLD, false);

        assertEquals(LootStatsData.CODEC.encodeStart(JsonOps.INSTANCE, first).getOrThrow(),
                LootStatsData.CODEC.encodeStart(JsonOps.INSTANCE, second).getOrThrow(),
                "serialization order must not depend on insertion order");
    }

    @Test
    void tamperedSaveIsClampedOnDecode() {
        JsonObject json = new JsonObject();
        json.addProperty("containersLooted", -5);
        json.addProperty("injectedRewards", -1);
        JsonObject tiers = new JsonObject();
        tiers.addProperty("frontier", -3);
        tiers.addProperty("local", 2);
        json.add("tierCounts", tiers);

        LootStatsData stats = LootStatsData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(0, stats.containersLooted(), "negative totals clamp to zero");
        assertEquals(0, stats.injectedRewards(), "negative totals clamp to zero");
        assertEquals(Map.of("local", 2L), stats.tierCounts(), "non-positive entries are dropped");
    }

    @Test
    void mapsAreHardCappedAgainstBloat() {
        // A tampered save with more keys than the cap is truncated deterministically on decode…
        JsonObject json = new JsonObject();
        JsonObject structures = new JsonObject();
        for (int i = 0; i < LootStatsData.MAX_TRACKED_KEYS + 50; i++) {
            structures.addProperty(String.format("mod:structure_%05d", i), 1);
        }
        json.add("structureCounts", structures);
        LootStatsData decoded = LootStatsData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(LootStatsData.MAX_TRACKED_KEYS, decoded.distinctStructures(),
                "decode must truncate to the cap");

        // …and live recording admits no new bucket past the cap while still counting the total.
        decoded.recordGeneration("local", ResourceLocation.parse("mod:one_too_many"), false);
        assertEquals(LootStatsData.MAX_TRACKED_KEYS, decoded.distinctStructures(),
                "a new structure key past the cap gains no bucket");
        assertEquals(1, decoded.containersLooted(), "the generation still counts in the totals");
    }

    @Test
    void viewsAreUnmodifiable() {
        LootStatsData stats = new LootStatsData();
        stats.recordGeneration("local", null, false);
        assertThrows(UnsupportedOperationException.class, () -> stats.tierCounts().put("x", 1L));
        assertThrows(UnsupportedOperationException.class, () -> stats.structureCounts().clear());
        assertEquals(List.of("local"), List.copyOf(stats.tierCounts().keySet()));
    }
}
