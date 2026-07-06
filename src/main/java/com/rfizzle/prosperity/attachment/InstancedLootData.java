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
 * <p><b>Bounding:</b> the heavy {@code playerInventories} map holds an entry only while a player has
 * <em>uncollected</em> items. Once they loot a container clean, {@link #storeOrEvict} drops their
 * inventory so a high-traffic container does not accrue a stored inventory per visitor forever. Their
 * lightweight {@code lastGeneratedTick} (and {@code refreshCount}) remain as the "has visited" marker
 * — see {@link #hasGenerated} — so no loot is re-rolled and the looted indicator is unaffected. Those
 * long-valued maps still grow one small entry per distinct player; a refresh clear or
 * {@code /prosperity reset|refresh} removes the tick (retaining the salt), and the config-gated
 * absent-player eviction (issue #43) reclaims all three entries via {@link #evictPlayer} for players
 * gone beyond a last-seen threshold.
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
                            .forGetter(InstancedLootData::toEntries),
                    TeamEntry.CODEC.listOf().optionalFieldOf("teamMembers", List.of())
                            .forGetter(InstancedLootData::toTeamEntries)
            ).apply(instance, InstancedLootData::fromCodec));

    private final Map<UUID, NonNullList<ItemStack>> playerInventories = new HashMap<>();
    private final Map<UUID, Long> lastGeneratedTick = new HashMap<>();
    private final Map<UUID, Long> refreshCount = new HashMap<>();

    /**
     * Party loot mode membership snapshot (issue #53): team loot key &rarr; the UUIDs of players who
     * have opened this container under that team. Populated only in party loot mode, so it is empty (and
     * absent from NBT) for a normal per-player container. It records which shared instance a player is
     * bound to on this container even after they leave the team, closing the "leave, re-loot the same
     * chest" loop &mdash; resolution, not migration. The team key itself is a synthetic (type-3) UUID
     * that also indexes the per-player maps above, holding the shared inventory/tick/refresh; the
     * members here are the real player UUIDs that resolve to it.
     */
    private final Map<UUID, Set<UUID>> teamMembers = new HashMap<>();

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

    /**
     * Whether the given player currently holds <em>uncollected</em> items here. Returns {@code false}
     * once they have looted every slot (their inventory is evicted by {@link #storeOrEvict} to bound
     * NBT), even though they remain {@linkplain #hasGenerated visited}. Use {@link #hasGenerated} for
     * the "has this player opened this container" question; this is only for reading back live items.
     */
    public boolean hasInventory(UUID player) {
        return playerInventories.containsKey(player);
    }

    /**
     * Whether the given player has generated an instance here, whether or not they still hold items.
     * A fully-looted player keeps their {@code lastGeneratedTick} after {@link #storeOrEvict} drops
     * their emptied inventory, so this stays {@code true} — driving the "looted" indicator (S-009),
     * Jade status, protection check (S-017), and the re-open path that serves them an empty container
     * rather than fresh loot. Goes {@code false} only once a refresh clear removes their entry.
     */
    public boolean hasGenerated(UUID player) {
        return playerInventories.containsKey(player) || lastGeneratedTick.containsKey(player);
    }

    /**
     * The UUIDs of every player who has generated an instance here (holding items or fully looted),
     * as a defensive copy. The size is the instance count reported by {@code /prosperity reset}
     * (S-004). Mirrors {@link #hasGenerated}: a player whose emptied inventory has been evicted still
     * appears here via their retained {@code lastGeneratedTick}.
     */
    public Set<UUID> playerIds() {
        Set<UUID> ids = new HashSet<>(playerInventories.keySet());
        ids.addAll(lastGeneratedTick.keySet());
        return ids;
    }

    /**
     * Every player with <em>any</em> entry here — a stored inventory, a last-generated tick, or a
     * retained refresh count — as a defensive copy. A superset of {@link #playerIds()}: a salt-only
     * player (all their state cleared by {@code /prosperity reset}, leaving just the refresh count)
     * appears here but is not a live instance. This is the census for absent-player eviction
     * (issue #43), which must reclaim salt-only entries too.
     */
    public Set<UUID> trackedPlayerIds() {
        Set<UUID> ids = new HashSet<>(playerInventories.keySet());
        ids.addAll(lastGeneratedTick.keySet());
        ids.addAll(refreshCount.keySet());
        return ids;
    }

    /**
     * Drop every entry for {@code player} — inventory, last-generated tick, and refresh count —
     * without advancing any salt (absent-player eviction, issue #43). Unlike {@link #clearForPlayer},
     * which preserves the refresh count as the re-roll salt for a player expected back, this forgets
     * the player entirely: if they do return, they regenerate from scratch with a salt of {@code 0}.
     */
    public void evictPlayer(UUID player) {
        playerInventories.remove(player);
        lastGeneratedTick.remove(player);
        refreshCount.remove(player);
        // Also forget this player's team-membership records so a fully-evicted player is not silently
        // re-bound to an old shared instance if they return. Drops any team set that empties out.
        teamMembers.values().forEach(members -> members.remove(player));
        teamMembers.values().removeIf(Set::isEmpty);
    }

    /**
     * Record (party loot mode, issue #53) that {@code member} has opened this container under the shared
     * {@code teamKey}, so they keep {@linkplain #teamKeyForMember resolving} to that instance even after
     * leaving the team. Idempotent.
     */
    public void recordTeamMember(UUID teamKey, UUID member) {
        teamMembers.computeIfAbsent(teamKey, key -> new HashSet<>()).add(member);
    }

    /** Whether {@code member} is recorded under the shared {@code teamKey} on this container. */
    public boolean isTeamMember(UUID teamKey, UUID member) {
        Set<UUID> members = teamMembers.get(teamKey);
        return members != null && members.contains(member);
    }

    /**
     * The team loot key this container has already bound {@code member} to, or {@code null} if none.
     * Drives the "resolution, not migration" rule: a player who opened a container with their team stays
     * on that instance for this container regardless of later team changes.
     */
    @Nullable
    public UUID teamKeyForMember(UUID member) {
        for (Map.Entry<UUID, Set<UUID>> entry : teamMembers.entrySet()) {
            if (entry.getValue().contains(member)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The synthetic team loot keys with a membership snapshot here, as a defensive copy. */
    public Set<UUID> teamKeys() {
        return new HashSet<>(teamMembers.keySet());
    }

    /** Whether {@code key} is a team loot key (has a membership snapshot), not an individual player. */
    public boolean isTeamKey(UUID key) {
        return teamMembers.containsKey(key);
    }

    /** The member UUIDs recorded under {@code teamKey}, as a defensive copy (empty if none). */
    public Set<UUID> teamMembers(UUID teamKey) {
        Set<UUID> members = teamMembers.get(teamKey);
        return members == null ? Set.of() : new HashSet<>(members);
    }

    /**
     * Every real player UUID recorded in any team snapshot here, as a defensive copy. A team member's
     * loot state lives under the synthetic team key, so members never appear in {@link #trackedPlayerIds}
     * — absent-player eviction (issue #43) unions this in so a long-departed member is dropped from the
     * snapshot too, keeping it bounded (the "bound every persisted collection" invariant).
     */
    public Set<UUID> allTeamMembers() {
        Set<UUID> members = new HashSet<>();
        teamMembers.values().forEach(members::addAll);
        return members;
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
     * Write the player's inventory back after they close the container, evicting it when fully looted.
     * An all-empty inventory is dropped from the heavy {@code playerInventories} map so a container
     * looted by every visitor does not accumulate one stored inventory per player forever (the
     * mc-persistence "bound every persisted collection" bar). The player's {@code lastGeneratedTick}
     * and {@code refreshCount} stay put, so the container still reads as {@linkplain #hasGenerated
     * visited}: no fresh loot on re-open, the indicator stays looted, and a refresh still clears and
     * re-rolls on schedule. A non-empty inventory is stored verbatim, preserving partial loot.
     */
    public void storeOrEvict(UUID player, NonNullList<ItemStack> inventory) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                playerInventories.put(player, inventory);
                return;
            }
        }
        playerInventories.remove(player);
    }

    /**
     * Clear the player's inventory and last-generated tick so their next interaction regenerates
     * from scratch (loot refresh, S-016; {@code /prosperity refresh}, S-004). Advances the player's
     * {@linkplain #getRefreshCount refresh count} when they had a live instance, so a re-roll under
     * {@code randomizeLootOnRefresh} draws different items than the cleared generation. The count
     * outlives the cleared inventory.
     */
    public void clearForPlayer(UUID player) {
        if (playerInventories.containsKey(player) || lastGeneratedTick.containsKey(player)) {
            refreshCount.merge(player, 1L, Long::sum);
        }
        playerInventories.remove(player);
        lastGeneratedTick.remove(player);
    }

    /**
     * Clear every player's instanced data ({@code /prosperity reset}, S-004). Advances the refresh
     * count of every player who had a live instance, on the same basis as {@link #clearForPlayer}.
     */
    public void clearAll() {
        Set<UUID> present = new HashSet<>(playerInventories.keySet());
        present.addAll(lastGeneratedTick.keySet());
        for (UUID player : present) {
            refreshCount.merge(player, 1L, Long::sum);
        }
        playerInventories.clear();
        lastGeneratedTick.clear();
    }

    /**
     * How many times this player's instance here has been cleared (loot refresh or
     * {@code /prosperity reset|refresh}); {@code 0} before the first clear. Folded into the roll seed
     * as a salt when {@code randomizeLootOnRefresh} is on, so each regeneration draws fresh items
     * while staying reproducible across a reload for a given count.
     */
    public long getRefreshCount(UUID player) {
        return refreshCount.getOrDefault(player, 0L);
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

    /** The union of the per-player maps, sorted by UUID for deterministic NBT output. */
    private List<PlayerEntry> toEntries() {
        Set<UUID> players = new TreeSet<>(playerInventories.keySet());
        players.addAll(lastGeneratedTick.keySet());
        players.addAll(refreshCount.keySet());
        List<PlayerEntry> entries = new ArrayList<>(players.size());
        for (UUID player : players) {
            NonNullList<ItemStack> inv = playerInventories.get(player);
            entries.add(new PlayerEntry(player,
                    inv != null ? List.copyOf(inv) : List.of(),
                    Optional.ofNullable(lastGeneratedTick.get(player)),
                    refreshCount.getOrDefault(player, 0L)));
        }
        return entries;
    }

    /** The team-membership snapshot as a list of entries, sorted by team key for deterministic NBT. */
    private List<TeamEntry> toTeamEntries() {
        Set<UUID> keys = new TreeSet<>(teamMembers.keySet());
        List<TeamEntry> entries = new ArrayList<>(keys.size());
        for (UUID teamKey : keys) {
            Set<UUID> members = teamMembers.get(teamKey);
            if (members != null && !members.isEmpty()) {
                entries.add(new TeamEntry(teamKey, new ArrayList<>(new TreeSet<>(members))));
            }
        }
        return entries;
    }

    private static InstancedLootData fromCodec(boolean generated,
            Optional<ResourceKey<LootTable>> originalLootTable, long originalSeed,
            Optional<String> tierName, Optional<ResourceLocation> structure,
            Optional<BlockPos> redirect, List<PlayerEntry> players, List<TeamEntry> teamMembers) {
        InstancedLootData data = new InstancedLootData();
        data.generated = generated;
        data.originalLootTable = originalLootTable.orElse(null);
        data.originalSeed = originalSeed;
        data.tierName = tierName.orElse(null);
        data.structure = structure.orElse(null);
        data.redirect = redirect.orElse(null);
        for (TeamEntry entry : teamMembers) {
            if (!entry.members().isEmpty()) {
                data.teamMembers.put(entry.teamKey(), new HashSet<>(entry.members()));
            }
        }
        for (PlayerEntry entry : players) {
            if (!entry.items().isEmpty()) {
                NonNullList<ItemStack> inv = NonNullList.withSize(entry.items().size(), ItemStack.EMPTY);
                for (int slot = 0; slot < inv.size(); slot++) {
                    inv.set(slot, entry.items().get(slot));
                }
                data.playerInventories.put(entry.uuid(), inv);
            }
            entry.lastTick().ifPresent(tick -> data.lastGeneratedTick.put(entry.uuid(), tick));
            if (entry.refreshCount() > 0L) {
                data.refreshCount.put(entry.uuid(), entry.refreshCount());
            }
        }
        return data;
    }

    /**
     * One player's slice of a container: their inventory (an empty list when none is stored), their
     * last-generated tick, and their refresh count (omitted from NBT while {@code 0}).
     * {@code ItemStack.OPTIONAL_CODEC} preserves empty slots, so the list length carries the inventory
     * size and data components survive intact.
     */
    private record PlayerEntry(UUID uuid, List<ItemStack> items, Optional<Long> lastTick, long refreshCount) {

        private static final Codec<PlayerEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(PlayerEntry::uuid),
                        ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of())
                                .forGetter(PlayerEntry::items),
                        Codec.LONG.optionalFieldOf("lastTick").forGetter(PlayerEntry::lastTick),
                        Codec.LONG.optionalFieldOf("refreshCount", 0L).forGetter(PlayerEntry::refreshCount)
                ).apply(instance, PlayerEntry::new));
    }

    /**
     * One shared instance's membership: the synthetic team loot key and the real player UUIDs bound to
     * it on this container (party loot mode, issue #53). Serialized only when non-empty, so a normal
     * per-player container adds nothing to NBT.
     */
    private record TeamEntry(UUID teamKey, List<UUID> members) {

        private static final Codec<TeamEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.STRING_CODEC.fieldOf("teamKey").forGetter(TeamEntry::teamKey),
                        UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("members", List.of())
                                .forGetter(TeamEntry::members)
                ).apply(instance, TeamEntry::new));
    }
}
