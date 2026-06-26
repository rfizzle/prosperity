package com.rfizzle.prosperity.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.loot.index.LootIndexEntry;
import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 (fabric-loader-junit) round-trip tests for {@link LootIndexS2CPayload}. The rows carry
 * {@link ItemStack}s, so the codec rides {@link RegistryFriendlyByteBuf} and the test bootstraps the
 * vanilla registries to build one (mirrors {@code InstancedLootDataTest}). {@code ItemStack} and the
 * {@link LootIndexEntry} record use identity {@code equals}, so rows are compared field-by-field with
 * {@link ItemStack#matches} rather than {@code assertEquals} on the whole payload.
 */
class LootIndexPayloadCodecTest {

    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    }

    private LootIndexS2CPayload roundTrip(LootIndexS2CPayload original) {
        RegistryFriendlyByteBuf buf = buf();
        LootIndexS2CPayload.CODEC.encode(buf, original);
        LootIndexS2CPayload decoded = LootIndexS2CPayload.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "buffer should be fully consumed after decode");
        return decoded;
    }

    private static LootIndexEntry vanillaRow(net.minecraft.world.item.Item item, ResourceLocation table) {
        return new LootIndexEntry(new ItemStack(item), table,
                ResourceLocation.withDefaultNamespace("simple_dungeon"), Optional.empty(), Origin.VANILLA);
    }

    private static void assertRowsMatch(List<LootIndexEntry> expected, List<LootIndexEntry> actual) {
        assertEquals(expected.size(), actual.size(), "row count");
        for (int i = 0; i < expected.size(); i++) {
            LootIndexEntry e = expected.get(i);
            LootIndexEntry a = actual.get(i);
            assertTrue(ItemStack.matches(e.output(), a.output()), "output stack at row " + i);
            assertEquals(e.lootTable(), a.lootTable(), "loot table at row " + i);
            assertEquals(e.structure(), a.structure(), "structure at row " + i);
            assertEquals(e.minTier(), a.minTier(), "min tier at row " + i);
            assertEquals(e.origin(), a.origin(), "origin at row " + i);
        }
    }

    @Test
    void emptyIndexRoundTrips() {
        var original = new LootIndexS2CPayload(List.of());
        var decoded = roundTrip(original);
        assertTrue(decoded.rows().isEmpty());
    }

    @Test
    void multiRowRoundTripIsLossless() {
        ItemStack enchanted = new ItemStack(Items.DIAMOND_SWORD);
        enchanted.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Relic"));
        ItemStack stacked = new ItemStack(Items.GOLD_INGOT, 7);

        List<LootIndexEntry> rows = List.of(
                vanillaRow(Items.DIAMOND, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")),
                new LootIndexEntry(stacked, ResourceLocation.withDefaultNamespace("chests/stronghold_corridor"),
                        ResourceLocation.withDefaultNamespace("stronghold"), Optional.of("wilderness"), Origin.INJECTED),
                new LootIndexEntry(enchanted, ResourceLocation.fromNamespaceAndPath("prosperity", "chests/custom"),
                        ResourceLocation.withDefaultNamespace("other"), Optional.of("depths"), Origin.INJECTED));

        var decoded = roundTrip(new LootIndexS2CPayload(rows));
        assertRowsMatch(rows, decoded.rows());
    }

    @Test
    void ofTruncatesOversizedIndex() {
        List<LootIndexEntry> rows = new ArrayList<>(LootIndexS2CPayload.MAX_ENTRIES + 1);
        ResourceLocation table = ResourceLocation.withDefaultNamespace("chests/simple_dungeon");
        for (int i = 0; i <= LootIndexS2CPayload.MAX_ENTRIES; i++) {
            rows.add(vanillaRow(Items.DIRT, table));
        }
        var payload = LootIndexS2CPayload.of(rows);
        assertEquals(LootIndexS2CPayload.MAX_ENTRIES, payload.rows().size());
    }

    @Test
    void ofPreservesWithinCap() {
        List<LootIndexEntry> rows = List.of(
                vanillaRow(Items.DIAMOND, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
        assertEquals(1, LootIndexS2CPayload.of(rows).rows().size());
    }

    @Test
    void decodeRejectsBogusSize() {
        RegistryFriendlyByteBuf buf = buf();
        buf.writeVarInt(Integer.MAX_VALUE); // entry count — bogus
        assertThrows(DecoderException.class, () -> LootIndexS2CPayload.CODEC.decode(buf));
    }

    @Test
    void payloadReturnsCorrectType() {
        assertEquals(LootIndexS2CPayload.TYPE, new LootIndexS2CPayload(List.of()).type());
    }
}
