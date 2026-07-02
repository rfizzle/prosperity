package com.rfizzle.prosperity.indicator;

/**
 * Pure timing/placement math for the unlooted-container overlay (SPEC §2). Kept free of any
 * Minecraft imports so the bob, fade, and animation-frame curves can be unit-tested without
 * bootstrapping the client (the renderer in the {@code client} source set consumes these).
 */
public final class IndicatorMath {

    /** Sinusoidal bob amplitude, in blocks (±). */
    public static final double BOB_AMPLITUDE = 0.05;
    /** Bob period, in ticks — a 2-second cycle at 20 tps. */
    public static final double BOB_PERIOD_TICKS = 40.0;
    /** Render-distance band, in blocks, over which the indicator fades to transparent. */
    public static final double FADE_BAND = 8.0;
    /** Frames in the sparkle animation, one standalone {@code unlooted_<n>.png} texture each. */
    public static final int FRAME_COUNT = 4;
    /** Ticks each animation frame is shown before advancing to the next. */
    public static final long FRAME_TIME_TICKS = 5;

    private IndicatorMath() {
    }

    /**
     * Vertical bob offset at {@code timeTicks} (game time plus partial tick), oscillating in
     * {@code [-BOB_AMPLITUDE, +BOB_AMPLITUDE]} with a {@link #BOB_PERIOD_TICKS}-tick period.
     */
    public static double bobOffset(double timeTicks) {
        return BOB_AMPLITUDE * Math.sin(timeTicks * (2.0 * Math.PI / BOB_PERIOD_TICKS));
    }

    /**
     * Opacity for an indicator {@code distance} blocks from the camera, given the configured
     * {@code renderDistance}: fully opaque up to {@code renderDistance - FADE_BAND}, then a
     * linear ramp to {@code 0} at {@code renderDistance}, and {@code 0} beyond it.
     */
    public static double fadeAlpha(double distance, double renderDistance) {
        if (renderDistance <= 0.0 || distance >= renderDistance) {
            return 0.0;
        }
        double fadeStart = renderDistance - FADE_BAND;
        if (distance <= fadeStart) {
            return 1.0;
        }
        return (renderDistance - distance) / FADE_BAND;
    }

    /**
     * The animation frame index ({@code 0..FRAME_COUNT-1}) for the strip at {@code gameTime},
     * advancing every {@link #FRAME_TIME_TICKS} ticks and wrapping.
     */
    public static int animationFrame(long gameTime) {
        return (int) Math.floorMod(gameTime / FRAME_TIME_TICKS, (long) FRAME_COUNT);
    }
}
