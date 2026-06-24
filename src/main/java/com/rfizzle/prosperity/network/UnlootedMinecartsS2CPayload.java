package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server's minecart answer to {@link RequestUnlootedC2SPayload}: the network ids of every container
 * minecart in the requested chunk that the player has not yet looted. The entity parallel of
 * {@link UnlootedContainersS2CPayload} — carts move, so they are keyed by network id rather than the
 * block protocol's chunk-relative {@code BlockPos}.
 *
 * <p>The client adds these ids to a flat set and anchors each sparkle to the live entity it resolves
 * by id every frame, so no per-chunk grouping is carried: the set is pruned by
 * {@link MinecartLootedS2CPayload}, {@link MinecartRemovedS2CPayload}, and client-side entity unload.</p>
 */
public record UnlootedMinecartsS2CPayload(List<Integer> entityIds) implements CustomPacketPayload {

    // A loaded chunk holds at most a handful of container minecarts; 4096 mirrors the block payload's
    // ceiling and still bounds a malicious server's allocation on decode.
    public static final int MAX_ENTRIES = 4096;

    public static final Type<UnlootedMinecartsS2CPayload> TYPE =
            new Type<>(Prosperity.id("unlooted_minecarts_s2c"));

    public static final StreamCodec<FriendlyByteBuf, UnlootedMinecartsS2CPayload> CODEC =
            StreamCodec.of(UnlootedMinecartsS2CPayload::encode, UnlootedMinecartsS2CPayload::decode);

    private static void encode(FriendlyByteBuf buf, UnlootedMinecartsS2CPayload payload) {
        if (payload.entityIds.size() > MAX_ENTRIES) {
            throw new EncoderException(
                    "UnlootedMinecartsS2CPayload exceeds entry cap: " + payload.entityIds.size() + " > " + MAX_ENTRIES);
        }
        buf.writeVarInt(payload.entityIds.size());
        for (int id : payload.entityIds) {
            buf.writeVarInt(id);
        }
    }

    private static UnlootedMinecartsS2CPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("UnlootedMinecartsS2CPayload entry count out of bounds: " + size);
        }
        List<Integer> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buf.readVarInt());
        }
        return new UnlootedMinecartsS2CPayload(ids);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
