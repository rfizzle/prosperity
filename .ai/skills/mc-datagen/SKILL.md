---
name: mc-datagen
description: Create and maintain Minecraft Fabric data generation providers (models, blockstates, recipes, tags, loot tables, advancements). TRIGGER when creating or editing *Provider.java, *DataGenerator.java, DataGeneratorEntrypoint, or when the user mentions datagen, data generation, or runDatagen.
---

The user is writing or modifying data generation code in a Fabric mod. Apply this guidance whenever datagen providers or the generator entrypoint are being touched.

## Architecture: DataGeneratorEntrypoint

Fabric datagen uses a single entrypoint that registers all providers to a pack:

```java
public class MyDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();

        // Order matters for tag providers — block tags before item tags
        pack.addProvider(MyModelProvider::new);
        pack.addProvider(MyBlockLootTableProvider::new);
        pack.addProvider(MyRecipeProvider::new);
        MyBlockTagProvider blockTags = pack.addProvider(MyBlockTagProvider::new);
        pack.addProvider((output, registries) ->
                new MyItemTagProvider(output, registries, blockTags));
        pack.addProvider(MyEnchantmentTagProvider::new);
    }
}
```

Register in `fabric.mod.json`:
```json
{
    "entrypoints": {
        "fabric-datagen": ["com.example.mymod.data.MyDataGenerator"]
    }
}
```

## Provider dependency ordering

**Block tags must run before item tags** when using copy-through (`copy()`) to derive item tags from block tags. Pass the `BlockTagProvider` instance to the `ItemTagProvider` constructor.

General ordering:
1. Models (no dependencies)
2. Loot tables (no dependencies)
3. Recipes (no dependencies)
4. Block tags (no dependencies)
5. Item tags (depends on block tags)
6. Other tags (enchantment, entity type, damage type)
7. Advancements (may reference tags)

## Recipe providers

### Shaped recipes
```java
public class MyRecipeProvider extends FabricRecipeProvider {
    public MyRecipeProvider(FabricDataOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    // 1.21.1 / Fabric API 0.116: override buildRecipes(RecipeOutput) directly and use the
    // static builders. The createRecipeProvider(...) + instance shaped()/has() shape is
    // the 1.21.2+ API and does not compile here.
    @Override
    public void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, MyRegistry.MY_BLOCK)
                .define('D', Items.DIAMOND)
                .define('S', Items.STICK)
                .pattern("DDD")
                .pattern(" S ")
                .pattern(" S ")
                .unlockedBy("has_diamond", has(Items.DIAMOND))
                .save(output);
    }
}
```

Reference: `CultivationRecipeProvider.buildRecipes(RecipeOutput)` and
`DistillationRecipeProvider` — both ship this form.

### Shapeless recipes
```java
ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Items.DIAMOND, 9)
        .requires(MyRegistry.DIAMOND_BLOCK)
        .unlockedBy("has_diamond_block", has(MyRegistry.DIAMOND_BLOCK))
        .save(output);
```

### Smelting / smoking / blasting
```java
SimpleCookingRecipeBuilder.smelting(
        Ingredient.of(MyRegistry.RAW_ORE),
        RecipeCategory.MISC,
        MyRegistry.INGOT,
        0.7f, // experience
        200)  // cooking time in ticks
        .unlockedBy("has_raw_ore", has(MyRegistry.RAW_ORE))
        .save(output);
```

### Custom recipe types
For non-standard recipes (stat-gated table crafting, machine recipes), hand-write the JSON files under `src/main/resources/data/<modid>/recipe/` and don't generate them. Datagen is best for recipes that follow vanilla patterns.

## Tag providers

