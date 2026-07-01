package com.rfizzle.prosperity.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 (fabric-loader-junit) Codec round-trip tests for {@link InstancedLootData}. Needs the
 * vanilla registries for {@code ItemStack} codec serialization, so it bootstraps Minecraft. The
 * data is round-tripped through {@link InstancedLootData#CODEC} with registry-aware NBT ops,
 * exactly as the Fabric attachment serializes it on the block entity's own NBT.
 */
class InstancedLootDataTest {

    private static RegistryOps<Tag> ops;

    private static final ResourceKey<LootTable> DUNGEON =
            ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ops = RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    private static InstancedLootData roundTrip(InstancedLootData source) {
        Tag tag = InstancedLootData.CODEC.encodeStart(ops, source).getOrThrow();
        return InstancedLootData.CODEC.parse(ops, tag).getOrThrow();
    }

    private static void assertInventoriesMatch(NonNullList<ItemStack> expected, NonNullList<ItemStack> actual) {
        assertEquals(expected.size(), actual.size(), "inventory size");
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(ItemStack.matches(expected.get(i), actual.get(i)),
                    "slot " + i + " mismatch: expected " + expected.get(i) + " got " + actual.get(i));
        }
    }

    @Test
    void roundTrip_empty() {
        InstancedLootData restored = roundTrip(new InstancedLootData());

        assertFalse(restored.isGenerated());
        assertNull(restored.getOriginalLootTable());
        assertEquals(0L, restored.getOriginalSeed());
        assertNull(restored.getRedirect());
        assertNull(restored.getTierName());
        assertNull(restored.getStructure());
    }

    @Test
    void roundTrip_singlePlayer_preservesGenerationMetadata() {
        UUID player = new UUID(7L, 11L);
        InstancedLootData source = new InstancedLootData();
        source.markGenerated(DUNGEON, 0xCAFEBABEL);
        source.setTierName("frontier");
        source.setStructure(ResourceLocation.withDefaultNamespace("village_plains"));
        source.setLastGeneratedTick(player, 12345L);

        NonNullList<ItemStack> inv = source.getOrCreateInventory(player, 27);
        inv.set(0, new ItemStack(Items.DIAMOND, 5));
        inv.set(13, new ItemStack(Items.GOLDEN_APPLE, 2));

        InstancedLootData restored = roundTrip(source);

        assertTrue(restored.isGenerated());
        assertEquals(DUNGEON, restored.getOriginalLootTable());
        assertEquals(0xCAFEBABEL, restored.getOriginalSeed());
        assertEquals("frontier", restored.getTierName());
        assertEquals(ResourceLocation.withDefaultNamespace("village_plains"), restored.getStructure());
        assertEquals(12345L, restored.getLastGeneratedTick(player));
        assertTrue(restored.hasInventory(player));
        assertInventoriesMatch(inv, restored.getInventory(player));
    }

    @Test
    void roundTrip_multiPlayer_keepsInventoriesIndependent() {
        UUID a = new UUID(1L, 1L);
        UUID b = new UUID(2L, 2L);
        InstancedLootData source = new InstancedLootData();

        NonNullList<ItemStack> invA = source.getOrCreateInventory(a, 27);
        invA.set(0, new ItemStack(Items.EMERALD, 3));
        NonNullList<ItemStack> invB = source.getOrCreateInventory(b, 27);
        invB.set(26, new ItemStack(Items.NETHERITE_INGOT, 1));

        InstancedLootData restored = roundTrip(source);

        assertInventoriesMatch(invA, restored.getInventory(a));
        assertInventoriesMatch(invB, restored.getInventory(b));
        assertTrue(restored.getInventory(a).get(26).isEmpty(), "player A slot 26 stays empty");
        assertTrue(restored.getInventory(b).get(0).isEmpty(), "player B slot 0 stays empty");
    }

    @Test
    void roundTrip_doubleChestRedirectMarker() {
        BlockPos primary = new BlockPos(-40, 63, 128);
        InstancedLootData source = new InstancedLootData();
        source.setRedirect(primary);

        InstancedLootData restored = roundTrip(source);

        assertEquals(primary, restored.getRedirect());
    }

    @Test
    void roundTrip_itemDataComponentsSurvive() {
        UUID player = new UUID(99L, 99L);
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        named.set(DataComponents.DAMAGE, 42);

        InstancedLootData source = new InstancedLootData();
        NonNullList<ItemStack> inv = source.getOrCreateInventory(player, 27);
        inv.set(4, named);

        ItemStack restored = roundTrip(source).getInventory(player).get(4);

        assertTrue(ItemStack.matches(named, restored), "data components must survive");
        assertEquals(Component.literal("Excalibur"), restored.get(DataComponents.CUSTOM_NAME));
        assertEquals(42, restored.get(DataComponents.DAMAGE));
    }

    @Test
    void encode_ordersPlayersByUuid() {
        // Insert out of UUID order; expect the serialized list sorted ascending.
        UUID high = new UUID(3L, 0L);
        UUID mid = new UUID(2L, 0L);
        UUID low = new UUID(1L, 0L);
        InstancedLootData source = new InstancedLootData();
        source.getOrCreateInventory(high, 27);
        source.getOrCreateInventory(low, 27);
        source.getOrCreateInventory(mid, 27);

        CompoundTag tag = (CompoundTag) InstancedLootData.CODEC.encodeStart(ops, source).getOrThrow();

        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            order.add(parseUuid(players.getCompound(i).getString("uuid")));
        }
        assertEquals(List.of(low, mid, high), order);
    }

    @Test
    void clearForPlayer_dropsOnlyThatPlayer() {
        UUID a = new UUID(1L, 0L);
        UUID b = new UUID(2L, 0L);
        InstancedLootData source = new InstancedLootData();
        source.getOrCreateInventory(a, 27).set(0, new ItemStack(Items.DIAMOND));
        source.setLastGeneratedTick(a, 100L);
        source.getOrCreateInventory(b, 27).set(0, new ItemStack(Items.EMERALD));

        source.clearForPlayer(a);

        InstancedLootData restored = roundTrip(source);
        assertFalse(restored.hasInventory(a));
        assertEquals(-1L, restored.getLastGeneratedTick(a));
        assertTrue(restored.hasInventory(b));
    }

    @Test
    void clearForPlayer_advancesRefreshCountAndSurvivesRoundTrip() {
        UUID player = new UUID(5L, 9L);
        InstancedLootData source = new InstancedLootData();
        source.getOrCreateInventory(player, 27).set(0, new ItemStack(Items.DIAMOND));
        assertEquals(0L, source.getRefreshCount(player), "an untouched player starts at refresh count 0");

        source.clearForPlayer(player);
        assertEquals(1L, source.getRefreshCount(player), "clearing a live instance advances the count");

        // The count outlives the cleared inventory and persists even with nothing else stored.
        InstancedLootData restored = roundTrip(source);
        assertFalse(restored.hasInventory(player));
        assertEquals(1L, restored.getRefreshCount(player), "the refresh count survives serialization");

        restored.clearForPlayer(player);
        assertEquals(1L, restored.getRefreshCount(player),
                "clearing a player with no live instance must not advance the count");
    }

    @Test
    void clearAll_advancesRefreshCountForPresentPlayers() {
        UUID a = new UUID(1L, 0L);
        UUID b = new UUID(2L, 0L);
        InstancedLootData source = new InstancedLootData();
        source.getOrCreateInventory(a, 27).set(0, new ItemStack(Items.DIAMOND));
        source.setLastGeneratedTick(b, 50L);

        source.clearAll();

        InstancedLootData restored = roundTrip(source);
        assertEquals(1L, restored.getRefreshCount(a), "a player with an inventory advances on reset");
        assertEquals(1L, restored.getRefreshCount(b), "a player with only a tick advances on reset");
        assertFalse(restored.hasInventory(a));
    }

    @Test
    void storeOrEvict_dropsEmptyInventoryButKeepsPlayerVisited() {
        UUID player = new UUID(4L, 8L);
        InstancedLootData source = new InstancedLootData();
        source.markGenerated(DUNGEON, 0x1234L);
        source.setLastGeneratedTick(player, 200L);
        NonNullList<ItemStack> loot = NonNullList.withSize(27, ItemStack.EMPTY);
        loot.set(0, new ItemStack(Items.DIAMOND, 3));
        source.setInventory(player, loot);
        assertTrue(source.hasInventory(player), "starts with a stored inventory");

        // The player loots every slot and closes the container: an all-empty inventory is written back.
        source.storeOrEvict(player, NonNullList.withSize(27, ItemStack.EMPTY));

        assertFalse(source.hasInventory(player), "an emptied inventory is evicted to bound the map");
        assertNull(source.getInventory(player));
        assertTrue(source.hasGenerated(player), "the player stays visited via lastGeneratedTick");
        assertTrue(source.playerIds().contains(player), "a looted-clean player still counts as an instance");
        assertEquals(200L, source.getLastGeneratedTick(player), "the generation tick is retained");

        // The evicted state survives serialization: still visited, still no stored items.
        InstancedLootData restored = roundTrip(source);
        assertFalse(restored.hasInventory(player));
        assertNull(restored.getInventory(player));
        assertTrue(restored.hasGenerated(player));
        assertEquals(200L, restored.getLastGeneratedTick(player));
    }

    @Test
    void storeOrEvict_keepsInventoryWithAnyItem() {
        UUID player = new UUID(6L, 6L);
        InstancedLootData source = new InstancedLootData();
        NonNullList<ItemStack> partial = NonNullList.withSize(27, ItemStack.EMPTY);
        partial.set(13, new ItemStack(Items.GOLD_INGOT, 4));

        source.storeOrEvict(player, partial);

        assertTrue(source.hasInventory(player), "a partially-looted inventory is retained");
        assertInventoriesMatch(partial, source.getInventory(player));
    }

    @Test
    void hasGenerated_countsTickOnlyPlayerAsVisited() {
        UUID player = new UUID(8L, 0L);
        InstancedLootData source = new InstancedLootData();
        assertFalse(source.hasGenerated(player), "an unseen player has not generated");

        source.setLastGeneratedTick(player, 5L);

        assertTrue(source.hasGenerated(player), "a generation tick alone marks the player visited");
        assertFalse(source.hasInventory(player), "a tick alone stores no inventory");
        assertTrue(source.playerIds().contains(player), "playerIds includes a looted-clean, tick-only player");
    }

    private static UUID parseUuid(String s) {
        return UUID.fromString(s);
    }
}
