package com.rfizzle.prosperity.compat.index;

import com.rfizzle.prosperity.compat.emi.EmiIndexRefresh;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Refreshes the loaded recipe viewers after a synced loot index lands on the client (S-047), so their
 * "Loot Tables" tab reflects the server's rows on a remote dedicated server.
 *
 * <p>Only EMI exposes a usable reload, so only EMI is force-refreshed. REI and JEI have no safe
 * programmatic reload (REI offers only fragile staged-pipeline internals; JEI none), so they rely on
 * the sync landing before they build their lists — correct on first join, the common case. They pick
 * up a live {@code /reload} on rejoin or a manual resource reload (F3+T). The snapshot is published for
 * all three regardless, so each shows rows on join.
 */
public final class LootIndexViewerRefresh {

    private LootIndexViewerRefresh() {
    }

    public static void refreshViewers() {
        if (FabricLoader.getInstance().isModLoaded("emi")) {
            EmiIndexRefresh.reload();
        }
    }
}
