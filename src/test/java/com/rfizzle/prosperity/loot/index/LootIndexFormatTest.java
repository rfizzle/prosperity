package com.rfizzle.prosperity.loot.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit coverage of the loot-index label formatter (S-029): the structure lang-key path,
 * the injected tier badge, and the title-cased fallback the viewers use when no lang key resolves.
 * No Minecraft bootstrap — {@link LootIndexFormat} is MC-import-free by design.
 */
class LootIndexFormatTest {

    @Test
    void structureKeyAppendsPath() {
        assertEquals("prosperity.loot_index.structure.dungeon", LootIndexFormat.structureKey("dungeon"));
        assertEquals("prosperity.loot_index.structure.other", LootIndexFormat.structureKey("other"));
    }

    @Test
    void tierBadgeTitleCasesAndAppendsPlus() {
        assertEquals("Frontier+", LootIndexFormat.tierBadge("frontier"));
        assertEquals("Depths+", LootIndexFormat.tierBadge("depths"));
    }

    @Test
    void titleCaseSplitsUnderscoresAndCapitalizes() {
        assertEquals("Trial Chambers", LootIndexFormat.titleCase("trial_chambers"));
        assertEquals("Frontier", LootIndexFormat.titleCase("frontier"));
        assertEquals("Bastion Remnant", LootIndexFormat.titleCase("bastion_remnant"));
    }

    @Test
    void titleCaseNormalizesMixedCaseAndStrayWhitespace() {
        assertEquals("Ancient City", LootIndexFormat.titleCase("ANCIENT_city"));
        assertEquals("End City", LootIndexFormat.titleCase("  end   city  "));
    }

    @Test
    void titleCaseEmptyForNullOrBlank() {
        assertEquals("", LootIndexFormat.titleCase(null));
        assertEquals("", LootIndexFormat.titleCase(""));
        assertEquals("", LootIndexFormat.titleCase("   "));
    }
}
