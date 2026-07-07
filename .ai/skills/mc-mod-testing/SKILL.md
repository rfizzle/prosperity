---
name: mc-mod-testing
description: Write and maintain tests for Fabric Minecraft mods across the three-tier test spectrum (pure JUnit, fabric-loader-junit, Fabric Gametest). TRIGGER when creating or editing *Test.java, *GameTest.java, or when the user asks about testing a Minecraft mod, fabric-loader-junit, Fabric Gametest, or guarding shipped resources (lang keys, models, textures) with tests.
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
- **Config validation** — `clamp()`/`validate()` are pure POJO methods (see the
  `mc-config` skill); test the bounds at Tier 1.

A method that takes a `ServerLevel` only to read one number, or a screen that bakes
its formatting into `render()`, has hidden a Tier-1-testable core inside a Tier-3
shell. Pull the core out. When you find yourself reaching for Tier 3 to test pure
logic, that's the signal the logic is in the wrong place.

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
lands), with a file-path fallback:

```java
private static final String RESOURCE = "/assets/mymod/lang/en_us.json";
private static final Path SOURCE = Path.of("src/main/resources/assets/mymod/lang/en_us.json");

private static JsonObject lang() {
    try (InputStream in = LangContractTest.class.getResourceAsStream(RESOURCE)) {
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

- **Config lang contract** — every `config.<mod>.<section>.<key>` label has a
  matching non-blank `.tooltip` key, so the Cloth screen never renders a raw
  key. Collect all misses into a list before asserting, so the failure names
  exactly which keys are missing (exclude `.title`, `.category.*`, and
  `.tooltip` keys themselves from the label sweep):

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

Register via `fabric-gametest` entrypoint in `fabric.mod.json`:
```json
{
    "entrypoints": {
        "fabric-gametest": ["com.example.mymod.gametest.MyFeatureGameTest"]
    }
}
```

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

### Structure templates

Templates: `src/main/resources/data/<modid>/gametest/structure/<name>.snbt`

Templates must be in `src/main/resources`, not `src/gametest/resources`.

### Runtime patterns

**Mock player positioning:** `makeMockServerPlayerInLevel()` places the player near (0,0,0), not in the test region. Teleport:

```java
ServerPlayer player = helper.makeMockServerPlayerInLevel();
BlockPos abs = helper.absolutePos(new BlockPos(0, 2, 1));
player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
```

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
