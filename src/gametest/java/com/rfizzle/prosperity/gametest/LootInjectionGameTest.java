package com.rfizzle.prosperity.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Entry;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.LevelPolicy;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Tiered;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.minecraft.core.Holder;
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
 * adds exactly one tier-eligible reward at or above {@code min_tier} and nothing below it, and
 * dimension-gated entries (S-039) resolve against the level's real {@code dimension()} id.
 *
 * <p>Asserts against {@link LootInjectionManager#augment} directly with a fixed seed/UUID rather than
 * opening a container, so the outcome is exact (one filled slot) without RNG sampling.
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
        helper.assertTrue(LootInjectionManager.targets().contains(SIMPLE_DUNGEON.location()),
                "all_chests must expand to minecraft:chests/simple_dungeon after reload");
        helper.succeed();
    }

    /** At Frontier the built-in defaults (no dimension filter) inject exactly one reward into an empty slot. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void injectsOneRewardAtFrontier(GameTestHelper helper) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, tier("frontier"), helper.getLevel(), SEED, 0L, PLAYER);

        helper.assertTrue(filledSlots(items) == 1,
                "a Frontier container must receive exactly one injected item: got " + filledSlots(items));
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
                LootInjectionManager.eligibleEntries(list, frontier, overworld, cfg).equals(List.of(anywhere)),
                "a Nether-scoped injection must not fire in the Overworld; the unscoped one does");
        helper.assertTrue(
                LootInjectionManager.eligibleEntries(list, frontier, NETHER, cfg).size() == 2,
                "in the Nether both the unscoped and Nether-scoped groups apply");
        helper.assertTrue(
                LootInjectionManager.eligibleEntries(list, ProsperityConfig.LOCAL_SENTINEL, NETHER, cfg).isEmpty(),
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

        ItemStack drawn = LootInjectionManager.draw(List.of(generative), RandomSource.create(SEED), registries);
        helper.assertTrue(drawn != null && drawn.is(Items.ENCHANTED_BOOK),
                "a generative entry must realize to an enchanted book");
        ItemEnchantments stored = drawn.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        helper.assertTrue(stored.size() == 1,
                "exactly one enchantment is drawn from the tag: got " + stored.size());
        Holder<Enchantment> enchantment = stored.keySet().iterator().next();
        helper.assertTrue(enchantment.is(EnchantmentTags.TREASURE),
                "the drawn enchantment must come from the named tag: got " + enchantment);
        int expected = LootInjectionManager.policyLevel(LevelPolicy.MID,
                enchantment.value().getMinLevel(), enchantment.value().getMaxLevel(), RandomSource.create(0L));
        helper.assertTrue(stored.getLevel(enchantment) == expected,
                "the mid policy stores ceil(max/2): expected " + expected + ", got " + stored.getLevel(enchantment));

        ItemStack replay = LootInjectionManager.draw(List.of(generative), RandomSource.create(SEED), registries);
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

        ItemStack drawn = LootInjectionManager.draw(List.of(unresolvable, literal),
                RandomSource.create(SEED), registries);
        helper.assertTrue(drawn != null && drawn.is(Items.BREAD),
                "the literal sibling must win the slot when the tag resolves empty");
        helper.assertTrue(
                LootInjectionManager.draw(List.of(unresolvable), RandomSource.create(SEED), registries) == null,
                "a pool of only unresolvable generative entries injects nothing");
        helper.succeed();
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
