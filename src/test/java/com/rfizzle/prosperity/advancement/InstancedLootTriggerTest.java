package com.rfizzle.prosperity.advancement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.advancement.InstancedLootTrigger.TriggerInstance;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Predicate-matrix tests for the milestone criterion (issue #50). {@code matches} is the entire
 * gating logic behind every advancement in the tab, so it is unit tested directly: a tier instance
 * fires only for its exact tier, a threshold instance fires at and above its boundary and never
 * below, and each instance ignores the fields it does not set. Bootstraps Minecraft because loading
 * {@link TriggerInstance} initializes its codec (which references the vanilla entity-predicate codec).
 */
class InstancedLootTriggerTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tierInstanceMatchesOnlyItsTier() {
        TriggerInstance frontier = TriggerInstance.tier("frontier");
        assertTrue(frontier.matches("frontier", 0L, 0), "must fire for its own tier");
        assertFalse(frontier.matches("wilderness", 0L, 0), "must not fire for another tier");
        assertFalse(frontier.matches("local", 999L, 99), "must not fire for another tier regardless of totals");
    }

    @Test
    void tierInstanceIgnoresRunningTotals() {
        TriggerInstance depths = TriggerInstance.tier("depths");
        assertTrue(depths.matches("depths", 0L, 0), "a tier instance leaves the total fields unset, so they must not gate it");
    }

    @Test
    void containerThresholdIsInclusiveAndFloored() {
        TriggerInstance ten = TriggerInstance.containers(10L);
        assertFalse(ten.matches("frontier", 9L, 0), "below the threshold must not fire");
        assertTrue(ten.matches("frontier", 10L, 0), "exactly at the threshold must fire");
        assertTrue(ten.matches("depths", 11L, 0), "above the threshold must fire, and tier is irrelevant");
    }

    @Test
    void structureThresholdIsInclusiveAndFloored() {
        TriggerInstance eight = TriggerInstance.structures(8);
        assertFalse(eight.matches("frontier", 500L, 7), "below the distinct-structure threshold must not fire");
        assertTrue(eight.matches("frontier", 0L, 8), "exactly at the threshold must fire, and the container total is irrelevant");
        assertTrue(eight.matches("frontier", 0L, 15), "above the threshold must fire");
    }

    @Test
    void codecRoundTripsEachInstanceShape() {
        assertRoundTrip(TriggerInstance.tier("outlands"));
        assertRoundTrip(TriggerInstance.containers(250L));
        assertRoundTrip(TriggerInstance.structures(3));
    }

    private static void assertRoundTrip(TriggerInstance instance) {
        JsonElement json = TriggerInstance.CODEC.encodeStart(JsonOps.INSTANCE, instance)
                .getOrThrow(err -> new AssertionError("encode failed: " + err));
        TriggerInstance decoded = TriggerInstance.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(err -> new AssertionError("decode failed: " + err));
        assertEquals(instance, decoded, "the instance must survive a codec round-trip unchanged");
    }
}
