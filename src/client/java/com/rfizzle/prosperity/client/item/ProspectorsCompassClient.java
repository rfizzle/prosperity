package com.rfizzle.prosperity.client.item;

import com.rfizzle.prosperity.client.indicator.UnlootedIndicatorCache;
import com.rfizzle.prosperity.item.ProsperityItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Client needle logic for the Prospector's Compass: an {@code angle} item property (the same
 * predicate the vanilla compass model uses) that points at the nearest container the local player
 * has not yet looted, read from {@link UnlootedIndicatorCache} — the per-player unlooted set the
 * sparkle indicators already sync. Reach is therefore the client's loaded-chunk radius. With no
 * candidate cached, {@link CompassItemPropertyFunction} spins the needle randomly, exactly like a
 * vanilla compass outside its dimension.
 *
 * <p>The local player's target is sticky: a different candidate takes over only when it is more
 * than {@link #HYSTERESIS_BLOCKS} closer, so the needle does not flicker between two
 * near-equidistant containers as the player moves. The sticky state is one slot for the local
 * player only — a compass rendered for another holder (item frame, another player's hand) gets a
 * plain nearest-from-their-position answer, so it cannot clobber the local needle. Looting or
 * unloading the target drops it from the cache and the needle retargets on the next frame. State
 * is client-thread only, like the cache it reads.</p>
 */
public final class ProspectorsCompassClient {

    /** A new candidate must beat the current target's distance by this many blocks to steal it. */
    static final double HYSTERESIS_BLOCKS = 2.0;

    @Nullable
    private static BlockPos currentTarget;

    private ProspectorsCompassClient() {
    }

    public static void register() {
        ItemProperties.register(ProsperityItems.PROSPECTORS_COMPASS,
                ResourceLocation.withDefaultNamespace("angle"),
                new CompassItemPropertyFunction(ProspectorsCompassClient::target));
    }

    /** Forget the sticky target (disconnect / world change, alongside the cache clear). */
    public static void reset() {
        currentTarget = null;
    }

    @Nullable
    private static GlobalPos target(ClientLevel level, ItemStack stack, Entity entity) {
        if (UnlootedIndicatorCache.isEmpty()) {
            currentTarget = null;
            return null;
        }
        Iterable<BlockPos> candidates =
                () -> UnlootedIndicatorCache.view().values().stream().flatMap(Set::stream).iterator();
        boolean local = entity == Minecraft.getInstance().player;
        BlockPos target = selectTarget(local ? currentTarget : null, candidates, entity.position());
        if (local) {
            currentTarget = target;
        }
        return target == null ? null : GlobalPos.of(level.dimension(), target);
    }

    /**
     * The nearest candidate to {@code player}, with stickiness: while {@code current} is still a
     * candidate, it is kept unless the nearest one is more than {@link #HYSTERESIS_BLOCKS} closer.
     * {@code null} when there are no candidates. Pure — no client state touched.
     */
    @Nullable
    static BlockPos selectTarget(@Nullable BlockPos current, Iterable<BlockPos> candidates,
            Vec3 player) {
        BlockPos nearest = null;
        double nearestSq = Double.MAX_VALUE;
        double currentSq = -1;
        for (BlockPos pos : candidates) {
            double distSq = pos.distToCenterSqr(player.x, player.y, player.z);
            if (distSq < nearestSq) {
                nearest = pos;
                nearestSq = distSq;
            }
            if (pos.equals(current)) {
                currentSq = distSq;
            }
        }
        if (nearest == null) {
            return null;
        }
        if (currentSq >= 0 && Math.sqrt(nearestSq) + HYSTERESIS_BLOCKS > Math.sqrt(currentSq)) {
            return current;
        }
        return nearest;
    }
}
