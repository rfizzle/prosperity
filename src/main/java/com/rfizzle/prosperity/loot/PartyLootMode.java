package com.rfizzle.prosperity.loot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Lifecycle wiring for party loot mode's transient state (issue #53). The persistent side — the team
 * membership snapshot — rides the container attachment and needs no registration; this only clears the
 * two in-memory helpers ({@link PartyGraceTracker} leave-grace memory and {@link SharedInstanceLocks}
 * open-instance locks) on server stop, so a subsequent world load on the same JVM (singleplayer world
 * switch, dedicated-server restart in tests) starts with no stale grace timers or leaked locks.
 */
public final class PartyLootMode {

    private PartyLootMode() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PartyGraceTracker.clear();
            SharedInstanceLocks.clear();
        });
    }
}
