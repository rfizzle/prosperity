package com.rfizzle.prosperity.client;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.hud.PeekHint;
import com.rfizzle.prosperity.config.ProsperityConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * One-time discovery hint for the hold-to-peek loot detail panel (S-082). On each world join, until the
 * player has opened the panel once, this posts a chat line naming the bound peek key so the mod's
 * richest feedback surface is discoverable without a Controls-menu visit. The "seen once" flag lives in
 * {@link ProsperityConfig.ClientConfig#peekHintDismissed} and is set by the panel renderer on first
 * open; {@link PeekHint#shouldSend} also suppresses the hint when the key is unbound.
 */
public final class PeekHintMessage {

    private PeekHintMessage() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            LocalPlayer player = client.player;
            if (player == null) {
                return;
            }
            boolean keyUnbound = ProsperityClient.KEY_PEEK_LOOT_DETAIL == null
                    || ProsperityClient.KEY_PEEK_LOOT_DETAIL.isUnbound();
            if (!PeekHint.shouldSend(Prosperity.getConfig().client.peekHintDismissed, keyUnbound)) {
                return;
            }
            player.displayClientMessage(
                    Component.translatable("chat.prosperity.peek_hint",
                            ProsperityClient.KEY_PEEK_LOOT_DETAIL.getTranslatedKeyMessage()),
                    false);
        }));
    }
}
