---
name: mc-mod-testing
description: Write and maintain tests for Fabric Minecraft mods across the three-tier test spectrum (pure JUnit, fabric-loader-junit, Fabric Gametest), and account for coverage honestly. TRIGGER when creating or editing *Test.java, *GameTest.java, or when the user asks about testing a Minecraft mod, fabric-loader-junit, Fabric Gametest, test coverage, or guarding shipped resources (lang keys, models, textures) with tests.
---

The user is writing or modifying tests in a Fabric mod. Apply this guidance whenever test code is being touched.

## Decision tree — pick one tier per test

Ask these in order and stop at the first "yes":

1. **Does the test reference any `net.minecraft.*` or `net.fabricmc.*` class?**
   No -> **Tier 1: Pure JUnit**. Normal `@Test`, no framework, no bootstrap.

2. **Does the test need a real `ServerLevel`, tick loop, entity behavior, block placement, or redstone?**
   Yes -> **Tier 3: Gametest**. `@GameTest` with `GameTestHelper`. Runs via `./gradlew runGametest`.

3. **Does the test need the mod's own registered content** (custom items, blocks, block entities)?
   Yes -> **Tier 3: Gametest**. fabric-loader-junit does not run `onInitialize`.

4. **Everything else** (vanilla registries, enchantments, payload codecs, mixin accessors, AW-widened members) -> **Tier 2: fabric-loader-junit** + explicit `Bootstrap.bootStrap()`.

### Quick routing cheat sheet

| What you're testing | Tier |
|---------------------|------|
| Pure math, config parsing, utility methods | 1 |
| Shipped-resource contracts (lang JSON, model JSON, texture presence) | 1 |
| Codec round-trip on vanilla types | 2 |
| Vanilla registry lookups (`Items.DIAMOND`, `Attributes.MAX_HEALTH`) | 2 |
| Attribute computation on vanilla `AttributeMap` | 2 |
| StreamCodec encode/decode for custom payloads | 2 |
| Mixin accessor reads on vanilla classes | 2 |
| Block interaction, menu open/close flow | 3 |
| Hopper transfer into mod block entity | 3 |
| Custom recipe matching in a real crafting context | 3 |
| Enchantment behavior on a real entity | 3 |
| Any test needing mod-registered items/blocks | 3 |

## Architect for Tier 1: pure core, thin Minecraft shell

The single biggest lever on a mod's testability is **where the logic lives**, not
which framework runs it. Across the suite, every well-tested subsystem splits into
a **pure core** — decision and math logic with no `net.minecraft.*` types — behind
a **thin Minecraft shell** that wires the core to the game. The core tests at
Tier 1 (fast, no bootstrap); the shell gets a handful of Tier 3 gametests for the
wiring. This is *why* the mods carry ~25 unit-test classes each instead of pushing
everything to slow gametests.

The same split is the suite's multi-version insurance. A pure core references no
`net.minecraft.*` or `net.fabricmc.*` types, so mapping renames, Minecraft version
bumps, and any future multi-version targeting (Stonecutter) or loader split touch
only the thin shells — logic buried in an event handler has to be re-verified per
version; logic in a pure core ports untouched. Extract pure logic whenever a seam
allows it: every line moved from shell to core is a line that is unit-testable
today and version-portable tomorrow.

The move is to **take the game objects as plain parameters and return a plain
result**, so the same method a gametest would exercise through a real entity is
callable from a unit test with primitives:

```java
// Shell (server-coupled): reads the world, raises the dirty flag, calls the core.
public int incrementTick(UUID uuid, int amount, int levelUpTicks, int maxLevel) {
    PlayerData pd = getPlayerData(uuid);
    int old = pd.level;
    int gained = applyTicks(pd, amount, levelUpTicks, maxLevel);   // ← pure core
    if (pd.level != old) setDirty();
    return gained;
}

// Core (pure): no MC types, unit-tested directly with primitives.
static int applyTicks(PlayerData pd, int amount, int levelUpTicks, int maxLevel) { /* math only */ }
```

Recurring seams worth extracting:

- **Scaling/economy math** — `ScalingEngine.computeTimeFactor(...)`, `LootScaling.scaledCount(...)`:
  axis functions take doubles, return doubles.
- **State transitions** — `applyTicks` / `applyReduce` on a raw data object, with
  the `setDirty()`/event/sync left to the shell.
