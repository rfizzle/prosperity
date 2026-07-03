package com.rfizzle.prosperity.client.render;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.indicator.IndicatorMath;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom {@link RenderType}s for the unlooted-container overlay (SPEC §2). Both are translucent,
 * unculled, and write no depth (so indicators never occlude one another); they differ only in
 * depth testing — {@link #xray(int)} ignores it (visible through walls within the xray range),
 * {@link #occluded(int)} respects it (hidden behind geometry beyond that range).
 *
 * <p>Each animation frame is its own standalone square texture ({@code unlooted_0.png}…) bound
 * whole; the sparkle carries no multi-frame strip or animation {@code .mcmeta}, so nothing in a
 * resource pipeline can reinterpret it as an animated sprite and stretch a single frame over the
 * quad. The renderer advances the frame via {@link IndicatorMath#animationFrame(long)}.</p>
 */
public final class ProsperityRenderTypes {

    // POSITION_TEX_COLOR: a textured quad with a per-vertex fade alpha. No lightmap — the sparkle
    // is emissive (full-bright), matching the gold glow rather than scene lighting. One render type
    // per frame, keyed by the frame's own texture.
    private static final RenderType[] XRAY = new RenderType[IndicatorMath.FRAME_COUNT];
    private static final RenderType[] OCCLUDED = new RenderType[IndicatorMath.FRAME_COUNT];

    static {
        for (int frame = 0; frame < IndicatorMath.FRAME_COUNT; frame++) {
            ResourceLocation texture = Prosperity.id("textures/overlay/unlooted_" + frame + ".png");
            XRAY[frame] = create("unlooted_xray_" + frame, texture, RenderStateShard.NO_DEPTH_TEST);
            OCCLUDED[frame] = create("unlooted_occluded_" + frame, texture, RenderStateShard.LEQUAL_DEPTH_TEST);
        }
    }

    private ProsperityRenderTypes() {
    }

    public static RenderType xray(int frame) {
        return XRAY[frame];
    }

    public static RenderType occluded(int frame) {
        return OCCLUDED[frame];
    }

    private static RenderType create(String name, ResourceLocation texture,
            RenderStateShard.DepthTestStateShard depthTest) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(depthTest)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                .createCompositeState(false);
        return RenderType.create(Prosperity.MOD_ID + ":" + name,
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, state);
    }
}
