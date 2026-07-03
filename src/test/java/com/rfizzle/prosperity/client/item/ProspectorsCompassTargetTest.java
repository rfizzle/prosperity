package com.rfizzle.prosperity.client.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProspectorsCompassTargetTest {

    private static final Vec3 ORIGIN = new Vec3(0.5, 0.5, 0.5);

    @Test
    void noCandidatesYieldsNull() {
        assertNull(ProspectorsCompassClient.selectTarget(null, List.of(), ORIGIN));
    }

    @Test
    void picksNearestCandidate() {
        BlockPos near = new BlockPos(5, 0, 0);
        BlockPos far = new BlockPos(50, 0, 0);
        assertEquals(near, ProspectorsCompassClient.selectTarget(null, List.of(far, near), ORIGIN));
    }

    @Test
    void staleCurrentIsReplaced() {
        BlockPos gone = new BlockPos(3, 0, 0);
        BlockPos remaining = new BlockPos(40, 0, 0);
        assertEquals(remaining,
                ProspectorsCompassClient.selectTarget(gone, List.of(remaining), ORIGIN));
    }

    @Test
    void currentSticksAgainstMarginallyCloserCandidate() {
        BlockPos current = new BlockPos(10, 0, 0);
        BlockPos slightlyCloser = new BlockPos(9, 0, 0);
        assertEquals(current, ProspectorsCompassClient.selectTarget(current,
                List.of(slightlyCloser, current), ORIGIN));
    }

    @Test
    void currentLosesToClearlyCloserCandidate() {
        BlockPos current = new BlockPos(20, 0, 0);
        BlockPos muchCloser = new BlockPos(5, 0, 0);
        assertEquals(muchCloser, ProspectorsCompassClient.selectTarget(current,
                List.of(muchCloser, current), ORIGIN));
    }

    @Test
    void candidateWinsAtExactHysteresisBoundary() {
        // The hysteresis margin is inclusive on the switching side: a candidate exactly
        // HYSTERESIS_BLOCKS closer (13.0 vs 15.0 blocks from the origin center) takes over.
        BlockPos current = new BlockPos(15, 0, 0);
        BlockPos exactlyTwoCloser = new BlockPos(13, 0, 0);
        assertEquals(exactlyTwoCloser, ProspectorsCompassClient.selectTarget(current,
                List.of(exactlyTwoCloser, current), ORIGIN));
    }

    @Test
    void currentIsKeptWhenItIsStillNearest() {
        BlockPos current = new BlockPos(5, 0, 0);
        BlockPos farther = new BlockPos(30, 0, 0);
        assertEquals(current, ProspectorsCompassClient.selectTarget(current,
                List.of(farther, current), ORIGIN));
    }
}
