package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Tells a single player that they have just generated loot from the container minecart with network
 * id {@code entityId}, so the client drops its unlooted indicator for that cart. The entity parallel
 * of {@link ContainerLootedS2CPayload} (S-038).
 */
public record MinecartLootedS2CPayload(int entityId) implements CustomPacketPayload {

    public static final Type<MinecartLootedS2CPayload> TYPE =
            new Type<>(Prosperity.id("minecart_looted_s2c"));

    public static final StreamCodec<FriendlyByteBuf, MinecartLootedS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.entityId),
                    buf -> new MinecartLootedS2CPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