### Block tags
```java
public class MyBlockTagProvider extends FabricTagProvider.BlockTagProvider {
    public MyBlockTagProvider(FabricDataOutput output,
                              CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        // Custom mod tag
        getOrCreateTagBuilder(TagKey.create(Registries.BLOCK, MyMod.id("shelves")))
                .add(MyRegistry.SHELF_A)
                .add(MyRegistry.SHELF_B);

        // Vanilla tag participation
        getOrCreateTagBuilder(BlockTags.MINEABLE_WITH_AXE)
                .add(MyRegistry.SHELF_A);

        // Convention tags
        getOrCreateTagBuilder(ConventionalBlockTags.BOOKSHELVES)
                .add(MyRegistry.SHELF_A);
    }
}
```

### Item tags (with copy-through)
```java
public class MyItemTagProvider extends FabricTagProvider.ItemTagProvider {
    public MyItemTagProvider(FabricDataOutput output,
                             CompletableFuture<HolderLookup.Provider> registries,
                             FabricTagProvider.BlockTagProvider blockTags) {
        super(output, registries, blockTags);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        // Copy block tags to item tags (BlockItem-based items)
        copy(ConventionalBlockTags.BOOKSHELVES, ConventionalItemTags.BOOKSHELVES);
        copy(BlockTags.MINEABLE_WITH_AXE, ItemTags.AXES_MINEABLE);

        // Item-only tags
        getOrCreateTagBuilder(TagKey.create(Registries.ITEM, MyMod.id("tomes")))
                .add(MyRegistry.SCRAP_TOME)
                .add(MyRegistry.EXTRACTION_TOME);
    }
}
```

## Model providers

### Block models + blockstates
```java
public class MyModelProvider extends FabricModelProvider {
    public MyModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators generators) {
        // Simple cube-all block
        generators.createTrivialCube(MyRegistry.MY_BLOCK);

        // Block with custom model parent
        generators.createTrivialBlock(MyRegistry.FANCY_BLOCK,
                TexturedModel.CUBE_BOTTOM_TOP);
    }

    @Override
    public void generateItemModels(ItemModelGenerators generators) {
        // Simple flat item
        generators.generateFlatItem(MyRegistry.MY_ITEM, ModelTemplates.FLAT_ITEM);
    }
}
```

## Loot table providers

```java
public class MyBlockLootTableProvider extends FabricBlockLootTableProvider {
    protected MyBlockLootTableProvider(FabricDataOutput output,
                                       CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void generate() {
        // Simple drop-self — note the sequence, see below
        dropSelfWithSequence(MyRegistry.MY_BLOCK);

        // Silk touch required
        add(MyRegistry.GLASS_BLOCK, withSequence(MyRegistry.GLASS_BLOCK,
                createSilkTouchOnlyTable(MyRegistry.GLASS_BLOCK)));

        // Custom drop with conditions
        add(MyRegistry.ORE_BLOCK, withSequence(MyRegistry.ORE_BLOCK,
                createOreDrop(MyRegistry.ORE_BLOCK, MyRegistry.RAW_ORE)));
    }

    /** {@link #dropSelf(Block)} with the table's random sequence restored. */
    private void dropSelfWithSequence(Block block) {
        add(block, createSingleItemTable(block).setRandomSequence(block.getLootTable().location()));
    }

    private LootTable.Builder withSequence(Block block, LootTable.Builder builder) {
        return builder.setRandomSequence(block.getLootTable().location());
    }
}
```

### Always stamp `random_sequence` yourself

**Fabric's loot provider drops a key vanilla's own provider always writes.** Vanilla's
`LootTableProvider` calls `setRandomSequence(id)` before `setParamSet`;
`FabricLootTableProviderImpl.run` only calls `setParamSet(...).build()`, so a bare
`dropSelf` emits a table with no `"random_sequence"`.

The key is not cosmetic. `LootTable.getRandomItems` hands the
`Optional<ResourceLocation>` to `LootContext.Builder.create(...)`: present, the roll
draws from `ServerLevel.getRandomSequence(id)` — a per-table stream seeded off the world
seed and **persisted in the world's `random_sequences` data**; absent, it falls back to
an unsequenced `RandomSource`. A table without it sits outside the persisted sequence
state vanilla puts every table into.

