package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Tells every client tracking a container minecart that the cart with network id {@code entityId} is
 * gone (destroyed or killed), so each drops its unlooted indicator. The entity parallel of
 * {@link ContainerRemovedS2CPayload} (S-038).
 */
public record MinecartRemovedS2CPayload(int entityId) implements CustomPacketPayload {

    public static final Type<MinecartRemovedS2CPayload> TYPE =
            new Type<>(Prosperity.id("minecart_removed_s2c"));

    public static final StreamCodec<FriendlyByteBuf, MinecartRemovedS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.entityId),
                    buf -> new MinecartRemovedS2CPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
