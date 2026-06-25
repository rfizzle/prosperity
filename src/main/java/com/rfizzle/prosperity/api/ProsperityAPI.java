package com.rfizzle.prosperity.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Public, read-only API for Prosperity (Concord API Standard v1).
 *
 * <p>Currently the HUD coordination accessors of the Concord HUD Standard (§6): a lower-priority
 * HUD slot in a sibling mod queries these to offset past Prosperity's slot-3 tier badge without
 * hardcoding its height. Reflection-backed into the client overlay so this common-side class never
 * references client-only code.
 *
 * <p>Safe to use as a soft dependency: compile with {@code modCompileOnly} and guard call sites with
 * {@code FabricLoader.getInstance().isModLoaded("prosperity")}.
 */
@Stable
public final class ProsperityAPI {

    private ProsperityAPI() {
    }

    // Render-thread only — resolved once on the first ENV=CLIENT call.
    private static boolean hudHandlesResolved;
    private static MethodHandle isHudVisibleHandle;
    private static MethodHandle getHudHeightHandle;

    private static void resolveHudHandles() {
        if (hudHandlesResolved) {
            return;
        }
        hudHandlesResolved = true;
        try {
            Class<?> overlay = Class.forName("com.rfizzle.prosperity.client.hud.ProsperityHudOverlay");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isHudVisibleHandle = lookup.findStatic(overlay, "isHudVisible", MethodType.methodType(boolean.class));
            getHudHeightHandle = lookup.findStatic(overlay, "getHudHeight", MethodType.methodType(int.class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            isHudVisibleHandle = null;
            getHudHeightHandle = null;
        }
    }

    /**
     * HUD coordination accessor (Concord HUD-STANDARD §6): whether Prosperity's tier HUD badge is
     * currently being drawn. Safe to call unconditionally from common code on either side.
     *
     * <p>Reflection-backed into the client overlay so this class never references client-only code.
     * Documented sentinel: {@code false} on a dedicated server, when the HUD is config-disabled, or
     * when it is currently hidden (F1, open screen, spectator, death screen). Rendering coordination
     * only — never use for gameplay logic.
     *
     * @return true if the tier HUD badge is currently visible
     */
    public static boolean isHudVisible() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            resolveHudHandles();
            if (isHudVisibleHandle == null) {
                return false;
            }
            try {
                return (boolean) isHudVisibleHandle.invokeExact();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * HUD coordination accessor (Concord HUD-STANDARD §6): this element's current height contribution
     * in pixels (element + stacking gap), for a lower-priority HUD slot to offset past. Safe to call
     * unconditionally from common code on either side.
     *
     * <p>Reflection-backed into the client overlay. Documented sentinel: {@code 0} on a dedicated
     * server or whenever {@link #isHudVisible} is false; {@code 22} (20px standard element + 2px gap)
     * when visible.
     *
     * @return the element's height contribution in px, or 0 if not visible
     */
    public static int getHudHeight() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            resolveHudHandles();
            if (getHudHeightHandle == null) {
                return 0;
            }
            try {
                return (int) getHudHeightHandle.invokeExact();
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }
}
