package com.rfizzle.prosperity.compat.jade;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.compat.LootTooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Server half of the Jade container tooltip (SPEC §10): packs the per-look loot state for the
 * requesting player into the probe's server-data tag. All logic lives in
 * {@link LootTooltip#writeServerData}; this only unpacks the Jade accessor.
 */
public enum ContainerLootDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    @Override
    public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Player player = accessor.getPlayer();
        if (player == null) {
            return;
        }
        LootTooltip.writeServerData(tag, level, player, accessor.getPosition(), accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() {
        return Prosperity.id("container_loot");
    }
}
