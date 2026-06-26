package com.rfizzle.prosperity.loot.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.InjectedView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 (fabric-loader-junit) coverage of the pure loot-index assembler (S-025): vanilla vs
 * injected origin, "Any tier" (empty min-tier) vs an injected entry's gating tier, structure
 * resolution, and deterministic ordering. Bootstraps Minecraft for the item registry.
 */
class LootIndexAssemblyTest {

    private static final ResourceLocation DUNGEON = ResourceLocation.withDefaultNamespace("chests/simple_dungeon");
    private static final ResourceLocation MINESHAFT =
            ResourceLocation.withDefaultNamespace("chests/abandoned_mineshaft");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaEntriesCarryNoTierInjectedEntriesCarryTheirs() {
        Map<ResourceLocation, List<net.minecraft.world.item.Item>> items =
                Map.of(DUNGEON, List.of(Items.DIAMOND, Items.BREAD));
        Map<ResourceLocation, List<InjectedView>> injections = Map.of(DUNGEON,
                List.of(new InjectedView(new ItemStack(Items.NETHERITE_INGOT), "outlands")));

        List<LootIndexEntry> index = LootIndexDataSource.assemble(items, injections, new ProsperityConfig());

        assertEquals(3, index.size());
        // Vanilla entries come first, in extraction order, with no tier restriction.
        assertEquals(Items.DIAMOND, index.get(0).output().getItem());
        assertEquals(Origin.VANILLA, index.get(0).origin());
        assertTrue(index.get(0).minTier().isEmpty(), "vanilla entries have no min tier (Any tier)");
        assertEquals(StructureIcons.DUNGEON, index.get(0).structure());

        assertEquals(Items.BREAD, index.get(1).output().getItem());
        assertEquals(Origin.VANILLA, index.get(1).origin());

        // The injected entry follows, tagged with its gating tier.
        LootIndexEntry injected = index.get(2);
        assertEquals(Items.NETHERITE_INGOT, injected.output().getItem());
        assertEquals(Origin.INJECTED, injected.origin());
        assertEquals("outlands", injected.minTier().orElseThrow());
        assertEquals(StructureIcons.DUNGEON, injected.structure());
    }

    @Test
    void orderingIsDeterministicBySortedTableId() {
        Map<ResourceLocation, List<net.minecraft.world.item.Item>> items = new HashMap<>();
        // Insert out of sorted order; assemble must still emit mineshaft (a) before dungeon (s).
        items.put(DUNGEON, List.of(Items.DIAMOND));
        items.put(MINESHAFT, List.of(Items.IRON_INGOT));

        List<LootIndexEntry> index = LootIndexDataSource.assemble(items, Map.of(), new ProsperityConfig());

        assertEquals(2, index.size());
        assertEquals(MINESHAFT, index.get(0).lootTable());
        assertEquals(DUNGEON, index.get(1).lootTable());
    }

    @Test
    void injectionOnlyTableStillProducesAnEntry() {
        ResourceLocation injectedOnly = ResourceLocation.withDefaultNamespace("chests/buried_treasure");
        Map<ResourceLocation, List<InjectedView>> injections = Map.of(injectedOnly,
                List.of(new InjectedView(new ItemStack(Items.EMERALD), "frontier")));

        List<LootIndexEntry> index = LootIndexDataSource.assemble(Map.of(), injections, new ProsperityConfig());

        assertEquals(1, index.size());
        assertEquals(Origin.INJECTED, index.get(0).origin());
        assertEquals(StructureIcons.BURIED_TREASURE, index.get(0).structure());
        assertFalse(index.get(0).minTier().isEmpty());
    }

    @Test
    void configOverrideSetsStructure() {
        ResourceLocation modTable = ResourceLocation.fromNamespaceAndPath("examplemod", "chests/tower");
        ProsperityConfig cfg = new ProsperityConfig();
        cfg.lootTableStructures.put(modTable.toString(), "minecraft:stronghold");

        List<LootIndexEntry> index = LootIndexDataSource.assemble(
                Map.of(modTable, List.of(Items.STICK)), Map.of(), cfg);

        assertEquals(StructureIcons.STRONGHOLD, index.get(0).structure());
    }

    @Test
    void unmappedTableFallsToOther() {
        ResourceLocation modTable = ResourceLocation.fromNamespaceAndPath("examplemod", "chests/tower");

        List<LootIndexEntry> index = LootIndexDataSource.assemble(
                Map.of(modTable, List.of(Items.STICK)), Map.of(), new ProsperityConfig());

        assertEquals(LootTableStructures.OTHER, index.get(0).structure());
    }
}
