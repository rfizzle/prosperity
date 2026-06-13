package com.rfizzle.prosperity.component;

import org.ladysnake.cca.api.v3.block.BlockComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.block.BlockComponentInitializer;

public final class ProsperityComponents implements BlockComponentInitializer {
    // TODO: ComponentKey<InstancedLootComponent> per design/SPEC.md §1

    @Override
    public void registerBlockComponentFactories(BlockComponentFactoryRegistry registry) {
        // TODO: register InstancedLootComponent on RandomizableContainerBlockEntity (Phase 0.2)
    }
}
