package com.rfizzle.prosperity.compat.wthit;

import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import mcp.mobius.waila.api.IClientRegistrar;
import mcp.mobius.waila.api.IWailaClientPlugin;

/**
 * Client half of the WTHIT integration: registers the body component provider that renders the lines
 * {@link ContainerLootWthitProvider} builds from the server data. Discovered through
 * {@code waila_plugins.json}, so it loads only when WTHIT is present.
 */
public final class ProsperityWthitClientPlugin implements IWailaClientPlugin {

    @Override
    public void register(IClientRegistrar registrar) {
        registrar.body(ContainerLootWthitProvider.INSTANCE, RandomizableContainerBlockEntity.class);
    }
}
