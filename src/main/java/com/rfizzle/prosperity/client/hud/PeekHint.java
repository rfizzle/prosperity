package com.rfizzle.prosperity.client.hud;

/**
 * Pure decision logic for the one-time peek-panel discovery hint (S-082): a chat message sent when the
 * player joins a world, until they open the loot detail panel once. Kept free of any Minecraft imports
 * — like {@link HudMath} — so the show/suppress rule can be unit-tested without bootstrapping the
 * client. The message itself (naming the bound key) is sent from the {@code client} source set.
 */
public final class PeekHint {

    private PeekHint() {
    }

    /**
     * Whether to send the discovery hint on join: only before the player's first peek
     * ({@code !dismissed}) and only while the peek keybind is actually bound. If the key is unbound —
     * the player cleared it in Controls — a hint naming the key would be meaningless, so it stays
     * silent.
     */
    public static boolean shouldSend(boolean dismissed, boolean keyUnbound) {
        return !dismissed && !keyUnbound;
    }
}
