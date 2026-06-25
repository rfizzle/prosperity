package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client asks the server whether the container at {@code pos} is break-protected, sent when the
 * player starts breaking a block (S-017). The server answers with {@link ProtectionResultS2CPayload}
 * carrying the multiplier to slow the cracking animation by. Rate-limited per player server-side.
 */
public record QueryProtectionC2SPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<QueryProtectionC2SPayload> TYPE =
            new Type<>(Prosperity.id("query_protection_c2s"));

    public static final StreamCodec<FriendlyByteBuf, QueryProtectionC2SPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new QueryProtectionC2SPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
