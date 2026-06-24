package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Tells a single player that they have just generated loot from the container at
 * {@code pos}, so the client drops its unlooted indicator there. Consumed in E-003.
 */
public record ContainerLootedS2CPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ContainerLootedS2CPayload> TYPE =
            new Type<>(Prosperity.id("container_looted_s2c"));

    public static final StreamCodec<FriendlyByteBuf, ContainerLootedS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos),
                    buf -> new ContainerLootedS2CPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
