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
        assertEquals(InstancedLootGenerator.playerSeed(1234L, A),
                InstancedLootGenerator.playerSeed(1234L, A),
                "the same (base, UUID) pair must roll the same seed");
    }

    @Test
    void differentPlayersDiffer() {
        assertNotEquals(InstancedLootGenerator.playerSeed(1234L, A),
                InstancedLootGenerator.playerSeed(1234L, B),
                "distinct players must get distinct seeds from one container");
    }

    @Test
    void differentBaseSeedsDiffer() {
        assertNotEquals(InstancedLootGenerator.playerSeed(1L, A),
                InstancedLootGenerator.playerSeed(2L, A),
                "distinct container seeds must derive distinct per-player seeds");
    }

    @Test
    void neverReturnsTheRandomizeSentinel() {
        // A base seed equal to the UUID's mix would otherwise cancel to 0, which vanilla treats
        // as "pick a fresh random seed". The guard must lift it off zero.
        long mix = (A.getMostSignificantBits() * 0x9E3779B97F4A7C15L)
                ^ Long.rotateLeft(A.getLeastSignificantBits(), 32);
        assertNotEquals(0L, InstancedLootGenerator.playerSeed(mix, A),
                "the derived seed must never be the 0 randomize sentinel");
    }
}
