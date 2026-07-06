package com.rfizzle.prosperity.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the party loot mode in-use lock (no game bootstrap). */
class SharedInstanceLocksTest {

    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TEAM = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String CONTAINER = "minecraft:overworld@42";
    private static final String OTHER_CONTAINER = "minecraft:overworld@43";

    @AfterEach
    void reset() {
        SharedInstanceLocks.clear();
    }

    @Test
    void firstAcquireSucceedsSecondFails() {
        assertTrue(SharedInstanceLocks.tryAcquire(TEAM, CONTAINER), "the first opener takes the lock");
        assertTrue(SharedInstanceLocks.isHeld(TEAM, CONTAINER));
        assertFalse(SharedInstanceLocks.tryAcquire(TEAM, CONTAINER), "a teammate is refused while held");
    }

    @Test
    void releaseFreesTheLock() {
        SharedInstanceLocks.tryAcquire(TEAM, CONTAINER);
        SharedInstanceLocks.release(TEAM, CONTAINER);
        assertFalse(SharedInstanceLocks.isHeld(TEAM, CONTAINER));
        assertTrue(SharedInstanceLocks.tryAcquire(TEAM, CONTAINER), "the lock is reusable after release");
    }

    @Test
    void differentContainersDoNotContend() {
        assertTrue(SharedInstanceLocks.tryAcquire(TEAM, CONTAINER));
        assertTrue(SharedInstanceLocks.tryAcquire(TEAM, OTHER_CONTAINER),
                "the same team can open a different container at once");
    }

    @Test
    void differentTeamsDoNotContend() {
        assertTrue(SharedInstanceLocks.tryAcquire(TEAM, CONTAINER));
        assertTrue(SharedInstanceLocks.tryAcquire(OTHER_TEAM, CONTAINER),
                "two teams never share a lock on one container");
    }

    @Test
    void clearReleasesEverything() {
        SharedInstanceLocks.tryAcquire(TEAM, CONTAINER);
        SharedInstanceLocks.tryAcquire(OTHER_TEAM, OTHER_CONTAINER);
        SharedInstanceLocks.clear();
        assertFalse(SharedInstanceLocks.isHeld(TEAM, CONTAINER));
        assertFalse(SharedInstanceLocks.isHeld(OTHER_TEAM, OTHER_CONTAINER));
    }
}
