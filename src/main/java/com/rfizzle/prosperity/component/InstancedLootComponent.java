package com.rfizzle.prosperity.component;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.Component;

/**
 * Per-player loot state attached to a vanilla {@code RandomizableContainerBlockEntity}
 * (chests, barrels, shulker boxes, dispensers, droppers). See {@code design/SPEC.md} §1.
 *
 * <p>Each player who opens an instanced container gets their own private inventory,
 * generated on first visit and retrieved thereafter. The original loot table and seed
 * are preserved here so the vanilla fields on the block entity can be nulled (S-006),
 * defeating hopper/comparator exploits.
 *
 * <p>Mutators persist the owning block entity (CCA writes component data as part of the
 * block entity's NBT), so callers do not need to mark the block entity changed themselves.
 */
public interface InstancedLootComponent extends Component {

    /** Whether any player has triggered generation (vanilla loot table has been or will be nulled). */
    boolean isGenerated();

    /**
     * Record that generation has occurred, preserving the original loot table and seed.
     * Idempotent for the table/seed: the first non-null table wins so later players reuse it.
     */
    void markGenerated(@Nullable ResourceKey<LootTable> originalLootTable, long originalSeed);

    /** The preserved loot table key, or {@code null} if never generated / it had none. */
    @Nullable
    ResourceKey<LootTable> getOriginalLootTable();

    /** The preserved loot table seed (0 when none). */
    long getOriginalSeed();

    /** Whether the given player already has a generated inventory in this container. */
    boolean hasInventory(UUID player);

    /**
     * The player's inventory, creating an empty one sized to {@code size} if absent.
     * Mutating the returned list and then calling {@link #setInventory} (or any mutator)
     * persists it.
     */
    NonNullList<ItemStack> getOrCreateInventory(UUID player, int size);

    /** The player's inventory, or {@code null} if they have not generated one. */
    @Nullable
    NonNullList<ItemStack> getInventory(UUID player);

    /** Store (and persist) the player's inventory. */
    void setInventory(UUID player, NonNullList<ItemStack> inventory);

    /**
     * Clear the player's inventory and last-generated tick so their next interaction
     * regenerates from scratch (loot refresh, S-016; {@code /prosperity refresh}, S-004).
     */
    void clearForPlayer(UUID player);

    /** Clear every player's instanced data ({@code /prosperity reset}, S-004). */
    void clearAll();

    /** Absolute game time at which the player last generated, or {@code -1} if never. */
    long getLastGeneratedTick(UUID player);

    /** Record the absolute game time at which the player generated. */
    void setLastGeneratedTick(UUID player, long gameTime);

    /** Cached resolved tier name (S-011), or {@code null} if not yet resolved. */
    @Nullable
    String getTierName();

    void setTierName(@Nullable String tierName);

    /** Cached resolved structure id (S-012), or {@code null} if outside any structure. */
    @Nullable
    ResourceLocation getStructure();

    void setStructure(@Nullable ResourceLocation structure);

    /**
     * For the secondary half of a double chest: the primary half's position that holds the
     * shared instanced inventory (S-007). {@code null} for single containers and primaries.
     */
    @Nullable
    BlockPos getRedirect();

    void setRedirect(@Nullable BlockPos primary);
}
