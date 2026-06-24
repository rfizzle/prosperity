package com.rfizzle.prosperity.client;

import com.rfizzle.prosperity.client.network.ProsperityClientNetworking;
import com.rfizzle.prosperity.client.render.UnlootedOverlayRenderer;
import net.fabricmc.api.ClientModInitializer;

public class ProsperityClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        /* TODO: HUD badge (SPEC §14) */
        ProsperityClientNetworking.init();
        UnlootedOverlayRenderer.register();
    }
}
