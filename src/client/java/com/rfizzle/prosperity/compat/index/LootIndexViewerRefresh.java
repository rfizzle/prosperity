package com.rfizzle.prosperity.compat.index;

import com.rfizzle.prosperity.compat.emi.EmiIndexRefresh;
import com.rfizzle.prosperity.compat.jei.JeiIndexRefresh;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Refreshes the loaded recipe viewers after a synced loot index lands on the client (S-047), so their
 * "Loot Tables" tab reflects the server's rows on a remote dedicated server.
 *
 * <p>EMI and JEI are force-refreshed: EMI through its internal reload reached by reflection, JEI through
 * its public {@code IRecipeManager} runtime API. Both end up deterministic regardless of whether the
 * sync beat the viewer's own list build. REI exposes no safe programmatic reload (only fragile
 * staged-pipeline internals), so it relies on the sync landing before it builds its list — correct on
 * first join, the common case — and otherwise picks up a live {@code /reload} on rejoin or a manual
 * resource reload (F3+T). The snapshot is published for all three regardless, so each shows rows on join.
 */
public final class LootIndexViewerRefresh {

    private LootIndexViewerRefresh() {
    }

    public static void refreshViewers() {
        if (FabricLoader.getInstance().isModLoaded("emi")) {
            EmiIndexRefresh.reload();
        }
        if (FabricLoader.getInstance().isModLoaded("jei")) {
            JeiIndexRefresh.reload();
        }
    }
}
