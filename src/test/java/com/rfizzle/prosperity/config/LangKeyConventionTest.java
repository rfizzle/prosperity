package com.rfizzle.prosperity.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Concord terminology alignment: every custom translation-key tree the mod ships follows
 * the ratified {@code <concept>.<mod>} ordering (Concord HUD Standard §4/§8, concord#22), the shipped
 * {@code ✦} notification glyph is retained, and no key carries a mod-first prefix. A pure
 * resource/string check with no Fabric APIs, so it runs in the fast JUnit tier.
 */
class LangKeyConventionTest {

    private static final String RESOURCE = "/assets/prosperity/lang/en_us.json";
    private static final Path SOURCE = Path.of("src/main/resources/assets/prosperity/lang/en_us.json");

    private static JsonObject lang() {
        try (InputStream in = LangKeyConventionTest.class.getResourceAsStream(RESOURCE)) {
            String json = in != null
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(SOURCE, StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("could not load en_us.json for the lang-key convention check", e);
        }
    }

    @Test
    void customTreesUseConceptFirstNamespace() {
        JsonObject lang = lang();

        // The renamed trees are present under the <concept>.<mod> ordering.
        assertTrue(lang.has("config.prosperity.title"), "config tree moved to config.prosperity.*");
        assertTrue(lang.has("config.prosperity.enable_tier_hud"), "tier-HUD toggle key");
        assertTrue(lang.has("message.prosperity.peek_hint"), "chat tree moved to message.prosperity.*");
        assertTrue(lang.has("notification.prosperity.loot_generated"),
                "notification tree moved to notification.prosperity.*");
        assertTrue(lang.has("hud.prosperity.detail.title"), "panel keys moved to hud.prosperity.detail.*");
        assertTrue(lang.has("key.prosperity.peek_detail"), "peek keybind uses the standard id");

        // No key retains a pre-alignment prefix.
        for (String key : lang.keySet()) {
            assertFalse(key.startsWith("prosperity.config."), key);
            assertFalse(key.startsWith("chat.prosperity."), key);
            assertFalse(key.startsWith("prosperity.notification."), key);
            assertFalse(key.startsWith("hud.prosperity.loot_detail."), key);
            assertNotEquals("key.prosperity.peek_loot_detail", key);
        }
    }

    @Test
    void remainingCustomTreesUseConceptFirstNamespace() {
        JsonObject lang = lang();

        // The jade, loot index, tier, structure, party, and compass-tooltip trees carry the
        // <concept>.<mod> ordering. These endpoints double as the targets the code assembles at
        // runtime (e.g. "tier.prosperity." + name, LootIndexFormat.STRUCTURE_KEY_PREFIX + path),
        // so an orphaned prefix would render a raw key in a tooltip, notification, or index label.
        assertTrue(lang.has("jade.prosperity.status.looted"), "jade tree moved to jade.prosperity.*");
        assertTrue(lang.has("loot_index.prosperity.injected"),
                "loot index tree moved to loot_index.prosperity.*");
        assertTrue(lang.has("loot_index.prosperity.structure.dungeon"),
                "assembled loot-index structure keys resolve under loot_index.prosperity.structure.*");
        assertTrue(lang.has("tier.prosperity.wilderness"), "tier tree moved to tier.prosperity.*");
        assertTrue(lang.has("structure.prosperity.monument"),
                "structure tree moved to structure.prosperity.*");
        assertTrue(lang.has("party.prosperity.container_in_use"),
                "party message moved to party.prosperity.*");
        assertTrue(lang.has("tooltip.prosperity.prospectors_compass.points"),
                "compass tooltip moved to tooltip.prosperity.*");

        // No key retains a mod-first prefix for any of the renamed trees.
        for (String key : lang.keySet()) {
            assertFalse(key.startsWith("prosperity.jade."), key);
            assertFalse(key.startsWith("prosperity.loot_index."), key);
            assertFalse(key.startsWith("prosperity.tier."), key);
            assertFalse(key.startsWith("prosperity.structure."), key);
            assertFalse(key.startsWith("prosperity.party."), key);
            assertFalse(key.startsWith("prosperity.item.prospectors_compass.tooltip."), key);
        }
    }

    @Test
    void badgeToggleUsesShowLabel() {
        // HUD Standard §4: the badge toggle reads "Show <Domain> HUD".
        assertEquals("Show Tier HUD", lang().get("config.prosperity.enable_tier_hud").getAsString());
    }

    @Test
    void notificationGlyphRetained() {
        // concord#22 §2 keeps ✦ as Prosperity's notification glyph on the loot toast and peek hint.
        JsonObject lang = lang();
        assertTrue(lang.get("notification.prosperity.loot_generated").getAsString().contains("✦"));
        assertTrue(lang.get("message.prosperity.peek_hint").getAsString().contains("✦"));
    }
}
