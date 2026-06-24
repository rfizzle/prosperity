package com.rfizzle.prosperity.client.network;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.ProsperityConfig;

/**
 * Client-side snapshot of the server-config values the indicator renderer needs (SPEC §2). The
 * server pushes these on join and on {@code /prosperity reload} via {@link com.rfizzle.prosperity.network.ConfigSyncS2CPayload};
 * until a sync lands (e.g. before login) the local config supplies sane defaults.
 *
 * <p>Read and written only on the client thread (network receivers hop through
 * {@code context.client().execute(...)}, the renderer runs on the render thread), so plain fields
 * suffice — no synchronization.</p>
 */
public final class ClientProsperityData {

    private static boolean visualIndicators = Prosperity.getConfig().enableVisualIndicators;
    private static int renderDistance = Prosperity.getConfig().indicatorRenderDistance;
    private static int xrayDistance = Prosperity.getConfig().indicatorXrayDistance;

    private ClientProsperityData() {
    }

    /** Adopt the server's authoritative indicator config from a {@code ConfigSyncS2C} payload. */
    public static void setServerConfig(ProsperityConfig config) {
        visualIndicators = config.enableVisualIndicators;
        renderDistance = config.indicatorRenderDistance;
        xrayDistance = config.indicatorXrayDistance;
    }

    public static boolean visualIndicators() {
        return visualIndicators;
    }

    public static int renderDistance() {
        return renderDistance;
    }

    public static int xrayDistance() {
        return xrayDistance;
    }
}
