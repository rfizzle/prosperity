package com.rfizzle.prosperity.loot;

import com.rfizzle.prosperity.attachment.InstancedLootData;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A thin proxy over one vanilla loot source so the core instanced-loot loop (generate-or-retrieve,
 * nullify, serve, persist) is written once rather than against a single class. Two shapes are
 * wrapped: {@link BlockEntityContainerAdapter} for {@code RandomizableContainerBlockEntity} block
 * entities and {@link MinecartContainerAdapter} for {@code AbstractMinecartContainer} minecarts.
 *
 * <p>The accessors and the loot-table/state writes ({@link #lootTable()}, {@link #clearLootTable()},
 * {@link #update}) run at serve time against a fresh source. {@link #persist} and
 * {@link #closeFeedback()} run later, when the screen closes, so each implementation re-resolves or
 * guards its source against removal while the menu was open.
 */
public interface ContainerAdapter {

    /** The live loot table key, or {@code null} once nulled (or for a player-placed container). */
    @Nullable
    ResourceKey<LootTable> lootTable();

    /** The live loot table seed. */
    long lootTableSeed();

    /** Null the source's loot table and seed, severing it from the global loot (S-006). */
    void clearLootTable();

    /** Slot count of the served instance (27 for a chest, 5 for a hopper minecart). */
    int size();

    /** Title for the served screen. */
    Component displayName();

    /** World position used as the loot {@code ORIGIN} and for the open/close cue. */
    Vec3 origin();

    /** The level the source lives in. */
    ServerLevel level();

    /** The instanced-loot state on the source, or {@code null} if none has been attached yet. */
    @Nullable
    InstancedLootData data();

    /** Apply a mutation to the source's instanced-loot state, creating and persisting it as needed. */
    InstancedLootData update(Consumer<InstancedLootData> mutation);

    /** Write the closed screen's contents back to the player's stored inventory. */
    void persist(UUID player, Container screenInventory);

    /**
     * Tell the player's client that they have just generated loot here, so it drops the unlooted
     * indicator (S-009). Called once, on the generation path only. Block sources push a
     * {@code BlockPos}-keyed {@code ContainerLootedS2C}; minecarts are entities the block-keyed
     * indicator protocol cannot address, so their adapter no-ops until the entity-anchored path (S-038).
     */
    void notifyGenerated(ServerPlayer player);

    /** Play the open sound and (for blocks) animate the lid. */
    void openFeedback();

    /** Play the close sound and (for blocks) close the lid. */
    void closeFeedback();

    /** Whether the source carries (or has consumed) a loot table and so should be instanced. */
    default boolean isLootContainer() {
        InstancedLootData data = data();
        return lootTable() != null || (data != null && data.isGenerated());
    }
}
