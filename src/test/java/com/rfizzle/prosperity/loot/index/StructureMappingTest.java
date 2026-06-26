package com.rfizzle.prosperity.loot.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rfizzle.prosperity.config.ProsperityConfig;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 coverage of structure resolution for the loot index (S-025): the hardcoded vanilla
 * loot-table&rarr;structure map, prefix rules, config-override precedence, the "Other" fallback,
 * and icon lookup (vanilla coverage + modded chest fallback). Bootstraps Minecraft for {@code Items}.
 */
class StructureMappingTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    @Test
    void hardcodedVanillaMappings() {
        ProsperityConfig cfg = new ProsperityConfig();
        assertEquals(StructureIcons.DUNGEON, LootTableStructures.structureFor(mc("chests/simple_dungeon"), cfg));
        assertEquals(StructureIcons.STRONGHOLD,
                LootTableStructures.structureFor(mc("chests/stronghold_library"), cfg));
        assertEquals(StructureIcons.BASTION_REMNANT,
                LootTableStructures.structureFor(mc("chests/bastion_treasure"), cfg));
        assertEquals(StructureIcons.FORTRESS, LootTableStructures.structureFor(mc("chests/nether_bridge"), cfg));
    }

    @Test
    void prefixRulesCoverVillageAndTrialChambers() {
        ProsperityConfig cfg = new ProsperityConfig();
        assertEquals(StructureIcons.VILLAGE,
                LootTableStructures.structureFor(mc("chests/village/village_armorer"), cfg));
        assertEquals(StructureIcons.TRIAL_CHAMBERS,
                LootTableStructures.structureFor(mc("chests/trial_chambers/reward_ominous"), cfg));
    }

    @Test
    void unmappedFallsToOther() {
        ProsperityConfig cfg = new ProsperityConfig();
        assertEquals(LootTableStructures.OTHER,
                LootTableStructures.structureFor(ResourceLocation.fromNamespaceAndPath("examplemod", "chests/tower"), cfg));
        // A minecraft loot table outside the chest set is also unmapped.
        assertEquals(LootTableStructures.OTHER, LootTableStructures.structureFor(mc("entities/sheep"), cfg));
    }

    @Test
    void configOverrideTakesPrecedenceOverHardcoded() {
        ProsperityConfig cfg = new ProsperityConfig();
        cfg.lootTableStructures.put("minecraft:chests/simple_dungeon", "minecraft:fortress");
        assertEquals(StructureIcons.FORTRESS,
                LootTableStructures.structureFor(mc("chests/simple_dungeon"), cfg));
    }

    @Test
    void invalidConfigOverrideFallsThroughToHardcoded() {
        ProsperityConfig cfg = new ProsperityConfig();
        cfg.lootTableStructures.put("minecraft:chests/simple_dungeon", "NOT A VALID ID");
        assertEquals(StructureIcons.DUNGEON, LootTableStructures.structureFor(mc("chests/simple_dungeon"), cfg));
    }

    @Test
    void iconLookupCoversVanillaAndFallsBackToChest() {
        assertEquals(Items.MOSSY_COBBLESTONE, StructureIcons.iconFor(StructureIcons.DUNGEON));
        assertEquals(Items.END_PORTAL_FRAME, StructureIcons.iconFor(StructureIcons.STRONGHOLD));
        assertEquals(Items.VAULT, StructureIcons.iconFor(StructureIcons.TRIAL_CHAMBERS));
        // Every mapped structure resolves to a non-chest dedicated icon.
        for (ResourceLocation structure : StructureIcons.mappedStructures()) {
            assertNotEquals(Items.CHEST, StructureIcons.iconFor(structure),
                    "mapped structure " + structure + " should have a dedicated icon");
        }
        // Modded / Other structures fall back to the chest icon.
        assertEquals(Items.CHEST, StructureIcons.iconFor(LootTableStructures.OTHER));
        assertEquals(Items.CHEST,
                StructureIcons.iconFor(ResourceLocation.fromNamespaceAndPath("examplemod", "tower")));
        assertTrue(StructureIcons.mappedStructures().size() >= 20, "all vanilla structures covered");
    }
}