- **Render math** — `HudMath` (anchor/color lerp), `IndicatorMath` (fade/bob/cone)
  live in `src/main` precisely so `src/test` can cover them without the client.
- **Formatters** — row/stat-line formatters extracted from screens
  (`LibraryRowFormatter`, `StatLineFormatter`) test as string-in/string-out.
- **Command bodies** — split the effect from the Brigadier wiring (e.g.
  `runReload(Runnable, MessageSink)`) so the behavior tests without a command stack.
- **Config validation** — `clamp()` is a pure POJO method (see the `mc-config`
  skill); test the bounds at Tier 1, including that a non-finite field is
  clamped rather than passed through.

A method that takes a `ServerLevel` only to read one number, or a screen that bakes
its formatting into `render()`, has hidden a Tier-1-testable core inside a Tier-3
shell. Pull the core out. When you find yourself reaching for Tier 3 to test pure
logic, that's the signal the logic is in the wrong place.

## Coverage accounting

Coverage is measured over `src/main` with the **merged** unit + gametest JaCoCo
report (`jacocoMergedReport` — build wiring in the `mc-gradle-builds` skill).
The unit-test-only `jacocoTestReport` badly undercounts a Fabric mod: every line
only a gametest exercises reads as 0%, which makes exactly the runtime-heavy
packages (events, commands, registration) look untested when they aren't. Never
diagnose coverage from the unit-test-only number.

- **Target: ≥80% merged line coverage** on `src/main`. Suite mods sit in the
  70s from their existing test suites once the merge is wired; the last stretch
  comes from pure-core extraction (above), not from more gametests.
- **The per-package readout is the work list.** A package far below the mod's
  average is usually a shell holding a hidden pure core — extract and unit-test
  it. Reach for a new gametest only when the uncovered code is genuinely wiring.
- **Honest exclusions only.** Mixin classes are excluded mechanically (their
  bodies execute inside transformed vanilla classes, so the agent cannot
  attribute them — behavioral coverage comes from gametests on the features
  they enable). A compat shim for a viewer that is absent from the dev/gametest
  runtime (an API-only dependency like WTHIT) can never execute in any test
  tier; accept it as a small residual miss or exclude that one shim. Never
  exclude a package because it is inconvenient to test — exclude only what a
  test provably cannot reach.
- **`src/client` is out of scope for the number.** Client rendering only
  executes in a live client; its testable parts are the pure math/formatter
  classes, which live in `src/main` precisely so Tier 1 covers them.

## Tier 1: Pure JUnit

Location: `src/test/java/`

```java
package com.example.mymod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScalingEngineTest {
    @Test
    void timeFactor_cappedBeforeMaxLevel() {
        assertEquals(2.5, ScalingEngine.computeTimeFactor(500, 0.01, 2.5), 1e-9);
    }
}
```

No Minecraft imports, no framework, no bootstrap. Fast. Run with:
```bash
./gradlew test --tests "com.example.mymod.ScalingEngineTest" 2>&1
```

### Resource-contract guards

Shipped resources drift silently: a renamed lang key, a config entry without a
tooltip, a model whose `layer0` points at a moved texture. All of it compiles
fine, datagen never sees it, and the failure only shows up in-game — a raw
translation key on a Cloth screen, a black-purple checker on an item. Guard
these with plain JUnit tests that parse the shipped JSON and enforce the
convention. They run in milliseconds with no Fabric runtime and turn
suite-standard prose into an executable regression gate.

The canonical loader reads off the test classpath (where `src/main/resources`
lands), with a file-path fallback. Read the classpath *first* — Gradle's
up-to-date checks track the test task's classpath, so a resource edit invalidates
a classpath-reading test and reruns it, while a test that reaches for
`src/main/resources` by path is invisible to that tracking: the resource changes,
the task stays `UP-TO-DATE`, and the guard goes quiet exactly when it should
fire. This is the same input-tracking mechanism the gametest registration guard
has to declare explicitly (see "Registering the suite" below); reading the
classpath gets it for free. The path fallback exists for IDE runners that launch
without the processed-resources directory on the classpath — it is a fallback,
never the primary read.

