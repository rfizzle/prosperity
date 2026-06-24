package com.rfizzle.prosperity.client.render;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom {@link RenderType}s for the unlooted-container overlay (SPEC §2). Both are translucent,
 * unculled, and write no depth (so indicators never occlude one another); they differ only in
 * depth testing — {@link #xray()} ignores it (visible through walls within the xray range),
 * {@link #occluded()} respects it (hidden behind geometry beyond that range).
 */
public final class ProsperityRenderTypes {

    private static final ResourceLocation TEXTURE = Prosperity.id("textures/overlay/unlooted.png");

    // POSITION_TEX_COLOR: a textured quad with a per-vertex fade alpha. No lightmap — the sparkle
    // is emissive (full-bright), matching the gold glow rather than scene lighting.
    private static final RenderType XRAY = create("unlooted_xray", RenderStateShard.NO_DEPTH_TEST);
    private static final RenderType OCCLUDED = create("unlooted_occluded", RenderStateShard.LEQUAL_DEPTH_TEST);

    private ProsperityRenderTypes() {
    }

    public static RenderType xray() {
        return XRAY;
    }

    public static RenderType occluded() {
        return OCCLUDED;
    }

    private static RenderType create(String name, RenderStateShard.DepthTestStateShard depthTest) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
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
