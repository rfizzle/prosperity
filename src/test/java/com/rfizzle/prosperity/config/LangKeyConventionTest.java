package com.rfizzle.prosperity.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the concord localization vocabulary (DESIGN-SYSTEM §10): every shipped translation key is
 * namespaced by the <em>surface</em> it renders on — a sanctioned {@code <surface>.prosperity.*} prefix,
 * a vanilla-mandated name key ({@code item.prosperity.<id>}), or a recipe-viewer-mandated category key.
 * Arbitrary gameplay-concept prefixes ({@code tier.}, {@code structure.}, {@code jade.}, …) are the exact
 * drift #104 retired, so the whitelist below is the durable guard against their return. A pure
 * resource/string check with no Fabric APIs, so it runs in the fast JUnit tier.
 */
class LangKeyConventionTest {

    private static final String RESOURCE = "/assets/prosperity/lang/en_us.json";
    private static final Path SOURCE = Path.of("src/main/resources/assets/prosperity/lang/en_us.json");

    /** Surface prefixes from DESIGN-SYSTEM §10; every custom key reads {@code <surface>.prosperity.<path>}. */
    private static final Set<String> SURFACE_PREFIXES = Set.of(
            "config", "command", "hud", "gui", "tooltip", "message",
            "notification", "advancements", "info", "key", "stat");

    /**
     * Keys that do NOT take the {@code <surface>.prosperity.*} shape: vanilla name keys
     * ({@code <registry>.<mod>.<id>}), the per-mod keybind category, and the recipe-viewer category keys
     * whose leading token the viewer mandates — EMI derives {@code emi.category.<ns>.<path>} from the
     * category id, REI/JEI read their own {@code <viewer>.<mod>.category.*}.
     */
    private static final List<String> ALLOWED_LITERAL_PREFIXES = List.of(
            "item.prosperity.",
            "block.prosperity.",
            "enchantment.prosperity.",
            "key.categories.prosperity",
            "emi.category.prosperity.",
            "rei.prosperity.",
            "jei.prosperity.");

    /** The concept prefixes #104 retired; a regression must fail loudly here, not ship a raw key to players. */
    private static final List<String> RETIRED_PREFIXES = List.of(
            "tier.prosperity.", "structure.prosperity.", "jade.prosperity.",
            "party.prosperity.", "loot_index.prosperity.", "category.prosperity.");

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

    private static boolean isSanctioned(String key) {
        for (String literal : ALLOWED_LITERAL_PREFIXES) {
            if (key.startsWith(literal)) {
                return true;
            }
        }
        int firstDot = key.indexOf('.');
        if (firstDot < 0) {
            return false;
        }
        String surface = key.substring(0, firstDot);
        return SURFACE_PREFIXES.contains(surface) && key.startsWith(surface + ".prosperity.");
    }

    @Test
    void everyKeyUsesASanctionedSurfacePrefix() {
        for (String key : lang().keySet()) {
            assertTrue(isSanctioned(key),
                    key + " is not under a sanctioned surface/vanilla/viewer prefix (DESIGN-SYSTEM §10)");
        }
    }

    @Test
    void retiredConceptPrefixesAreGone() {
        for (String key : lang().keySet()) {
            for (String retired : RETIRED_PREFIXES) {
                assertFalse(key.startsWith(retired), key + " still uses the retired " + retired + " prefix");
            }
        }
    }

    @Test
    void reclassifiedKeysResolveUnderTheirNewSurface() {
        JsonObject lang = lang();
        // The runtime-assembled endpoints each surface's code builds: an orphaned prefix would render a raw
        // key in a toast, tooltip, or index label, so pin each new home explicitly.
        assertTrue(lang.has("notification.prosperity.tier.wilderness"),
                "tier names moved to notification.prosperity.tier.*");
        assertTrue(lang.has("notification.prosperity.structure.monument"),
                "structure names moved to notification.prosperity.structure.*");
        assertTrue(lang.has("notification.prosperity.container_in_use"),
                "party refusal cue moved to notification.prosperity.*");
        assertTrue(lang.has("tooltip.prosperity.status.looted"),
                "probe-tooltip lines moved to tooltip.prosperity.*");
        assertTrue(lang.has("tooltip.prosperity.override.fixed"),
                "probe-tooltip override lines moved to tooltip.prosperity.*");
        assertTrue(lang.has("gui.prosperity.injected"), "loot-index labels moved to gui.prosperity.*");
        assertTrue(lang.has("gui.prosperity.structure.dungeon"),
                "loot-index structure names moved to gui.prosperity.structure.*");
        assertTrue(lang.has("rei.prosperity.category.loot_tables"), "REI category title");
        assertTrue(lang.has("jei.prosperity.category.loot_tables"), "JEI category title");
        assertTrue(lang.has("emi.category.prosperity.loot_tables"), "EMI category title (viewer-mandated)");
    }

    @Test
    void tierTranslationKeyIsTheSharedChokePoint() {
        // The six tier-name sites now route through DistanceTier.translationKey(); it must land in-vocabulary
        // and resolve against the shipped lang file.
        assertEquals("notification.prosperity.tier.wilderness", DistanceTier.translationKey("wilderness"));
        assertTrue(isSanctioned(DistanceTier.translationKey("depths")),
                "the assembled tier key is under a sanctioned surface prefix");
        assertTrue(lang().has(DistanceTier.translationKey("depths")), "the assembled tier key resolves");
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