```java
private static final String RESOURCE = "/assets/mymod/lang/en_us.json";
private static final Path SOURCE = Path.of("src/main/resources/assets/mymod/lang/en_us.json");

private static JsonObject lang() {
    try (InputStream in = LangResourceContractTest.class.getResourceAsStream(RESOURCE)) {
        String json = in != null
                ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(SOURCE, StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    } catch (IOException e) {
        throw new AssertionError("could not load en_us.json", e);
    }
}
```

What to pin — pick the contracts the mod actually relies on:

- **Config lang contract** — every `config.<mod>.*` field label has a matching
  non-blank `.tooltip` key, so the Cloth screen never renders a raw key. Collect
  all misses into a list before asserting, so the failure names exactly which
  keys are missing (exclude `.title`, `.category.*`, enum option labels, and
  `.tooltip` keys themselves from the label sweep — see the `mc-config` skill for
  the full key vocabulary):

  ```java
  @Test
  void everyConfigLabelHasATooltip() {
      JsonObject lang = lang();
      List<String> missing = new ArrayList<>();
      for (String key : lang.keySet()) {
          if (!isConfigLabel(key)) continue;
          if (!lang.has(key + ".tooltip")) missing.add(key + ".tooltip");
      }
      assertTrue(missing.isEmpty(), "Config entries missing a .tooltip lang key: " + missing);
  }
  ```

- **Key-prefix conventions** — sweep every key: allowed surface prefixes
  (`config.` / `message.` / `notification.` / `hud.` / `command.` /
  `advancements.` / `key.`), `assertFalse(key.startsWith(...))` bans on retired
  prefixes, and standard-mandated strings — the suite's ✦ notification glyph,
  the `"Show <Domain> HUD"` badge-toggle label.
- **Per-registered-id lang coverage** — a `@TestFactory` mapping the mod's id
  roster to `DynamicTest`s: every block id has `block.<mod>.<id>` *and* its
  purpose-line key, both non-blank. One dynamic test per id makes the failing
  id readable in the report.
- **Model/texture integrity** — the item model parents the right vanilla model
  (`minecraft:item/generated` for flat 2D items), `layer0` resolves to the
  expected texture path, and the referenced `.png` actually exists on the
  classpath (`assertNotNull(getResourceAsStream(...))`). This catches the
  model-texture drift that datagen never sees.
- **Dynamically built keys** — when code assembles keys at runtime
  (`baseKey + ".hint"`), a rename orphans them invisibly to the compiler.
  Assert the assembled keys exist and carry the expected `%s` arg count.

What NOT to pin: exact copy text (churns with every wording pass), key order
in the JSON file, or values datagen already guarantees.

## Tier 2: fabric-loader-junit

Location: `src/test/java/`

### What it gives you
- Knot classloader applies mixins and access wideners
- `Bootstrap.bootStrap()` populates `BuiltInRegistries`, `Attributes`, `Items`, etc.
- Does **not** run `onInitialize` — mod-registered content is absent
- Does **not** start a server or create a `Level`

### Template
```java
package com.example.mymod;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnchantmentCodecTest {
    @BeforeAll
    static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaItemsAvailable() {
        assertNotNull(net.minecraft.world.item.Items.DIAMOND_SWORD);
    }
}
```

### Required dependency
```groovy
testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
```

### testRuntimeClasspath exclusion

With `splitEnvironmentSourceSets`, Loom leaves an unmapped fabric-api sibling on `testRuntimeClasspath` that carries an intermediary-namespace access widener. fabric-loader-junit rejects it. Fix:

```groovy
configurations.testRuntimeClasspath {
    exclude group: 'net.fabricmc.fabric-api', module: 'fabric-api'
}
```

### Tier 2 sweet spot

Bridge tests that prove pure-math results land correctly on real vanilla objects. Example: verifying a computed attribute factor produces the expected `getMaxHealth()` when applied to a real vanilla `AttributeMap`.

## Tier 3: Fabric Gametest

Location: `src/gametest/java/`

### Template
```java
package com.example.mymod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class MyFeatureGameTest implements FabricGameTest {
    @GameTest(template = "mymod:empty_3x3")
    public void placeAndVerifyBlock(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, MyRegistry.MY_BLOCK);
        helper.assertBlockPresent(MyRegistry.MY_BLOCK, pos);
        helper.succeed();
    }
}
```

### Registering the suite

