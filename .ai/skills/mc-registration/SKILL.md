---
name: mc-registration
description: Guide Minecraft Fabric mod content registration (blocks, items, block entities, menus, creative tabs, particles, sounds, stats, commands). TRIGGER when creating or editing *Registry.java, *Items.java, *Blocks.java, or any class calling Registry.register(), BuiltInRegistries, or Fabric registration APIs.
---

The user is registering mod content in a Fabric mod. Apply this guidance whenever registration code is being written or modified.

## Core pattern: central registry class

Every mod should have a single registry class that owns all `Registry.register()` calls. This keeps registration order explicit, gives datagen and compat modules a single place to enumerate all content, and prevents scattered registrations that are hard to audit.

```java
public final class ModRegistry {

    // Insertion-ordered so datagen providers and compat modules can walk all blocks
    public static final Map<ResourceLocation, Block> BLOCKS = new LinkedHashMap<>();
    public static final List<Item> STANDALONE_ITEMS = new ArrayList<>();

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        // All registration calls go here, in dependency order
        registerBlock("example_block", EXAMPLE_BLOCK, new Item.Properties());
        registerBlockEntityType("example_block", EXAMPLE_BLOCK_BE);
        registerItem("example_item", EXAMPLE_ITEM);
        registerMenuType("example_menu", EXAMPLE_MENU);
        registerCreativeTab();
    }

    // ... helper methods below
}
```

Call `ModRegistry.register()` from your `ModInitializer.onInitialize()`. Registration must happen before `BuiltInRegistries` freezes (which happens after all mod initializers run).

## Block + BlockItem companion registration

Always register a block and its BlockItem together. The helper method ensures they share the same `ResourceLocation` and the block is tracked in the `BLOCKS` map:

```java
public static <T extends Block> T registerBlock(String name, T block, Item.Properties itemProps) {
    ResourceLocation id = MyMod.id(name);
    Registry.register(BuiltInRegistries.BLOCK, id, block);
    BLOCKS.put(id, block);
    Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, itemProps));
    return block;
}
```

## Block entity type registration

Use `BlockEntityType.Builder.of(factory, ...blocks).build(null)` — the `null` is the vanilla `Type<?>` parameter (unused by mods):

```java
public static final BlockEntityType<MyBlockEntity> MY_BE =
        BlockEntityType.Builder.of(MyBlockEntity::new, MY_BLOCK).build(null);
```

Register with:
```java
public static <T extends BlockEntity> BlockEntityType<T> registerBlockEntityType(
        String name, BlockEntityType<T> type) {
    Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, MyMod.id(name), type);
    return type;
}
```

## Menu type registration

For menus that only need the sync ID + player inventory (no extra data):
```java
public static final MenuType<MyMenu> MY_MENU =
        new MenuType<>(MyMenu::new, FeatureFlags.VANILLA_SET);
```

For menus that need extra data (e.g., a `BlockPos`), use Fabric's `ExtendedScreenHandlerType` with a `StreamCodec`:
```java
public static final ExtendedScreenHandlerType<MyMenu, BlockPos> MY_MENU =
        new ExtendedScreenHandlerType<>(MyMenu::new, BlockPos.STREAM_CODEC);
```

## Standalone item registration

Items that are not BlockItems (tools, materials, tomes, etc.):

```java
public static <T extends Item> T registerItem(String name, T item) {
    Registry.register(BuiltInRegistries.ITEM, MyMod.id(name), item);
    STANDALONE_ITEMS.add(item);
    return item;
}
```

Track standalone items separately from blocks so the creative tab builder can enumerate both:

```java
private static void registerCreativeTab() {
    Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MyMod.id("my_mod"),
            FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.my_mod"))
                    .icon(() -> new ItemStack(ICON_ITEM))
                    .displayItems((params, output) -> {
                        BLOCKS.values().forEach(output::accept);
                        STANDALONE_ITEMS.forEach(output::accept);
                    })
                    .build());
}
```

## Other registry types

### Loot condition types
```java
public static final LootItemConditionType MY_CONDITION = new LootItemConditionType(MyCondition.CODEC);

// In register():
Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, MyMod.id("my_condition"), MY_CONDITION);
```

### Particle types
```java
public static final SimpleParticleType MY_PARTICLE = FabricParticleTypes.simple();

// In register():
Registry.register(BuiltInRegistries.PARTICLE_TYPE, MyMod.id("my_particle"), MY_PARTICLE);
```

### Custom advancement triggers
```java
public static final MyCriterionTrigger MY_TRIGGER = new MyCriterionTrigger();

// In register():
Registry.register(BuiltInRegistries.TRIGGER_TYPES, MyMod.id("my_trigger"), MY_TRIGGER);
```

