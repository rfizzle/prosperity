package com.rfizzle.prosperity.compat.jade;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.compat.LootTooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Client half of the Jade container tooltip (SPEC §10): renders the lines {@link LootTooltip#buildLines}
 * builds from the server data. Adds nothing when no Prosperity data is present (plain storage, or any
 * other block entity Jade also queried under the shared {@code BaseEntityBlock} registration), so the
 * provider is inert outside instanced containers.
 */
public enum ContainerLootComponentProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        for (Component line : LootTooltip.buildLines(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return Prosperity.id("container_loot");
    }
}
