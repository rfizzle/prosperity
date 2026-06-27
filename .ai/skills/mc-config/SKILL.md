---
name: mc-config
description: Build and maintain a Concord mod's JSON config the suite way — a GSON POJO with versioned schema migration, warn-and-clamp validation, atomic save, a volatile reloadable singleton, a Cloth/ModMenu editor, and a server→client sync with client-fallback precedence. TRIGGER when creating or editing a *Config.java, *ConfigMigrator.java, a compat/modmenu/ModMenuIntegration.java, or any code that reads mod config, syncs config to clients, or adds a config field/section/migration.
---

The user is adding or changing a mod's configuration. Every Concord mod uses the
same config stack, and the parts that make it robust are easy to get subtly
wrong: the **load lifecycle** (migrate → deserialize → fill → validate → persist),
**schema migration** that carries renamed fields forward instead of dropping
them, **warn-and-clamp validation** that never trusts a hand-edited file, an
**atomic save** that can't truncate the file on a crash, and — for anything
gameplay-affecting — a **server→client sync** so a client honors the server's
rules, not its own local file.

Treat this as a single recipe. A new field touches the POJO, `fillDefaults()`,
`validate()`, the ModMenu screen, and (if it gates gameplay) the sync payload.

## The config object

One plain POJO per mod, GSON-serialized, with nested `static` classes for
sections and a `configVersion` field. Group fields into sections so the JSON
stays navigable and the ModMenu screen has natural categories.

```java
public class TribulationConfig {
    public int configVersion = ConfigMigrator.CURRENT_VERSION;
    public General general = new General();
    public TimeScaling timeScaling = new TimeScaling();
    public Hud hud = new Hud();                 // client-facing section
    // ... one field per section, each its own nested static class
}
```

Split **server-authoritative** fields (gameplay rules, caps, toggles that change
balance) from **client-only** fields (HUD anchor, render toggles). Mercantile
keeps a flat object with a documented split; Prosperity nests the client fields
under a `client` block so "the synced view = everything except `client`" is
structural. Either is fine — pick one and document which side owns each field.

## The load lifecycle

The order is load-bearing. Migration runs on **raw JSON before Gson** so renamed
keys survive; validation runs **after** deserialize so the in-memory object is
always sane; a migrated file is **persisted back** so the on-disk schema catches
up; and a parse failure **never overwrites the user's file**.

```java
static TribulationConfig load(Path path) {
    if (!Files.exists(path)) {                       // first run → write defaults
        TribulationConfig config = new TribulationConfig();
        config.save(path);
        return config;
    }
    try {
        String content = Files.readString(path);
        JsonElement element = JsonParser.parseString(content);
        if (element == null || !element.isJsonObject()) {   // not an object → defaults
            TribulationConfig fresh = new TribulationConfig();
            fresh.save(path);
            return fresh;
        }
        JsonObject raw = element.getAsJsonObject();
        boolean migrated = ConfigMigrator.migrate(raw);     // 1. raw-JSON migration

        TribulationConfig config = GSON.fromJson(raw, TribulationConfig.class); // 2. deserialize
        if (config == null) { /* defaults + save */ }
        config.fillDefaults();                              // 3. null sections → new instances
        config.validate();                                 // 4. clamp every field, logging each fix
        if (migrated) config.save(path);                   // 5. persist the upgraded schema
        return config;
    } catch (JsonSyntaxException e) {
        // 6. corrupt JSON: log, run with defaults, LEAVE THE FILE UNTOUCHED so the
        //    user can fix their typo instead of losing their settings.
        LOGGER.error("Failed to parse config at {}; using defaults (existing file left untouched)", path, e);
        TribulationConfig fallback = new TribulationConfig();
        fallback.fillDefaults();
        fallback.validate();
        return fallback;
    }
}
```

`fillDefaults()` replaces any `null` section/collection with a fresh instance, so
a partial hand-edited file (or one a migration only stubbed out) still has every
sub-object present before `validate()` runs:

```java
private void fillDefaults() {
    if (general == null) general = new General();
    if (general.scalingMode == null) general.scalingMode = ScalingMode.NEAREST; // null-heal a Gson enum
    if (general.excludedEntities == null) general.excludedEntities = new ArrayList<>();
    // ... every section and every nullable field
}
```

## Versioned schema migration

