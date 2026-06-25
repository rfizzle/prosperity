package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server's answer to {@link QueryProtectionC2SPayload}: the break-speed {@code multiplier} the client
 * should apply to the container at {@code pos} ({@code 1.0} = unprotected, normal speed). The client
 * divides {@code getDestroyProgress} by this so its cracking animation matches the server gate (S-017).
 */
public record ProtectionResultS2CPayload(BlockPos pos, float multiplier) implements CustomPacketPayload {

    public static final Type<ProtectionResultS2CPayload> TYPE =
            new Type<>(Prosperity.id("protection_result_s2c"));

    public static final StreamCodec<FriendlyByteBuf, ProtectionResultS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeBlockPos(payload.pos);
                        buf.writeFloat(payload.multiplier);
                    },
                    buf -> new ProtectionResultS2CPayload(buf.readBlockPos(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
