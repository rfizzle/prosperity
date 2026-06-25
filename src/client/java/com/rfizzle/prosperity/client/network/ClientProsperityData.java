package com.rfizzle.prosperity.client.network;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;

/**
 * Client-side snapshot of the server config the indicator renderer (SPEC §2) and the tier HUD
 * badge (SPEC §14) need. The server pushes its authoritative config on join and on
 * {@code /prosperity reload} via {@link com.rfizzle.prosperity.network.ConfigSyncS2CPayload}; until a
 * sync lands (e.g. before login) the local config supplies sane defaults. The whole config is
 * retained — not just the indicator distances — because the HUD resolves the player's tier against
 * the server's {@code distanceTiers}/{@code endAlwaysMaxTier}, which a client can read no other way.
 *
 * <p>The reference is swapped atomically (a {@code volatile} field) by the network receiver
 * (hopping through {@code context.client().execute(...)}) and read by the renderer/HUD on the render
 * thread, so no further synchronization is needed.</p>
 */
public final class ClientProsperityData {

    private static volatile ProsperityConfig config = Prosperity.getConfig();

    private ClientProsperityData() {
    }

    /** Adopt the server's authoritative config from a {@code ConfigSyncS2C} payload. */
    public static void setServerConfig(ProsperityConfig synced) {
        config = synced;
    }

    /** The current server-authoritative config view (the local config until the first sync). Never null. */
    public static ProsperityConfig config() {
        return config;
    }

    public static boolean visualIndicators() {
        return config.enableVisualIndicators;
    }

    public static int renderDistance() {
        return config.indicatorRenderDistance;
    }

    public static int xrayDistance() {
        return config.indicatorXrayDistance;
    }
}