Entrypoints go in a second manifest at `src/gametest/resources/fabric.mod.json`, declaring a
separate `<modid>-gametest` mod that depends on the main mod. Never in the shipped
`src/main/resources/fabric.mod.json`: Loom's dev runtime pulls in `fabric-gametest-api-v1`,
whose `main` entrypoint is ungated and instantiates every declared `fabric-gametest` class on
every dev launch. The default `server` run set does not carry the gametest source set, so
`runServer` dies with a `ClassNotFoundException`. A released jar is unaffected — the shipped
`fabric-api` does not bundle `fabric-gametest-api-v1`, so dangling entries there are inert —
which is why this survives unnoticed until someone runs the dev server.

```json
{
    "schemaVersion": 1,
    "id": "mymod-gametest",
    "version": "1.0.0",
    "name": "MyMod Gametests",
    "description": "Fabric gametests for MyMod. Present only on the gametest run classpath — never bundled in the shipped jar.",
    "environment": "*",
    "entrypoints": {
        "fabric-gametest": ["com.example.mymod.gametest.MyFeatureGameTest"]
    },
    "depends": {
        "mymod": "*"
    }
}
```

`depends` needs only the main mod. Loader, Minecraft, Java, and Fabric API are all enforced
transitively — the main mod cannot load without them — and restating their version floors here
creates a second place to update on every Minecraft bump. The manifest carries no `icon`,
`mixins`, `accessWidener`, or `provides`.

Every dev run set is affected, not just `server`. A `source sourceSets.gametest` line in the
`datagen` run block is this same problem papered over one run set at a time — it buys a passing
datagen by putting the whole suite on that classpath too. With the entrypoints in their own
manifest, no run set needs it.

**Literal version string:** the gametest manifest hard-codes its version. `processResources` is
configured for the `main` source set only; `processGametestResources` is an unconfigured
`ProcessResources` task, so a `${version}` placeholder is copied through untouched and reaches
the loader as that literal string, which is not a valid version.

**Registration fails silently.** An unregistered `FabricGameTest` class does not warn — it
simply never runs, and a suite can rot for months looking green. Guard it with a Tier 1 test
that walks `src/gametest/java` and compares the suites on disk against the entrypoints declared
in the gametest manifest, failing in both directions so a deleted class is caught alongside an
unregistered one. Walk the tree rather than listing one directory, or suites in subpackages
slip past.

#### The canonical guard

One shape, so a reader moving between mods finds the same file saying the same thing:

- **Class name and location** — `GametestRegistrationTest`, at
  `src/test/java/com/rfizzle/<mod>/GametestRegistrationTest.java`. It is a Tier 1 test that
  reads the gametest *source tree*, so it belongs in the mod's root test package, not in a
  `gametest` subpackage of `src/test` — nothing in `src/test/java` is part of the gametest
  source set. Not `GametestEntrypointTest`, `ManifestEntrypointTest`, or `ManifestContractTest`;
  a guard nobody can find by name is a guard nobody ports to the next mod.
- **Detection basis** — `implements FabricGameTest`, matched as
  `Pattern.compile("implements\\s+[^{]*\\bFabricGameTest\\b")`. Not a filename suffix, and not
  an annotation regex. Match the pattern, not the literal string: a suite declaring
  `implements Tickable, FabricGameTest` is invisible to a plain `contains("implements
  FabricGameTest")`. A suite that inherits the interface from an abstract base is rejected by
  this basis — that is deliberate, since the manifest needs the concrete class named anyway.
- **Four core assertions** — every suite on disk is registered; every registered entrypoint
  resolves to a class on disk; the shipped `src/main/resources/fabric.mod.json` declares no
  `fabric-gametest` entrypoints at all; the gametest manifest's `depends` is exactly the main
  mod and nothing else. Assert that last one as set equality, not containment — a containment
  check passes while a stray Fabric API version floor rots in the manifest.
- **The two-way naming check** — a class that implements `FabricGameTest` but is not named
  `*GameTest` fails, and a class named `*GameTest` that does not implement it fails too.

Detection basis is the part that decides whether the guard works at all. The gametest source
set holds helpers too, so a guard that treats every class as a suite flags the helpers as
unregistered, and registering one to quiet the guard hands the ungated initializer a class that
is not a test. Match on a bare filename suffix instead and the hole runs the other way: a suite
named `BrewingTests` is missing from both sides of the comparison at once, so the guard stays
green while the tests never run. An annotation regex has a third failure mode — unless it is
line-anchored (`(?m)^\s*@GameTest`), it matches `@GameTest` inside a comment or a string
literal and registers a class that holds no tests. `implements FabricGameTest` has none of
these holes: it is the same predicate the loader itself uses.

