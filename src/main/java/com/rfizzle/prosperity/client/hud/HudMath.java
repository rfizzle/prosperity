package com.rfizzle.prosperity.client.hud;

import com.rfizzle.prosperity.config.ProsperityConfig;

import java.util.Locale;

/**
 * Pure color, transition, and placement math for the tier HUD badge (SPEC §14). Kept free of any
 * Minecraft imports so the tier-color table, the gold&rarr;tier crossing lerp, and the anchor/stack
 * origin math can be unit-tested without bootstrapping the client (the
 * {@link ProsperityHudOverlay} in the {@code client} source set consumes these). Mirrors the
 * {@code indicator.IndicatorMath} split that keeps client render math testable from {@code src/test}.
 */
public final class HudMath {

    /** Gold the tier-crossing flash lerps from (SPEC §14). */
    public static final int GOLD_COLOR = 0xFFFFD700;
    /** Duration of the gold&rarr;tier color lerp on a tier change, in ms (SPEC §14: 1.5s). */
    public static final long TIER_TRANSITION_MS = 1500;

    // SPEC §14 tier-color table, keyed by the tier's config name.
    private static final int LOCAL = 0xFFFFFFFF;       // white
    private static final int FRONTIER = 0xFF55FF55;    // green
    private static final int WILDERNESS = 0xFFFFFF55;  // yellow
    private static final int OUTLANDS = 0xFFFF8C00;    // orange
    private static final int DEPTHS = 0xFFAA55FF;      // purple

    private HudMath() {
    }

    /**
     * The ARGB text/icon color for the tier named {@code name} (SPEC §14). The five default tier
     * names map to their specced colors; an unrecognized custom tier falls back to white so a
     * user-renamed or extra tier still renders legibly.
     */
    public static int tierColor(String name) {
        if (name == null) {
            return LOCAL;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "frontier" -> FRONTIER;
            case "wilderness" -> WILDERNESS;
            case "outlands" -> OUTLANDS;
            case "depths" -> DEPTHS;
            default -> LOCAL;
        };
    }

    /**
     * The badge color at {@code nowMs}: during the {@link #TIER_TRANSITION_MS} window after a tier
     * change it lerps from {@link #GOLD_COLOR} to {@code tierColor}; otherwise the steady tier color.
     * A {@code lastChangeMs} far in the past (e.g. the {@code 0} default before any change) yields a
     * huge elapsed and so the steady color &mdash; no flash on first render.
     */
    public static int animatedColor(int tierColor, long lastChangeMs, long nowMs) {
        long elapsed = nowMs - lastChangeMs;
        if (elapsed >= 0 && elapsed < TIER_TRANSITION_MS) {
            return lerpColor(GOLD_COLOR, tierColor, (float) elapsed / TIER_TRANSITION_MS);
        }
        return tierColor;
    }

    /** Linear interpolation between two ARGB colors, channel-wise, at {@code t} in {@code [0,1]}. */
    public static int lerpColor(int from, int to, float t) {
        int fa = (from >> 24) & 0xFF;
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;

        int ta = (to >> 24) & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;

        int a = (int) (fa + (ta - fa) * t);
        int r = (int) (fr + (tr - fr) * t);
        int g = (int) (fg + (tg - fg) * t);
        int b = (int) (fb + (tb - fb) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Badge left edge for {@code anchor}: offsets are measured inward from the anchored edge so the
     * badge keeps its distance from its corner regardless of screen size (HUD-STANDARD §4).
     */
    public static int computeOriginX(ProsperityConfig.Anchor anchor, int screenW, int offsetX, int badgeW) {
        return switch (anchor) {
            case TOP_LEFT, BOTTOM_LEFT -> offsetX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenW - offsetX - badgeW;
        };
    }

    /**
     * Badge top edge for {@code anchor}: the sibling {@code stackOffset} shifts inward from the
     * anchored vertical edge &mdash; down from a top anchor, up from a bottom anchor (HUD-STANDARD §4).
     */
    public static int computeOriginY(ProsperityConfig.Anchor anchor, int screenH, int offsetY,
            int badgeH, int stackOffset) {
        return switch (anchor) {
            case TOP_LEFT, TOP_RIGHT -> offsetY + stackOffset;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - offsetY - badgeH - stackOffset;
        };
    }

    /**
     * Sibling stacking reservation (HUD-STANDARD §4): stacking applies within an anchor. Prosperity
     * owns slot 3, below Tribulation (slot 1) and Mercantile (slot 2), whose anchors are not
     * queryable through the coordination accessors &mdash; so the reservation applies only at our
     * default {@code TOP_LEFT} anchor, the slot registry's canonical position. Moving the badge to
     * another corner opts out of stacking against the default-placed siblings.
     */
    public static int stackOffsetFor(ProsperityConfig.Anchor anchor, int siblingHeight) {
        return anchor == ProsperityConfig.Anchor.TOP_LEFT ? siblingHeight : 0;
    }

    /**
     * Title-cases a tier's config name for display when no {@code tier.prosperity.<name>} lang key
     * resolves (a custom tier) &mdash; {@code "wilderness"} &rarr; {@code "Wilderness"}. The five
     * default tiers have lang keys, so this is only the fallback.
     */
    public static String displayName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
