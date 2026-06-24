package com.rfizzle.prosperity.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCodecTest {

    private FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private <T> T roundTrip(StreamCodec<? super FriendlyByteBuf, T> codec, T original) {
        FriendlyByteBuf buf = buf();
        codec.encode(buf, original);
        T decoded = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "buffer should be fully consumed after decode");
        return decoded;
    }

    // --- ConfigSyncS2C ---

    @Test
    void configSyncS2C() {
        var original = new ConfigSyncS2CPayload("{\"enableInstancedLoot\":true,\"qualityModifier\":2}");
        assertEquals(original, roundTrip(ConfigSyncS2CPayload.CODEC, original));
    }

    @Test
    void configSyncS2CAcceptsAtBoundary() {
        String exact = "a".repeat(ConfigSyncS2CPayload.MAX_CONFIG_JSON_CHARS);
        var original = new ConfigSyncS2CPayload(exact);
        assertEquals(original, roundTrip(ConfigSyncS2CPayload.CODEC, original));
    }

    @Test
    void configSyncS2CRejectsOversizedString() {
        String oversized = "a".repeat(ConfigSyncS2CPayload.MAX_CONFIG_JSON_CHARS + 1);
        var payload = new ConfigSyncS2CPayload(oversized);
        FriendlyByteBuf buf = buf();
        assertThrows(EncoderException.class, () -> ConfigSyncS2CPayload.CODEC.encode(buf, payload));
    }

    // --- ContainerLootedS2C / ContainerRemovedS2C ---

    @Test
    void containerLootedS2C() {
        var original = new ContainerLootedS2CPayload(new BlockPos(120, -32, -4500));
        assertEquals(original, roundTrip(ContainerLootedS2CPayload.CODEC, original));
    }

    @Test
    void containerRemovedS2C() {
        var original = new ContainerRemovedS2CPayload(new BlockPos(-7000, 319, 64));
        assertEquals(original, roundTrip(ContainerRemovedS2CPayload.CODEC, original));
    }

    // --- RequestUnlootedC2S ---

    @Test
    void requestUnlootedC2S() {
        var original = new RequestUnlootedC2SPayload(new ChunkPos(312, -918));
        assertEquals(original, roundTrip(RequestUnlootedC2SPayload.CODEC, original));
    }

    // --- UnlootedContainersS2C ---

    @Test
    void unlootedContainersS2CEmpty() {
        var original = new UnlootedContainersS2CPayload(new ChunkPos(0, 0), List.of());
        var decoded = roundTrip(UnlootedContainersS2CPayload.CODEC, original);
        assertTrue(decoded.entries().isEmpty());
        assertEquals(original, decoded);
    }

    @Test
    void unlootedContainersS2CMultiEntryLossless() {
        // Cover the full packing range: every in-chunk corner, both Y extremes, single + double slots.
        var entries = List.of(
                new UnlootedContainersS2CPayload.Entry(0, 0, -64, 27),
                new UnlootedContainersS2CPayload.Entry(15, 15, 319, 54),
                new UnlootedContainersS2CPayload.Entry(7, 9, 0, 9),
                new UnlootedContainersS2CPayload.Entry(15, 0, 200, 5));
        var original = new UnlootedContainersS2CPayload(new ChunkPos(-3, 42), entries);
        assertEquals(original, roundTrip(UnlootedContainersS2CPayload.CODEC, original));
    }

    @Test
    void unlootedContainersEntryRoundTripsThroughBlockPos() {
        ChunkPos chunk = new ChunkPos(-3, 42);
        // Absolute pos inside that chunk: minBlockX = -48, minBlockZ = 672.
        BlockPos abs = new BlockPos(-48 + 11, 73, 672 + 4);
        var entry = UnlootedContainersS2CPayload.Entry.of(abs, 27);
        assertEquals(11, entry.relX());
        assertEquals(4, entry.relZ());
        assertEquals(abs, entry.toBlockPos(chunk));
    }

    @Test
    void unlootedContainersS2CRejectsOversizedOnEncode() {
        List<UnlootedContainersS2CPayload.Entry> entries =
                new ArrayList<>(UnlootedContainersS2CPayload.MAX_ENTRIES + 1);
        for (int i = 0; i <= UnlootedContainersS2CPayload.MAX_ENTRIES; i++) {
            entries.add(new UnlootedContainersS2CPayload.Entry(i & 15, (i >> 4) & 15, 64, 27));
        }
        var payload = new UnlootedContainersS2CPayload(new ChunkPos(0, 0), entries);
        FriendlyByteBuf buf = buf();
        assertThrows(EncoderException.class, () -> UnlootedContainersS2CPayload.CODEC.encode(buf, payload));
    }

    @Test
    void unlootedContainersS2CRejectsBogusSizeOnDecode() {
        FriendlyByteBuf buf = buf();
        buf.writeVarInt(0); // chunk x
        buf.writeVarInt(0); // chunk z
        buf.writeVarInt(Integer.MAX_VALUE); // entry count — bogus
        assertThrows(DecoderException.class, () -> UnlootedContainersS2CPayload.CODEC.decode(buf));
    }

    // --- Payload type identity ---

    @Test
    void allPayloadsReturnCorrectType() {
        assertEquals(ConfigSyncS2CPayload.TYPE, new ConfigSyncS2CPayload("").type());
        assertEquals(ContainerLootedS2CPayload.TYPE, new ContainerLootedS2CPayload(BlockPos.ZERO).type());
        assertEquals(ContainerRemovedS2CPayload.TYPE, new ContainerRemovedS2CPayload(BlockPos.ZERO).type());
        assertEquals(RequestUnlootedC2SPayload.TYPE, new RequestUnlootedC2SPayload(new ChunkPos(0, 0)).type());
        assertEquals(UnlootedContainersS2CPayload.TYPE,
                new UnlootedContainersS2CPayload(new ChunkPos(0, 0), List.of()).type());
    }
}