Reference implementation: cultivation's `GametestRegistrationTest.java:52-53` for the detection
regex and `:128-148` for the two-way naming check. Distillation's `ManifestEntrypointTest.java`
is worth reading for the assertion *shapes* — `:56-78` and `:103-123` cover all four, and it is
the only carrier that collapses registration parity into a single `assertEquals(discovered,
declared)`. Read it for those and nothing else: its class name is one of the ones ruled out
above, and its detection basis is the annotation regex this section rules out, so it is not a
model for either.

Four of the six existing guards already assert dependency exclusivity as set equality:
distillation `:103-113`, meridian `:175`, mercantile `:92`, instinct `:96`. Tribulation's
`GametestEntrypointTest.java:136` is the containment form the bullet above warns about,
cultivation asserts nothing about `depends` at all, and prosperity and respite carry no guard of
any kind. Those four are the gap; the other four already hold this assertion.

Concord's own `make gametest-check` is **not** a substitute. The hub checker
(`scripts/check-gametest-manifest.py`) fails on a shipped manifest that declares
`fabric-gametest` entrypoints, on gametest sources with no companion manifest, and on an
unexpanded `${version}` placeholder — but it never compares individual suites against
entrypoints, it only *notes* a companion manifest that fails to depend on the main mod, and it
never inspects dependency floors at all. It catches the structural mistakes across every member
at once; only the per-repo `GametestRegistrationTest` catches the suite you forgot to register
this morning.

#### Naming and package layout

Gametest suites live in `com.rfizzle.<mod>.gametest` **or any subpackage of it other than
`util`**, and are named `*GameTest`. Non-suite helpers — fixture builders, mock factories, floor
templates — live in `com.rfizzle.<mod>.gametest.util`, are *not* named `*GameTest`, and are not
registered.

Subpackages are allowed on purpose: a mod with forty suites wants
`gametest.enchantments` and `gametest.shelf`, and the guard walks the tree anyway. What the rule
forbids is a suite living *outside* `<mod>.gametest` entirely — a suite in `<mod>.event` or
`<mod>.library` is invisible to anyone looking for the test suite and to any tooling that scopes
by package.

This is what makes the two-way naming check enforceable: with the convention held, "implements
the interface" and "named `*GameTest`" describe the same set, and any divergence is a real
defect rather than a style difference the guard has to tolerate.

The guard reads the source tree because the gametest source set is not on the test classpath
and its classes cannot be enumerated from there. Gradle therefore sees no dependency between
them — declare the inputs or the check stays `UP-TO-DATE` exactly when registration has
drifted:

```groovy
test {
    inputs.files(fileTree('src/gametest/java'),
                 file('src/gametest/resources/fabric.mod.json'),
                 file('src/main/resources/fabric.mod.json'))
            .withPropertyName('gametestRegistration')
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
```

The shipped manifest is in the list because the guard's third assertion reads it. It also
arrives transitively through `processResources`, so leaving it out is not a live staleness hole
— but declaring all three inputs keeps the block readable as "what this guard reads".

The cost of this coupling is that any edit under `src/gametest/java` invalidates the whole
`test` task, so the full Tier 1 suite reruns. That is the price of a guard that reads a source
tree Gradle otherwise has no reason to watch.

### Test-only data

Because the gametest manifest makes `src/gametest/resources` a discovered mod root, fixtures
that exist only to serve the suite — structure templates, bespoke loot tables, any other data a
test needs — live there, under the same `data/<namespace>/...` path they would occupy in the
main source set:

```
src/gametest/resources/data/<namespace>/gametest/structure/<name>.snbt
```

The namespace segment stays the mod's own namespace even though the providing root is now the
`-gametest` companion mod. Resolution is a namespace lookup against the merged
`ResourceManager`, not a mod-id one, so `@GameTest(template = "mymod:empty_3x3")` resolves from
the companion's `data/mymod/...` tree exactly as several mods can each contribute to
`data/c/tags/...`. Written out it looks like a copy-paste error to anyone who does not know the
mechanism, which is why it is worth saying.

