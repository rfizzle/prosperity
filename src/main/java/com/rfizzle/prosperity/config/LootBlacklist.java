package com.rfizzle.prosperity.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable matcher for the {@code lootTableBlacklist} config (SPEC §7): the loot tables excluded
 * from all Prosperity behavior. Built once from the raw pattern list in {@link ProsperityConfig#clamp()}
 * and cached on the live config, so each lookup is a {@link HashSet} hit plus a scan of the (typically
 * empty) wildcard list rather than a re-parse.
 *
 * <p>Two pattern forms, matched against the {@code namespace:path} string of a loot table id:
 * <ul>
 *   <li><b>Exact:</b> {@code minecraft:chests/village/village_weaponsmith} — matches only that id.</li>
 *   <li><b>Wildcard:</b> any entry ending in {@code *} matches by prefix — {@code somebigmod:*} excludes a
 *       whole namespace, {@code minecraft:chests/*} a whole subtree. (A bare {@code *} excludes everything.)</li>
 * </ul>
 *
 * <p>The matching logic is pure string work (the only Minecraft type is {@link ResourceLocation}, used
 * solely to render the id), so it is exercised directly by pure-JUnit tests.
 */
public final class LootBlacklist {

    private static final LootBlacklist EMPTY = new LootBlacklist(Set.of(), List.of());

    private final Set<String> exact;
    private final List<String> wildcardPrefixes;

    private LootBlacklist(Set<String> exact, List<String> wildcardPrefixes) {
        this.exact = exact;
        this.wildcardPrefixes = wildcardPrefixes;
    }

    /**
     * Parse a raw pattern list into a matcher. Null, blank, and surrounding-whitespace entries are
     * dropped; an entry ending in {@code *} becomes a prefix wildcard, everything else an exact id.
     * A null or effectively-empty list yields the shared {@link #EMPTY} matcher.
     */
    public static LootBlacklist of(@Nullable List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }
        Set<String> exact = new HashSet<>();
        List<String> wildcards = new ArrayList<>();
        for (String raw : patterns) {
            if (raw == null) {
                continue;
            }
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.endsWith("*")) {
                wildcards.add(pattern.substring(0, pattern.length() - 1));
            } else {
                exact.add(pattern);
            }
        }
        if (exact.isEmpty() && wildcards.isEmpty()) {
            return EMPTY;
        }
        return new LootBlacklist(exact, wildcards);
    }

    /** Whether no patterns are configured (the common default; lets callers skip the lookup). */
    public boolean isEmpty() {
        return exact.isEmpty() && wildcardPrefixes.isEmpty();
    }

    /** Whether the loot table {@code id} is blacklisted. A null id is never blacklisted. */
    public boolean matches(@Nullable ResourceLocation id) {
        return id != null && matches(id.toString());
    }

    /** String form of {@link #matches(ResourceLocation)}, matched against {@code namespace:path}. */
    boolean matches(String id) {
        if (exact.contains(id)) {
            return true;
        }
        for (String prefix : wildcardPrefixes) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
