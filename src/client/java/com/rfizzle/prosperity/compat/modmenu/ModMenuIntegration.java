package com.rfizzle.prosperity.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu entry point (the {@code modmenu} entrypoint) -- hands Mod Menu the Cloth Config screen
 * when both mods are present. Only Mod Menu loads this class, and the Cloth-built screen lives in
 * {@link ProsperityConfigScreen} behind an {@code isModLoaded} guard, so Mod Menu without Cloth
 * degrades to "no config screen" instead of a {@code NoClassDefFoundError} (mc-config).
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return parent -> null;
        }
        return ProsperityConfigScreen::create;
    }
}
