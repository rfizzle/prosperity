package com.rfizzle.prosperity.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.indicator.UnlootedIndicatorCache;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.indicator.IndicatorMath;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.Set;

/**
 * Renders the gold sparkle hovering above each cached unlooted container (SPEC §2), as a
 * camera-facing billboard in {@link WorldRenderEvents#LAST} — a post-pass over the finished scene
 * that touches no block/chunk/BE rendering, so it composes with Sodium, EBE, and Iris.
 *
 * <p>The quad is built camera-relative on the world renderer's identity pose stack (the same frame
 * of reference entities use) and rotated by the camera orientation to billboard; the active modelview
 * applies the camera transform. Per indicator: a ±0.05 sinusoidal bob, a distance fade over the final
 * {@link IndicatorMath#FADE_BAND} blocks, depth-test off within the xray range and on beyond it, and
 * the animation strip's current frame.</p>
 */
public final class UnlootedOverlayRenderer {

    /** Sprite half-extent in blocks → a ~0.4-block billboard. */
    private static final float HALF_SIZE = 0.2f;
    /** Hover height above the container's top face. */
    private static final double HOVER = 0.25;

    private UnlootedOverlayRenderer() {
    }

    public static void register() {
        WorldRenderEvents.LAST.register(UnlootedOverlayRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (!ClientProsperityData.visualIndicators() || !Prosperity.getConfig().client.showIndicators) {
            return;
        }
        if (UnlootedIndicatorCache.isEmpty() || context.world() == null) {
            return;
        }
        MultiBufferSource consumers = context.consumers();
        if (!(consumers instanceof MultiBufferSource.BufferSource bufferSource)) {
            return;
        }

        double renderDistance = ClientProsperityData.renderDistance();
        if (renderDistance <= 0.0) {
            return;
        }
        double xrayDistance = ClientProsperityData.xrayDistance();

        Camera camera = context.camera();
        Vec3 cam = camera.getPosition();
        Quaternionf cameraRotation = camera.rotation();
        long gameTime = context.world().getGameTime();
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
        double bob = IndicatorMath.bobOffset(gameTime + partialTick);
        int frame = IndicatorMath.animationFrame(gameTime);
        float v0 = (float) frame / IndicatorMath.FRAME_COUNT;
        float v1 = (float) (frame + 1) / IndicatorMath.FRAME_COUNT;

        PoseStack pose = context.matrixStack();
        boolean drewAny = false;
        for (Map.Entry<ChunkPos, Set<BlockPos>> chunk : UnlootedIndicatorCache.view().entrySet()) {
            for (BlockPos blockPos : chunk.getValue()) {
                drewAny |= renderOne(pose, consumers, cam, cameraRotation, blockPos,
                        bob, v0, v1, renderDistance, xrayDistance);
            }
        }

        if (drewAny) {
            bufferSource.endBatch(ProsperityRenderTypes.xray());
            bufferSource.endBatch(ProsperityRenderTypes.occluded());
        }
    }

    private static boolean renderOne(PoseStack pose, MultiBufferSource consumers, Vec3 cam,
            Quaternionf cameraRotation, BlockPos blockPos, double bob, float v0, float v1,
            double renderDistance, double xrayDistance) {
        // Centre of the block's top face, raised by the hover height.
        double dx = blockPos.getX() + 0.5 - cam.x;
        double dy = blockPos.getY() + 1.0 + HOVER - cam.y;
        double dz = blockPos.getZ() + 0.5 - cam.z;

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float alpha = (float) IndicatorMath.fadeAlpha(distance, renderDistance);
        if (alpha <= 0.0f) {
            return false;
        }
        int alpha255 = Math.clamp((long) (alpha * 255.0f), 0, 255);

        VertexConsumer buffer = consumers.getBuffer(
                distance <= xrayDistance ? ProsperityRenderTypes.xray() : ProsperityRenderTypes.occluded());

        pose.pushPose();
        pose.translate(dx, dy + bob, dz);
        pose.mulPose(cameraRotation);
        Matrix4f matrix = pose.last().pose();
        // Texture top (v0) maps to the quad's top (+y); the sprite is gold, so tint white + fade alpha.
        buffer.addVertex(matrix, -HALF_SIZE, -HALF_SIZE, 0.0f).setUv(0.0f, v1).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, HALF_SIZE, -HALF_SIZE, 0.0f).setUv(1.0f, v1).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, HALF_SIZE, HALF_SIZE, 0.0f).setUv(1.0f, v0).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, -HALF_SIZE, HALF_SIZE, 0.0f).setUv(0.0f, v0).setColor(255, 255, 255, alpha255);
        pose.popPose();
        return true;
    }
}
