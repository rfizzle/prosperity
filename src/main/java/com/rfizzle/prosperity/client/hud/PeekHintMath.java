package com.rfizzle.prosperity.client.hud;

/**
 * Pure visibility and layout math for the one-time peek-panel hint chip (S-082). Kept free of any
 * Minecraft imports — like {@link HudMath} — so the show/hide rule and the chip's added width can be
 * unit-tested without bootstrapping the client. The {@link ProsperityHudOverlay} in the {@code client}
 * source set consumes these to append a {@code [key]} hint to the tier badge until the player has
 * peeked once.
 */
public final class PeekHintMath {

    /** Horizontal padding inside the chip box, per side (px). */
    public static final int CHIP_PAD_H = 3;
    /** Gap between the tier label and the chip (px). */
    public static final int CHIP_GAP = 4;

    private PeekHintMath() {
    }

    /**
     * Whether the hint chip should render: only before the player's first peek ({@code !dismissed}) and
     * only while the keybind is actually bound. If the key is unbound — the player cleared it in
     * Controls — a {@code [key]} chip would be meaningless, so it hides.
     */
    public static boolean shouldShowHint(boolean dismissed, boolean keyUnbound) {
        return !dismissed && !keyUnbound;
    }

    /**
     * The width the chip adds to the badge, given the rendered pixel width of the hint text: a
     * leading {@link #CHIP_GAP} plus the padded box ({@link #CHIP_PAD_H} each side). The chip is laid
     * out to the right of the tier label so it never changes the badge height (and thus never disturbs
     * sibling HUD stacking); including it in the badge width keeps a right-anchored badge on-screen.
     */
    public static int chipWidth(int hintTextWidth) {
        return CHIP_GAP + CHIP_PAD_H + hintTextWidth + CHIP_PAD_H;
    }
}
