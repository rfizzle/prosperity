package com.rfizzle.prosperity.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * Default {@link InstancedLootComponent}. All mutators persist the owning block entity via
 * {@link BlockEntity#setChanged()} so CCA writes the component when the chunk saves.
 *
 * <p><b>Threading:</b> a block entity's CCA component is only touched on the server thread
 * (interaction handling, chunk load/save), so the maps are plain {@link HashMap}s guarded by
 * that confinement rather than {@code ConcurrentHashMap}.
 */
public final class InstancedLootComponentImpl implements InstancedLootComponent {

    @Nullable
    private final BlockEntity owner;

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

    public InstancedLootComponentImpl(@Nullable BlockEntity owner) {
        this.owner = owner;
    }

    private void persist() {
        if (owner != null) {
            owner.setChanged();
        }
    }

    @Override
    public boolean isGenerated() {
        return generated;
    }

    @Override
    public void markGenerated(@Nullable ResourceKey<LootTable> originalLootTable, long originalSeed) {
        this.generated = true;
        if (this.originalLootTable == null && originalLootTable != null) {
            this.originalLootTable = originalLootTable;
            this.originalSeed = originalSeed;
        }
        persist();
    }

    @Override
    @Nullable
    public ResourceKey<LootTable> getOriginalLootTable() {
        return originalLootTable;
    }

    @Override
    public long getOriginalSeed() {
        return originalSeed;
    }

    @Override
    public boolean hasInventory(UUID player) {
        return playerInventories.containsKey(player);
    }

    @Override
    public Set<UUID> playerIds() {
        return new HashSet<>(playerInventories.keySet());
    }

    @Override
    public NonNullList<ItemStack> getOrCreateInventory(UUID player, int size) {
        NonNullList<ItemStack> existing = playerInventories.get(player);
        if (existing != null) {
            return existing;
        }
        NonNullList<ItemStack> created = NonNullList.withSize(size, ItemStack.EMPTY);
        playerInventories.put(player, created);
        persist();
        return created;
    }

    @Override
    @Nullable
    public NonNullList<ItemStack> getInventory(UUID player) {
        return playerInventories.get(player);
    }

    @Override
    public void setInventory(UUID player, NonNullList<ItemStack> inventory) {
        playerInventories.put(player, inventory);
        persist();
    }

    @Override
    public void clearForPlayer(UUID player) {
        boolean changed = playerInventories.remove(player) != null;
        changed |= lastGeneratedTick.remove(player) != null;
        if (changed) {
            persist();
        }
    }

    @Override
    public void clearAll() {
        if (playerInventories.isEmpty() && lastGeneratedTick.isEmpty()) {
            return;
        }
        playerInventories.clear();
        lastGeneratedTick.clear();
        persist();
    }

    @Override
    public long getLastGeneratedTick(UUID player) {
        return lastGeneratedTick.getOrDefault(player, -1L);
    }

    @Override
    public void setLastGeneratedTick(UUID player, long gameTime) {
        lastGeneratedTick.put(player, gameTime);
        persist();
    }

    @Override
    @Nullable
    public String getTierName() {
        return tierName;
    }

    @Override
    public void setTierName(@Nullable String tierName) {
        this.tierName = tierName;
        persist();
    }

    @Override
    @Nullable
    public ResourceLocation getStructure() {
        return structure;
    }

    @Override
    public void setStructure(@Nullable ResourceLocation structure) {
        this.structure = structure;
        persist();
    }

    @Override
    @Nullable
    public BlockPos getRedirect() {
        return redirect;
    }

    @Override
    public void setRedirect(@Nullable BlockPos primary) {
        this.redirect = primary;
        persist();
    }

    @Override
    public void readFromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        playerInventories.clear();
        lastGeneratedTick.clear();

        generated = tag.getBoolean("Generated");
        originalSeed = tag.getLong("OriginalSeed");
        originalLootTable = null;
        if (tag.contains("OriginalLootTable", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("OriginalLootTable"));
            if (id != null) {
                originalLootTable = ResourceKey.create(Registries.LOOT_TABLE, id);
            }
        }
        tierName = tag.contains("TierName", Tag.TAG_STRING) ? tag.getString("TierName") : null;
        structure = tag.contains("Structure", Tag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString("Structure"))
                : null;
        redirect = NbtUtils.readBlockPos(tag, "Redirect").orElse(null);

        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            UUID player = NbtUtils.loadUUID(entry.get("UUID"));
            if (entry.contains("Items", Tag.TAG_LIST)) {
                NonNullList<ItemStack> inv = NonNullList.withSize(entry.getInt("Size"), ItemStack.EMPTY);
                ContainerHelper.loadAllItems(entry, inv, registries);
                playerInventories.put(player, inv);
            }
            if (entry.contains("LastTick", Tag.TAG_LONG)) {
                lastGeneratedTick.put(player, entry.getLong("LastTick"));
            }
        }
    }

    /**
     * Whether the component carries no instanced state. CCA skips serializing a component
     * whose {@code writeToNbt} leaves the tag empty, so an inert component contributes nothing
     * — a naturally-placed storage chest/barrel (no loot table, never generated) stays
     * byte-identical to vanilla on disk.
     */
    private boolean isInert() {
        return !generated
                && originalLootTable == null
                && redirect == null
                && tierName == null
                && structure == null
                && playerInventories.isEmpty()
                && lastGeneratedTick.isEmpty();
    }

    @Override
    public void writeToNbt(CompoundTag tag, HolderLookup.Provider registries) {
        if (isInert()) {
            return;
        }
        tag.putBoolean("Generated", generated);
        tag.putLong("OriginalSeed", originalSeed);
        if (originalLootTable != null) {
            tag.putString("OriginalLootTable", originalLootTable.location().toString());
        }
        if (tierName != null) {
            tag.putString("TierName", tierName);
        }
        if (structure != null) {
            tag.putString("Structure", structure.toString());
        }
        if (redirect != null) {
            tag.put("Redirect", NbtUtils.writeBlockPos(redirect));
        }

        // Union of both per-player maps, sorted by UUID for deterministic NBT output.
        List<UUID> players = new ArrayList<>(playerInventories.keySet());
        for (UUID player : lastGeneratedTick.keySet()) {
            if (!playerInventories.containsKey(player)) {
                players.add(player);
            }
        }
        players.sort(null);

        ListTag playerList = new ListTag();
        for (UUID player : players) {
            CompoundTag entry = new CompoundTag();
            entry.put("UUID", NbtUtils.createUUID(player));
            NonNullList<ItemStack> inv = playerInventories.get(player);
            if (inv != null) {
                entry.putInt("Size", inv.size());
                ContainerHelper.saveAllItems(entry, inv, registries);
            }
            Long tick = lastGeneratedTick.get(player);
            if (tick != null) {
                entry.putLong("LastTick", tick);
            }
            playerList.add(entry);
        }
        tag.put("Players", playerList);
    }
}