In `src/main/resources` fixtures ride along in the release jar. A loot table in the mod's
namespace is then eagerly parsed and validated on every datapack reload on every server,
including the integrated server behind a singleplayer world, purely to serve a test. A
structure template is cheaper — it loads on demand — but it is still offered in
`/place template` autocomplete, so a server running the jar surfaces test fixtures to operators
as if they were content.

Guard this one against the **test classpath**, not the source tree: the classpath is what the
jar is built from. Walk every shipped root — under `splitEnvironmentSourceSets()` the client
source set contributes to the jar too, so a guard that checks only `main` misses a fixture
dropped into the other. Resolve each root by anchoring on a file known to sit at its top level,
then assert the entries you expect directly beneath it, so an anchor that moves into a
subdirectory fails loudly instead of silently narrowing the walk to that subdirectory. A source
set with no resources at all contributes no anchor and belongs out of the list; assert the
number of roots you expect to resolve, so adding `src/client/resources` later cannot leave it
quietly unscanned.

#### Every mod ships this guard

`ShippedResourceHygieneTest`, at `src/test/java/com/rfizzle/<mod>/resources/`, is a required
deliverable, not an optional extra — the same standing as `GametestRegistrationTest`. The two
guard opposite boundaries of the same mistake and neither one catches the other's failure:
registration drift means the tests never run, fixture leak means the tests ship. Both fail
silently.

Three assertions, in widening order:

- **Known fixtures are absent from the shipped classpath** — `getResource(path)` returns `null`
  for each fixture the mod actually uses. Cheap, names the offender exactly, and doubles as a
  regression test after a fixture is relocated. **Resolve the shipped roots before asserting
  `null`**, or the whole assertion passes vacuously against an empty classpath — a guard that
  cannot fail is worse than no guard, because it reads as coverage.
- **No `gametest` path segment anywhere in the shipped roots** — catches the next fixture,
  which by definition is not in the list above.
- **No structure templates anywhere in the shipped roots** — `.snbt` is a test artifact for a
  mod that ships no structures of its own. Drop this one only if the mod genuinely ships
  structures, and say so in the test.

**Pick the anchor per source set.** The anchor is a resource you know sits at the top level of
that root, whose parent directory therefore *is* the root. Meridian uses `/meridian.accesswidener`
for `main` and `/meridian.client.mixins.json` for `client`
(`ShippedResourceHygieneTest.java:46-50`). A mod with neither can anchor `main` on
`/fabric.mod.json`, but note that only resolves the main root — the client root needs an anchor
of its own, and a source set with no resources at all contributes no anchor and belongs out of
the list entirely.

Model: meridian's `ShippedResourceHygieneTest.java:72`, `:85`, and `:98`, with the anchored-root
resolution at `:144-177` and the moved-anchor failure at `:165-171`. Meridian's own fixtures live
in `src/gametest/resources/data/meridian/gametest/structure/`, which is the proof that relocating
them costs nothing — resolution from the companion-mod root works exactly as described above.

Run the guard against a clean build. A stale `build/resources/` tree can still hold fixtures
that were relocated in source, which reads as a live leak that a rebuild makes disappear.

### Source set setup in build.gradle
```groovy
sourceSets {
    gametest {
        compileClasspath += sourceSets.main.compileClasspath + sourceSets.main.output
        runtimeClasspath += sourceSets.main.runtimeClasspath + sourceSets.main.output
    }
}

configurations {
    gametestImplementation.extendsFrom implementation
    gametestRuntimeOnly.extendsFrom runtimeOnly
}

loom {
    runs {
        gametest {
            server()
            name "Game Test"
            source sourceSets.gametest
            vmArg "-Dfabric-api.gametest"
            vmArg "-Dfabric-api.gametest.report-file=${layout.buildDirectory.file('junit-gametest.xml').get().asFile}"
            runDir "build/gametest"
        }
    }
}
```

### Runtime patterns

**Mock player positioning:** the connected replica places the player near (0,0,0), not in the test region. Teleport:

```java
ServerPlayer player = MockPlayers.serverPlayerInLevel(helper);
BlockPos abs = helper.absolutePos(new BlockPos(0, 2, 1));
player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
```

`GameTestHelper.makeMockServerPlayerInLevel()` is `@Deprecated(forRemoval = true)` in MC 1.21.1 — use the mod's `MockPlayers` helper instead, and retire the player when the test is done. The `mc-testing-mock` skill has the canonical class, the packet-channel variant, and the teardown rules.

