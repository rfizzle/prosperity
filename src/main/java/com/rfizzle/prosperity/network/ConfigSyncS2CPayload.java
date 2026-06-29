package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Pushes the server's authoritative config to a client as a compact JSON string
 * ({@link com.rfizzle.prosperity.config.ProsperityConfig#toSyncJson()}). Sent on join
 * (config before any data) and on {@code /prosperity reload}.
 */
public record ConfigSyncS2CPayload(String configJson) implements CustomPacketPayload {

    // Cap serialized config JSON. writeUtf/readUtf enforce a char limit; the cap sits well
    // above the realistic config size (the compact default is ~1.6k chars, leaving headroom
    // for user-grown blacklists, structure overrides, and loot-table mappings) while still
    // bounding a hostile server's payload below writeUtf's 32767 hard limit. If a future
    // config addition exceeds this, the codec throws EncoderException — a deliberate
    // fail-fast signal to bump the cap or switch to per-field encoding.
    public static final int MAX_CONFIG_JSON_CHARS = 16384;

    public static final Type<ConfigSyncS2CPayload> TYPE =
            new Type<>(Prosperity.id("config_sync_s2c"));

    public static final StreamCodec<FriendlyByteBuf, ConfigSyncS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.configJson, MAX_CONFIG_JSON_CHARS),
                    buf -> new ConfigSyncS2CPayload(buf.readUtf(MAX_CONFIG_JSON_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
