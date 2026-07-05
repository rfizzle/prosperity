package com.rfizzle.prosperity.loot.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.index.LootIndexEntry.Origin;
import com.rfizzle.prosperity.loot.injection.InjectedView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
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
    void generativeEntryGainsRandomEnchantmentLore() {
        InjectedView generative = new InjectedView(new ItemStack(Items.ENCHANTED_BOOK), "frontier",
                Optional.of(TagKey.create(Registries.ENCHANTMENT,
                        ResourceLocation.fromNamespaceAndPath("prosperity", "rarity/common"))));
        InjectedView literal = new InjectedView(new ItemStack(Items.EMERALD), "frontier");
        Map<ResourceLocation, List<InjectedView>> injections =
                Map.of(DUNGEON, List.of(generative, literal));

        List<LootIndexEntry> index = LootIndexDataSource.assemble(Map.of(), injections, new ProsperityConfig());

        ItemLore lore = index.get(0).output().get(DataComponents.LORE);
        assertTrue(lore != null && lore.lines().size() == 1,
                "a generative entry's display stack carries one descriptive lore line");
        Component line = lore.lines().get(0);
        assertTrue(line.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals("prosperity.loot_index.random_enchantment"),
                "the lore line is the random-enchantment translatable: got " + line);
        Object arg = ((TranslatableContents) line.getContents()).getArgs()[0];
        assertTrue(arg instanceof Component rarity
                        && rarity.getContents() instanceof TranslatableContents band
                        && band.getKey().equals("prosperity.rarity.common")
                        && "Common".equals(band.getFallback()),
                "the rarity nests as an overridable translatable with a title-cased fallback: got " + arg);

        ItemLore literalLore = index.get(1).output().get(DataComponents.LORE);
        assertTrue(literalLore == null || literalLore.lines().isEmpty(),
                "a literal entry's display stack stays as authored (no lore lines)");
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
