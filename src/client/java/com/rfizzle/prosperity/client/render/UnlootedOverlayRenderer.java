package com.rfizzle.prosperity.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.client.indicator.UnlootedIndicatorCache;
import com.rfizzle.prosperity.client.indicator.UnlootedMinecartIndicatorCache;
import com.rfizzle.prosperity.client.network.ClientProsperityData;
import com.rfizzle.prosperity.indicator.IndicatorMath;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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
 *
 * <p>Block containers anchor above their top face from the per-chunk {@link UnlootedIndicatorCache}.
 * Container minecarts move, so they anchor to the live entity resolved by network id each frame from
 * the flat {@link UnlootedMinecartIndicatorCache} (S-038), at the cart's partial-tick-interpolated
 * position; an id whose entity can no longer be resolved is silently skipped.</p>
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
        if ((UnlootedIndicatorCache.isEmpty() && UnlootedMinecartIndicatorCache.isEmpty())
                || context.world() == null) {
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
        // Every indicator this frame shares the same animation frame, so a single per-frame render
        // type (keyed by the frame's own texture) batches them all.
        int frame = IndicatorMath.animationFrame(gameTime);

        ClientLevel blockLevel = context.world();
        PoseStack pose = context.matrixStack();
        boolean drewAny = false;
        for (Map.Entry<ChunkPos, Set<BlockPos>> chunk : UnlootedIndicatorCache.view().entrySet()) {
            for (BlockPos blockPos : chunk.getValue()) {
                // Centre of the block's top face, raised by the hover height.
                double centreX = blockPos.getX() + 0.5;
                double centreZ = blockPos.getZ() + 0.5;
                // A double chest is cached at its primary half only, so nudge the anchor half a block
                // toward the connected half to sit over the pair's seam rather than one half's centre.
                BlockState state = blockLevel.getBlockState(blockPos);
                if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    Direction connected = ChestBlock.getConnectedDirection(state);
                    centreX += connected.getStepX() * 0.5;
                    centreZ += connected.getStepZ() * 0.5;
                }
                drewAny |= renderBillboard(pose, consumers, cam, cameraRotation,
                        centreX, blockPos.getY() + 1.0 + HOVER, centreZ,
                        bob, frame, renderDistance, xrayDistance);
            }
        }

        // Container minecarts move, so anchor each sparkle to the live entity resolved by id this frame,
        // at its partial-tick-interpolated position; a stale id whose entity is gone is silently skipped.
        if (!UnlootedMinecartIndicatorCache.isEmpty()) {
            ClientLevel level = context.world();
            for (int entityId : UnlootedMinecartIndicatorCache.view()) {
                Entity entity = level.getEntity(entityId);
                if (!(entity instanceof AbstractMinecartContainer cart) || cart.isRemoved()) {
                    continue;
                }
                Vec3 anchor = cart.getPosition(partialTick);
                drewAny |= renderBillboard(pose, consumers, cam, cameraRotation,
                        anchor.x, anchor.y + cart.getBbHeight() + HOVER, anchor.z,
                        bob, frame, renderDistance, xrayDistance);
            }
        }

        if (drewAny) {
            bufferSource.endBatch(ProsperityRenderTypes.xray(frame));
            bufferSource.endBatch(ProsperityRenderTypes.occluded(frame));
        }
    }

    private static boolean renderBillboard(PoseStack pose, MultiBufferSource consumers, Vec3 cam,
            Quaternionf cameraRotation, double anchorX, double anchorY, double anchorZ, double bob,
            int frame, double renderDistance, double xrayDistance) {
        double dx = anchorX - cam.x;
        double dy = anchorY - cam.y;
        double dz = anchorZ - cam.z;

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float alpha = (float) IndicatorMath.fadeAlpha(distance, renderDistance);
        if (alpha <= 0.0f) {
            return false;
        }
        int alpha255 = Math.clamp((long) (alpha * 255.0f), 0, 255);

        VertexConsumer buffer = consumers.getBuffer(distance <= xrayDistance
                ? ProsperityRenderTypes.xray(frame) : ProsperityRenderTypes.occluded(frame));

        pose.pushPose();
        pose.translate(dx, dy + bob, dz);
        pose.mulPose(cameraRotation);
        Matrix4f matrix = pose.last().pose();
        // The frame's own texture fills the quad (v=0 at the top); the sprite is gold, so tint white + fade alpha.
        buffer.addVertex(matrix, -HALF_SIZE, -HALF_SIZE, 0.0f).setUv(0.0f, 1.0f).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, HALF_SIZE, -HALF_SIZE, 0.0f).setUv(1.0f, 1.0f).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, HALF_SIZE, HALF_SIZE, 0.0f).setUv(1.0f, 0.0f).setColor(255, 255, 255, alpha255);
        buffer.addVertex(matrix, -HALF_SIZE, HALF_SIZE, 0.0f).setUv(0.0f, 0.0f).setColor(255, 255, 255, alpha255);
        pose.popPose();
        return true;
    }
}
