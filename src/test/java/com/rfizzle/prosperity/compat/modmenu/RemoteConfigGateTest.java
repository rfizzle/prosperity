package com.rfizzle.prosperity.compat.modmenu;

import com.rfizzle.prosperity.config.ProsperityConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for the Mod Menu remote read-only gate (issue #124). */
class RemoteConfigGateTest {

    @Test
    void lockedOnlyOnRemoteServer() {
        // A world is loaded and this client is not hosting it → remote server → locked.
        assertTrue(RemoteConfigGate.serverSettingsLocked(true, false));
    }

    @Test
    void singleplayerAndHostStayEditable() {
        // Level loaded but this client hosts the integrated server (singleplayer or opened to LAN).
        assertFalse(RemoteConfigGate.serverSettingsLocked(true, true));
    }

    @Test
    void mainMenuStaysEditable() {
        // No level loaded (main menu) → nothing to lock, regardless of any stale server flag.
        assertFalse(RemoteConfigGate.serverSettingsLocked(false, false));
        assertFalse(RemoteConfigGate.serverSettingsLocked(false, true));
    }

    @Test
    void unlockedPersistsWorkingCopyVerbatim() {
        ProsperityConfig live = new ProsperityConfig();
        ProsperityConfig working = new ProsperityConfig();
        assertSame(working, RemoteConfigGate.persistedConfig(false, live, working));
    }

    @Test
    void lockedKeepsLiveServerFieldsAndTakesWorkingClientBlock() {
        // Live (on-disk) config: protection off. The player's working copy has a stale/edited server
        // field the read-only widget could otherwise write back, plus a genuine client-block edit.
        ProsperityConfig live = new ProsperityConfig();
        live.enableContainerProtection = false;
        live.client.hudOffsetX = 4;

        ProsperityConfig working = new ProsperityConfig();
        working.enableContainerProtection = true;      // must NOT reach the persisted file
        working.client.hudOffsetX = 99;                // must reach the persisted file

        ProsperityConfig persisted = RemoteConfigGate.persistedConfig(true, live, working);

        assertNotSame(live, persisted, "persisted must be a copy, not the live instance");
        assertFalse(persisted.enableContainerProtection, "server field stays the operator's live value");
        assertEquals(99, persisted.client.hudOffsetX, "client block is grafted from the working copy");
    }

    @Test
    void lockedPersistDoesNotMutateLive() {
        ProsperityConfig live = new ProsperityConfig();
        live.enableContainerProtection = false;
        ProsperityConfig working = new ProsperityConfig();
        working.enableContainerProtection = true;

        RemoteConfigGate.persistedConfig(true, live, working);

        assertFalse(live.enableContainerProtection, "the live config must be left untouched");
    }
}
