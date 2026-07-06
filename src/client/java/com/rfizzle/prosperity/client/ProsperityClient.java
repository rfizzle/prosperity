package com.rfizzle.prosperity.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rfizzle.prosperity.client.hud.LootDetailPanelRenderer;
import com.rfizzle.prosperity.client.hud.ProsperityHudOverlay;
import com.rfizzle.prosperity.client.item.ProspectorsCompassClient;
import com.rfizzle.prosperity.client.network.ClientProtectionState;
import com.rfizzle.prosperity.client.network.ProsperityClientNetworking;
import com.rfizzle.prosperity.client.render.UnlootedOverlayRenderer;
import com.rfizzle.prosperity.loot.ContainerProtection;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class ProsperityClient implements ClientModInitializer {

    /**
     * Hold-to-peek keybind for the loot detail panel (S-035). Bound to Left Alt by default — the panel
     * never captures the mouse or opens a {@link net.minecraft.client.gui.screens.Screen}, so the
     * conflict risk of a real default is low, and it makes the mod's richest feedback surface
     * discoverable without a Controls-menu visit. A player who has already assigned their own key keeps
     * it: Fabric only applies the registered default for a binding absent from {@code options.txt}.
     */
    public static KeyMapping KEY_PEEK_LOOT_DETAIL;

    @Override
    public void onInitializeClient() {
        KEY_PEEK_LOOT_DETAIL = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.prosperity.peek_loot_detail",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.prosperity"));

        ProsperityClientNetworking.init();
        ProspectorsCompassClient.register();
        UnlootedOverlayRenderer.register();
        ProsperityHudOverlay.register();
        PeekHintMessage.register();
        HudRenderCallback.EVENT.register(new LootDetailPanelRenderer());
        // Let the common break-protection mixin read the client's queried multiplier (S-017).
        ContainerProtection.setClientView(ClientProtectionState.get());
    }
}