**Synchronous vs deferred assertions:**
- `helper.succeed()` — immediate success (state is already correct)
- `helper.succeedWhen(() -> { ... })` — polls every tick until the lambda runs without throwing. Use for state that needs ticks (AI, projectiles, block entity processing).

**Float tolerance:** `helper.assertValueEqual` uses exact equality. For fractional values:
```java
helper.assertTrue(Math.abs(actual - expected) < 1e-4, "value within tolerance");
```

**Deterministic assertions:** Disable randomized systems when asserting a single axis:
```java
boolean saved = config.someRandomFeature;
config.someRandomFeature = false;
try {
    // ... deterministic test
} finally {
    config.someRandomFeature = saved;
}
```

### Running
```bash
./gradlew runGametest 2>&1
```

## Guardrails

- **Never** skip `Bootstrap.bootStrap()` in a Tier 2 test that touches `BuiltInRegistries`. Knot does not call it.
- **Never** try to register mod items in a Tier 2 `@BeforeAll`. `Bootstrap.bootStrap()` freezes registries. Route to Tier 3.
- **Never** use reflection on `MappedRegistry` to unfreeze or force-register.
- **Never** widen production method access just for a test. Test observable behavior or use the public API. If you must access internals, use reflection or same-package placement — not ad-hoc `public` widening.
- **Never** assume a test needs Tier 2 just because it imports a Minecraft class. `BlockPos` and `RandomSource` are POJOs — try Tier 1 first.
- **Never** ignore the return value of `ExecutorService.awaitTermination()` in a concurrency test. A `false` return means workers hung — fail the test.
- **Never** write a concurrency test that only asserts "no exceptions thrown." Assert the actual invariant the concurrent code must maintain.
- **Always** clean up all static/shared state touched by tests in `@AfterEach`, including state in classes you didn't directly write to but that accumulate entries (caches, registries, name pools).
- **Always** test persisted-data migrations (legacy→new, idempotency, passthrough) when a serialized format changes.
- **Always** wrap config mutations in try/finally to restore the original value, even in tests you expect to pass.
- **Always** write at least one test per config toggle verifying the feature is inert when disabled.
- **Never** write tests whose only assertion is `assertNotNull`, `assertDoesNotThrow`, or bare `helper.succeed()`. Assert specific observable behavior.
- **Always** run the single test with `./gradlew test --tests '<FQN>'` before claiming it passes.
- **Never** put a `fabric-gametest` entrypoint or a test-only fixture in the shipped `src/main/resources`. Entrypoints there break `runServer`; fixtures there ship to players.
- **Always** guard both boundaries with a Tier 1 test — `GametestRegistrationTest` (every gametest class registered) and `ShippedResourceHygieneTest` (no test-only data on the shipped classpath). Both are required deliverables; both fail silently otherwise.

## Concurrency test discipline

When testing thread safety of managers, registries, or shared state:

### Assert invariants, not just "no exceptions"
A concurrency test that only asserts `assertDoesNotThrow` or counts results proves nothing. Assert the cross-structure invariant that the code is supposed to maintain. If two maps must stay in sync (e.g., a bidirectional mapping), assert their consistency after concurrent mutations complete.

### Check `awaitTermination` return values
If `pool.awaitTermination(...)` returns `false`, a worker task hung — exactly the failure mode these tests should catch. Always capture the return value, fail the test if `false`, and call `shutdownNow()`:

```java
pool.shutdown();
boolean clean = pool.awaitTermination(10, TimeUnit.SECONDS);
if (!clean) pool.shutdownNow();
assertTrue(clean, "Thread pool did not terminate — a worker task hung");
```

### Use overlapping key partitions
Tests that give each thread disjoint data (thread 0 uses villagers 0-99, thread 1 uses 100-199) never exercise the interesting races. Include at least one test variant where threads operate on overlapping keys.

### Clean up ALL shared static state
In `@AfterEach` or `@AfterAll`, reset every static/shared manager the tests touch — not just the ones the current test writes to. Leaked state across tests causes false passes that break under future test additions. Audit every static field, `ConcurrentHashMap`, volatile reference, and `ThreadLocal` in the classes under test.

## Testing persisted data migrations

When a persisted format changes (NBT codec, hash scheme, serialized IDs), write tests covering:

