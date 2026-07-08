package com.rfizzle.prosperity.client.hud;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Guards the Concord HUD Standard §3 "Draw batch integrity" invariant (mc-hud "Commit the draw
 * batch"): every HUD render pass must end with {@code graphics.flush()} so a batching optimizer
 * (ImmediatelyFast) or a framebuffer-reading effect (Blur+, a post shader) can't fold in, drop, or
 * capture GUI geometry left unflushed (#102).
 *
 * <p>The render methods take a live {@link net.minecraft.client.gui.GuiGraphics}, so they can't be
 * exercised in the JUnit or gametest tiers; this is a pure source guard — the same fast tier and
 * project-root-relative file access as {@code LangKeyConventionTest} — that keeps a future refactor
 * from silently dropping the flush, which is the exact regression that opened #102.
 */
class HudFlushGuardTest {

    private static final Path OVERLAY =
            Path.of("src/client/java/com/rfizzle/prosperity/client/hud/ProsperityHudOverlay.java");
    private static final Path PANEL =
            Path.of("src/client/java/com/rfizzle/prosperity/client/hud/LootDetailPanelRenderer.java");

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read HUD source for the flush-guard check: " + source, e);
        }
    }

    @Test
    void badgeRenderPassFlushes() {
        assertTrue(read(OVERLAY).contains("graphics.flush()"),
                "ProsperityHudOverlay.render must end with graphics.flush() (HUD-STANDARD §3, mc-hud)");
    }

    @Test
    void peekPanelRenderPassFlushes() {
        assertTrue(read(PANEL).contains("graphics.flush()"),
                "LootDetailPanelRenderer.onHudRender must end with graphics.flush() (HUD-STANDARD §3, mc-hud)");
    }
}
