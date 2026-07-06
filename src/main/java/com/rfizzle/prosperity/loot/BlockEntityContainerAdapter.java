package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import com.rfizzle.prosperity.attachment.ProsperityAttachments;
import com.rfizzle.prosperity.network.ProsperityNetworking;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ContainerAdapter} over a {@code RandomizableContainerBlockEntity} (chests, barrels, shulker
 * boxes, dispensers, droppers, hoppers) &mdash; the common static-container case. The deferred operations
 * ({@link #persist}, {@link #closeFeedback()}) re-resolve the block entity by position, since it may
 * have been broken or unloaded while the screen was open.
 */
public final class BlockEntityContainerAdapter implements ContainerAdapter {

    private final ServerLevel level;
    private final BlockPos pos;
    private final RandomizableContainerBlockEntity be;

    public BlockEntityContainerAdapter(ServerLevel level, BlockPos pos,
            RandomizableContainerBlockEntity be) {
        this.level = level;
        this.pos = pos;
        this.be = be;
    }

    @Nullable
    @Override
    public ResourceKey<LootTable> lootTable() {
        return be.getLootTable();
    }

    @Override
    public long lootTableSeed() {
        return be.getLootTableSeed();
    }

    @Override
    public void clearLootTable() {
        be.setLootTable(null);
        be.setLootTableSeed(0L);
        be.setChanged();
    }

    @Override
    public int size() {
        return be.getContainerSize();
    }

    @Override
    public Component displayName() {
        return be.getDisplayName();
    }

    @Override
    public Vec3 origin() {
        return pos.getCenter();
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Nullable
    @Override
    public InstancedLootData data() {
        return ProsperityAttachments.get(be);
    }

    @Override
    public InstancedLootData update(Consumer<InstancedLootData> mutation) {
        return ProsperityAttachments.update(be, mutation);
    }

    @Override
    public void persist(UUID player, Container screenInventory) {
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity current) {
            InstancedLootInteraction.persist(current, player, screenInventory);
        }
    }

    @Override
    public void notifyGenerated(ServerPlayer player) {
        ProsperityNetworking.sendContainerLooted(player, pos);
    }

    @Override
    public void notifyGeneratedForMembers(MinecraftServer server, Set<UUID> members) {
        for (UUID member : members) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                ProsperityNetworking.sendContainerLooted(online, pos);
            }
        }
    }

    @Override
    public String containerId() {
        return InstancedLootInteraction.blockContainerId(level, pos);
    }

    @Override
    public void openFeedback() {
        ContainerFeedback.playSound(level, pos, ContainerFeedback.openSound(be));
        ContainerFeedback.animate(level, pos, be, true);
    }

    @Override
    public void closeFeedback() {
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity current) {
            ContainerFeedback.playSound(level, pos, ContainerFeedback.closeSound(current));
            ContainerFeedback.animate(level, pos, current, false);
        }
    }
}
