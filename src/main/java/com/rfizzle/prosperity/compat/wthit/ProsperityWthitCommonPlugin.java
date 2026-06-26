package com.rfizzle.prosperity.compat.wthit;

import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import mcp.mobius.waila.api.ICommonRegistrar;
import mcp.mobius.waila.api.IWailaCommonPlugin;

/**
 * Server half of the WTHIT integration: registers the data provider that packs the per-look loot state
 * into the probe's server data. Discovered through {@code waila_plugins.json}, so it loads only when
 * WTHIT is present.
 */
public final class ProsperityWthitCommonPlugin implements IWailaCommonPlugin {

    @Override
    public void register(ICommonRegistrar registrar) {
        registrar.blockData(ContainerLootWthitProvider.INSTANCE, RandomizableContainerBlockEntity.class);
    }
}
