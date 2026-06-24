package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;

/**
 * Client asks the server for the set of instanced containers in {@code chunkPos} that the
 * requesting player has not yet looted. The server answers with {@link UnlootedContainersS2CPayload}.
 * Sent when a chunk loads client-side (E-003); rate-limited per player server-side.
 */
public record RequestUnlootedC2SPayload(ChunkPos chunkPos) implements CustomPacketPayload {

    public static final Type<RequestUnlootedC2SPayload> TYPE =
            new Type<>(Prosperity.id("request_unlooted_c2s"));

    public static final StreamCodec<FriendlyByteBuf, RequestUnlootedC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.chunkPos.x);
                        buf.writeVarInt(payload.chunkPos.z);
                    },
                    buf -> new RequestUnlootedC2SPayload(new ChunkPos(buf.readVarInt(), buf.readVarInt())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
