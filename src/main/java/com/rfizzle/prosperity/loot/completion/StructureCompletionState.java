package com.rfizzle.prosperity.loot.completion;

import com.rfizzle.prosperity.Prosperity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension ledger of structure completion awards (the structure completion bonus): for each
 * structure instance, the players who have already earned its one-time bonus. Lives in the owning
 * {@link ServerLevel}'s {@code DimensionDataStorage} rather than on any container attachment, so an
 * award survives the awarding container being broken and a loot refresh clearing per-player container
 * state &mdash; the "one completion per structure instance per player, ever" guarantee cannot be
 * reset from the container side.
 *
 * <p>Keys are {@link StructureCompletion#instanceKey} strings ({@code <structure id>@<startChunkX>,
 * <startChunkZ>}); the dimension is implicit in which level's storage holds the ledger.
 *
 * <p><b>Bounding:</b> one UUID per (player, completed structure instance) &mdash; entries are written
 * only on an actual award, never speculatively. The ledger is deliberately never evicted: dropping an
 * entry would re-arm the bonus it exists to make one-time. At ~60 bytes per award the footprint stays
 * negligible against the worlds' own structure data.
 *
 * <p><b>Threading:</b> mutated only on the server thread (the loot-generation path); the map is a
 * {@link ConcurrentHashMap} so the save-time iteration can never race a late write.
 */
public final class StructureCompletionState extends SavedData {

    static final String STORAGE_KEY = "prosperity_structure_completions";
    private static final String TAG_COMPLETIONS = "completions";
    private static final String TAG_ID = "id";
    private static final String TAG_PLAYERS = "players";

    /**
     * The {@link DataFixTypes} must be non-null: on 1.21.1 {@code DimensionDataStorage} calls
     * {@code dataFixTypes.update(...)} unconditionally on read, so a {@code null} type NPEs inside the
     * storage's swallowed load, and {@code computeIfAbsent} would silently hand back a fresh empty
     * ledger on every server restart &mdash; re-arming every completion. Any {@code SAVED_DATA_*}
     * constant is benign here: {@link #save} stamps the current DataVersion, so the fixer
     * short-circuits without touching the tag.
     */
    public static final SavedData.Factory<StructureCompletionState> FACTORY =
            new SavedData.Factory<>(StructureCompletionState::new, StructureCompletionState::load,
                    DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /** Structure instance key &rarr; players awarded its completion bonus. */
    private final Map<String, Set<UUID>> completions = new ConcurrentHashMap<>();

    /** The ledger for {@code level}'s dimension, created on first use. */
    public static StructureCompletionState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, STORAGE_KEY);
    }

    /** Whether {@code player} has already been awarded the bonus for {@code structureKey}. */
    public boolean isCompleted(String structureKey, UUID player) {
        Set<UUID> players = completions.get(structureKey);
        return players != null && players.contains(player);
    }

    /**
     * Record {@code player}'s completion of {@code structureKey}. Returns {@code true} when this is a
     * new award (and marks the ledger dirty); {@code false} when it was already recorded, in which
     * case nothing changes &mdash; callers can gate the bonus grant on the return value so a re-entrant
     * award can never double-pay.
     */
    public boolean markCompleted(String structureKey, UUID player) {
        boolean added = completions
                .computeIfAbsent(structureKey, key -> ConcurrentHashMap.newKeySet())
                .add(player);
        if (added) {
            setDirty();
        }
        return added;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        List<String> keys = new ArrayList<>(completions.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            Set<UUID> players = completions.get(key);
            if (players == null || players.isEmpty()) {
                continue;
            }
            List<String> ids = new ArrayList<>(players.size());
            for (UUID player : players) {
                ids.add(player.toString());
            }
            ids.sort(String::compareTo);
            ListTag playerList = new ListTag();
            for (String id : ids) {
                playerList.add(StringTag.valueOf(id));
            }
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_ID, key);
            entry.put(TAG_PLAYERS, playerList);
            list.add(entry);
        }
        tag.put(TAG_COMPLETIONS, list);
        return tag;
    }

    /** Rebuild from NBT, skipping malformed entries with a warning rather than failing the load. */
    public static StructureCompletionState load(CompoundTag tag, HolderLookup.Provider registries) {
        StructureCompletionState state = new StructureCompletionState();
        for (Tag raw : tag.getList(TAG_COMPLETIONS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            String key = entry.getString(TAG_ID);
            if (key.isEmpty()) {
                Prosperity.LOGGER.warn("Skipping structure completion entry with no id");
                continue;
            }
            Set<UUID> players = ConcurrentHashMap.newKeySet();
            for (Tag playerTag : entry.getList(TAG_PLAYERS, Tag.TAG_STRING)) {
                try {
                    players.add(UUID.fromString(playerTag.getAsString()));
                } catch (IllegalArgumentException e) {
                    Prosperity.LOGGER.warn("Skipping malformed player UUID {} in structure completion {}",
                            playerTag.getAsString(), key);
                }
            }
            if (!players.isEmpty()) {
                state.completions.put(key, players);
            }
        }
        return state;
    }
}
