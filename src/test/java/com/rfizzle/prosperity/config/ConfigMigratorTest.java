package com.rfizzle.prosperity.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JUnit coverage for {@link ProsperityConfigMigrator} and the migration seam in
 * {@link ProsperityConfig#load}. No Fabric APIs — only the raw-JSON pipeline.
 */
class ConfigMigratorTest {

    @Test
    void absentVersionReadsAsZero() {
        JsonObject raw = JsonParser.parseString("{}").getAsJsonObject();
        assertEquals(0, ProsperityConfigMigrator.readVersion(raw));
    }

    @Test
    void nonNumberVersionReadsAsZero() {
        JsonObject raw = JsonParser.parseString("{\"configVersion\": \"nope\"}").getAsJsonObject();
        assertEquals(0, ProsperityConfigMigrator.readVersion(raw));
    }

    @Test
    void migrateStampsCurrentVersionOnPreVersionedJson() {
        JsonObject raw = JsonParser.parseString("{\"enableInstancedLoot\": false}").getAsJsonObject();

        boolean changed = ProsperityConfigMigrator.migrate(raw);

        assertTrue(changed, "a v0 (no configVersion) file must report a migration");
        assertEquals(ProsperityConfigMigrator.CURRENT_VERSION, raw.get("configVersion").getAsInt());
        // The migration runs on raw JSON: pre-existing keys are carried forward untouched.
        assertFalse(raw.get("enableInstancedLoot").getAsBoolean());
    }

    @Test
    void migrateIsNoOpAtCurrentVersion() {
        JsonObject raw = new JsonObject();
        raw.addProperty("configVersion", ProsperityConfigMigrator.CURRENT_VERSION);

        assertFalse(ProsperityConfigMigrator.migrate(raw), "a current-version file needs no migration");
    }

    @Test
    void migrateIsIdempotent() {
        JsonObject raw = JsonParser.parseString("{}").getAsJsonObject();

        assertTrue(ProsperityConfigMigrator.migrate(raw), "first pass migrates v0 → current");
        assertFalse(ProsperityConfigMigrator.migrate(raw), "second pass is a no-op (already current)");
    }

    @Test
    void v1ToV2AppendsTrialChambersOverride() {
        JsonObject raw = JsonParser.parseString(
                "{\"configVersion\": 1, \"structureOverrides\": ["
                        + "{\"structure\": \"minecraft:monument\", \"mode\": \"fixed\", \"tier\": \"wilderness\"}"
                        + "]}").getAsJsonObject();

        assertTrue(ProsperityConfigMigrator.migrate(raw));

        var overrides = raw.getAsJsonArray("structureOverrides");
        assertEquals(2, overrides.size(), "the trial_chambers default is appended");
        JsonObject added = overrides.get(1).getAsJsonObject();
        assertEquals("minecraft:trial_chambers", added.get("structure").getAsString());
        assertEquals("minimum", added.get("mode").getAsString());
        assertEquals("wilderness", added.get("tier").getAsString());
    }

    @Test
    void v1ToV2RespectsExistingTrialChambersOverride() {
        JsonObject raw = JsonParser.parseString(
                "{\"configVersion\": 1, \"structureOverrides\": ["
                        + "{\"structure\": \"minecraft:trial_chambers\", \"mode\": \"fixed\", \"tier\": \"depths\"}"
                        + "]}").getAsJsonObject();

        assertTrue(ProsperityConfigMigrator.migrate(raw), "the version stamp still advances");

        var overrides = raw.getAsJsonArray("structureOverrides");
        assertEquals(1, overrides.size(), "a hand-tuned trial_chambers entry is left alone");
        assertEquals("depths", overrides.get(0).getAsJsonObject().get("tier").getAsString());
    }

    @Test
    void v1ToV2ToleratesMissingOrMalformedOverrides() {
        JsonObject missing = JsonParser.parseString("{\"configVersion\": 1}").getAsJsonObject();
        assertTrue(ProsperityConfigMigrator.migrate(missing));
        assertFalse(missing.has("structureOverrides"), "a missing list is left for clamp() to backfill");

        JsonObject malformed = JsonParser.parseString(
                "{\"configVersion\": 1, \"structureOverrides\": \"nope\"}").getAsJsonObject();
        assertTrue(ProsperityConfigMigrator.migrate(malformed));
        assertEquals("nope", malformed.get("structureOverrides").getAsString());
    }

    @Test
    void noVersionFileMigratesToCurrentAndResaves(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        // A pre-versioned file: valid config object, no configVersion key.
        Files.writeString(path, "{\"enableInstancedLoot\": false}");

        ProsperityConfig loaded = ProsperityConfig.load(path);

        assertEquals(ProsperityConfigMigrator.CURRENT_VERSION, loaded.configVersion);
        assertFalse(loaded.enableInstancedLoot, "the migrated value is carried through deserialize");
        // The file was re-saved with the stamped version.
        JsonObject onDisk = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(ProsperityConfigMigrator.CURRENT_VERSION, onDisk.get("configVersion").getAsInt());
    }

    @Test
    void currentVersionFileRoundTripsWithoutResave(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        new ProsperityConfig().save(path);
        String before = Files.readString(path);

        ProsperityConfig.load(path);

        assertEquals(before, Files.readString(path), "a current-version config must not be spuriously re-saved");
    }

    @Test
    void clampStillAppliesAfterMigration(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        // Pre-versioned file with an out-of-range value.
        Files.writeString(path, "{\"indicatorRenderDistance\": -5}");

        ProsperityConfig loaded = ProsperityConfig.load(path);

        assertEquals(ProsperityConfigMigrator.CURRENT_VERSION, loaded.configVersion);
        assertEquals(0, loaded.indicatorRenderDistance, "clamp() runs after migration + deserialize");
    }

    @Test
    void corruptedFileFallsBackWithoutOverwrite(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        String garbage = "{ this is not valid json ";
        Files.writeString(path, garbage);

        ProsperityConfig loaded = ProsperityConfig.load(path);

        assertEquals(48, loaded.indicatorRenderDistance, "defaults returned on parse failure");
        assertEquals(garbage, Files.readString(path), "a syntactically-broken file is preserved untouched");
    }

    @Test
    void emptyFileLoadsDefaults(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("prosperity.json");
        Files.writeString(path, "   ");

        ProsperityConfig loaded = ProsperityConfig.load(path);

        assertEquals(ProsperityConfigMigrator.CURRENT_VERSION, loaded.configVersion);
        assertEquals(48, loaded.indicatorRenderDistance);
    }
}