For a plain `dropSelf` the visible effect is small — `survives_explosion` is the only
roll and it is statistically identical either way — which is exactly why this goes
unnoticed. Converting hand-authored tables to datagen silently drops the key from every
one of them. Stamp it explicitly, as above.

> **Not a concern: `sends_telemetry_event`.** `Advancement.Builder.advancement()` sets it
> **true**, and the bare `new Advancement.Builder()` leaves it false — but the flag is
> unreachable for modded content. Its only reader in the whole game,
> `WorldSessionTelemetryManager`, gates on `sendsTelemetryEvent() && id.getNamespace()
> .equals("minecraft")`, so a mod advancement never fires it. Either value is fine; do
> not churn generated trees over it.

## Output directory

Generated files go to `src/main/generated/`. `gradle/concord-common.gradle` (hub-owned, see
`mc-gradle-builds`) declares it as a resource root of the main source set, so it ships in the jar
and lands on the test classpath exactly like `src/main/resources`.

## Running datagen

```bash
./gradlew runDatagen 2>&1    # or: make run-datagen
```

The Loom `datagen` run config (`-Dfabric-api.datagen`, the `src/main/generated` output dir, and
`-Dfabric-api.datagen.modid=<archives_base_name>`) also comes from `gradle/concord-common.gradle`;
a member does not configure it.

**Put the datagen classes where the coverage exclusion expects them.** Providers run only under
`runDatagen`, never under `test` or `runGametest`, so the merged JaCoCo report excludes them from
the denominator by convention: anything under a `datagen` package, plus any `*DataGenerator*` or
`*Provider*` class inside `com.rfizzle.<mod>.data`. Keep the entrypoint and every provider in
`data/` with those names (`<Mod>DataGenerator`, `<Mod>RecipeProvider`, ...). The rule is scoped to
`data/` because runtime persistence classes (`PlayerData`, `*Attachments`) share the package and
must stay measured, and the compat packages hold runtime Jade/WTHIT `*Provider` classes. A runtime
class in `data/` with `Provider` in its name silently drops out of the coverage number, so don't
name one that.

## The four datagen anchors

Datagen is wired in four places, and they only work as a set:

| # | Anchor | Where |
|---|---|---|
| 1 | `DataGeneratorEntrypoint` — the class **and** its `fabric-datagen` key | `src/main/java/…/data/<Mod>DataGenerator.java` + `src/main/resources/fabric.mod.json` |
| 2 | The Loom `datagen` run config | `gradle/concord-common.gradle` (hub-owned; every member gets it) |
| 3 | The `run-datagen` make target | `concord.mk` (hub-owned; every member gets it) |
| 4 | `verifyDatagenIdempotent` | `gradle/concord-common.gradle` (hub-owned; every member gets it) |

Any one missing degrades quietly rather than failing:

- **No entrypoint (1)** — the run and the task both still exist and both still
  succeed. `runDatagen` starts a server, generates nothing, and exits 0.
- **No run config (2)** — `./gradlew runDatagen` fails with "task not found",
  which is at least loud.
- **No make target (3)** — the documented entry point doesn't exist, so the task
  is discoverable only by reading `build.gradle`.
- **No verify task (4)** — CI silently stops checking. Concord's reusable
  `mod-ci.yml` gates the step on whether the task is defined at all:

  ```yaml
  - name: Verify datagen is idempotent
    run: |
      if ./gradlew --no-daemon -q help --task verifyDatagenIdempotent >/dev/null 2>&1; then
        ./gradlew verifyDatagenIdempotent --no-daemon --stacktrace
      else
        echo "verifyDatagenIdempotent is not defined in this repo — skipping."
      fi
  ```

  `help --task` exits non-zero when the task is absent, and the `if` swallows
  that. A member without the task gets a green job and one skip line in a log
  nobody reads.

