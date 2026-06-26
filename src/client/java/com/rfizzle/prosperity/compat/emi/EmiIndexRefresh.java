package com.rfizzle.prosperity.compat.emi;

import com.rfizzle.prosperity.Prosperity;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Best-effort EMI recipe reload (S-047): asks EMI to re-run its plugins so {@code ProsperityEmiPlugin}
 * re-reads the freshly-synced {@link com.rfizzle.prosperity.loot.index.LootIndexDataSource#snapshot()}
 * on a remote dedicated server (where the index arrives over the network after EMI first registers).
 *
 * <p>EMI's only reload entry point is the internal {@code dev.emi.emi.runtime.EmiReloadManager}, which
 * is absent from the {@code :api} artifact this mod compiles against — so it is reached by reflection.
 * The call is wrapped and latches off on the first failure (a future EMI version that moved or renamed
 * the symbol), after which the loot tab simply refreshes on rejoin like REI/JEI. Reflection means this
 * class carries no EMI import, so it is safe to load even when EMI is absent — though the caller still
 * gates on {@code isModLoaded}.
 */
public final class EmiIndexRefresh {

    private static volatile boolean unavailable = false;

    private EmiIndexRefresh() {
    }

    public static void reload() {
        if (unavailable) {
            return;
        }
        try {
            Class<?> reloadManager = Class.forName("dev.emi.emi.runtime.EmiReloadManager");
            MethodHandle reloadRecipes = MethodHandles.publicLookup()
                    .findStatic(reloadManager, "reloadRecipes", MethodType.methodType(void.class));
            reloadRecipes.invokeExact();
        } catch (Throwable t) {
            unavailable = true;
            Prosperity.LOGGER.warn("EMI loot-index reload unavailable; its loot tab will refresh on rejoin", t);
        }
    }
}
