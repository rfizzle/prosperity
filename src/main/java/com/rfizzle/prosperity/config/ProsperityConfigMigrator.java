package com.rfizzle.prosperity.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rfizzle.prosperity.Prosperity;

/**
 * Runs ordered JSON-level migrations on the raw config before Gson
 * deserialization. Each migration transforms a {@link JsonObject} from version
 * N to N+1, operating on raw keys/values so renamed or restructured fields are
 * carried forward instead of silently dropped by a lenient deserialize.
 *
 * <p>Migration runs only on the file-load path ({@link ProsperityConfig#load}).
 * {@link ProsperityConfig#fromJson} is fed already-current JSON — the Mod Menu
 * working copy and the server&rarr;client config sync both originate from a
 * {@link ProsperityConfig#toJson} of an already-migrated config — so it needs no
 * migration.
 *
 * <p>When adding a new migration:
 * <ol>
 *   <li>Bump {@link #CURRENT_VERSION}.</li>
 *   <li>Update the default {@code configVersion} field in
 *       {@link ProsperityConfig} to match.</li>
 *   <li>Append the migration lambda to {@link #MIGRATIONS}.</li>
 *   <li>Add a test in {@code ConfigMigratorTest}.</li>
 * </ol>
 */
final class ProsperityConfigMigrator {

    static final int CURRENT_VERSION = 1;

    @FunctionalInterface
    interface Migration {
        void apply(JsonObject json);
    }

    /**
     * Ordered array of migrations. Index 0 = v0&rarr;v1, index 1 = v1&rarr;v2, etc.
     * Each entry MUST correspond to the transition from version {@code i} to
     * version {@code i+1}.
     */
    private static final Migration[] MIGRATIONS = {
            // v0 → v1: baseline version tag. Pre-versioned configs are
            // structurally identical to v1; clamp() backfills any missing
            // fields after deserialize. This migration exists so the
            // infrastructure is exercised on first load of a version-0 file and
            // future renames have a seam to slot into.
            json -> {}
    };

    private ProsperityConfigMigrator() {}

    /**
     * Run any pending migrations on the raw JSON object. Returns {@code true}
     * if at least one migration was applied (the caller persists the result to
     * disk so the file reflects the latest schema).
     */
    static boolean migrate(JsonObject json) {
        if (json == null) {
            return false;
        }

        int version = readVersion(json);
        if (version >= CURRENT_VERSION) {
            return false;
        }

        boolean changed = false;
        for (int i = version; i < CURRENT_VERSION && i < MIGRATIONS.length; i++) {
            int from = i;
            int to = i + 1;
            try {
                MIGRATIONS[i].apply(json);
                Prosperity.LOGGER.info("Migrated prosperity.json from v{} to v{}", from, to);
                changed = true;
            } catch (Exception e) {
                Prosperity.LOGGER.warn("Migration v{} to v{} failed; skipping: {}", from, to, e.getMessage());
            }
        }

        if (changed) {
            json.addProperty("configVersion", CURRENT_VERSION);
        }

        return changed;
    }

    /**
     * Extract the config version from the raw JSON, defaulting to 0 if the field
     * is absent or not a number (pre-versioned configs).
     */
    static int readVersion(JsonObject json) {
        if (json == null) {
            return 0;
        }
        JsonElement element = json.get("configVersion");
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        return 0;
    }
}