**1 and 4 together are what make the guard mean anything.** The check shells out
to `git --no-optional-locks status --porcelain -- <genDir>` (the task below). With
no entrypoint that directory is never created or tracked, so the `--` pathspec
matches nothing, git exits 0 with empty output, and the task reports clean — a
passing check that verified nothing. A member holding 2+3+4 without 1 is in the worst state of the
four: it looks the most wired and proves the least.

Anchors 2, 3 and 4 now ship from the hub (`gradle/concord-common.gradle`, `concord.mk`), so a
member always has them; anchor 1, the entrypoint, is the only one a member owns. A member
without it fails `verifyDatagenIdempotent` loudly (the task finds no tracked files under
`src/main/generated/`) instead of passing vacuously.

So a member is conformant when it is **4-of-4**, or when it deliberately has
**none of the four** and records why in its `AGENTS.md`. Scaffolding 2+3+4 ahead
of the entrypoint is a legitimate waypoint — it is how a member gets ready to
generate before it has anything to generate — but it must not be mistaken for a
working guard, and it is not a resting state.

## Idempotency verification

`gradle/concord-common.gradle` ships a CI-friendly task, `verifyDatagenIdempotent`, that runs
datagen and asserts git reports no changes in the generated directory. It is hub-owned: a member
neither writes nor copies it, and a fix lands once in the hub.

**Clear Fabric's hash cache first, or the task is falsely green locally.** Fabric datagen keeps
a gitignored `src/main/generated/.cache/` index of provider hashes and skips rewriting any file
whose provider hash is unchanged — so a hand edit to a generated file survives `runDatagen`
and the check compares the edit to itself. CI (fresh checkout, no cache) sees the real
result; a developer never does. `verifyDatagenIdempotent` therefore depends on a
`cleanDatagenCache` task, and `runDatagen` is ordered after it. Mutation-test it once: edit a
generated file, confirm red, revert, confirm clean.

**The cache stays out of the jars.** `src/main/generated` is a resource root, so the
`.cache/` directory would be packaged unless every task that copies it excludes it. The shared
file excludes `**/.cache` from `processResources`, `processClientResources`, `jar` and
`sourcesJar`. Check with `unzip -l build/libs/*.jar | grep -c .cache` (and the `-sources.jar`)
after a `runDatagen`; the count must be 0.

Each guard in the shared task earns its place:

- **A clean `git status` proves nothing until git is shown to know the tree.**
  The task first runs `git ls-files -- src/main/generated` and fails if it lists
  nothing: `git status --porcelain` exits 0 with empty output for a path that
  does not exist or holds nothing tracked, so a datagen entrypoint that stopped
  running, a typo'd output-dir vmArg, or a bad merge would otherwise leave the
  task green while verifying nothing at all.
- **The `--` separator is required.** Without it git parses the path
  as a revision-or-path and aborts with exit 128 (`ambiguous
  argument`) whenever `src/main/generated/` is neither tracked nor
  present — the steady state before a `fabric-datagen` entrypoint is
  declared. With it, a missing directory is an empty pathspec and the
  task passes.
- **`git status --porcelain` covers all three drift shapes.**
  Staged (`M `), unstaged (` M`), and untracked (`??`) output all
  appear in one machine-readable listing. A `git diff` check sees only
  the worktree-versus-index delta, so drift that has been `git add`-ed
  slips through — a verification task passing on real drift. Reading
  stdout rather than an exit code also means no exit value has to be
  reserved for "differences found", and unlike `git diff HEAD` it
  works in a repository with no commits yet.
- **The remedy carries staging through to a commit.** Because the
  check reports the index as well as the worktree, staged-but-
  uncommitted output fails it, so a message that stops at `git add`
  strands the developer on a step that changes nothing. Staging alone
  does not clear the failure and a commit alone cannot reach untracked
  or unstaged output — `git commit` never picks up untracked paths,
  which is exactly what a mod's first datagen run produces. Only the
  two together clear every shape the check reports, and `git add` on
  already-staged content is a harmless no-op.
