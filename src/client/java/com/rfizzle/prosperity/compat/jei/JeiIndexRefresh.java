package com.rfizzle.prosperity.compat.jei;

/**
 * Bridges the live JEI runtime to the loot-index sync receiver (S-047) without leaking JEI types into
 * the viewer-agnostic refresh path. {@link ProsperityJeiPlugin} binds a refresh action once JEI hands
 * it the runtime ({@code onRuntimeAvailable}) and clears it on teardown ({@code onRuntimeUnavailable});
 * {@link com.rfizzle.prosperity.compat.index.LootIndexViewerRefresh} invokes it when a freshly-synced
 * index lands on a remote dedicated server.
 *
 * <p>The action is held as a plain {@link Runnable}, so this class carries no JEI import and is safe to
 * load even when JEI is absent — the action simply stays {@code null} and {@link #reload()} is a no-op
 * (the caller also gates on {@code isModLoaded}). Unlike EMI's reflective hook, the bound action drives
 * JEI's public {@code IRecipeManager} API, so no internal symbols are touched here.
 */
public final class JeiIndexRefresh {

    private static volatile Runnable refreshAction;

    private JeiIndexRefresh() {
    }

    static void bind(Runnable action) {
        refreshAction = action;
    }

    static void unbind() {
        refreshAction = null;
    }

    public static void reload() {
        Runnable action = refreshAction;
        if (action != null) {
            action.run();
        }
    }
}
