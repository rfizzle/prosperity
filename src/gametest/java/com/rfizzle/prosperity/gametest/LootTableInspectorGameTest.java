package com.rfizzle.prosperity.gametest;

import com.rfizzle.prosperity.loot.index.LootTableInspector;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Runtime check for the loot-index tree-walker (S-025) against a real loaded loot table. The walker
 * needs a running server (loot tables live in the reloadable registries) and the accessor mixins
 * applied, so it runs as a gametest rather than a unit test. Asserts the known item sources of
 * {@code chests/simple_dungeon} are enumerated and that walking terminates.
 */
public class LootTableInspectorGameTest implements FabricGameTest {

    private static final String BATCH = "loot_index";

    @GameTest(batch = BATCH, template = FabricGameTest.EMPTY_STRUCTURE)
    @SuppressWarnings("removal")
    public void enumeratesSimpleDungeonItemSources(GameTestHelper helper) {
        ReloadableServerRegistries.Holder registries = helper.getLevel().getServer().reloadableRegistries();
        Function<ResourceKey<LootTable>, LootTable> resolver = registries::getLootTable;
        LootTable dungeon = registries.getLootTable(BuiltInLootTables.SIMPLE_DUNGEON);

        List<Item> items = LootTableInspector.collect(dungeon, resolver);

        helper.assertTrue(items.size() > 5,
                "simple_dungeon should yield many item sources: got " + items.size());
        helper.assertTrue(items.contains(Items.SADDLE), "simple_dungeon must include its saddle reward");
        helper.assertTrue(items.contains(Items.BREAD), "simple_dungeon must include its bread");
        helper.assertTrue(items.contains(Items.BONE), "simple_dungeon must include its bone");
        // Deduplicated: no item appears twice.
        helper.assertTrue(items.size() == items.stream().distinct().count(),
                "item sources must be deduplicated");
        helper.succeed();
    }
}
