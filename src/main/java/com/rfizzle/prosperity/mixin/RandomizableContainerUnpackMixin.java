package com.rfizzle.prosperity.mixin;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.component.InstancedLootComponent;
import com.rfizzle.prosperity.component.ProsperityComponents;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Belt-and-suspenders guard against the hopper/comparator loot-drain exploit (S-006).
 *
 * <p>Once Prosperity has taken over a container, {@code InstancedLootInteraction.generateAndStore}
 * nulls the block entity's vanilla loot table, which already makes {@code unpackLootTable} a no-op.
 * This mixin cancels the call outright for any container the mod has generated, catching paths that
 * invoke unpack directly (and any future code that re-populates the field) so the global loot can
 * never materialize into the shared vanilla inventory.
 *
 * <p>Targets the {@link RandomizableContainer} interface because {@code unpackLootTable} is a default
 * method there, not declared on the block entity. The interface is also implemented by minecart
 * containers, hence the {@code instanceof RandomizableContainerBlockEntity} guard &mdash; only block
 * entities carry our CCA component.
 */
@Mixin(RandomizableContainer.class)
public interface RandomizableContainerUnpackMixin {

    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    private void prosperity$blockInstancedUnpack(Player player, CallbackInfo ci) {
        try {
            if (!(this instanceof RandomizableContainerBlockEntity be)) {
                return;
            }
            InstancedLootComponent component = ProsperityComponents.INSTANCED_LOOT.getNullable(be);
            if (component != null && component.isGenerated()) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            Prosperity.LOGGER.error("Failed to evaluate instanced-loot unpack guard", e);
        }
    }
}
