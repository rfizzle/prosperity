package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Belt-and-suspenders guard against the hopper/comparator loot-drain exploit for container minecarts
 * (S-035) — the minecart parallel of {@link RandomizableContainerUnpackMixin}.
 *
 * <p>Once Prosperity has taken over a minecart, {@code MinecartContainerAdapter.clearLootTable} nulls
 * its loot table, which already makes {@code unpackChestVehicleLootTable} a no-op. This mixin cancels
 * the call outright for any minecart the mod has generated, catching the paths that invoke unpack
 * directly: a hopper draining the cart routes through {@code getChestVehicleItem} →
 * {@code unpackChestVehicleLootTable(null)}, so this guard covers it.
 *
 * <p>Targets the {@link ContainerEntity} interface because {@code unpackChestVehicleLootTable} is a
 * default method there. The {@code instanceof AbstractMinecartContainer} guard restricts the cancel to
 * minecarts, which carry the minecart instanced-loot attachment.
 */
@Mixin(ContainerEntity.class)
public interface ContainerEntityUnpackMixin {

    @Inject(method = "unpackChestVehicleLootTable", at = @At("HEAD"), cancellable = true)
    private void prosperity$blockInstancedUnpack(Player player, CallbackInfo ci) {
        try {
            if (!(this instanceof AbstractMinecartContainer cart)) {
                return;
            }
            InstancedLootData data = ProsperityAttachments.get(cart);
            if (data != null && data.isGenerated()) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to evaluate minecart instanced-loot unpack guard", e);
        }
    }
}