Migrations run on the raw `JsonObject` indexed by from-version, so a renamed or
restructured field is carried forward rather than silently dropped by a lenient
deserialize. This file is near-identical across mods — copy it and grow the
array.

```java
final class ConfigMigrator {
    static final int CURRENT_VERSION = 6;

    @FunctionalInterface interface Migration { void apply(JsonObject json); }

    // Index i = the v(i) → v(i+1) transition. Append only; never reorder.
    private static final Migration[] MIGRATIONS = {
        json -> {},                                              // v0 → v1: baseline tag
        json -> { if (!json.has("hardcoreHearts")) json.add("hardcoreHearts", new JsonObject()); }, // v1 → v2: new section
        // ...
        json -> {                                                // v5 → v6: rename, carrying tuning forward
            JsonElement legacy = json.remove("xpAndLoot");
            if (!json.has("xp") && legacy != null && legacy.isJsonObject()) {
                JsonObject xp = new JsonObject();
                JsonElement mult = legacy.getAsJsonObject().get("xpMultiplier");
                if (mult != null && mult.isJsonPrimitive() && mult.getAsJsonPrimitive().isNumber()) xp.add("xpMultiplier", mult);
                json.add("xp", xp);
            }
        }
    };

    static boolean migrate(JsonObject json) {
        int version = readVersion(json);                         // missing/non-numeric ⇒ 0
        if (version >= CURRENT_VERSION) return false;
        boolean changed = false;
        for (int i = version; i < CURRENT_VERSION && i < MIGRATIONS.length; i++) {
            try { MIGRATIONS[i].apply(json); LOGGER.info("Migrated config from v{} to v{}", i, i + 1); changed = true; }
            catch (Exception e) { LOGGER.warn("Migration v{} to v{} failed; skipping: {}", i, i + 1, e.getMessage()); }
        }
        if (changed) json.addProperty("configVersion", CURRENT_VERSION);
        return changed;
    }
}
```

**To add a migration:** bump `CURRENT_VERSION`, update the default `configVersion`
in the config POJO to match, append the lambda, and add a `ConfigMigratorTest`
case (legacy→new, idempotency, already-current passthrough — see the
`mc-mod-testing` skill).

Migration runs **only on the file-load path**. A config built from
`fromJson`/`toJson` for the ModMenu working copy or the server→client sync is
already current — don't re-run migration on it.

## Warn-and-clamp validation

`validate()` bounds every numeric field and **logs each correction** so a player
sees exactly what their hand-edit did. Use a small set of shared helpers rather
than open-coding `Math.max`/`Math.min` per field:

```java
private static double clampNonNegative(String name, double value) {
    if (value < 0) { LOGGER.warn("{} must be >= 0, got {}; clamped to 0", name, value); return 0; }
    return value;
}
private static double clampUnit(String name, double value) {     // [0,1]
    if (value < 0) { LOGGER.warn("{} must be in [0,1], got {}; clamped to 0", name, value); return 0; }
    if (value > 1) { LOGGER.warn("{} must be in [0,1], got {}; clamped to 1", name, value); return 1; }
    return value;
}
// also: clampPositive (→1), clampAtLeast(name, value, min), clampPercent (→[0,100])
```

`validate()` assigns each field its clamped result (`m.healthRate =
clampNonNegative("...healthRate", m.healthRate)`). Mercantile/Prosperity name the
same method `clamp()` and run it on **every** entry into the object — after load,
and again after the ModMenu screen writes new values — so the in-memory config is
clamped no matter how it was populated.

## Atomic save

Write to a sibling `.tmp` then atomically rename, so a crash mid-write can never
leave a truncated `config.json`. Fall back to a plain move where atomic moves
aren't supported, and clean up the orphan tmp on failure.

```java
void save(Path path) {
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    try {
        Files.createDirectories(path.getParent());
        Files.writeString(tmp, GSON.toJson(this));
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    } catch (IOException e) {
        LOGGER.error("Failed to save config", e);
        try { Files.deleteIfExists(tmp); } catch (IOException cleanup) { LOGGER.warn("orphan tmp {}", tmp, cleanup); }
    }
}
```

## Reloadable singleton

Hold the active config in a `volatile` field behind a double-checked-locked
`get()`, with a `reload()` that rebuilds it (wired to a `/<mod> reload` command).
Readers get a consistent snapshot; a reload swaps the reference atomically.

