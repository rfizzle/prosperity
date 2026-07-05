package com.rfizzle.prosperity.loot.eviction;

import com.rfizzle.prosperity.Prosperity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-global ledger of when each player was last seen online (issue #43), keyed by UUID and
 * measured in overworld game time — the same clock as {@code lastGeneratedTick} and
 * {@code lootRefreshDays}, so absence thresholds are deterministic and unit-testable, unlike
 * wall-clock or playerdata-file mtimes. Lives in the overworld's {@code DimensionDataStorage}
 * (one ledger per server, not per dimension), updated on player join and disconnect by
 * {@link AbsentPlayerEviction#register}.
 *
 * <p><b>Epoch fallback:</b> a UUID with no entry (a player who has not logged in since this ledger
 * first existed) reads as last seen at the ledger's creation {@code epoch}. Without it the
 * historical players an upgraded server most wants to evict — those who left before the ledger
 * existed and will never rejoin — could never become evictable. With it they become evictable one
 * full threshold after the ledger's creation, and no one can be evicted sooner than that.
 *
 * <p><b>Crash semantics:</b> a hard stop can skip the disconnect touch, leaving a player's entry at
 * their join time. That only under-reports presence by one session, and their next join re-stamps
 * it — an acceptable skew against a threshold measured in whole days.
 *
 * <p><b>Bounding:</b> one {@code long} per distinct player ever seen — the same order of growth as
 * vanilla's own {@code playerdata/} directory, and negligible beside it. Entries are deliberately
 * never dropped: removing one would promote the player to the (older) epoch fallback, making them
 * look <em>more</em> absent than the ledger actually knows.
 *
 * <p><b>Threading:</b> mutated only on the server thread (connection events); the map is a
 * {@link ConcurrentHashMap} so the save-time iteration can never race a late write.
 */
public final class PlayerLastSeenState extends SavedData {

    static final String STORAGE_KEY = "prosperity_player_last_seen";
    private static final String TAG_EPOCH = "epoch";
    private static final String TAG_PLAYERS = "players";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_LAST_SEEN = "lastSeen";

    /** Sentinel for an epoch not yet stamped; {@link #get} replaces it before handing the state out. */
    static final long EPOCH_UNSET = -1L;

    /**
     * The {@link DataFixTypes} must be non-null: on 1.21.1 {@code DimensionDataStorage} calls
     * {@code dataFixTypes.update(...)} unconditionally on read, so a {@code null} type NPEs inside
     * the storage's swallowed load and {@code computeIfAbsent} would silently hand back a fresh
     * empty ledger every restart — re-stamping the epoch and deferring all eviction by a full
     * threshold. Any {@code SAVED_DATA_*} constant is benign <em>on 1.21.1</em>: {@link #save}
     * stamps the current DataVersion, so the fixer short-circuits without touching the tag. On an
     * MC version bump this choice must be re-verified — a real random-sequences datafixer
     * registered between the save's version and the target would run against this tag and could
     * silently drop it (resetting the ledger and deferring eviction by a full threshold).
     */
    public static final SavedData.Factory<PlayerLastSeenState> FACTORY =
            new SavedData.Factory<>(PlayerLastSeenState::new, PlayerLastSeenState::load,
                    DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /** UUID &rarr; overworld game time of the player's most recent join or disconnect. */
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();

    /** Game time at which this ledger was first created; the fallback for unknown players. */
    private long epoch = EPOCH_UNSET;

    /**
     * The server's ledger, created on first use. A freshly-created ledger stamps the current
     * overworld game time as its {@link #epoch}.
     */
    public static PlayerLastSeenState get(MinecraftServer server) {
        PlayerLastSeenState state =
                server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_KEY);
        if (state.epoch == EPOCH_UNSET) {
            state.epoch = server.overworld().getGameTime();
            state.setDirty();
        }
        return state;
    }

    /** Record that {@code player} was seen at {@code gameTime}. Dirties only on an actual change. */
    public void touch(UUID player, long gameTime) {
        Long previous = lastSeen.put(player, gameTime);
        if (previous == null || previous != gameTime) {
            setDirty();
        }
    }

    /**
     * The game time {@code player} was last seen, falling back to the ledger's creation
     * {@link #epoch} for a player with no entry (never online since the ledger existed).
     */
    public long lastSeen(UUID player) {
        Long seen = lastSeen.get(player);
        if (seen != null) {
            return seen;
        }
        // An unset epoch is unreachable through get(), which always stamps first; if it is ever
        // reached anyway, fail safe — "seen in the far future" defers eviction rather than
        // treating everyone as absent since world start.
        return epoch == EPOCH_UNSET ? Long.MAX_VALUE : epoch;
    }

    /** The ledger's creation game time — the {@link #lastSeen} fallback for unknown players. */
    long epoch() {
        return epoch;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (epoch != EPOCH_UNSET) {
            tag.putLong(TAG_EPOCH, epoch);
        }
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(lastSeen.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Long> entry : entries) {
            CompoundTag player = new CompoundTag();
            player.putString(TAG_UUID, entry.getKey().toString());
            player.putLong(TAG_LAST_SEEN, entry.getValue());
            list.add(player);
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }

    /** Rebuild from NBT, skipping malformed entries with a warning rather than failing the load. */
    public static PlayerLastSeenState load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerLastSeenState state = new PlayerLastSeenState();
        // An absent epoch (a tag written before it was stamped) stays unset; get() re-stamps it,
        // which only defers eviction — never accelerates it.
        state.epoch = tag.contains(TAG_EPOCH, Tag.TAG_LONG) ? tag.getLong(TAG_EPOCH) : EPOCH_UNSET;
        for (Tag raw : tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            if (!entry.contains(TAG_LAST_SEEN, Tag.TAG_LONG)) {
                // A missing/mistyped tick would getLong() to 0 — "absent since world start", the
                // unsafe direction. Skip it: the player falls back to the epoch instead.
                Prosperity.LOGGER.warn("Skipping last-seen entry with no tick for UUID {}",
                        entry.getString(TAG_UUID));
                continue;
            }
            try {
                state.lastSeen.put(UUID.fromString(entry.getString(TAG_UUID)),
                        entry.getLong(TAG_LAST_SEEN));
            } catch (IllegalArgumentException e) {
                Prosperity.LOGGER.warn("Skipping malformed last-seen entry with UUID {}",
                        entry.getString(TAG_UUID));
            }
        }
        return state;
    }
}
