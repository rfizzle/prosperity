---
name: mc-config
description: Build and maintain a Concord mod's JSON config the suite way — a GSON POJO with versioned schema migration, warn-and-clamp validation, atomic save, a volatile reloadable singleton, a Cloth/ModMenu editor, and a server→client sync with client-fallback precedence. TRIGGER when creating or editing a *Config.java, *ConfigMigrator.java, a compat/modmenu/ModMenuIntegration.java, or any code that reads mod config, syncs config to clients, or adds a config field/section/migration.
---

The user is adding or changing a mod's configuration. Every Concord mod uses the
same config stack, and the parts that make it robust are easy to get subtly
wrong: the **load lifecycle** (migrate → deserialize → fill → clamp → persist),
**schema migration** that carries renamed fields forward instead of dropping
them, **warn-and-clamp validation** that never trusts a hand-edited file, an
**atomic save** that can't truncate the file on a crash, and — for anything
gameplay-affecting — a **server→client sync** so a client honors the server's
rules, not its own local file.

Treat this as a single recipe. A new field touches the POJO, `fillDefaults()`,
`clamp()`, the ModMenu screen, and (if it gates gameplay) the sync payload.

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
        config.clamp();                                    // 4. clamp every field, logging each fix
        if (migrated) config.save(path);                   // 5. persist the upgraded schema
        return config;
    } catch (JsonSyntaxException e) {
        // 6. corrupt JSON: log, run with defaults, LEAVE THE FILE UNTOUCHED so the
        //    user can fix their typo instead of losing their settings.
        LOGGER.error("Failed to parse config at {}; using defaults (existing file left untouched)", path, e);
        TribulationConfig fallback = new TribulationConfig();
        fallback.fillDefaults();
        fallback.clamp();
        return fallback;
    }
}
```

`fillDefaults()` replaces any `null` section/collection with a fresh instance, so
a partial hand-edited file (or one a migration only stubbed out) still has every
sub-object present before `clamp()` runs:

```java
private void fillDefaults() {
    if (general == null) general = new General();
    if (general.scalingMode == null) general.scalingMode = ScalingMode.NEAREST; // null-heal a Gson enum
    if (general.excludedEntities == null) general.excludedEntities = new ArrayList<>();
    // ... every section and every nullable field
}
```

## Initialization order

Config loads **first** in `onInitialize()`, ahead of every registration, event
hook, and manager. Registration bodies read config to decide what to register
and with what tuning, so anything that runs before the load either reads `null`
or bakes in a default the file overrides.

```java
@Override
public void onInitialize() {
    MercantileConfig.get();          // eager first load: writes defaults on first launch
    MercantileNetworking.registerPayloads();
    MercantileEvents.register();
    // ...
}
```

Touching `get()` on the first line does double duty: it primes the singleton and
it writes `config/<mod>.json` with defaults on a fresh install, so a player has a
file to edit before they ever open ModMenu.

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

The entry point is **`clamp()`** — one public method on the config object that
bounds every numeric field and assigns each field its clamped result
(`m.healthRate = clampNonNegative("...healthRate", m.healthRate)`). It touches
nothing but its own fields and a `LOGGER`, so it tests at Tier 1 — but the
logging is synchronous I/O and the cost scales with any user-extensible map the
config holds, so it is neither free nor thread-agnostic. See the sync section
below for where that matters.

`clamp()` runs after **every** path that populates the object — after a file
load, after the ModMenu screen writes new values, and after a sync payload is
decoded. A config that reached memory by any route is a config that has been
clamped.

Bound each field through a shared helper rather than open-coding
`Math.max`/`Math.min` per field. Three properties are required of that helper
set; the naming family is yours to pick, and a richer set is welcome.

**1. Log every correction**, so a player can see what their hand-edit did.

**2. Reject non-finite input in floating-point fields.** Gson yields a
non-finite `double` from a bare `NaN` or `Infinity` token, from their quoted
forms, and from any legal-JSON overflow like `1e400`. `Math.clamp`, `Mth.clamp`,
and a bare `value < min` test all pass `NaN` through, because `NaN` is false
against every *ordering* comparison. A negated lower bound folds `NaN` into the
underflow branch — but `+Infinity` satisfies every lower bound, so a helper with
no finite ceiling still needs an explicit `isFinite` gate:

```java
private static double clampNonNegative(String name, double value) {          // open-topped: needs both guards
    if (!(value >= 0) || !Double.isFinite(value)) {
        LOGGER.warn("{} must be a finite value >= 0, got {}; clamped to 0", name, value);
        return 0;
    }
    return value;
}