This is only the registration hook — criterion design (codecs, threshold
semantics, one-class-many-ids), fire-site discipline, tree design, and
grant-asserting tests live in **mc-advancements**.

### Brigadier commands
```java
// In onInitialize(), after registry:
CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
    dispatcher.register(Commands.literal("mymod")
            .then(Commands.literal("reload")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> { /* reload logic */ return 1; })));
});
```

## Custom statistics

Custom stats live in vanilla's `CUSTOM_STAT` registry and persist through the standard player statistics system — no mod-side persistence needed.

Registration is a two-step quirk: `BuiltInRegistries.CUSTOM_STAT` maps a `ResourceLocation` to itself, and the actual `Stat<ResourceLocation>` is materialized separately by `Stats.CUSTOM.get(id, formatter)`. Both steps are required — a helper keeps them together:

```java
private static void registerStat(ResourceLocation id, StatFormatter formatter) {
    Registry.register(BuiltInRegistries.CUSTOM_STAT, id, id);
    Stats.CUSTOM.get(id, formatter); // materializes the Stat with its formatter
}
```

### Awarding: counters vs high-water marks

Counters increment via `player.awardStat(stat)`. Monotonic high-water marks are written with `setValue`, and only when the new value exceeds the stored one:

```java
// Counter: increment on each occurrence
player.awardStat(SHARDS_USED);

// Monotonic high-water mark
int current = player.getStats().getValue(Stats.CUSTOM.get(HIGHEST_LEVEL));
if (newValue > current) {
    player.getStats().setValue(player, Stats.CUSTOM.get(HIGHEST_LEVEL), newValue);
}
```

Drive both from the mod's own callbacks — award at the moment the event fires, never by polling.

### Shared attribution with advancement triggers

When one event (e.g., a kill) should credit both a stat and an advancement trigger, resolve the responsible player once with a shared helper and do both from a single `AFTER_DEATH` traversal — never register two listeners that each resolve the killer:

```java
ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
    if (entity instanceof Mob mob && qualifies(mob)) {
        // Shared helper: getKillCredit() first (handles arrow/trident kills),
        // then damageSource.getEntity() as the fallback
        ServerPlayer killer = resolveKiller(mob, damageSource);
        if (killer != null) {
            killer.awardStat(SPECIAL_MOBS_KILLED);
            MY_TRIGGER.trigger(killer);
        }
    }
});
```

### Lang keys and verification

Every custom stat needs a `stat.<mod_id>.<path>` entry in `en_us.json` — that key names it in the vanilla statistics screen. Verify stat behavior with a gametest: `makeMockServerPlayerInLevel()`, fire the mod callback, then assert on `player.getStats().getValue(Stats.CUSTOM.get(id))` (including that a high-water mark does **not** decrease).

## Fabric API lookups (hopper/pipe interaction)

Register these separately from the main `register()` method — `ItemStorage.SIDED` uses Fabric mixin interfaces that don't exist in unit test classloaders:

```java
public static void registerApiLookups() {
    ItemStorage.SIDED.registerForBlockEntity(
            (be, direction) -> be.getStorageAdapter(), MY_BE_TYPE);
}
```

Call from `onInitialize()` after `register()`.

## ResourceLocation helper

Define a static helper on your mod's main class:

```java
public static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
}
```

## Registration ordering

1. Menu types (no dependencies)
2. Blocks + BlockItems (may reference menu types in their `use()`)
3. Block entity types (reference their blocks)
4. Standalone items (no dependencies, but listed after blocks for creative tab order)
5. Loot condition types, particle types, triggers, custom stats
6. Creative tab (references blocks + items, so must be last)
7. API lookups (post-registration, in `onInitialize`)
8. Brigadier commands (post-registration, in `onInitialize`)

## Version notes

- **1.20.5+:** `DataComponentType` replaces raw NBT on `ItemStack`. Register custom data components via `DataComponentType.Builder`. Use `DataComponents` for vanilla lookups.
- **1.21+:** Enchantments are fully data-driven JSON. `Holder<Enchantment>` replaces direct `Enchantment` references. Do not register `Enchantment` objects in Java.

## Guardrails

- Never call `Registry.register()` outside of `onInitialize` (or a method called from it). Registration after freeze throws.
- Never forget the BlockItem companion when registering a block the player should be able to pick up.
- Never scatter registration calls across multiple classes. One central registry class.
- Always use an insertion-ordered collection (`LinkedHashMap`) for the block map so datagen iteration order is deterministic.
- The `build(null)` on `BlockEntityType.Builder` is intentional — the `Type<?>` parameter is for vanilla's DFU data fixers, which mods don't use.
