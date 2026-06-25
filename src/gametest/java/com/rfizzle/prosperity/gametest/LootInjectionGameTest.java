package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.config.DistanceTier;
import com.rfizzle.prosperity.config.ProsperityConfig;
import com.rfizzle.prosperity.loot.injection.LootInjectionManager;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime checks for loot injection (S-014) after a real data-pack reload: the built-in defaults load,
 * the {@code prosperity:all_chests} wildcard expands against the loaded loot tables, and {@code augment}
 * adds exactly one tier-eligible reward at or above {@code min_tier} and nothing below it.
 *
 * <p>Asserts against {@link LootInjectionManager#augment} directly with a fixed seed/UUID rather than
 * opening a container, so the outcome is exact (one filled slot) without RNG sampling.
 */
public class LootInjectionGameTest implements FabricGameTest {

    private static final String BATCH = "loot_injection";
    private static final ResourceKey<LootTable> SIMPLE_DUNGEON = BuiltInLootTables.SIMPLE_DUNGEON;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000014d4");
    private static final long SEED = 8814L;

    /** The wildcard expanded at load: a vanilla chest table is a registered injection target. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void wildcardExpandsToVanillaChestTables(GameTestHelper helper) {
        helper.assertTrue(LootInjectionManager.targets().contains(SIMPLE_DUNGEON.location()),
                "all_chests must expand to minecraft:chests/simple_dungeon after reload");
        helper.succeed();
    }

    /** At Frontier the built-in defaults inject exactly one reward into an empty slot. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void injectsOneRewardAtFrontier(GameTestHelper helper) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, tier("frontier"), SEED, PLAYER);

        helper.assertTrue(filledSlots(items) == 1,
                "a Frontier container must receive exactly one injected item: got " + filledSlots(items));
        helper.succeed();
    }

    /** Below the lowest injection tier (Local), nothing is injected. */
    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void injectsNothingAtLocal(GameTestHelper helper) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        LootInjectionManager.augment(items, SIMPLE_DUNGEON, ProsperityConfig.LOCAL_SENTINEL, SEED, PLAYER);

        helper.assertTrue(filledSlots(items) == 0,
                "a Local container is below every default injection tier: got " + filledSlots(items));
        helper.succeed();
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