```java
private static volatile MercantileConfig INSTANCE;

public static MercantileConfig get() {
    MercantileConfig local = INSTANCE;
    if (local == null) synchronized (MercantileConfig.class) {
        local = INSTANCE;
        if (local == null) INSTANCE = local = load();
    }
    return local;
}
public static void reload() { synchronized (MercantileConfig.class) { INSTANCE = load(); } }
```

## Server→client sync with client-fallback precedence

Any config that affects gameplay or what the client *draws based on a server
rule* must be server-authoritative. The server sends its config (see the
`mc-networking` skill for the payload + codec); the client stores it and **every
client feature reads the synced copy first, falling back to its local file only
when not connected to a server that sent one**:

```java
// client state holder
public static @Nullable MercantileConfig getServerConfig() { return serverConfig; }

// every client call site — synced wins, local is the singleplayer/standalone fallback
MercantileConfig config = ClientMercantileData.getServerConfig();
if (config == null) config = MercantileConfig.get();
if (!config.enableTradeCycling) return;
```

Re-broadcast on `/<mod> reload` so a live config change reaches connected
clients. Clear the synced copy on disconnect (`setServerConfig(null)`) so the
next singleplayer world falls back to the local file. The precedence direction is
the whole point: a client must never enable a feature the server disabled.

## ModMenu / Cloth editor

Mirror each field into a Cloth `ConfigBuilder` category, seeding the current value
and a `setDefaultValue` from a fresh `new Config()`, and **re-clamp on save** so
the screen can't write an out-of-range value:

```java
MercantileConfig config = MercantileConfig.get();
MercantileConfig defaults = new MercantileConfig();
reputation.addEntry(entry.startBooleanToggle(Component.translatable("mercantile.config.enableReputation"), config.enableReputation)
        .setDefaultValue(defaults.enableReputation)
        .setSaveConsumer(v -> config.enableReputation = v)
        .build());
// on the builder's save: config.clamp(); config.save();
```

Use `Component.translatable` keys (`<mod>.config.<field>`) for every label and
tooltip; declare ModMenu + Cloth as `modCompileOnly` + `modLocalRuntime` and the
integration class as the `modmenu` entrypoint, so a missing ModMenu can't gate
the mod.

## Best-practice checklist

| Check | What to do |
|---|---|
| Load order | migrate(raw) → deserialize → fillDefaults → validate → save-if-migrated. |
| Corrupt file | Log and run on defaults; **never** overwrite the user's unparseable file. |
| Migration | Raw-JSON, indexed by from-version, append-only; carry renamed fields forward. |
| Add a field | POJO + `fillDefaults` + `validate`/`clamp` + ModMenu entry + sync payload if it gates gameplay. |
| Validation | Clamp every numeric with a logging helper; null-heal enums and collections. |
| Save | Atomic `.tmp` + `ATOMIC_MOVE`, plain-move fallback, orphan cleanup. |
| Singleton | `volatile` + double-checked `get()`; `reload()` swaps the reference. |
| Sync precedence | `getServerConfig()` first, local `get()` fallback; clear on disconnect; re-send on reload. |
| ModMenu | Mirror fields, `setDefaultValue` from a fresh instance, re-clamp on save, translatable keys. |
| Tests | Migration (legacy/idempotent/passthrough), clamp bounds, one disabled-path test per toggle. |

## Guardrails

- **Never** overwrite the user's config file when it fails to parse — log, fall
  back to defaults in memory, and leave the file for them to fix.
- **Never** run migration on a config built from `toJson`/`fromJson` (ModMenu
  working copy, server→client sync) — that JSON is already current.
- **Never** let a client feature read its local config for a server-authoritative
  rule. Read `getServerConfig()` first; local file is only the offline fallback.
- **Never** write the config with a plain `Files.writeString` to the real path —
  a crash mid-write truncates it. Use the `.tmp` + atomic-move dance.
- **Always** bump `CURRENT_VERSION`, the default `configVersion`, and add a
  migration test together — a schema change without a migration test is the #1
  source of silent settings loss on upgrade.
- **Always** clamp after every population path (load and ModMenu save), and log
  each clamp so a player can see what their edit did.
- **Always** wire `reload()` to clamp/validate and re-broadcast to clients.