1. **Legacy → new:** Provide legacy-format data, run the migration, assert new-format output.
2. **Idempotency:** Run the migration twice on the same data, assert the result is unchanged after the second pass.
3. **Non-legacy passthrough:** Provide already-new-format data, assert the migration leaves it untouched.

Format changes without migration tests are the #1 source of silent world-upgrade data loss in mods.

## Naming conventions

These are the suite's test-naming rules. They exist so a reader moving between mods can find
the same thing in the same place, and so a survey can tell a missing test from a renamed one.

Of the four, only `ConfigMigratorTest` is universal today; the other three are newly required
and the members are mid-migration. Audit a member against its own conformance sweep issue, not
against this list — a mod that fails one of these is behind the rule, not necessarily broken.

- **`ConfigMigratorTest` in `config/`** — the config-migration suite is
  `src/test/java/com/rfizzle/<mod>/config/ConfigMigratorTest.java`. One name, one location; the
  migration tests described under "Testing persisted data migrations" above live here.
- **`*ResourceContractTest` for shipped-resource guards** — a Tier 1 test pinning a shipped
  JSON contract is named for the surface it guards plus that suffix:
  `CommandResourceContractTest`, `SleepVoteResourceContractTest`,
  `AdvancementResourceContractTest`. The suffix is what makes "does this feature have a
  resource guard?" answerable by `ls` rather than by reading every test in the package. The
  `*LangContractTest`, `*AssetsTest`, and `*ResourcesTest` names in use today are the same guard
  under other names and are in scope for the rename.
- **Mod-prefixed camelCase gametest batches** — `@GameTest(batch = "...")` values start with
  the mod id and continue in camelCase: `distillationAntidotesToggle`, `cultivationWeather`,
  `instinctBehaviorsOff`. The prefix earns its keep today in report attribution and batch
  filtering — an unprefixed `advancementBeauty` (respite's, currently) does not say which mod it
  belongs to in a failure report. It also forecloses a collision: batch names share one namespace
  across every mod in a gametest run, so two members tested together would merge same-named
  batches. Give a batch a name of its own per test where a test sweeps the level (see
  `retireLeaked` in `mc-testing-mock`) — same-batch tests run concurrently.
- **Named timeout constants** — a non-default `timeoutTicks` is a named constant, never a bare
  number at the annotation:

  ```java
  private static final int TIMEOUT = 500;

  @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = TIMEOUT)
  public void antidoteClearsTheEffect(GameTestHelper helper) { ... }
  ```

  A literal repeated across a suite's twenty methods is twenty edits when the timing changes,
  and it never says *why* this suite needs longer than the default.

## Config mutation isolation

When tests modify global config (singleton or static), always restore the original in a `finally` block or `@AfterEach`. A test that sets `enableFollowMode = false` and then fails before restoring it poisons every subsequent test in the run.

```java
@Test
void featureDisabledWhenConfigOff() {
    boolean saved = MercantileConfig.get().enableFollowMode;
    try {
        MercantileConfig.get().enableFollowMode = false;
        // ... assertions about disabled behavior
    } finally {
        MercantileConfig.get().enableFollowMode = saved;
    }
}
```

For gametests, the same pattern applies — save/restore in try/finally within the test method, not in a shared setup hook that might not run on failure.

## Config-disabled path coverage

Every config toggle (`enableX`) needs at least one test verifying the feature is inert when disabled. These tests catch mixins that fire unconditionally, network handlers that skip the config check, and gated code that only checks the config on one of its code paths.

## No vacuous assertions

Assertions that prove almost nothing:
- `assertNotNull(result)` — only proves construction, not correctness
- `assertDoesNotThrow(() -> ...)` — proves no crash, not correct behavior
- `helper.succeed()` with no preceding assertions — passes unconditionally

Every test should assert specific observable behavior. If you can't articulate what would break if the test were deleted, the test has no value.

## Test-access patterns

Pick **one** pattern for accessing package-private methods in tests and use it consistently:

- **Preferred:** Keep methods package-private and use reflection in tests (or use the same package in the test source tree).
- **Acceptable:** Widen to `public` with a `// @VisibleForTesting` comment (or a real `@VisibleForTesting` annotation if JetBrains annotations or Guava are on the classpath).
- **Not acceptable:** Mix both approaches in the same test class or test suite.

## When asked to add a new test

1. Run the decision tree. Commit to a tier before writing code.
2. Use the matching template.
3. Run the single test to verify it passes.
