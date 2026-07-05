package com.rfizzle.prosperity.loot.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit checks for the structure completion bonus's MC-free pieces: the stable per-instance
 * ledger key and the piece-box-to-chunk-span expansion. The census and award flow need a live
 * {@code ServerLevel} and are covered by {@code StructureCompletionGameTest}.
 */
class StructureCompletionTest {

    @Test
    void instanceKeyIsStableAndReadable() {
        String key = StructureCompletion.instanceKey(
                ResourceLocation.withDefaultNamespace("stronghold"), new ChunkPos(12, -34));
        assertEquals("minecraft:stronghold@12,-34", key,
                "the ledger key is the structure id at its start chunk");
    }

    @Test
    void distinctInstancesGetDistinctKeys() {
        ResourceLocation stronghold = ResourceLocation.withDefaultNamespace("stronghold");
        String first = StructureCompletion.instanceKey(stronghold, new ChunkPos(0, 0));
        String second = StructureCompletion.instanceKey(stronghold, new ChunkPos(0, 1));
        assertTrue(!first.equals(second), "different start chunks are different instances");
    }

    @Test
    void chunkSpanCoversEveryIntersectedChunk() {
        // A box from block (0,0,0) to (17,10,5) spans chunks x 0..1, z 0..0.
        Set<Long> span = StructureCompletion.chunkSpan(List.of(new BoundingBox(0, 0, 0, 17, 10, 5)));
        assertEquals(Set.of(ChunkPos.asLong(0, 0), ChunkPos.asLong(1, 0)), span,
                "the span covers exactly the chunks the box intersects");
    }

    @Test
    void chunkSpanHandlesNegativeCoordinates() {
        // Block -1 lies in chunk -1; a box straddling the origin spans both sides.
        Set<Long> span = StructureCompletion.chunkSpan(List.of(new BoundingBox(-1, 0, -1, 0, 0, 0)));
        assertEquals(Set.of(ChunkPos.asLong(-1, -1), ChunkPos.asLong(-1, 0),
                        ChunkPos.asLong(0, -1), ChunkPos.asLong(0, 0)), span,
                "negative block coordinates floor into the negative chunk");
    }

    @Test
    void orderedChunkSpanWalksNearestFirst() {
        // A 3-chunk row scanned from its east end walks east-to-west; ties break by packed key.
        List<Long> ordered = StructureCompletion.orderedChunkSpan(
                List.of(new BoundingBox(0, 0, 0, 47, 10, 5)), new ChunkPos(2, 0));
        assertEquals(List.of(ChunkPos.asLong(2, 0), ChunkPos.asLong(1, 0), ChunkPos.asLong(0, 0)),
                ordered, "chunks are visited nearest the triggering container first");
    }

    @Test
    void chunkSpanDeduplicatesOverlappingBoxes() {
        // Two piece boxes sharing a chunk (mineshaft corridors) contribute it once.
        Set<Long> span = StructureCompletion.chunkSpan(List.of(
                new BoundingBox(0, 0, 0, 5, 5, 5),
                new BoundingBox(3, 0, 3, 15, 5, 15)));
        assertEquals(Set.of(ChunkPos.asLong(0, 0)), span,
                "overlapping boxes within one chunk yield that chunk once");
    }
}
