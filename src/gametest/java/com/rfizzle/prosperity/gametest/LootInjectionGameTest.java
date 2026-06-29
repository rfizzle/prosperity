package com.rfizzle.prosperity.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Entry;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager.Tiered;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        ResourceLocation dimension = helper.getLevel().dimension().location();
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, tier("frontier"), dimension, SEED, 0L, PLAYER);

        helper.assertTrue(filledSlots(items) == 1,
                "a Frontier container must receive exactly one injected item: got " + filledSlots(items));
        helper.succeed();
    }

    /** Below the lowest injection tier (Local), nothing is injected. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void injectsNothingAtLocal(GameTestHelper helper) {
        ResourceLocation dimension = helper.getLevel().dimension().location();
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, ProsperityConfig.LOCAL_SENTINEL, dimension, SEED, 0L, PLAYER);

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
