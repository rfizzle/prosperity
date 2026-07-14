package com.rfizzle.prosperity.compat.modmenu;

import com.rfizzle.prosperity.config.ProsperityConfig;

/**
 * Pure decision core for the Mod Menu screen's remote-server read-only mode (issue #124).
 *
 * <p>Server-authoritative settings (every top-level {@link ProsperityConfig} field) belong to the
 * server operator when the client is connected to a remote server; only the nested
 * {@link ProsperityConfig#client} block is the player's on any world. This class holds the two
 * decisions the screen shell wires up, kept free of any {@code net.minecraft.*} / Cloth Config types
 * so they are plain-JUnit testable. {@link ModMenuIntegration} (the thin client shell) reads the
 * connection state, seeds the widgets, and applies Cloth's read-only requirement.
 */
public final class RemoteConfigGate {

    private RemoteConfigGate() {
    }

    /**
     * Whether the server-authoritative entries must render read-only. True exactly when a world is
     * loaded and this client is <em>not</em> hosting it — i.e. a remote dedicated/LAN server whose
     * config the client cannot change. Singleplayer, an integrated LAN host, and the main menu (no
     * level loaded) all leave every entry editable.
     */
    public static boolean serverSettingsLocked(boolean levelLoaded, boolean hasIntegratedServer) {
        return levelLoaded && !hasIntegratedServer;
    }

    /**
     * The config the save should write to {@code prosperity.json}.
     *
     * <p>When unlocked, that is the screen's edited {@code working} copy verbatim. When locked, only
     * the {@link ProsperityConfig#client} block is the player's to change, so the live server-field
     * values are kept untouched (deep-copied from {@code live}) and the edited client block is grafted
     * on top. This is what stops a remote player from rewriting their local file's server-authoritative
     * values through the read-only widgets: those widgets show the server's synced values, and saving
     * must not persist them over the player's own local defaults.
     */
    public static ProsperityConfig persistedConfig(boolean locked, ProsperityConfig live, ProsperityConfig working) {
        if (!locked) {
            return working;
        }
        ProsperityConfig persisted = ProsperityConfig.fromJson(live.toJson());
        persisted.client = working.client;
        return persisted;
    }
}
