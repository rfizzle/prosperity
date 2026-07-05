package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.Prosperity;
import com.rfizzle.prosperity.item.ProsperityItems;
import com.rfizzle.prosperity.loot.injection.InjectionRegistry;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * Runtime checks for the Prospector's Compass item: it is registered under the expected id and
 * the bundled loot-injection datapack offers it on chest tables gated at the frontier tier. The
 * needle itself is a client-only item property (headless server has no models), and the
 * per-player unlooted set it points at is covered by {@link UnlootedSyncGameTest}.
 */
public class ProspectorsCompassGameTest implements FabricGameTest {

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void compassIsRegistered(GameTestHelper helper) {
        helper.assertTrue(
                BuiltInRegistries.ITEM.getOptional(Prosperity.id("prospectors_compass"))
                        .map(item -> item == ProsperityItems.PROSPECTORS_COMPASS).orElse(false),
                "prosperity:prospectors_compass should resolve to the registered item");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void compassIsInjectedAtFrontierTier(GameTestHelper helper) {
        boolean offered = InjectionRegistry
                .injectionsFor(BuiltInLootTables.SIMPLE_DUNGEON.location()).stream()
                .anyMatch(view -> view.stack().is(ProsperityItems.PROSPECTORS_COMPASS)
                        && "frontier".equals(view.minTier()));
        helper.assertTrue(offered,
                "chest tables should carry a frontier-gated prospector's compass injection");
        helper.succeed();
    }
}
