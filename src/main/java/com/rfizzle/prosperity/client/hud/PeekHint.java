package com.rfizzle.prosperity.client.hud;

/**
 * Pure decision logic for the peek-panel discovery hint (S-082): a chat message posted when the player
 * joins a world, at a throttled cadence, until they open the loot detail panel once. Kept free of any
 * Minecraft imports — like {@link HudMath} — so the eligibility and cadence rules can be unit-tested
 * without bootstrapping the client. The message itself (naming the bound key) is sent from the
 * {@code client} source set.
 */
public final class PeekHint {

    /** World joins between hint postings: the hint shows on every fifth eligible join. */
    public static final int SESSIONS_PER_HINT = 5;

    private PeekHint() {
    }

    /**
     * Whether this join counts toward the hint cadence at all: only before the player's first peek
     * ({@code !dismissed}) and only while the peek keybind is actually bound. If the key is unbound —
     * the player cleared it in Controls — a hint naming the key would be meaningless, so the join is
     * ignored entirely (it does not advance the counter).
     */
    public static boolean eligible(boolean dismissed, boolean keyUnbound) {
        return !dismissed && !keyUnbound;
    }

    /**
     * Whether to actually post the hint on an eligible join, given the running count of eligible joins
     * <em>including</em> this one (1-based). The hint fires on the first eligible join and then every
     * {@link #SESSIONS_PER_HINT}-th one after — joins 1, 6, 11, … — so a returning player is reminded
     * periodically without being nagged every session.
     */
    public static boolean showsOnJoin(int eligibleJoinCount) {
        return eligibleJoinCount > 0 && eligibleJoinCount % SESSIONS_PER_HINT == 1;
    }
}
