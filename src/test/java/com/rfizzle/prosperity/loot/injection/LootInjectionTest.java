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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
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

    private static HolderLookup.Provider registries;
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
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(List.of(
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(
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
        Map<ResourceLocation, List<Tiered>> registry = InjectionRegistry.build(
                List.of(new Loaded(Prosperity.id("gated"), file)), Set.of(CHEST_A, CHEST_B),
                mod -> mod.equals("meridian"));

        assertTrue(registry.isEmpty(), "requires_mods is conjunctive: one absent mod drops the injection");
    }

    @Test
    void parsesChanceDefaultingToAlways() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "entries": [ { "item": "minecraft:diamond" } ] },
                    { "target": "minecraft:chests/b", "min_tier": "frontier", "chance": 0.25,
                      "entries": [ { "item": "minecraft:emerald" } ] }
                  ]
                }
                """);

        assertEquals(1.0f, file.injections().get(0).chance(),
                "an omitted chance field defaults to 1.0 (always inject)");
        assertEquals(0.25f, file.injections().get(1).chance(), "the chance value round-trips");
    }

    @Test
    void rejectsChanceOutOfRange() {
        for (String chance : List.of("1.5", "-0.25")) {
            JsonElement element = JsonParser.parseString("""
                    {
                      "injections": [
                        { "target": "minecraft:chests/a", "min_tier": "frontier", "chance": %s,
                          "entries": [ { "item": "minecraft:diamond" } ] }
                      ]
                    }
                    """.formatted(chance));
            assertTrue(FileData.CODEC.parse(ops, element).isError(),
                    "chance " + chance + " is outside [0.0, 1.0] and must fail to parse");
        }
    }

    @Test
    void survivingEntriesRollChancePerGroup() {
        ProsperityConfig cfg = new ProsperityConfig();
        DistanceTier frontier = tier(cfg, "frontier");
        Entry always = new Entry(new ItemStack(Items.IRON_INGOT), 1);
        Entry never = new Entry(new ItemStack(Items.NETHERITE_INGOT), 1);
        List<Tiered> list = List.of(
                new Tiered("frontier", List.of(), 1.0f, List.of(always)),
                new Tiered("frontier", List.of(), 0.0f, List.of(never)));

        for (long seed = 0; seed < 50; seed++) {
            List<Entry> surviving = InjectionSelector.survivingEntries(
                    list, frontier, OVERWORLD, cfg, RandomSource.create(seed));
            assertEquals(List.of(always), surviving,
                    "chance 1.0 always survives and chance 0.0 never does (seed " + seed + ")");
        }
    }

    @Test
    void fullChanceConsumesNoRandomness() {
        // A group at the default 1.0 must not consume a roll: files without a chance field draw
        // bit-identically to a build without the gate, so existing worlds' injections are unchanged.
        ProsperityConfig cfg = new ProsperityConfig();
        DistanceTier frontier = tier(cfg, "frontier");
        List<Tiered> ungated = List.of(
                new Tiered("frontier", List.of(), List.of(new Entry(new ItemStack(Items.IRON_INGOT), 1))),
                new Tiered("frontier", List.of(), List.of(new Entry(new ItemStack(Items.GOLD_INGOT), 1))));

        RandomSource random = RandomSource.create(4242L);
        InjectionSelector.survivingEntries(ungated, frontier, OVERWORLD, cfg, random);
        assertEquals(RandomSource.create(4242L).nextLong(), random.nextLong(),
                "surviving full-chance groups must leave the random stream untouched");
    }

    @Test
    void fractionalChanceIsDeterministicAndProportional() {
        ProsperityConfig cfg = new ProsperityConfig();
        DistanceTier frontier = tier(cfg, "frontier");
        List<Tiered> gated = List.of(new Tiered("frontier", List.of(), 0.25f,
                List.of(new Entry(new ItemStack(Items.EMERALD), 1))));

        int survived = 0;
        for (long raw = 0; raw < 1000; raw++) {
            // Mix like augment() does before seeding: LegacyRandomSource's first nextFloat over
            // small sequential raw seeds is confined to a narrow band (the low bits never reach
            // the output's top state bits), which would starve the survival count.
            long seed = raw * 0x9E3779B97F4A7C15L;
            List<Entry> first = InjectionSelector.survivingEntries(
                    gated, frontier, OVERWORLD, cfg, RandomSource.create(seed));
            List<Entry> replay = InjectionSelector.survivingEntries(
                    gated, frontier, OVERWORLD, cfg, RandomSource.create(seed));
            assertEquals(first, replay, "the gate roll must be deterministic per seed");
            if (!first.isEmpty()) {
                survived++;
            }
        }
        assertTrue(survived > 150 && survived < 350,
                "a 0.25 chance survives roughly a quarter of generations: got " + survived + "/1000");
    }

    @Test
    void gateRollsConsumeTheStreamInGroupOrder() {
        // Golden test pinning the stream layout survivingEntries promises: one nextFloat per gated
        // group, consumed in list order. The mixed seed's first two floats are ~0.3317 then ~0.6079,
        // so against two 0.5-chance groups exactly the group holding the FIRST roll survives —
        // swapping the group order must swap the survivor. A refactor that reordered or batched the
        // gate rolls would change every gated world's loot; this pins the layout, not just P(survive).
        ProsperityConfig cfg = new ProsperityConfig();
        DistanceTier frontier = tier(cfg, "frontier");
        long seed = 112L * 0x9E3779B97F4A7C15L;
        Entry a = new Entry(new ItemStack(Items.APPLE), 1);
        Entry b = new Entry(new ItemStack(Items.BREAD), 1);
        Tiered groupA = new Tiered("frontier", List.of(), 0.5f, List.of(a));
        Tiered groupB = new Tiered("frontier", List.of(), 0.5f, List.of(b));

        assertEquals(List.of(a), InjectionSelector.survivingEntries(
                        List.of(groupA, groupB), frontier, OVERWORLD, cfg, RandomSource.create(seed)),
                "the first-listed group takes the first (passing) roll");
        assertEquals(List.of(b), InjectionSelector.survivingEntries(
                        List.of(groupB, groupA), frontier, OVERWORLD, cfg, RandomSource.create(seed)),
                "swapping the group order must hand the passing roll to the other group");
    }

    @Test
    void eligibleEntriesIgnoreChance() {
        ProsperityConfig cfg = new ProsperityConfig();
        List<Tiered> gated = List.of(new Tiered("frontier", List.of(), 0.0f,
                List.of(new Entry(new ItemStack(Items.EMERALD), 1))));

        assertEquals(1, InjectionSelector.eligibleEntries(gated, tier(cfg, "frontier"), OVERWORLD, cfg).size(),
                "the chance-free eligibility view (completion bonus, loot index) must ignore the gate");
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
        List<Entry> atWilderness = InjectionSelector.eligibleEntries(list, wilderness, OVERWORLD, cfg);
        assertEquals(List.of(frontierItem), atWilderness, "wilderness clears frontier but not depths");

        List<Entry> atDepths = InjectionSelector.eligibleEntries(list, tier(cfg, "depths"), OVERWORLD, cfg);
        assertEquals(2, atDepths.size(), "depths clears every lower-tier injection");

        List<Entry> atLocal =
                InjectionSelector.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, OVERWORLD, cfg);
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
        List<Entry> inOverworld = InjectionSelector.eligibleEntries(list, wilderness, OVERWORLD, cfg);
        assertEquals(List.of(anywhere), inOverworld,
                "an empty dimension list matches any dimension; a Nether-scoped one does not match the Overworld");

        List<Entry> inNether = InjectionSelector.eligibleEntries(list, wilderness, NETHER, cfg);
        assertEquals(2, inNether.size(), "in the Nether both the any-dimension and Nether-scoped groups apply");

        // The dimension gate composes with the tier gate: below min_tier, neither group fires in the Nether.
        List<Entry> belowTier =
                InjectionSelector.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, NETHER, cfg);
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

        ItemStack first = InjectionSelector.draw(eligible, RandomSource.create(99L), registries);
        ItemStack second = InjectionSelector.draw(eligible, RandomSource.create(99L), registries);
        assertTrue(ItemStack.isSameItem(first, second), "the same seed must draw the same item");

        assertNull(InjectionSelector.draw(List.of(), RandomSource.create(1L), registries),
                "empty pool draws nothing");

        ItemStack solo = InjectionSelector.draw(List.of(a), RandomSource.create(7L), registries);
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
            if (InjectionSelector.draw(eligible, random, registries).getItem() == Items.APPLE) {
                apples++;
            }
        }
        assertTrue(apples > 800, "the 9:1-weighted entry must dominate the draw: got " + apples + "/1000");
    }

    @Test
    void parsesGenerativeEntry() {
        FileData file = parse("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "outlands",
                      "entries": [
                        { "item": "minecraft:enchanted_book",
                          "enchant_randomly": "#meridian:rarity/rare", "level": "mid", "weight": 3 },
                        { "item": "minecraft:enchanted_book",
                          "enchant_randomly": "minecraft:treasure" }
                      ] }
                  ]
                }
                """);

        Entry hashed = file.injections().get(0).entries().get(0);
        assertTrue(hashed.enchantRandomly().isPresent());
        assertEquals(ResourceLocation.parse("meridian:rarity/rare"),
                hashed.enchantRandomly().get().location(), "the # prefix is accepted and stripped");
        assertEquals(LevelPolicy.MID, hashed.level());
        assertEquals(3, hashed.weight());

        Entry bare = file.injections().get(0).entries().get(1);
        assertEquals(ResourceLocation.withDefaultNamespace("treasure"),
                bare.enchantRandomly().orElseThrow().location(), "a bare tag id is also accepted");
        assertEquals(LevelPolicy.UNIFORM, bare.level(), "level defaults to uniform");
    }

    @Test
    void literalEntryCarriesNoGenerativeFields() {
        FileData file = parse("""
                { "injections": [ { "target": "minecraft:chests/a", "min_tier": "frontier",
                  "entries": [ { "item": "minecraft:diamond" } ] } ] }
                """);
        Entry literal = file.injections().get(0).entries().get(0);
        assertTrue(literal.enchantRandomly().isEmpty(),
                "a literal entry parses with no enchant_randomly tag");
    }

    @Test
    void rejectsComponentsMixedWithEnchantRandomly() {
        JsonElement element = JsonParser.parseString("""
                {
                  "injections": [
                    { "target": "minecraft:chests/a", "min_tier": "frontier",
                      "entries": [
                        { "item": "minecraft:enchanted_book",
                          "components": { "minecraft:damage": 1 },
                          "enchant_randomly": "#minecraft:treasure" }
                      ] }
                  ]
                }
                """);
        assertTrue(FileData.CODEC.parse(ops, element).isError(),
                "components and enchant_randomly are mutually exclusive");
    }

    @Test
    void policyLevelBandsAgainstTheEnchantmentRange() {
        RandomSource random = RandomSource.create(1L);
        assertEquals(3, InjectionSelector.policyLevel(LevelPolicy.MID, 1, 5, random),
                "mid is the rounded-up midpoint");
        assertEquals(2, InjectionSelector.policyLevel(LevelPolicy.MID, 1, 4, random));
        assertEquals(1, InjectionSelector.policyLevel(LevelPolicy.MID, 1, 1, random),
                "a single-level enchantment stays at 1");
        assertEquals(5, InjectionSelector.policyLevel(LevelPolicy.MAX, 1, 5, random));

        for (int i = 0; i < 100; i++) {
            int level = InjectionSelector.policyLevel(LevelPolicy.UNIFORM, 1, 3, random);
            assertTrue(level >= 1 && level <= 3, "uniform draws inside [min, max]: got " + level);
        }
    }

    @Test
    void unresolvableTagFallsThroughToSiblingEntries() {
        // The unit-test registry access carries no enchantment registry, so every tag is unresolvable —
        // exactly the mod-absent case: the generative entry must drop from the pool before weighting.
        Entry generative = new Entry(new ItemStack(Items.ENCHANTED_BOOK),
                Optional.of(TagKey.create(Registries.ENCHANTMENT,
                        ResourceLocation.parse("meridian:rarity/rare"))),
                LevelPolicy.MID, 1000);
        Entry literal = new Entry(new ItemStack(Items.BREAD), 1);

        ItemStack drawn = InjectionSelector.draw(List.of(generative, literal),
                RandomSource.create(42L), registries);
        assertEquals(Items.BREAD, drawn.getItem(),
                "an unresolvable tag must not consume the draw; the literal sibling wins the slot");

        assertNull(InjectionSelector.draw(List.of(generative), RandomSource.create(42L), registries),
                "a pool of only unresolvable generative entries draws nothing");
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