private static double clampRange(String name, double value, double min, double max) {  // finite ceiling: the bounds suffice
    if (!(value >= min)) { LOGGER.warn("{} must be in [{}, {}], got {}; clamped to {}", name, min, max, value, min); return min; }
    if (value > max)     { LOGGER.warn("{} must be in [{}, {}], got {}; clamped to {}", name, min, max, value, max); return max; }
    return value;
}
```

Folding `NaN` to the minimum is one valid answer; restoring the field's default
is another, and often the better one — a rate field whose minimum is `0` is
disabled by the first and healed by the second. Pick per field.

This is a floating-point rule. Gson rejects `NaN` into an `int` or `long` field
before any clamp runs, so `Math.clamp` is fine on integral fields.

**3. Cover both integral and floating-point fields.** A single helper returning
`double` forces lossy casts at `int` call sites; provide an `int` flavor
alongside the `float`/`double` one.

Beyond those three, pick the shape that reads best against your fields —
semantic helpers (`clampNonNegative`, `clampUnit`, `clampPercent`,
`clampAtLeast`) or generic ranges (`clampInt(name, value, min, max)`). Semantic
names carry the intent to the call site and are the better default when a bound
repeats across many fields.

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

The active config lives in one `static volatile` field. Five guarantees make it
safe to read from the server tick, a netty thread, and the render thread at once:

| Guarantee | Why |
|---|---|
| The field is `volatile` | A reload on one thread is visible to readers on every other. |
| `get()` is **lazy**, double-checked-locked | Any caller reaching config before `onInitialize` gets a loaded object, not `null`. |
| `reload()` swaps the **whole reference** under the same lock | One `get()` call returns a whole snapshot, never a half-applied edit. |
| Readers **snapshot `get()` once per method** | The atomicity above is per call, not per method: two `get()` calls straddling a reload mix generations. |
| The live instance is **never mutated in place** | A reader mid-tick cannot observe a field the editor has written and another it has not. |

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

/** The commit point for an edited copy — clamps, persists, then swaps in one store. */
public static void publish(MercantileConfig next) {
    next.clamp();
    next.save(configPath());
    synchronized (MercantileConfig.class) { INSTANCE = next; }
}
```

Which class holds the field is free — the config class itself, or the mod's main
class delegating through a `getConfig()`. Both satisfy the guarantees as long as
the accessor is the lazy double-checked form. A bare `return config;` over an
eagerly assigned field does not: it returns `null` to anything running before the
entrypoint — a mixin in a static initializer, a `ModInitializer` ordered ahead of
yours — and the failure is an NPE at a call site with no way to defend itself.
The lazy form costs one well-predicted branch and removes the class of bug.

The snapshot guarantee is the one hot paths care about. Each `get()` is a
volatile read, which the JIT cannot hoist out of a loop or merge with another, so
a method calling it four times performs four un-mergeable loads that would
collapse to one local:

```java
MercantileConfig cfg = MercantileConfig.get();   // snapshot once, thread the local through
if (!cfg.enablePathfindingFixes) return;
if (!cfg.enablePathfindingLadders) return;
```

This is the config-shaped case of the volatile-snapshot rule in the
`mc-shared-state` skill.

The last guarantee is what the ModMenu screen must respect. Editing `get()`'s
return value in place publishes each keystroke to live readers one field at a
time — and a non-volatile `double` write can even tear, handing a reader a value
nobody assigned. Edit a **deep copy** and publish it in one swap:

```java
MercantileConfig working = MercantileConfig.get().copy();   // deep copy — never the live instance
// ... Cloth save consumers write into `working` ...
MercantileConfig.publish(working);                          // on the builder's save
```

Give the config an explicit `copy()` rather than round-tripping through JSON: a
mod with a separate sync serializer has two `toJson`-shaped methods, and copying
through the sync one silently drops the client-only sections, which
`fillDefaults()` then resets to stock on save.

`publish()` is last-write-wins against a concurrent `/<mod> reload` — the working
copy is a snapshot from the moment the screen opened, so saving it discards any
reload that landed since. Where that matters, have `publish()` write the file and
then call `reload()`, leaving exactly one path that assigns `INSTANCE`.

Wire `reload()` to a `/<mod> reload` command, and re-broadcast to clients after
it (see the sync section below).

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

The payload is a `ConfigSyncPayload` carrying the serialized config as one
length-bounded string, registered under `<mod>:config_sync` — see the
`mc-networking` skill for the record, the codec, and the size cap. Serialize it
with a **compact** `Gson`: pretty-printing costs roughly 40% of the wire for
nothing.

`clamp()` the decoded object before publishing it to the client holder — the
bytes came off the network, and a server running a different build can send a
value this client's bounds reject. Run that clamp inside `client.execute(...)`,
never in `decode()` or the netty callback. `clamp()` emits one synchronous log
line per correction, and on this path a remote peer controls both how many
corrections there are and how often they arrive: a config packed with
out-of-range map entries, re-sent in a loop, is a write primitive against the
client's log file and its netty event loop. Bound it — log the first handful of
corrections plus an "…and N more" summary, and drop the rest to `debug`. A
hand-edited local file has earned a line per field; a remote server has not.

