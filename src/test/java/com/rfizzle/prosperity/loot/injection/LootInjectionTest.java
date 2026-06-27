package com.rfizzle.prosperity.loot.injection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Entry;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.FileData;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Loaded;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.RawInjection;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Tiered;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 (fabric-loader-junit) tests for loot injection (S-014): the datapack codec (item/count/
 * components/weight, defaults), wildcard + {@code replace} registry assembly, distance-tier gating,
 * and the weighted draw. Bootstraps Minecraft for the item registry and {@code ItemStack} codecs.
 */
class LootInjectionTest {

    private static RegistryOps<JsonElement> ops;

    /** Stand-in for {@code FabricLoader::isModLoaded} that treats every mod as present. */
    private static final Predicate<String> ALL_LOADED = mod -> true;

    private static final ResourceLocation ALL_CHESTS = Prosperity.id("all_chests");
    private static final ResourceLocation CHEST_A =
            ResourceLocation.withDefaultNamespace("chests/a");
    private static final ResourceLocation CHEST_B =
            ResourceLocation.withDefaultNamespace("chests/b");
    private static final ResourceLocation OVERWORLD =
            ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER =
            ResourceLocation.withDefaultNamespace("the_nether");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ops = RegistryOps.create(JsonOps.INSTANCE, registries);
    }

    private static FileData parse(String json) {
        JsonElement element = JsonParser.parseString(json);
        return FileData.CODEC.parse(ops, element).getOrThrow();
    }

    @Test
    void parsesCountWeightDefaultsAndComponents() {
        FileData file = parse("""
                {
                  "injections": [
                    {
                      "target": "minecraft:chests/simple_dungeon",
                      "min_tier": "frontier",
                      "entries": [
                        { "item": "minecraft:diamond" },
                        { "item": "minecraft:diamond_pickaxe", "components": { "minecraft:damage": 100 }, "weight": 7 }
                      ]
                    }
                  ]
                }
                """);

        assertFalse(file.replace(), "replace defaults to false");
        assertEquals(1, file.injections().size());
        RawInjection injection = file.injections().get(0);
        assertEquals("frontier", injection.minTier());
        assertEquals(ResourceLocation.withDefaultNamespace("chests/simple_dungeon"), injection.target());

        Entry plain = injection.entries().get(0);
        assertEquals(Items.DIAMOND, plain.stack().getItem());
        assertEquals(1, plain.stack().getCount(), "count defaults to 1");
        assertEquals(1, plain.weight(), "weight defaults to 1");

        Entry detailed = injection.entries().get(1);
        assertEquals(Items.DIAMOND_PICKAXE, detailed.stack().getItem());
        assertEquals(7, detailed.weight());
        assertEquals(100, detailed.stack().get(DataComponents.DAMAGE),
                "the damage component must be applied to the prototype stack");
    }

    @Test
    void wildcardExpandsToEveryChestTable() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "prosperity:all_chests", "min_tier": "frontier",
                      "entries": [ { "item": "minecraft:emerald" } ] }
                  ]
                }
                """);
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(
                List.of(new Loaded(Prosperity.id("wild"), file)), Set.of(CHEST_A, CHEST_B), ALL_LOADED);

        assertEquals(Set.of(CHEST_A, CHEST_B), registry.keySet(),
                "all_chests must fan out to every scanned chest table");
        assertFalse(registry.containsKey(ALL_CHESTS), "the wildcard token itself is not a target");
    }

    @Test
    void replaceClearsPriorInjectionsForTouchedTargetsOnly() {
        FileData first = parse("""
                { "injections": [ { "target": "minecraft:chests/a", "min_tier": "frontier",
                  "entries": [ { "item": "minecraft:dirt" } ] } ] }
                """);
        FileData second = parse("""
                { "replace": true, "injections": [ { "target": "minecraft:chests/a", "min_tier": "depths",
                  "entries": [ { "item": "minecraft:diamond" } ] } ] }
                """);
        FileData untouched = parse("""
                { "injections": [ { "target": "minecraft:chests/b", "min_tier": "frontier",
                  "entries": [ { "item": "minecraft:gold_ingot" } ] } ] }
                """);

        // Sorted by id: a_first < b_other < c_replace, so the replace file lands last.
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(List.of(
                new Loaded(Prosperity.id("a_first"), first),
                new Loaded(Prosperity.id("c_replace"), second),
                new Loaded(Prosperity.id("b_other"), untouched)),
                Set.of(CHEST_A, CHEST_B), ALL_LOADED);

        List<Tiered> a = registry.get(CHEST_A);
        assertEquals(1, a.size(), "replace must drop the earlier injection for chests/a");
        assertEquals("depths", a.get(0).minTier());
        assertEquals(Items.DIAMOND, a.get(0).entries().get(0).stack().getItem());

        assertEquals(1, registry.get(CHEST_B).size(), "an untouched target is unaffected by replace");
        assertEquals(Items.GOLD_INGOT, registry.get(CHEST_B).get(0).entries().get(0).stack().getItem());
    }

    @Test
    void requiresModsParsesDefaultingToEmpty() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "entries": [ { "item": "minecraft:diamond" } ] },
                    { "target": "minecraft:chests/b", "min_tier": "frontier",
                      "requires_mods": [ "meridian", "concord" ],
                      "entries": [ { "item": "minecraft:emerald" } ] }
                  ]
                }
                """);

        assertTrue(file.injections().get(0).requiresMods().isEmpty(),
                "an omitted requires_mods field defaults to empty (unconditional)");
        assertEquals(List.of("meridian", "concord"), file.injections().get(1).requiresMods(),
                "the requires_mods list round-trips in order");
    }

    @Test
    void buildDropsInjectionWhenRequiredModAbsent() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "requires_mods": [ "meridian" ],
                      "entries": [ { "item": "minecraft:diamond" } ] }
                  ]
                }
                """);
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(
                List.of(new Loaded(Prosperity.id("gated"), file)), Set.of(CHEST_A, CHEST_B),
                mod -> false);

        assertTrue(registry.isEmpty(), "an injection gated on an absent mod must be dropped at build");
    }

    @Test
    void buildRetainsInjectionWhenRequiredModsPresent() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "requires_mods": [ "meridian" ],
                      "entries": [ { "item": "minecraft:diamond" } ] }
                  ]
                }
                """);
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(
                List.of(new Loaded(Prosperity.id("gated"), file)), Set.of(CHEST_A, CHEST_B),
                mod -> mod.equals("meridian"));

        assertEquals(Set.of(CHEST_A), registry.keySet(),
                "an injection whose required mods are all present is retained");
        assertEquals(Items.DIAMOND, registry.get(CHEST_A).get(0).entries().get(0).stack().getItem());
    }

    @Test
    void buildGatesPerInjectionWithinOneFile() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "entries": [ { "item": "minecraft:diamond" } ] },
                    { "target": "minecraft:chests/b", "min_tier": "frontier",
                      "requires_mods": [ "meridian" ],
                      "entries": [ { "item": "minecraft:emerald" } ] }
                  ]
                }
                """);
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(
                List.of(new Loaded(Prosperity.id("mixed"), file)), Set.of(CHEST_A, CHEST_B),
                mod -> false);

        assertEquals(Set.of(CHEST_A), registry.keySet(),
                "the unconditional injection survives while the mod-gated sibling in the same file is dropped");
        assertEquals(Items.DIAMOND, registry.get(CHEST_A).get(0).entries().get(0).stack().getItem());
    }

    @Test
    void buildDropsInjectionWhenAnyRequiredModAbsent() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "requires_mods": [ "meridian", "concord" ],
                      "entries": [ { "item": "minecraft:diamond" } ] }
                  ]
                }
                """);
        Map<ResourceLocation, List<Tiered>> registry = LootInjectionManager.build(
                List.of(new Loaded(Prosperity.id("gated"), file)), Set.of(CHEST_A, CHEST_B),
                mod -> mod.equals("meridian"));

        assertTrue(registry.isEmpty(), "requires_mods is conjunctive: one absent mod drops the injection");
    }

    @Test
    void eligibleEntriesGateByMinTier() {
        ProsperityConfig cfg = new ProsperityConfig();
        Entry frontierItem = new Entry(new ItemStack(Items.IRON_INGOT), 1);
        Entry depthsItem = new Entry(new ItemStack(Items.NETHERITE_INGOT), 1);
        List<Tiered> list = List.of(
                new Tiered("frontier", List.of(), List.of(frontierItem)),
                new Tiered("depths", List.of(), List.of(depthsItem)));

        DistanceTier wilderness = tier(cfg, "wilderness");
        List<Entry> atWilderness = LootInjectionManager.eligibleEntries(list, wilderness, OVERWORLD, cfg);
        assertEquals(List.of(frontierItem), atWilderness, "wilderness clears frontier but not depths");

        List<Entry> atDepths = LootInjectionManager.eligibleEntries(list, tier(cfg, "depths"), OVERWORLD, cfg);
        assertEquals(2, atDepths.size(), "depths clears every lower-tier injection");

        List<Entry> atLocal =
                LootInjectionManager.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, OVERWORLD, cfg);
        assertTrue(atLocal.isEmpty(), "local is below every default injection tier");
    }

    @Test
    void eligibleEntriesGateByDimension() {
        ProsperityConfig cfg = new ProsperityConfig();
        Entry anywhere = new Entry(new ItemStack(Items.IRON_INGOT), 1);
        Entry netherOnly = new Entry(new ItemStack(Items.NETHERITE_INGOT), 1);
        List<Tiered> list = List.of(
                new Tiered("frontier", List.of(), List.of(anywhere)),
                new Tiered("frontier", List.of(NETHER), List.of(netherOnly)));

        DistanceTier wilderness = tier(cfg, "wilderness");
        // Both clear the tier; the Nether-scoped group fires only in the Nether.
        List<Entry> inOverworld = LootInjectionManager.eligibleEntries(list, wilderness, OVERWORLD, cfg);
        assertEquals(List.of(anywhere), inOverworld,
                "an empty dimension list matches any dimension; a Nether-scoped one does not match the Overworld");

        List<Entry> inNether = LootInjectionManager.eligibleEntries(list, wilderness, NETHER, cfg);
        assertEquals(2, inNether.size(), "in the Nether both the any-dimension and Nether-scoped groups apply");

        // The dimension gate composes with the tier gate: below min_tier, neither group fires in the Nether.
        List<Entry> belowTier =
                LootInjectionManager.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, NETHER, cfg);
        assertTrue(belowTier.isEmpty(), "the dimension match cannot override the tier gate");
    }

    @Test
    void parsesDimensionsDefaultingToEmpty() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "entries": [ { "item": "minecraft:diamond" } ] },
                    { "target": "minecraft:chests/b", "min_tier": "frontier",
                      "dimensions": [ "minecraft:the_nether", "minecraft:the_end" ],
                      "entries": [ { "item": "minecraft:emerald" } ] }
                  ]
                }
                """);

        RawInjection noDimensions = file.injections().get(0);
        assertTrue(noDimensions.dimensions().isEmpty(),
                "an omitted dimensions field defaults to empty (matches any dimension)");

        RawInjection scoped = file.injections().get(1);
        assertEquals(List.of(NETHER, ResourceLocation.withDefaultNamespace("the_end")), scoped.dimensions(),
                "the dimensions list round-trips in order");
    }

    @Test
    void drawIsDeterministicForASeed() {
        Entry a = new Entry(new ItemStack(Items.APPLE), 1);
        Entry b = new Entry(new ItemStack(Items.BREAD), 1);
        List<Entry> eligible = List.of(a, b);

        ItemStack first = LootInjectionManager.draw(eligible, RandomSource.create(99L));
        ItemStack second = LootInjectionManager.draw(eligible, RandomSource.create(99L));
        assertTrue(ItemStack.isSameItem(first, second), "the same seed must draw the same item");

        assertNull(LootInjectionManager.draw(List.of(), RandomSource.create(1L)), "empty pool draws nothing");

        ItemStack solo = LootInjectionManager.draw(List.of(a), RandomSource.create(7L));
        assertEquals(Items.APPLE, solo.getItem(), "a single-entry pool always yields that entry");
    }

    @Test
    void drawRespectsWeight() {
        Entry heavy = new Entry(new ItemStack(Items.APPLE), 9);
        Entry light = new Entry(new ItemStack(Items.BREAD), 1);
        List<Entry> eligible = List.of(heavy, light);

        RandomSource random = RandomSource.create(12345L);
        int apples = 0;
        for (int i = 0; i < 1000; i++) {
            if (LootInjectionManager.draw(eligible, random).getItem() == Items.APPLE) {
                apples++;
            }
        }
        assertTrue(apples > 800, "the 9:1-weighted entry must dominate the draw: got " + apples + "/1000");
    }

    private static DistanceTier tier(ProsperityConfig cfg, String name) {
        for (DistanceTier candidate : cfg.distanceTiers) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
