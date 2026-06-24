package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Broadcast to every client tracking {@code pos} that the loot container there was
 * broken, so all of them drop the unlooted indicator. Consumed in E-003 (sent from S-008).
 */
public record ContainerRemovedS2CPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ContainerRemovedS2CPayload> TYPE =
            new Type<>(Prosperity.id("container_removed_s2c"));

    public static final StreamCodec<FriendlyByteBuf, ContainerRemovedS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new ContainerRemovedS2CPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
