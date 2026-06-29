package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-JUnit checks for the per-player roll seed derivation (S-006). */
class InstancedLootGeneratorTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void sameSeedAndPlayerIsDeterministic() {
        assertEquals(InstancedLootGenerator.playerSeed(1234L, A, 0L),
                InstancedLootGenerator.playerSeed(1234L, A, 0L),
                "the same (base, UUID, salt) triple must roll the same seed");
    }

    @Test
    void differentPlayersDiffer() {
        assertNotEquals(InstancedLootGenerator.playerSeed(1234L, A, 0L),
                InstancedLootGenerator.playerSeed(1234L, B, 0L),
                "distinct players must get distinct seeds from one container");
    }

    @Test
    void differentBaseSeedsDiffer() {
        assertNotEquals(InstancedLootGenerator.playerSeed(1L, A, 0L),
                InstancedLootGenerator.playerSeed(2L, A, 0L),
                "distinct container seeds must derive distinct per-player seeds");
    }

    @Test
    void differentSaltsDiffer() {
        assertNotEquals(InstancedLootGenerator.playerSeed(1234L, A, 0L),
                InstancedLootGenerator.playerSeed(1234L, A, 1L),
                "a refresh (distinct salt) must re-roll a distinct per-player seed");
        assertNotEquals(InstancedLootGenerator.playerSeed(1234L, A, 1L),
                InstancedLootGenerator.playerSeed(1234L, A, 2L),
                "successive refreshes must keep producing distinct seeds");
    }

    @Test
    void sameSaltIsReproducible() {
        assertEquals(InstancedLootGenerator.playerSeed(1234L, A, 3L),
                InstancedLootGenerator.playerSeed(1234L, A, 3L),
                "the same refresh count must reproduce the same seed across a reload");
    }

    @Test
    void neverReturnsTheRandomizeSentinel() {
        // A base seed equal to the UUID's mix would otherwise cancel to 0 (at salt 0), which vanilla
        // treats as "pick a fresh random seed". The guard must lift it off zero.
        long mix = (A.getMostSignificantBits() * 0x9E3779B97F4A7C15L)
                ^ Long.rotateLeft(A.getLeastSignificantBits(), 32);
        assertNotEquals(0L, InstancedLootGenerator.playerSeed(mix, A, 0L),
                "the derived seed must never be the 0 randomize sentinel");
    }
}
