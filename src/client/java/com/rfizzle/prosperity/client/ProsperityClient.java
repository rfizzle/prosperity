package com.rfizzle.prosperity.client;

import com.rfizzle.prosperity.client.hud.ProsperityHudOverlay;
import com.rfizzle.prosperity.client.network.ClientProtectionState;
import com.rfizzle.prosperity.client.network.ProsperityClientNetworking;
import com.rfizzle.prosperity.client.render.UnlootedOverlayRenderer;
import com.rfizzle.prosperity.loot.ContainerProtection;
import net.fabricmc.api.ClientModInitializer;

public class ProsperityClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ProsperityClientNetworking.init();
        UnlootedOverlayRenderer.register();
        ProsperityHudOverlay.register();
        // Let the common break-protection mixin read the client's queried multiplier (S-017).
        ContainerProtection.setClientView(ClientProtectionState.get());
    }
}
