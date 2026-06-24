package com.rfizzle.prosperity.component;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.ladysnake.cca.api.v3.block.BlockComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.block.BlockComponentInitializer;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;

public final class ProsperityComponents implements BlockComponentInitializer {

    /**
     * Per-player instanced loot, attached to every {@link RandomizableContainerBlockEntity}.
     * CCA walks the block entity class hierarchy, so this covers chests, trapped chests,
     * barrels, shulker boxes, dispensers, and droppers — and excludes ender chests and
     * decorated pots, which do not extend that class.
     */
    public static final ComponentKey<InstancedLootComponent> INSTANCED_LOOT =
            ComponentRegistry.getOrCreate(Prosperity.id("instanced_loot"), InstancedLootComponent.class);

    @Override
    public void registerBlockComponentFactories(BlockComponentFactoryRegistry registry) {
        registry.registerFor(RandomizableContainerBlockEntity.class, INSTANCED_LOOT,
                InstancedLootComponentImpl::new);
    }
}
