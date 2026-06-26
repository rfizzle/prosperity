package com.rfizzle.prosperity.compat.wthit;

import com.rfizzle.prosperity.compat.LootTooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import mcp.mobius.waila.api.IBlockAccessor;
import mcp.mobius.waila.api.IBlockComponentProvider;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.ITooltip;

/**
 * WTHIT parallel of the Jade container providers (SPEC §10): the same per-player loot status, distance
 * tier, structure override, and refresh timer, surfaced through WTHIT instead. Both halves delegate to
 * the viewer-agnostic {@link LootTooltip} layer, so the tooltip is identical to Jade's by construction;
 * the only WTHIT-specific code is unpacking the accessor. Keeping the {@code mcp.mobius.waila} imports
 * confined to {@code compat.wthit} is what makes WTHIT's absence a no-op &mdash; the discovery manifest
 * {@code waila_plugins.json} is the sole loader, so without WTHIT these classes never load.
 *
 * <p>Both the server data provider and the client body provider key on
 * {@link RandomizableContainerBlockEntity} (WTHIT matches block component providers against the
 * block-entity class), exactly mirroring the data registration. The body provider renders nothing when
 * the server wrote no Prosperity data, so it is inert on plain storage and every other block entity.
 */
public enum ContainerLootWthitProvider
        implements IDataProvider<RandomizableContainerBlockEntity>, IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendData(IDataWriter data, IServerAccessor<RandomizableContainerBlockEntity> accessor,
            IPluginConfig config) {
        RandomizableContainerBlockEntity be = accessor.getTarget();
        LootTooltip.writeServerData(data.raw(), accessor.getLevel(), accessor.getPlayer(),
                be.getBlockPos(), be);
    }

    @Override
    public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
        for (Component line : LootTooltip.buildLines(accessor.getData().raw())) {
            tooltip.addLine(line);
        }
    }
}
