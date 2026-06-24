package com.rfizzle.prosperity.network;

import com.rfizzle.prosperity.Prosperity;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Server's answer to {@link RequestUnlootedC2SPayload}: every instanced container in
 * {@code chunkPos} that the requesting player has not yet looted, with the data the client
 * needs to place and size an indicator.
 *
 * <p>Each {@link Entry} packs its position relative to the chunk origin: the in-chunk X/Z
 * (each {@code 0..15}) share one byte ({@code (relX << 4) | relZ}) and the world Y rides in a
 * {@code short} (the {@code -64..319} build range fits a signed short). The container's slot
 * count travels as a {@code VarInt}; the client derives single-vs-double sizing from it
 * ({@code slots == 54}).</p>
 */
public record UnlootedContainersS2CPayload(ChunkPos chunkPos, List<Entry> entries)
        implements CustomPacketPayload {

    // A 16x16 chunk holds a few thousand block-entity slots at most; 4096 is a generous
    // ceiling that still bounds a malicious server's allocation on decode.
    public static final int MAX_ENTRIES = 4096;

    public static final Type<UnlootedContainersS2CPayload> TYPE =
            new Type<>(Prosperity.id("unlooted_containers_s2c"));

    public static final StreamCodec<FriendlyByteBuf, UnlootedContainersS2CPayload> CODEC =
            StreamCodec.of(UnlootedContainersS2CPayload::encode, UnlootedContainersS2CPayload::decode);

    /**
     * One unlooted container: position relative to the chunk origin plus its slot count.
     *
     * @param relX in-chunk X, {@code 0..15}
     * @param relZ in-chunk Z, {@code 0..15}
     * @param y    absolute world Y
     * @param slots container capacity (e.g. 27 single chest, 54 double chest)
     */
    public record Entry(int relX, int relZ, int y, int slots) {

        /** Build an entry from an absolute container position and its slot count. */
        public static Entry of(BlockPos pos, int slots) {
            return new Entry(pos.getX() & 15, pos.getZ() & 15, pos.getY(), slots);
        }

        /** Resolve back to an absolute world position within {@code chunkPos}. */
        public BlockPos toBlockPos(ChunkPos chunkPos) {
            return new BlockPos(chunkPos.getMinBlockX() + relX, y, chunkPos.getMinBlockZ() + relZ);
        }
    }

    private static void encode(FriendlyByteBuf buf, UnlootedContainersS2CPayload payload) {
        if (payload.entries.size() > MAX_ENTRIES) {
            throw new EncoderException(
                    "UnlootedContainersS2CPayload exceeds entry cap: " + payload.entries.size() + " > " + MAX_ENTRIES);
        }
        buf.writeVarInt(payload.chunkPos.x);
        buf.writeVarInt(payload.chunkPos.z);
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeByte(((entry.relX & 15) << 4) | (entry.relZ & 15));
            buf.writeShort(entry.y);
            buf.writeVarInt(entry.slots);
        }
    }

    private static UnlootedContainersS2CPayload decode(FriendlyByteBuf buf) {
        ChunkPos chunkPos = new ChunkPos(buf.readVarInt(), buf.readVarInt());
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("UnlootedContainersS2CPayload entry count out of bounds: " + size);
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int xz = buf.readUnsignedByte();
            int y = buf.readShort();
            int slots = buf.readVarInt();
            entries.add(new Entry((xz >> 4) & 15, xz & 15, y, slots));
        }
        return new UnlootedContainersS2CPayload(chunkPos, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
