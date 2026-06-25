package com.rfizzle.prosperity.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Pure-JUnit coverage of the {@link LootBlacklist} pattern matcher (SPEC §7). */
class LootBlacklistTest {

    @Test
    void exactMatch() {
        LootBlacklist bl = LootBlacklist.of(List.of("minecraft:chests/village/village_weaponsmith"));
        assertTrue(bl.matches("minecraft:chests/village/village_weaponsmith"));
        // An exact entry never matches by prefix.
        assertFalse(bl.matches("minecraft:chests/village/village_weaponsmith_extra"));
        assertFalse(bl.matches("minecraft:chests/village/village_armorer"));
    }

    @Test
    void namespaceWildcard() {
        LootBlacklist bl = LootBlacklist.of(List.of("somebigmod:*"));
        assertTrue(bl.matches("somebigmod:chests/dungeon"));
        assertTrue(bl.matches("somebigmod:anything/at/all"));
        assertFalse(bl.matches("minecraft:chests/simple_dungeon"));
    }

    @Test
    void subtreeWildcard() {
        LootBlacklist bl = LootBlacklist.of(List.of("minecraft:chests/*"));
        assertTrue(bl.matches("minecraft:chests/simple_dungeon"));
        assertTrue(bl.matches("minecraft:chests/woodland_mansion"));
        // Outside the subtree.
        assertFalse(bl.matches("minecraft:entities/sheep"));
    }

    @Test
    void bareWildcardMatchesEverything() {
        LootBlacklist bl = LootBlacklist.of(List.of("*"));
        assertTrue(bl.matches("minecraft:chests/simple_dungeon"));
        assertTrue(bl.matches("anymod:any/path"));
    }

    @Test
    void exactAndWildcardCombined() {
        LootBlacklist bl = LootBlacklist.of(Arrays.asList(
                "somebigmod:*", "minecraft:chests/village/village_weaponsmith"));
        assertTrue(bl.matches("somebigmod:foo"));
        assertTrue(bl.matches("minecraft:chests/village/village_weaponsmith"));
        assertFalse(bl.matches("minecraft:chests/simple_dungeon"));
    }

    @Test
    void emptyListMatchesNothing() {
        LootBlacklist bl = LootBlacklist.of(List.of());
        assertTrue(bl.isEmpty());
        assertFalse(bl.matches("minecraft:chests/simple_dungeon"));
    }

    @Test
    void nullListYieldsSharedEmptyMatcher() {
        LootBlacklist bl = LootBlacklist.of(null);
        assertTrue(bl.isEmpty());
        // The empty matcher is a shared singleton (null and all-blank both collapse to it).
        assertSame(bl, LootBlacklist.of(List.of("  ", "")));
    }

    @Test
    void blankAndNullEntriesAreDropped() {
        List<String> patterns = new ArrayList<>();
        patterns.add("  minecraft:chests/simple_dungeon  ");
        patterns.add("");
        patterns.add("   ");
        patterns.add(null);
        LootBlacklist bl = LootBlacklist.of(patterns);
        // Surrounding whitespace is trimmed off the surviving entry.
        assertTrue(bl.matches("minecraft:chests/simple_dungeon"));
        assertFalse(bl.isEmpty());
    }

    @Test
    void nullIdNeverMatches() {
        LootBlacklist bl = LootBlacklist.of(List.of("*"));
        assertFalse(bl.matches((ResourceLocation) null));
    }

    @Test
    void resourceLocationOverloadMatchesStringForm() {
        LootBlacklist bl = LootBlacklist.of(List.of("minecraft:chests/simple_dungeon", "somebigmod:*"));
        assertTrue(bl.matches(ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertTrue(bl.matches(ResourceLocation.parse("somebigmod:loot/x")));
        assertFalse(bl.matches(ResourceLocation.parse("minecraft:chests/woodland_mansion")));
    }

    @Test
    void configClampBuildsMatcher() {
        ProsperityConfig c = new ProsperityConfig();
        c.lootTableBlacklist = new ArrayList<>(List.of("minecraft:chests/simple_dungeon"));
        c.clamp();
        assertTrue(c.blacklist().matches(ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        assertFalse(c.blacklist().matches(ResourceLocation.parse("minecraft:chests/woodland_mansion")));
    }

    @Test
    void defaultConfigHasEmptyBlacklist() {
        ProsperityConfig c = new ProsperityConfig();
        assertTrue(c.blacklist().isEmpty());
    }
}
