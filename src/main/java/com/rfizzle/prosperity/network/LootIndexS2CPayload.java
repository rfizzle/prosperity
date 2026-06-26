package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Pushes the server-built loot index ({@link com.rfizzle.prosperity.loot.index.LootIndexDataSource})
 * to a client so the recipe-viewer plugins (S-029/030/031) can browse instanced loot on a remote
 * dedicated server, where loot tables live only in the server's datapacks and never reach the client.
 * Sent on join (after the config) and re-broadcast after a server {@code /reload} (S-047).
 *
 * <p>Carries one {@link LootIndexEntry} per row, so the codec rides {@link RegistryFriendlyByteBuf}
 * (the {@link ItemStack} component needs registry access). The index is data-driven and can be large,
 * so {@link #of(List)} truncates oversize input at the source with a warning (a huge modded index
 * shows most rows rather than failing entirely), while the codec hard-caps on decode as the
 * anti-malformed-packet guard. Realistic indexes sit far under MC's ~1 MiB custom-payload ceiling, so
 * a single capped payload suffices — no segmentation.
 */
public record LootIndexS2CPayload(List<LootIndexEntry> rows) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 8192;

    public static final Type<LootIndexS2CPayload> TYPE =
            new Type<>(Prosperity.id("loot_index_s2c"));

    private static final StreamCodec<FriendlyByteBuf, Origin> ORIGIN_CODEC =
            StreamCodec.of((buf, origin) -> buf.writeEnum(origin), buf -> buf.readEnum(Origin.class));

    private static final StreamCodec<RegistryFriendlyByteBuf, LootIndexEntry> ROW_CODEC =
            StreamCodec.composite(
                    // OPTIONAL (empty-tolerant) so one malformed row degrades to an empty display rather
                    // than throwing and dropping the whole client's index — index rows always carry a
                    // real item, but the sync should never fail wholesale on a bad upstream stack.
                    ItemStack.OPTIONAL_STREAM_CODEC, LootIndexEntry::output,
                    ResourceLocation.STREAM_CODEC, LootIndexEntry::lootTable,
                    ResourceLocation.STREAM_CODEC, LootIndexEntry::structure,
                    ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), LootIndexEntry::minTier,
                    ORIGIN_CODEC, LootIndexEntry::origin,
                    LootIndexEntry::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, LootIndexS2CPayload> CODEC =
            StreamCodec.of(LootIndexS2CPayload::encode, LootIndexS2CPayload::decode);

    /**
     * Build a payload from the current index, truncating to {@link #MAX_ENTRIES} (with a warning) if
     * the index is larger than the wire cap. The only sanctioned constructor for the send paths.
     */
    public static LootIndexS2CPayload of(List<LootIndexEntry> rows) {
        if (rows.size() > MAX_ENTRIES) {
            Prosperity.LOGGER.warn("Loot index has {} entries, exceeding the {}-row sync cap; truncating "
                    + "— remote clients will see the first {} rows", rows.size(), MAX_ENTRIES, MAX_ENTRIES);
            return new LootIndexS2CPayload(List.copyOf(rows.subList(0, MAX_ENTRIES)));
        }
        return new LootIndexS2CPayload(List.copyOf(rows));
    }

    private static void encode(RegistryFriendlyByteBuf buf, LootIndexS2CPayload payload) {
        int count = Math.min(payload.rows.size(), MAX_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ROW_CODEC.encode(buf, payload.rows.get(i));
        }
    }

    private static LootIndexS2CPayload decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("Loot index entry count out of bounds: " + count);
        }
        List<LootIndexEntry> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(ROW_CODEC.decode(buf));
        }
        return new LootIndexS2CPayload(rows);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
