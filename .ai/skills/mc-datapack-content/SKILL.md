---
name: mc-datapack-content
description: Datapack-driven content managers for Fabric Minecraft mods — loading gameplay data from data/<modid>/<domain>/*.json at runtime with pack-stack merging, "replace" overrides, error isolation, and an atomic volatile publish. TRIGGER when loading gameplay content (name pools, trade tables, weighted pools, mappings) from datapack JSON at runtime: registering a SimpleSynchronousResourceReloadListener, calling getResourceStack/listResourceStacks, honoring a "replace" key, or deciding between datagen, a code registry, and a runtime content manager.
---

The user is building a content manager: gameplay data that server owners can
override or extend with an ordinary datapack, loaded from
`data/<modid>/<domain>/*.json` on every server start and `/reload`. Three
things decide whether this goes well: **merge semantics** that respect the pack
stack (lower packs merge under higher ones, `"replace": true` wipes what came
before), **error isolation** so one bad pack file never kills the whole load,
and **atomic publication** so game-thread readers never observe a half-built
state. One skeleton handles all of it; every domain is the same class with a
different parser.

## Decision: datagen vs code registry vs content manager

| Data is… | Use | Why |
|---|---|---|
| Static, known at build time (models, recipes, loot tables, tags) | Datagen — see **mc-datagen** | Generated JSON ships in the jar; vanilla loads it through its own systems. |
| A fixed set of ids the code references directly (items, blocks, sounds) | Code registry — see **mc-registration** | Ids must exist at class-load; packs can't add new ones anyway. |
| Gameplay tuning a server owner should override without touching code (pools, tables, mappings) | **Datapack content manager** (this skill) | Runtime-loaded, `/reload`-able, mergeable and replaceable by any datapack. |

If a pack maker changing the values is a feature, it belongs here.

## The skeleton: reload listener on SERVER_DATA

Register a `SimpleSynchronousResourceReloadListener` on `PackType.SERVER_DATA`
with a namespaced fabric id. It fires on server start and every `/reload`.

```java
public final class WidgetPools {
    private static final Gson GSON = new Gson();
    private static final String DATA_PATH = "widget_pools"; // data/mymod/widget_pools/

    private static volatile Map<String, List<WidgetEntry>> POOLS = Map.of();

    public static void init() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return MyMod.id("widget_pools");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        loadPools(manager);
                    }
                }
        );
    }
}
```

Key the data by **filename**: `data/mymod/widget_pools/farmer.json` defines the
`farmer` pool. Two discovery styles, both correct:

- **Fixed key set** — loop a known list of keys and call
  `manager.getResourceStack(MyMod.id(DATA_PATH + "/" + key + ".json"))`.
- **Open key set** — let packs invent keys:
  `manager.listResourceStacks(DATA_PATH, id -> id.getPath().endsWith(".json"))`
  and derive the key from the filename. Subdirectory files keep the same
  filename key (`widget_pools/addon/farmer.json` still merges into `farmer`).

## Merge semantics: iterate the stack, honor "replace", isolate errors

`getResourceStack` / `listResourceStacks` return every pack's copy of a file,
**lowest-priority pack first**. Iterate in order so higher packs merge on top;
a root-level `"replace": true` in a higher pack discards everything below it —
the same contract vanilla tags use, and what pack makers expect.

```java
public static void loadPools(ResourceManager manager) {
    Map<String, List<WidgetEntry>> next = new HashMap<>();

    Map<ResourceLocation, List<Resource>> found = manager.listResourceStacks(
            DATA_PATH, id -> id.getPath().endsWith(".json"));

    for (var fileEntry : found.entrySet()) {
        ResourceLocation fileId = fileEntry.getKey();
        String path = fileId.getPath();
        String key = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);

        List<WidgetEntry> merged = next.computeIfAbsent(key, k -> new ArrayList<>());

        for (Resource resource : fileEntry.getValue()) {   // lowest pack first
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.open(), StandardCharsets.UTF_8))) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json == null) continue;

                if (json.has("replace") && json.get("replace").getAsBoolean()) {
                    merged.clear();                        // higher pack takes over
                }

                if (json.has("entries")) {
                    for (JsonElement element : json.getAsJsonArray("entries")) {
                        WidgetEntry parsed = parseEntry(element, fileId);
                        if (parsed != null) merged.add(parsed);   // warn-and-skip inside
                    }
                }
            } catch (Exception e) {
                MyMod.LOGGER.error("Failed to load widget pool: {}", fileId, e);
            }
        }
    }

    // publish + summary log — see below
}
```

The rules baked into that loop:

- **Per-file try/catch** around each resource: log with the file id and move
  on. One malformed pack file costs that file, never the load.
- **Per-entry warn-and-skip** inside `parseEntry`: a malformed entry (or an id
  that doesn't resolve — the most common pack-maker typo) logs a warning
  naming the file and the value, returns `null`; valid siblings still land.
- **Dedupe on merge** when entries are value-keyed (e.g. name strings): track a
  `HashSet` of what's already merged and only append unseen values, so a pack
  that re-lists a default doesn't double it.

## Publication: build everything, then one volatile swap

Readers (entity events, trade hooks) can hit the maps from game logic at any
tick, so never mutate the live map. Build the whole `next` state locally,
deep-copy list/set values, and publish with a single write of a `volatile`
field holding an immutable map — the volatile-swap idiom from
**mc-shared-state**:

```java
    Map<String, List<WidgetEntry>> immutable = new HashMap<>();
    for (var e : next.entrySet()) {
        immutable.put(e.getKey(), List.copyOf(e.getValue()));
    }
    int keyCount = immutable.size();
    int entryCount = immutable.values().stream().mapToInt(List::size).sum();

    POOLS = Map.copyOf(immutable);   // the only write readers can observe

    MyMod.LOGGER.info("Loaded {} widget entries across {} pools", entryCount, keyCount);
}
```

Compute the summary counts from the local accumulator *before* the swap, not
by reading back the field; readers that need mutually consistent lookups copy
the field to a local once. Exactly **one summary info line per manager per
load** — the operator's confirmation that their pack took effect. Everything
else is warn/error.

## Shipping defaults

The mod's own jar is the bottom of the pack stack: ship defaults at
`src/main/resources/data/mymod/<domain>/` and they merge under every user
datapack automatically. Conventions that hold up:

- **Per-key files** (`farmer.json`, `librarian.json`, …) so a pack maker
  overrides one key without copying the rest.
- **Sibling-conditional subdirectories** for content that only applies when a
  companion mod is present: `data/mymod/widget_pools/othermod/farmer.json`,
  gated by a root-level `fabric:load_conditions` array
  (`fabric:all_mods_loaded` on the sibling's id). The manager skips the file
  *silently* on a failed condition — absent by design, not malformed. The
  subdirectory is organizational; the filename still keys it.

## Testing the parse layer with plain JUnit

Keep parsing a static function from `JsonObject` (or a `Reader`) to your
entry type, and the load loop callable with any `ResourceManager`. Then unit
tests feed JSON text straight in — no Fabric runtime, no game client:

```java
@Test
void malformedEntryIsSkippedNotFatal() {
    WidgetEntry entry = WidgetPools.parseEntry(
            GSON.fromJson("{ \"weight\": \"not-a-number\" }", JsonObject.class),
            ResourceLocation.parse("mymod:widget_pools/farmer.json"));
    assertNull(entry);
}
```

Pure shape/field parsing needs nothing extra. Parsers that resolve registry
ids need `SharedConstants.tryDetectVersion()` + `Bootstrap.bootStrap()` in
`@BeforeAll` — vanilla bootstrap, still no Fabric game runtime. Merge,
replace, and dedupe semantics test through a tiny fake `ResourceManager`
whose resources open `ByteArrayInputStream`s of JSON strings in pack order.
Cover at minimum: happy path, `"replace": true` clearing lower packs,
duplicate dedupe, and a malformed entry skipped while its siblings survive.

## Guardrails

- **Always** register on `PackType.SERVER_DATA` with a namespaced
  `getFabricId()`; never load gameplay data from `CLIENT_RESOURCES`.
- **Always** iterate the full resource stack. Reading only the top resource
  silently discards shipped defaults or user additions — merge is the contract.
- **Always** honor root-level `"replace": true` by clearing what lower packs
  contributed, and document the key in the file format.
- **Never** let one file's exception escape the load loop — per-file
  try/catch, log with the file id, continue.
- **Never** mutate the published map. Build `next` locally, deep-copy values,
  publish via one `volatile` write of `Map.copyOf(...)`.
- **Always** emit exactly one summary info line per manager per load, counted
  from the local accumulator before the swap — never read the field back.
- **Always** keep `parseEntry` a static JSON-in/value-out function so the
  parse layer tests without a Fabric runtime.
