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

    @Override
    protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        return new RecipeProvider(registries, output) {
            @Override
            public void buildRecipes() {
                shaped(RecipeCategory.DECORATIONS, MyRegistry.MY_BLOCK)
                        .define('D', Items.DIAMOND)
                        .define('S', Items.STICK)
                        .pattern("DDD")
                        .pattern(" S ")
                        .pattern(" S ")
                        .unlockedBy("has_diamond", has(Items.DIAMOND))
                        .save(output);
            }
        };
    }

    @Override
    public String getName() {
        return "My Mod Recipes";
    }
}
```

### Shapeless recipes
```java
shapeless(RecipeCategory.MISC, Items.DIAMOND, 9)
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
        // Simple drop-self
        dropSelf(MyRegistry.MY_BLOCK);

        // Silk touch required
        add(MyRegistry.GLASS_BLOCK, createSilkTouchOnlyTable(MyRegistry.GLASS_BLOCK));

        // Custom drop with conditions
        add(MyRegistry.ORE_BLOCK,
                createOreDrop(MyRegistry.ORE_BLOCK, MyRegistry.RAW_ORE));
    }
}
```

## Output directory

Generated files go to `src/main/generated/`. This directory must be declared as a resource source in `build.gradle`:

```groovy
sourceSets {
    main {
        resources {
            srcDirs += ["src/main/generated"]
        }
    }
}
```

## Running datagen

```bash
./gradlew runDatagen 2>&1
```

Configure the run in `build.gradle`:
```groovy
loom {
    runs {
        datagen {
            inherit server
            name "Data Generation"
            vmArg "-Dfabric-api.datagen"
            vmArg "-Dfabric-api.datagen.output-dir=${file("src/main/generated")}"
            vmArg "-Dfabric-api.datagen.modid=mymod"
            runDir "build/datagen"
        }
    }
}
```

## Idempotency verification

Add a CI-friendly task that runs datagen and asserts git reports no changes in the generated directory:

```groovy
tasks.register('verifyDatagenIdempotent') {
    description = 'Runs datagen and asserts git reports no changes in src/main/generated/'
    group = 'verification'
    dependsOn 'runDatagen'
    notCompatibleWithConfigurationCache('shells out to git against live working-tree state')
    doLast {
        def genDir = file('src/main/generated').absolutePath
        def runGit = { List<String> argv ->
            try {
                def out = providers.exec {
                    commandLine argv
                    ignoreExitValue = true
                }
                out.result.get() // force resolution so a failure to start lands in the catch
                return out
            } catch (Exception e) {
                throw new GradleException(
                        'verifyDatagenIdempotent needs git on PATH to inspect ' +
                        'src/main/generated/, and could not run it: ' + e.message, e)
            }
        }
        def firstLines = { String text ->
            def lines = text.trim().readLines()
            lines.size() > 5 ? lines.take(5).join('\n') + "\n… (${lines.size()} lines total)"
                             : lines.join('\n')
        }

        def statusResult = runGit(['git', '--no-optional-locks', 'status', '--porcelain', '--', genDir])
        def statusExit = statusResult.result.get().exitValue
        if (statusExit != 0) {
            throw new GradleException(
                    "git status failed with exit ${statusExit} while checking src/main/generated/:\n" +
                    firstLines(statusResult.standardError.asText.get()))
        }
        def dirty = statusResult.standardOutput.asText.get().trim()
        if (!dirty.isEmpty()) {
            throw new GradleException(
                    'src/main/generated/ is not clean after runDatagen:\n' +
                    firstLines(dirty) +
                    '\nRun ./gradlew runDatagen, then git add src/main/generated/ ' +
                    'and commit the results ' +
                    '(staged-but-uncommitted output also fails this check).')
        }
    }
}
```

Each guard above earns its place:

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