package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.config.ProsperityConfig.Anchor;
import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the tier HUD badge math (S-024): tier colors, the crossing lerp, and anchor/stack placement. */
class HudMathTest {

    @Test
    void tierColorsMatchSpecTable() {
        assertEquals(0xFFFFFFFF, HudMath.tierColor("local"));
        assertEquals(0xFF55FF55, HudMath.tierColor("frontier"));
        assertEquals(0xFFFFFF55, HudMath.tierColor("wilderness"));
        assertEquals(0xFFFF8C00, HudMath.tierColor("outlands"));
        assertEquals(0xFFAA55FF, HudMath.tierColor("depths"));
    }

    @Test
    void tierColorIsCaseInsensitiveAndFallsBackToWhite() {
        assertEquals(0xFFFF8C00, HudMath.tierColor("OUTLANDS"));
        assertEquals(0xFFFFFFFF, HudMath.tierColor("some_custom_tier"));
        assertEquals(0xFFFFFFFF, HudMath.tierColor(null));
    }

    @Test
    void lerpColorHitsBothEndpoints() {
        int from = 0xFF000000;
        int to = 0xFFFFFFFF;
        assertEquals(from, HudMath.lerpColor(from, to, 0.0f));
        assertEquals(to, HudMath.lerpColor(from, to, 1.0f));
    }

    @Test
    void lerpColorBlendsChannelsAtMidpoint() {
        // 0x00 -> 0x40 at t=0.5 is 0x20 per channel.
        int mid = HudMath.lerpColor(0xFF000000, 0xFF404040, 0.5f);
        assertEquals(0x20, (mid >> 16) & 0xFF);
        assertEquals(0x20, (mid >> 8) & 0xFF);
        assertEquals(0x20, mid & 0xFF);
    }

    @Test
    void animatedColorStartsAtGoldAndEndsAtTier() {
        int tier = HudMath.tierColor("depths");
        // At the instant of change (elapsed 0) the badge is full gold.
        assertEquals(HudMath.GOLD_COLOR, HudMath.animatedColor(tier, 1000, 1000));
        // Partway through the window it is neither pure gold nor the steady tier color.
        int mid = HudMath.animatedColor(tier, 1000, 1000 + HudMath.TIER_TRANSITION_MS / 2);
        assertNotEquals(HudMath.GOLD_COLOR, mid);
        assertNotEquals(tier, mid);
    }

    @Test
    void animatedColorIsSteadyOutsideTheWindow() {
        int tier = HudMath.tierColor("frontier");
        // After the window closes.
        assertEquals(tier, HudMath.animatedColor(tier, 1000, 1000 + HudMath.TIER_TRANSITION_MS));
        // And with the 0-default last-change (no change has happened yet) — no flash on first render.
        assertEquals(tier, HudMath.animatedColor(tier, 0, 5_000_000L));
    }

    @Test
    void originXMeasuresInwardFromTheAnchoredEdge() {
        // Left anchors sit at the offset; right anchors sit a badge-width in from the right edge.
        assertEquals(4, HudMath.computeOriginX(Anchor.TOP_LEFT, 400, 4, 80));
        assertEquals(4, HudMath.computeOriginX(Anchor.BOTTOM_LEFT, 400, 4, 80));
        assertEquals(400 - 4 - 80, HudMath.computeOriginX(Anchor.TOP_RIGHT, 400, 4, 80));
        assertEquals(400 - 4 - 80, HudMath.computeOriginX(Anchor.BOTTOM_RIGHT, 400, 4, 80));
    }

    @Test
    void originYAppliesStackOffsetInward() {
        // Top anchors push the stack offset downward; bottom anchors push it upward.
        assertEquals(4 + 22, HudMath.computeOriginY(Anchor.TOP_LEFT, 300, 4, 20, 22));
        assertEquals(300 - 4 - 20 - 22, HudMath.computeOriginY(Anchor.BOTTOM_LEFT, 300, 4, 20, 22));
        // No sibling -> no offset, badge sits flush at the anchor offset.
        assertEquals(4, HudMath.computeOriginY(Anchor.TOP_RIGHT, 300, 4, 20, 0));
    }

    @Test
    void stackOffsetReservedOnlyAtTopLeft() {
        assertEquals(44, HudMath.stackOffsetFor(Anchor.TOP_LEFT, 44));
        assertEquals(0, HudMath.stackOffsetFor(Anchor.TOP_RIGHT, 44));
        assertEquals(0, HudMath.stackOffsetFor(Anchor.BOTTOM_LEFT, 44));
        assertEquals(0, HudMath.stackOffsetFor(Anchor.BOTTOM_RIGHT, 44));
    }

    @Test
    void displayNameTitleCasesTheFallback() {
        assertEquals("Wilderness", HudMath.displayName("wilderness"));
        assertEquals("", HudMath.displayName(""));
        assertTrue(HudMath.displayName("a").equals("A"));
    }
}