## ModMenu / Cloth editor

Mirror each field into a Cloth `ConfigBuilder` category, seeding the current
value from a **deep working copy** and a `setDefaultValue` from a fresh
`new Config()`, and **publish the copy on save** so the screen never writes into
the live instance:

```java
MercantileConfig working = MercantileConfig.get().copy();   // deep copy — never the live instance
MercantileConfig defaults = new MercantileConfig();
builder.setSavingRunnable(() -> MercantileConfig.publish(working));   // clamps, saves, one swap
reputation.addEntry(entry.startBooleanToggle(Component.translatable("config.mercantile.enableReputation"), working.enableReputation)
        .setDefaultValue(defaults.enableReputation)
        .setSaveConsumer(v -> working.enableReputation = v)
        .build());
```

Use `Component.translatable` keys for every label and tooltip, under the
`config.<mod>.*` prefix the suite's key vocabulary assigns to config surfaces
(`design/DESIGN-SYSTEM.md`):

| Key | Surface |
|---|---|
| `config.<mod>.title` | The screen title |
| `config.<mod>.category.<name>` | A category tab |
| `config.<mod>.<field>` | A field label |
| `config.<mod>.<section>.<field>` | A field label in a sectioned config; the section segment matches the JSON section |
| `config.<mod>.<field>.tooltip` | That field's tooltip — every label pairs with one |
| `config.<mod>.<field>.<option>` | One value of an enum field — required wherever a field renders as a dropdown (`HUD-STANDARD.md` §4) |

Declare ModMenu + Cloth as `modCompileOnly` + `modLocalRuntime` and the
integration class as the `modmenu` entrypoint, so a missing ModMenu can't gate
the mod.

## Best-practice checklist

| Check | What to do |
|---|---|
| Load order | migrate(raw) → deserialize → fillDefaults → clamp → save-if-migrated. |
| Corrupt file | Log and run on defaults; **never** overwrite the user's unparseable file. |
| Migration | Raw-JSON, indexed by from-version, append-only; carry renamed fields forward. |
| Add a field | POJO + `fillDefaults` + `clamp` + ModMenu entry + sync payload if it gates gameplay. |
| Init order | Config loads first in `onInitialize()`, ahead of every registration. |
| Validation | `clamp()` every numeric with a logging helper; guard floating-point fields against non-finite input; null-heal enums and collections. |
| Save | Atomic `.tmp` + `ATOMIC_MOVE`, plain-move fallback, orphan cleanup. |
| Singleton | `volatile` field + lazy double-checked `get()`; `reload()` swaps the whole reference; snapshot `get()` once per method; never mutate the live instance. |
| Sync precedence | `getServerConfig()` first, local `get()` fallback; clear on disconnect; re-send on reload. Clamp the decoded config on the main thread, with bounded logging. |
| ModMenu | Mirror fields into a deep working copy, `setDefaultValue` from a fresh instance, re-clamp and publish on save, `config.<mod>.*` keys. |
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
- **Never** let a non-finite value reach a floating-point field. `Math.clamp`,
  `Mth.clamp`, and a bare `value < min` test all pass `NaN` through, and Gson
  yields non-finite doubles from `NaN`/`Infinity` tokens and from overflows like
  `1e400`. Use a negated lower bound (`!(value >= min)`) or an `isNaN` test that
  restores the default, and give every open-topped helper an `isFinite` gate —
  `+Infinity` satisfies any lower bound, and it throws on the next `toJson`.
  `Math.clamp` is fine for `int`/`long`: Gson rejects `NaN` there before any
  clamp runs.
- **Never** mutate the live config instance in place — not from the ModMenu
  screen, not from a command. Edit a deep copy and publish it in one swap, or a
  reader mid-tick sees half your edit.
- **Never** call `get()` twice in one method, or inside a loop. Snapshot it once
  and thread the local through: each call is a volatile read the JIT cannot hoist
  or merge, and two of them can land on opposite sides of a `/<mod> reload`.
- **Never** hand a caller config from an eagerly assigned field with a bare
  `return config;`. Make `get()` lazy and double-checked, so a caller that runs
  before your entrypoint gets a config instead of an NPE.
- **Never** clamp a decoded sync payload on the netty thread, and never log a
  line per correction there — a remote peer chooses how many corrections it sends
  and how often. Clamp in `client.execute(...)` with bounded logging.
- **Always** clamp after every population path — load, ModMenu save, and sync
  payload decode — and log each clamp so a player can see what their edit did.
- **Always** load config first in `onInitialize()`; registration bodies read it.
- **Always** wire `reload()` to clamp and re-broadcast to clients.
