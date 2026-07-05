package com.rfizzle.prosperity.loot.eviction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * NBT round-trip and fallback checks for the last-seen ledger (issue #43). The
 * {@code DimensionDataStorage} wiring and epoch stamping via {@code get(server)} need a live
 * server and are covered by {@code AbsentPlayerEvictionGameTest}. The join/disconnect touches ride
 * {@code ServerPlayConnectionEvents}, which gametest mock players bypass (no connection
 * handshake), so that registration is verified by inspection only.
 */
class PlayerLastSeenStateTest {

    private static PlayerLastSeenState roundTrip(PlayerLastSeenState source) {
        return PlayerLastSeenState.load(source.save(new CompoundTag(), null), null);
    }

    @Test
    void roundTrip_preservesEntriesAndEpoch() {
        UUID a = new UUID(1L, 1L);
        UUID b = new UUID(2L, 2L);
        CompoundTag seed = new CompoundTag();
        seed.putLong("epoch", 123L);
        PlayerLastSeenState source = PlayerLastSeenState.load(seed, null);
        source.touch(a, 1_000L);
        source.touch(b, 2_000L);

        PlayerLastSeenState restored = roundTrip(source);

        assertEquals(1_000L, restored.lastSeen(a));
        assertEquals(2_000L, restored.lastSeen(b));
        assertEquals(123L, restored.epoch(), "the creation epoch survives reload");
    }

    @Test
    void unknownPlayerFallsBackToEpoch() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("epoch", 5_000L);
        PlayerLastSeenState state = PlayerLastSeenState.load(tag, null);

        assertEquals(5_000L, state.lastSeen(new UUID(9L, 9L)),
                "a player never seen since the ledger existed reads as last seen at the epoch");
    }

    @Test
    void touchOverwritesEarlierSighting() {
        UUID player = new UUID(3L, 3L);
        PlayerLastSeenState state = new PlayerLastSeenState();
        state.touch(player, 100L);
        state.touch(player, 400L);

        assertEquals(400L, state.lastSeen(player));
    }

    @Test
    void save_ordersPlayersDeterministically() {
        PlayerLastSeenState source = new PlayerLastSeenState();
        source.touch(new UUID(3L, 0L), 30L);
        source.touch(new UUID(1L, 0L), 10L);
        source.touch(new UUID(2L, 0L), 20L);

        ListTag first = source.save(new CompoundTag(), null).getList("players", Tag.TAG_COMPOUND);
        ListTag second = source.save(new CompoundTag(), null).getList("players", Tag.TAG_COMPOUND);

        assertEquals(first.toString(), second.toString(), "repeat saves are byte-identical");
        assertEquals(new UUID(1L, 0L).toString(), first.getCompound(0).getString("uuid"),
                "entries are sorted by UUID");
    }

    @Test
    void load_skipsMalformedEntries() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("epoch", 5_000L);
        ListTag players = new ListTag();
        CompoundTag badUuid = new CompoundTag();
        badUuid.putString("uuid", "not-a-uuid");
        badUuid.putLong("lastSeen", 1L);
        players.add(badUuid);
        // A missing tick must not getLong() to 0 ("absent since world start" — the unsafe
        // direction); the entry is skipped so the player falls back to the epoch instead.
        UUID tickless = new UUID(8L, 8L);
        CompoundTag noTick = new CompoundTag();
        noTick.putString("uuid", tickless.toString());
        players.add(noTick);
        CompoundTag good = new CompoundTag();
        good.putString("uuid", new UUID(7L, 7L).toString());
        good.putLong("lastSeen", 700L);
        players.add(good);
        tag.put("players", players);

        PlayerLastSeenState state = PlayerLastSeenState.load(tag, null);

        assertEquals(700L, state.lastSeen(new UUID(7L, 7L)), "the well-formed entry survives");
        assertEquals(5_000L, state.lastSeen(tickless),
                "a tickless entry falls back to the epoch, not to game time 0");
        ListTag saved = state.save(new CompoundTag(), null).getList("players", Tag.TAG_COMPOUND);
        assertTrue(saved.size() == 1, "the malformed entries are dropped, not resaved");
    }
}
