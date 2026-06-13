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

Add a CI-friendly task that runs datagen and asserts no git diff in the generated directory:

```groovy
tasks.register('verifyDatagenIdempotent') {
    description = 'Runs datagen and asserts no tracked diffs in src/main/generated/'
    group = 'verification'
    dependsOn 'runDatagen'
    doLast {
        def genDir = file('src/main/generated').absolutePath
        def diffResult = providers.exec {
            commandLine 'git', 'diff', '--exit-code', genDir
            ignoreExitValue = true
        }
        if (diffResult.result.get().exitValue != 0) {
            throw new GradleException(
                    'src/main/generated/ has unstaged changes after runDatagen. ' +
                    'Run ./gradlew runDatagen and commit the results.')
        }
    }
}
```

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