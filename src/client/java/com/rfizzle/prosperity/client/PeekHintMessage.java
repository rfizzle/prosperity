package com.rfizzle.prosperity.client;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.hud.PeekHint;
import com.rfizzle.prosperity.config.ProsperityConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Discovery hint for the hold-to-peek loot detail panel (S-082). Until the player has opened the panel
 * once, this posts a chat line naming the bound peek key on every fifth eligible world join, so the
 * mod's richest feedback surface is discoverable without a Controls-menu visit while not nagging every
 * session. The "seen once" flag lives in {@link ProsperityConfig.ClientConfig#peekHintDismissed} (set by
 * the panel renderer on first open) and the eligible-join tally in
 * {@link ProsperityConfig.ClientConfig#peekHintJoins}; {@link PeekHint#eligible} also skips the hint
 * entirely when the key is unbound.
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
            ProsperityConfig.ClientConfig cfg = Prosperity.getConfig().client;
            boolean keyUnbound = ProsperityClient.KEY_PEEK_LOOT_DETAIL == null
                    || ProsperityClient.KEY_PEEK_LOOT_DETAIL.isUnbound();
            if (!PeekHint.eligible(cfg.peekHintDismissed, keyUnbound)) {
                return;
            }
            // Advance the eligible-join tally and persist it, so the once-per-five cadence survives
            // restarts. One write per session while the hint is still pending — a negligible one-shot.
            int joins = ++cfg.peekHintJoins;
            Prosperity.getConfig().save();
            if (!PeekHint.showsOnJoin(joins)) {
                return;
            }
            player.displayClientMessage(
                    Component.translatable("message.prosperity.peek_hint",
                            ProsperityClient.KEY_PEEK_LOOT_DETAIL.getTranslatedKeyMessage()),
                    false);
        }));
    }
}
