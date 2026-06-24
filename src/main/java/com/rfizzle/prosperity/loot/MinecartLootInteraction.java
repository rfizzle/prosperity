package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.Prosperity;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Minecart half of the instanced-loot loop (S-035). Container minecarts (chest and hopper carts) are
 * entities, not block entities, so {@link InstancedLootInteraction}'s {@code UseBlockCallback} never
 * sees them; this intercepts {@code UseEntityCallback} and serves the same per-player instance through
 * a {@link MinecartContainerAdapter}. Mineshaft loot is overwhelmingly chest minecarts, so this is
 * first-class coverage.
 *
 * <p>The gate mirrors the block path: server side, main hand, real (non-fake) {@link ServerPlayer},
 * {@code enableInstancedLoot}, and a minecart carrying (or having consumed) a loot table. Cancelling
 * with {@code SUCCESS} suppresses vanilla's own open — which would otherwise unpack the global loot —
 * so the mod opens its instance instead.
 */
public final class MinecartLootInteraction {

    private MinecartLootInteraction() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register(MinecartLootInteraction::onUseEntity);
    }

    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
            Entity entity, @Nullable EntityHitResult hit) {
        if (level.isClientSide || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!Prosperity.getConfig().enableInstancedLoot) {
            return InteractionResult.PASS;
        }
        // Automation opening through a fake player passes through to vanilla untouched (S-034).
        if (FakePlayers.isFakePlayer(serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Unlike a block, there is no place-against-entity interaction, so a sneaking player
        // right-clicking a cart still just opens it; we must intercept (not pass through) or vanilla
        // would open and unpack the shared loot, bypassing instancing.
        if (!(entity instanceof AbstractMinecartContainer cart)) {
            return InteractionResult.PASS;
        }

        MinecartContainerAdapter adapter = new MinecartContainerAdapter((ServerLevel) level, cart);
        if (!adapter.isLootContainer()) {
            return InteractionResult.PASS;
        }

        InstancedLootInteraction.serveInstance(adapter, serverPlayer);
        return InteractionResult.SUCCESS;
    }
}
