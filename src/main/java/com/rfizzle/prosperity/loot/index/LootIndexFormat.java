package com.rfizzle.prosperity.loot.index;

import java.util.Locale;

/**
 * Pure formatting helpers for the loot-index recipe-viewer plugins (S-029/030/031). Kept free of
 * any Minecraft imports so the label formatting can be unit-tested without bootstrapping the game,
 * mirroring the {@code TierFormat}/{@code DoubleChestLayout} split.
 *
 * <p>The viewers build their {@code Component}s from these strings, degrading custom-config tier
 * and modded-structure names to a title-cased fallback via {@code translatableWithFallback}.
 */
public final class LootIndexFormat {

    /** Lang-key prefix for per-structure display names; the structure id path is appended. */
    public static final String STRUCTURE_KEY_PREFIX = "prosperity.loot_index.structure.";

    private LootIndexFormat() {
    }

    /**
     * The translation key for a structure id path, e.g. {@code "dungeon"} →
     * {@code "prosperity.loot_index.structure.dungeon"}. Unmapped/modded structures fall back to
     * {@link #titleCase(String)} of the path via {@code translatableWithFallback}.
     */
    public static String structureKey(String path) {
        return STRUCTURE_KEY_PREFIX + path;
    }

    /**
     * The tier badge for an injected entry's minimum tier, e.g. {@code "frontier" -> "Frontier+"}.
     * Used as the {@code translatableWithFallback} fallback so custom-config tier names still read
     * sensibly when they carry no lang key.
     */
    public static String tierBadge(String tierName) {
        return titleCase(tierName) + "+";
    }

    /**
     * Title-case an underscore- or space-separated id path: {@code "trial_chambers" -> "Trial
     * Chambers"}, {@code "frontier" -> "Frontier"}. Null/blank input yields an empty string. Used
     * as the human-readable fallback when no lang key resolves.
     */
    public static String titleCase(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] words = path.replace('_', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }
}
