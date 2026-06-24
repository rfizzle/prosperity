package com.rfizzle.prosperity.attachment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Per-player loot state for a vanilla loot source, stored as a persistent Fabric data attachment
 * (see {@link ProsperityAttachments}). For block-entity containers it rides
 * {@code RandomizableContainerBlockEntity}; see {@code design/SPEC.md} §1.
 *
 * <p>Each player who opens an instanced container gets their own private inventory, generated on
 * first visit and retrieved thereafter. The original loot table and seed are preserved here so the
 * vanilla fields on the block entity can be nulled (S-006), defeating hopper/comparator exploits.
 *
 * <p><b>Dirtying:</b> this is a plain mutable value with no reference to its owner. Mutating it in
 * place does <em>not</em> mark the owning block entity dirty — only {@code setAttached(...)} does.
 * Every write must go through {@link ProsperityAttachments#update}, which calls
 * {@link BlockEntity#setChanged()} after the mutation so it persists.
 *
 * <p><b>Threading:</b> the attachment is only touched on the server thread (interaction handling,
 * chunk load/save), so the maps are plain {@link HashMap}s guarded by that confinement rather than
 * {@code ConcurrentHashMap}.
 */
public final class InstancedLootData {

    /** Round-trips through the block entity's own NBT (Fabric serializes the attachment there). */
    public static final Codec<InstancedLootData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.optionalFieldOf("generated", false)
                            .forGetter(d -> d.generated),
                    ResourceKey.codec(Registries.LOOT_TABLE).optionalFieldOf("originalLootTable")
                            .forGetter(d -> Optional.ofNullable(d.originalLootTable)),
                    Codec.LONG.optionalFieldOf("originalSeed", 0L)
                            .forGetter(d -> d.originalSeed),
                    Codec.STRING.optionalFieldOf("tierName")
                            .forGetter(d -> Optional.ofNullable(d.tierName)),
                    ResourceLocation.CODEC.optionalFieldOf("structure")
                            .forGetter(d -> Optional.ofNullable(d.structure)),
                    BlockPos.CODEC.optionalFieldOf("redirect")
                            .forGetter(d -> Optional.ofNullable(d.redirect)),
                    PlayerEntry.CODEC.listOf().optionalFieldOf("players", List.of())
                            .forGetter(InstancedLootData::toEntries)
            ).apply(instance, InstancedLootData::fromCodec));

    private final Map<UUID, NonNullList<ItemStack>> playerInventories = new HashMap<>();
    private final Map<UUID, Long> lastGeneratedTick = new HashMap<>();

    private boolean generated;
    @Nullable
    private ResourceKey<LootTable> originalLootTable;
    private long originalSeed;
    @Nullable
    private String tierName;
    @Nullable
    private ResourceLocation structure;
    @Nullable
    private BlockPos redirect;

    public InstancedLootData() {
    }

    /** Whether any player has triggered generation (vanilla loot table has been or will be nulled). */
    public boolean isGenerated() {
        return generated;
    }

    /**
     * Record that generation has occurred, preserving the original loot table and seed. Idempotent
     * for the table/seed: the first non-null table wins so later players reuse it.
     */
    public void markGenerated(@Nullable ResourceKey<LootTable> originalLootTable, long originalSeed) {
        this.generated = true;
        if (this.originalLootTable == null && originalLootTable != null) {
            this.originalLootTable = originalLootTable;
            this.originalSeed = originalSeed;
        }
    }

    /** The preserved loot table key, or {@code null} if never generated / it had none. */
    @Nullable
    public ResourceKey<LootTable> getOriginalLootTable() {
        return originalLootTable;
    }

    /** The preserved loot table seed (0 when none). */
    public long getOriginalSeed() {
        return originalSeed;
    }

    /** Whether the given player already has a generated inventory in this container. */
    public boolean hasInventory(UUID player) {
        return playerInventories.containsKey(player);
    }

    /**
     * The UUIDs of every player with a stored inventory here, as a defensive copy. The size is the
     * instance count reported by {@code /prosperity reset} (S-004); also serves the indicator scan
     * (S-009) and protection check (S-017).
     */
    public Set<UUID> playerIds() {
        return new HashSet<>(playerInventories.keySet());
    }

    /** The player's inventory, creating an empty one sized to {@code size} if absent. */
    public NonNullList<ItemStack> getOrCreateInventory(UUID player, int size) {
        return playerInventories.computeIfAbsent(player, key -> NonNullList.withSize(size, ItemStack.EMPTY));
    }

    /** The player's inventory, or {@code null} if they have not generated one. */
    @Nullable
    public NonNullList<ItemStack> getInventory(UUID player) {
        return playerInventories.get(player);
    }

    /** Store the player's inventory. */
    public void setInventory(UUID player, NonNullList<ItemStack> inventory) {
        playerInventories.put(player, inventory);
    }

    /**
     * Clear the player's inventory and last-generated tick so their next interaction regenerates
     * from scratch (loot refresh, S-016; {@code /prosperity refresh}, S-004).
     */
    public void clearForPlayer(UUID player) {
        playerInventories.remove(player);
        lastGeneratedTick.remove(player);
    }

    /** Clear every player's instanced data ({@code /prosperity reset}, S-004). */
    public void clearAll() {
        playerInventories.clear();
        lastGeneratedTick.clear();
    }

    /** Absolute game time at which the player last generated, or {@code -1} if never. */
    public long getLastGeneratedTick(UUID player) {
        return lastGeneratedTick.getOrDefault(player, -1L);
    }

    /** Record the absolute game time at which the player generated. */
    public void setLastGeneratedTick(UUID player, long gameTime) {
        lastGeneratedTick.put(player, gameTime);
    }

    /** Cached resolved tier name (S-011), or {@code null} if not yet resolved. */
    @Nullable
    public String getTierName() {
        return tierName;
    }

    public void setTierName(@Nullable String tierName) {
        this.tierName = tierName;
    }

    /** Cached resolved structure id (S-012), or {@code null} if outside any structure. */
    @Nullable
    public ResourceLocation getStructure() {
        return structure;
    }

    public void setStructure(@Nullable ResourceLocation structure) {
        this.structure = structure;
    }

    /**
     * For the secondary half of a double chest: the primary half's position that holds the shared
     * instanced inventory (S-007). {@code null} for single containers and primaries.
     */
    @Nullable
    public BlockPos getRedirect() {
        return redirect;
    }

    public void setRedirect(@Nullable BlockPos primary) {
        this.redirect = primary;
    }

    /** The union of both per-player maps, sorted by UUID for deterministic NBT output. */
    private List<PlayerEntry> toEntries() {
        Set<UUID> players = new TreeSet<>(playerInventories.keySet());
        players.addAll(lastGeneratedTick.keySet());
        List<PlayerEntry> entries = new ArrayList<>(players.size());
        for (UUID player : players) {
            NonNullList<ItemStack> inv = playerInventories.get(player);
            entries.add(new PlayerEntry(player,
                    inv != null ? List.copyOf(inv) : List.of(),
                    Optional.ofNullable(lastGeneratedTick.get(player))));
        }
        return entries;
    }

    private static InstancedLootData fromCodec(boolean generated,
            Optional<ResourceKey<LootTable>> originalLootTable, long originalSeed,
            Optional<String> tierName, Optional<ResourceLocation> structure,
            Optional<BlockPos> redirect, List<PlayerEntry> players) {
        InstancedLootData data = new InstancedLootData();
        data.generated = generated;
        data.originalLootTable = originalLootTable.orElse(null);
        data.originalSeed = originalSeed;
        data.tierName = tierName.orElse(null);
        data.structure = structure.orElse(null);
        data.redirect = redirect.orElse(null);
        for (PlayerEntry entry : players) {
            if (!entry.items().isEmpty()) {
                NonNullList<ItemStack> inv = NonNullList.withSize(entry.items().size(), ItemStack.EMPTY);
                for (int slot = 0; slot < inv.size(); slot++) {
                    inv.set(slot, entry.items().get(slot));
                }
                data.playerInventories.put(entry.uuid(), inv);
            }
            entry.lastTick().ifPresent(tick -> data.lastGeneratedTick.put(entry.uuid(), tick));
        }
        return data;
    }

    /**
     * One player's slice of a container: their inventory (an empty list when only a tick is stored)
     * and their last-generated tick. {@code ItemStack.OPTIONAL_CODEC} preserves empty slots, so the
     * list length carries the inventory size and data components survive intact.
     */
    private record PlayerEntry(UUID uuid, List<ItemStack> items, Optional<Long> lastTick) {

        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(PlayerEntry::uuid),
                        ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of())
                                .forGetter(PlayerEntry::items),
                        Codec.LONG.optionalFieldOf("lastTick").forGetter(PlayerEntry::lastTick)
                ).apply(instance, PlayerEntry::new));
    }
}