- **`--no-optional-locks` keeps a read-only check from taking a
  write lock.** `git status` rewrites `.git/index` when cached stat
  info is stale, which is precisely the state `runDatagen` leaves
  behind after rewriting every file under the generated directory.
  With `org.gradle.parallel=true`, or a developer's IDE or git GUI
  polling the same repository, that is an avoidable `index.lock`
  contention window in a task that only ever reads. The flag is git's
  documented form for tooling that inspects state without intending
  to write, and it is a global option — it goes before the
  subcommand, not after it.
- **Separate git's failure from datagen's drift.** Any nonzero exit is
  git itself failing, and surfaces git's stderr instead of being
  relabelled as drift. A bare `!= 0` check tells the user to commit
  results that do not exist.
- **Force the lazy resolution inside the `try`.** `providers.exec`
  starts the process on the first result read, so wrapping only the
  `providers.exec` call catches nothing. `ignoreExitValue` covers a
  nonzero exit, not a process that never starts — without
  `out.result.get()` inside the `try`, an absent git binary surfaces
  as a bare process-start exception naming neither datagen nor a
  remedy.
- **Cap both git outputs at five lines.** A run that rewrites a large
  provider's output can list hundreds of changed files, and git's own
  diagnostics are not bounded either. Neither belongs inlined wholesale
  in a build failure, and the first few lines carry the diagnosis.
- **Declare the configuration-cache incompatibility.** The check reads
  live working-tree state, which Gradle cannot model as task inputs.
  Declaring it keeps the intent explicit rather than leaving a hard
  failure for whoever enables the cache. The marker only discards the
  configuration-cache entry for invocations whose task graph actually
  contains this task; builds that never schedule it store and reuse
  their entries normally. The contained cost is smaller still — the
  task `dependsOn runDatagen`, which forks a Minecraft server, so any
  invocation that schedules it was never going to be cache-fast anyway.
- **The check is only as good as what git tracks.** If
  `src/main/generated/` is gitignored or has never been committed, git
  reports nothing and the verification silently passes. Committing
  datagen output is the point — do not ignore the generated directory.

The task needs Gradle 7.5+ (`providers.exec`; `notCompatibleWithConfigurationCache` needs 7.4+) and git 2.15+ (`--no-optional-locks`). On older git the flag is rejected before `status` runs, and the task reports `git status failed with exit 129` with git's own `unknown option` on the next line.

## Convention tags

Use convention tags for cross-mod compatibility:

| Convention tag | Use for |
|----------------|---------|
| `ConventionalBlockTags.BOOKSHELVES` | Blocks that function as bookshelves |
| `ConventionalItemTags.GEMS` | Gem-type items (diamond, emerald, etc.) |
| `ConventionalItemTags.INGOTS` | Metal ingots |
| `ConventionalItemTags.DUSTS` | Powder/dust items |

Convention tags use the `c:` namespace and are defined in Fabric API's `net.fabricmc.fabric.api.tag.convention.v2` package.

## Guardrails

- **Never** hand-edit files in `src/main/generated/`. They will be overwritten on the next datagen run.
- **Always** run `./gradlew runDatagen` after changing any provider, then commit the generated output.
- **Always** register `BlockTagProvider` before `ItemTagProvider` when using `copy()`.
- **Never** put datagen-only code in the main source set's runtime classpath. The `DataGeneratorEntrypoint` runs in a separate Gradle task.
- **Always** include `unlockedBy()` on recipes — it controls when the recipe appears in the recipe book.

## Version notes

- **1.20.5+:** `DefaultCustomIngredients.components()` replaces NBT-based ingredient matching. Use component predicates for items with specific data.
- **1.21+:** Enchantment tags use `Registries.ENCHANTMENT` and `HolderLookup`. Tag providers must accept `CompletableFuture<HolderLookup.Provider>`.