package com.rfizzle.prosperity.compat.jade;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade integration (SPEC §10): a probe tooltip showing per-player loot status, distance tier, structure
 * override, and refresh timer on instanced containers. All tooltip content comes from the
 * viewer-agnostic {@link com.rfizzle.prosperity.compat.LootTooltip} layer; this class only wires Jade's
 * data + component providers, so Jade's absence simply leaves this entrypoint unloaded.
 *
 * <p>The data provider keys on {@link RandomizableContainerBlockEntity} (Jade walks the BE hierarchy,
 * covering chests, barrels, shulkers, dispensers, droppers). The component keys on the common
 * {@link BaseEntityBlock} superclass and renders nothing unless the server wrote our data, so it
 * no-ops on every other block entity. Container minecarts (S-035) are entities and out of scope here;
 * an entity provider could be added if minecart tooltips are wanted later.
 */
@WailaPlugin
public class ProsperityJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ContainerLootDataProvider.INSTANCE,
                RandomizableContainerBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ContainerLootComponentProvider.INSTANCE,
                BaseEntityBlock.class);
    }
}
