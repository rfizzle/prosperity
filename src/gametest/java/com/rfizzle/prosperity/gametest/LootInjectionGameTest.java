package com.rfizzle.prosperity.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.Entry;
import com.rfizzle.prosperity.loot.injection.InjectionRegistry;
import com.rfizzle.prosperity.loot.injection.InjectionSelector;
import com.rfizzle.prosperity.loot.injection.LevelPolicy;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.loot.injection.Tiered;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for loot injection (S-014) after a real data-pack reload: the built-in defaults load,
 * the {@code prosperity:all_chests} wildcard expands against the loaded loot tables, {@code augment}
 * adds at most one tier-eligible reward at or above {@code min_tier} and nothing below it, the shipped
 * per-group {@code chance} gate (issue #68) holds the Frontier bonus rate near its tuned 1-in-20 and
 * stays deterministic per seed, the shipped {@code prosperity:rarity/*} tags resolve treasure-free
 * against the real enchantment registry, the completion bonus bypasses the chance gate, and
 * dimension-gated entries (S-039) resolve against the level's real {@code dimension()} id.
 *
 * <p>Asserts against {@link LootInjectionManager#augment} directly with fixed seeds/UUIDs rather than
 * opening a container, so every outcome — including the gate-rate scan — is deterministic.
 */
public class LootInjectionGameTest implements FabricGameTest {

    private static final String BATCH = "loot_injection";
    private static final ResourceKey<LootTable> SIMPLE_DUNGEON = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000014d4");
    private static final long SEED = 8814L;
    /** The Nether dimension id, used to assert dimension-gated injection eligibility. */
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");

    /** The wildcard expanded at load: a vanilla chest table is a registered injection target. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void wildcardExpandsToVanillaChestTables(GameTestHelper helper) {
        helper.assertTrue(InjectionRegistry.targets().contains(SIMPLE_DUNGEON.location()),
                "all_chests must expand to minecraft:chests/simple_dungeon after reload");
        helper.succeed();
    }

    /**
     * The shipped Frontier defaults are chance-gated (issue #68): across a fixed seed range the
     * placement rate lands near the tuned ~1-in-20 (frontier group 0.04 + compass group 0.01), never
     * more than one reward lands per generation, and a placing seed reproduces the identical item —
     * the whether and the which of the bonus are both deterministic per (seed, salt, player).
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void frontierInjectionIsChanceGatedAndDeterministic(GameTestHelper helper) {
        int samples = 2000;
        int placed = 0;
        long placingSeed = -1L;
        for (long seed = 0; seed < samples; seed++) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            if (LootInjectionManager.augment(items, SIMPLE_DUNGEON, tier("frontier"),
                    helper.getLevel(), seed, 0L, PLAYER)) {
                placed++;
                placingSeed = seed;
                helper.assertTrue(filledSlots(items) == 1,
                        "a passing generation places exactly one reward: got " + filledSlots(items));
            }
        }
        // ~5% expected; the wide band guards the gate's existence and order of magnitude, not the
        // exact tuning, so a datapack-level retune does not silently break the test.
        helper.assertTrue(placed >= samples / 100 && placed <= samples / 8,
                "the Frontier bonus rate must sit near 1-in-20: " + placed + "/" + samples);

        NonNullList<ItemStack> first = NonNullList.withSize(27, ItemStack.EMPTY);
        NonNullList<ItemStack> replay = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(first, SIMPLE_DUNGEON, tier("frontier"),
                helper.getLevel(), placingSeed, 0L, PLAYER);
        LootInjectionManager.augment(replay, SIMPLE_DUNGEON, tier("frontier"),
                helper.getLevel(), placingSeed, 0L, PLAYER);
        for (int slot = 0; slot < first.size(); slot++) {
            helper.assertTrue(ItemStack.matches(first.get(slot), replay.get(slot)),
                    "the same seed must reproduce the identical gated injection");
        }
        helper.succeed();
    }

    /** Below the lowest injection tier (Local), nothing is injected. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void injectsNothingAtLocal(GameTestHelper helper) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, ProsperityConfig.LOCAL_SENTINEL, helper.getLevel(), SEED, 0L, PLAYER);

        helper.assertTrue(filledSlots(items) == 0,
                "a Local container is below every default injection tier: got " + filledSlots(items));
        helper.succeed();
    }

    /**
     * A Nether-scoped injection does not fire in the Overworld gametest level but does in the Nether,
     * while staying tier-gated — checked against the level's real {@code dimension()} id, so the comparison
     * matches the runtime dimension key the generation call sites pass (rather than a hand-written id as in
     * the unit test). An unscoped group at the same tier fires in both dimensions.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void dimensionScopedInjectionSkipsOverworld(GameTestHelper helper) {
        ResourceLocation overworld = helper.getLevel().dimension().location();
        ProsperityConfig cfg = Prosperity.getConfig();
        DistanceTier frontier = tier("frontier");
        Entry anywhere = new Entry(new ItemStack(Items.DIAMOND), 1);
        Entry netherOnly = new Entry(new ItemStack(Items.NETHER_STAR), 1);
        List<Tiered> list = List.of(
                new Tiered("frontier", List.of(), List.of(anywhere)),
                new Tiered("frontier", List.of(NETHER), List.of(netherOnly)));

        helper.assertTrue(
                InjectionSelector.eligibleEntries(list, frontier, overworld, cfg).equals(List.of(anywhere)),
                "a Nether-scoped injection must not fire in the Overworld; the unscoped one does");
        helper.assertTrue(
                InjectionSelector.eligibleEntries(list, frontier, NETHER, cfg).size() == 2,
                "in the Nether both the unscoped and Nether-scoped groups apply");
        helper.assertTrue(
                InjectionSelector.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, NETHER, cfg).isEmpty(),
                "the dimension match cannot override the tier gate");
        helper.succeed();
    }

    /**
     * The file-level {@code fabric:load_conditions} gate the loader applies in {@code reload()} is
     * honored by the Fabric runtime: a header naming a loaded mod ({@code prosperity}) passes so the file
     * loads, while one naming an absent mod is rejected so the file is silently skipped. Exercises the
     * same public {@link ResourceCondition} parse-and-test the manager runs in {@code conditionsMet},
     * against the runtime-registered {@code fabric:all_mods_loaded} condition type — which only exists
     * once Fabric is loaded, so this cannot be a unit test.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void fileLevelLoadConditionsAreHonored(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        helper.assertTrue(conditionMet(allModsLoaded("prosperity"), registries),
                "a fabric:all_mods_loaded gate naming the loaded prosperity mod must pass");
        helper.assertTrue(!conditionMet(allModsLoaded("prosperity_absent_sibling"), registries),
                "a fabric:all_mods_loaded gate naming an absent mod must fail, skipping the file");
        helper.succeed();
    }

    /**
     * A generative entry against a real enchantment registry: the draw yields an enchanted book with
     * exactly one enchantment from the named vanilla tag, at the {@code mid} policy level of that
     * enchantment's own range, and the same seed reproduces the identical book (S-014 determinism,
     * issue #59).
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void generativeEntryDrawsOneTaggedEnchantment(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Entry generative = new Entry(new ItemStack(Items.ENCHANTED_BOOK),
                Optional.of(TagKey.create(Registries.ENCHANTMENT,
                        ResourceLocation.withDefaultNamespace("treasure"))),
                LevelPolicy.MID, 1);

        ItemStack drawn = InjectionSelector.draw(List.of(generative), RandomSource.create(SEED), registries);
        helper.assertTrue(drawn != null && drawn.is(Items.ENCHANTED_BOOK),
                "a generative entry must realize to an enchanted book");
        ItemEnchantments stored = drawn.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        helper.assertTrue(stored.size() == 1,
                "exactly one enchantment is drawn from the tag: got " + stored.size());
        Holder<Enchantment> enchantment = stored.keySet().iterator().next();
        helper.assertTrue(enchantment.is(EnchantmentTags.TREASURE),
                "the drawn enchantment must come from the named tag: got " + enchantment);
        int expected = InjectionSelector.policyLevel(LevelPolicy.MID,
                enchantment.value().getMinLevel(), enchantment.value().getMaxLevel(), RandomSource.create(0L));
        helper.assertTrue(stored.getLevel(enchantment) == expected,
                "the mid policy stores ceil(max/2): expected " + expected + ", got " + stored.getLevel(enchantment));

        ItemStack replay = InjectionSelector.draw(List.of(generative), RandomSource.create(SEED), registries);
        helper.assertTrue(ItemStack.matches(drawn, replay), "the same seed must reproduce the identical book");
        helper.succeed();
    }

    /**
     * A generative entry whose tag resolves empty (an unregistered tag — the mod-absent case) drops
     * from the pool before weighting: a literal sibling wins the slot rather than the draw being
     * wasted, and a pool of only unresolvable entries injects nothing.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void emptyTagDoesNotConsumeTheDraw(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Entry unresolvable = new Entry(new ItemStack(Items.ENCHANTED_BOOK),
                Optional.of(TagKey.create(Registries.ENCHANTMENT,
                        Prosperity.id("gametest/does_not_exist"))),
                LevelPolicy.MID, 1000);
        Entry literal = new Entry(new ItemStack(Items.BREAD), 1);

        ItemStack drawn = InjectionSelector.draw(List.of(unresolvable, literal),
                RandomSource.create(SEED), registries);
        helper.assertTrue(drawn != null && drawn.is(Items.BREAD),
                "the literal sibling must win the slot when the tag resolves empty");
        helper.assertTrue(
                InjectionSelector.draw(List.of(unresolvable), RandomSource.create(SEED), registries) == null,
                "a pool of only unresolvable generative entries injects nothing");
        helper.succeed();
    }

    /**
     * The shipped {@code prosperity:rarity/*} tags (issue #68) resolve non-empty against the real
     * enchantment registry, the four rarity bands carry no treasure and no curse enchantments — a
     * treasure enchant is only reachable through the Depths-gated {@code rarity/treasure} draw — and
     * the treasure tag itself is all vanilla-treasure yet curse-free.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void shippedRarityTagsResolveTreasureFree(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        for (String band : List.of("common", "uncommon", "rare", "very_rare")) {
            HolderSet.Named<Enchantment> holders = rarityTag(registries, band);
            helper.assertTrue(holders != null && holders.size() > 0,
                    "prosperity:rarity/" + band + " must resolve non-empty after reload");
            for (Holder<Enchantment> enchantment : holders) {
                helper.assertTrue(!enchantment.is(EnchantmentTags.TREASURE),
                        band + " must not carry the treasure enchantment " + enchantment);
                helper.assertTrue(!enchantment.is(EnchantmentTags.CURSE),
                        band + " must not carry the curse " + enchantment);
            }
        }
        HolderSet.Named<Enchantment> treasure = rarityTag(registries, "treasure");
        helper.assertTrue(treasure != null && treasure.size() > 0,
                "prosperity:rarity/treasure must resolve non-empty after reload");
        for (Holder<Enchantment> enchantment : treasure) {
            helper.assertTrue(enchantment.is(EnchantmentTags.TREASURE),
                    "rarity/treasure holds only vanilla-treasure enchantments: got " + enchantment);
            helper.assertTrue(!enchantment.is(EnchantmentTags.CURSE),
                    "rarity/treasure must stay curse-free: got " + enchantment);
        }
        helper.succeed();
    }

    /**
     * A generative draw over the shipped {@code rarity/common} tag — the rewritten Frontier book
     * entry — yields books whose single stored enchantment is in the tag, at levels that vary across
     * seeds under the {@code uniform} policy: repeated Frontier chests no longer converge on a fixed
     * Sharpness 3 / Protection 3 (issue #68 acceptance).
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void frontierBookDrawVariesEnchantmentAndLevel(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Entry frontierBook = new Entry(new ItemStack(Items.ENCHANTED_BOOK),
                Optional.of(TagKey.create(Registries.ENCHANTMENT, Prosperity.id("rarity/common"))),
                LevelPolicy.UNIFORM, 1);

        Set<Integer> levels = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            ItemStack drawn = InjectionSelector.draw(List.of(frontierBook),
                    RandomSource.create(seed), registries);
            helper.assertTrue(drawn != null && drawn.is(Items.ENCHANTED_BOOK),
                    "the shipped common tag must realize to an enchanted book");
            ItemEnchantments stored =
                    drawn.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            helper.assertTrue(stored.size() == 1,
                    "exactly one enchantment is drawn: got " + stored.size());
            Holder<Enchantment> enchantment = stored.keySet().iterator().next();
            helper.assertTrue(rarityTag(registries, "common").contains(enchantment),
                    "the drawn enchantment must come from rarity/common: got " + enchantment);
            levels.add(stored.getLevel(enchantment));
        }
        // Every rarity/common member spans levels 1–4+; 40 uniform draws over fixed seeds cannot
        // all land on one level unless the policy stopped varying.
        helper.assertTrue(levels.size() > 1,
                "uniform draws across seeds must vary the stored level: got only " + levels);
        helper.succeed();
    }

    /**
     * The structure-completion bonus draw bypasses the per-group chance gate (issue #68): at Frontier,
     * where every shipped group is gated to a few percent, the bonus still pays out for every
     * structure seed — the completion is already earned, so the pool is drawn ungated.
     */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void completionBonusBypassesChanceGate(GameTestHelper helper) {
        for (long structureSeed = 0; structureSeed < 20; structureSeed++) {
            ItemStack bonus = LootInjectionManager.completionBonus(SIMPLE_DUNGEON, tier("frontier"),
                    helper.getLevel(), SEED, 0L, PLAYER, structureSeed);
            helper.assertTrue(bonus != null && !bonus.isEmpty(),
                    "the completion bonus must ignore the chance gate and always draw at Frontier"
                            + " (structure seed " + structureSeed + ")");
        }
        helper.succeed();
    }

    /** The named {@code prosperity:rarity/<band>} tag resolved against {@code registries}. */
    private static HolderSet.Named<Enchantment> rarityTag(RegistryAccess registries, String band) {
        return registries.lookup(Registries.ENCHANTMENT)
                .flatMap(lookup -> lookup.get(TagKey.create(Registries.ENCHANTMENT,
                        Prosperity.id("rarity/" + band))))
                .orElse(null);
    }

    /** A {@code fabric:load_conditions} payload requiring {@code modId} to be loaded. */
    private static JsonElement allModsLoaded(String modId) {
        return JsonParser.parseString(
                "[{\"condition\":\"fabric:all_mods_loaded\",\"values\":[\"" + modId + "\"]}]");
    }

    private static boolean conditionMet(JsonElement conditions, RegistryAccess registries) {
        return ResourceCondition.CONDITION_CODEC.parse(JsonOps.INSTANCE, conditions).getOrThrow().test(registries);
    }

    private static int filledSlots(NonNullList<ItemStack> items) {
        int count = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static DistanceTier tier(String name) {
        for (DistanceTier candidate : Prosperity.getConfig().distanceTiers) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("missing default tier " + name);
    }
}
