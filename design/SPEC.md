# Prosperity — Feature Spec

Minecraft 1.21.1 Fabric mod. Instanced loot overhaul.

**Asset philosophy:** Container blocks stay vanilla — the proxy layer never registers custom blocks, replaces block entities, or retextures/swaps world-gen blocks, which keeps full compatibility with Sodium, Enhanced Block Entities (EBE), and shader packs (see Architectural philosophy below). That is an architectural constraint on the block layer, **not** a vanilla-purity stance: mod-specific UI, HUD, and world-overlay sprites are custom pixel art authored through Concord's glyph pipeline (`/glyph`, `mc-textures` skill, concord `design/DESIGN-SYSTEM.md` §8, with `.glyph` sources kept beside the masters) — see the asset inventory in `design/DESIGN.md`. Unlooted-container indicators render as client-side sprite overlays via `WorldRenderEvents.LAST`. Sounds stay vanilla where the cue is organic (chest lids, XP pickup — physical sounds vanilla already nails); custom synthesized cues are added through the `/sfx` pipeline where a sound benefits from its own identity (concord `design/DESIGN-SYSTEM.md` §9).

**Architectural philosophy:** Zero-trust proxy. The mod intercepts interactions with vanilla loot containers dynamically via events — it does not register custom blocks or entities, replace block entities, or modify world generation. Per-player loot state is attached to the vanilla loot sources — `RandomizableContainerBlockEntity` block entities and `AbstractMinecartContainer` minecarts — via persistent Fabric data attachments (the same `AttachmentType` mechanism the rest of the Concord suite uses, here on block-entity and entity targets). The vanilla block, entity, model, block entity type, and renderer are never touched.

---

## 1. Instanced Loot

Per-player loot instances for all naturally generated containers. Replaces the functionality of Lootr.

### Problem

Vanilla Minecraft generates loot once per container. The first player to open a dungeon chest claims everything — subsequent players find it empty. This creates a race condition in multiplayer and removes the reward loop for exploration in shared worlds.

### Behavior

When a player right-clicks a container that has (or had) a valid `LootTable`:

1. **Intercept** — `UseBlockCallback.EVENT` (block containers) and `UseEntityCallback.EVENT` (container minecarts) fire. The handler ignores fake-player openers (see [Container Adapters](#container-adapters)) and checks whether the target is a proxy-managed loot container — a `RandomizableContainerBlockEntity` or `AbstractMinecartContainer` with an active or previously-consumed loot table.
2. **Cancel vanilla open** — Return `InteractionResult.SUCCESS` to prevent the normal container screen from opening.
3. **Lookup** — Read the instanced-loot attachment on the block entity. It stores a `Map<UUID, DefaultedList<ItemStack>>` mapping player UUIDs to their individual loot inventories.
4. **Generate or retrieve:**
   - **First visit (UUID absent):** Generate loot using the container's `LootTable` and the player's context (luck, position). Store the result in the map under this player's UUID. Nullify the vanilla `lootTable` field on the block entity after the first generation for *any* player (prevents hopper/comparator exploits that trigger vanilla's `unpackLootTable`).
   - **Return visit (UUID present):** Retrieve the player's saved inventory from the map.
5. **Serve virtual UI** — Open a `SimpleInventory`-backed container screen sized to match the original container (27 slots for chests, 27 for barrels, 5 for hoppers — both block hoppers and hopper minecarts). The inventory is populated from the player's instanced loot.
6. **Sync animations** — On open: `world.blockEvent(pos, block, 1, 1)` + `world.playSound()` (chest open sound). On close: `world.blockEvent(pos, block, 1, 0)` + `world.playSound()` (chest close sound). This triggers the vanilla lid animation and audio without needing access to the block entity's internal animation state.
7. **Persist** — On close, write the current inventory state back to the attachment map (and mark the block entity changed). Changes (items taken, items left, items rearranged) are saved per-player.

### Supported Containers

Prosperity instances every naturally-generated, loot-table-bearing container, across the three vanilla loot-source shapes (see [Container Adapters](#container-adapters) for how each is reached):

**Block-entity containers** — `RandomizableContainerBlockEntity`:
- Chests (single and double), trapped chests (single and double)
- Barrels
- Shulker boxes (end city loot)
- Dispensers (jungle temple), droppers, and hoppers (the latter two if modded loot tables target them — vanilla world generation does not place loot-bearing droppers or block hoppers, but any 5-slot block hopper bearing a loot table is instanced and served through a hopper menu)

**Container entities** — `AbstractMinecartContainer`:
- Chest minecarts and hopper minecarts. Mineshaft loot is overwhelmingly chest minecarts, so this is first-class coverage, not an afterthought.

**Not instanced:** Ender chests (already per-player), decorated pots (no loot table in vanilla world generation — they hold a single hand-placed item, not a rolled table), and brushable blocks — suspicious sand and gravel (`BrushableBlockEntity`) stay vanilla (global, first-come), as archaeology is a niche feature whose self-destroying, single-item extraction model is disproportionately costly to instance per player.

### Container Adapters

The proxy reaches loot through a thin **adapter** over the vanilla loot-source shapes rather than assuming a single class. An adapter exposes the operations the instancing loop needs — read/clear the loot table and seed, container size, display name, world position — so the §1 core loop (generate-or-retrieve, nullify, serve, persist, scale) is written once against the adapter, not against `RandomizableContainerBlockEntity` directly. Two adapters ship:

- **Block-entity adapter** — wraps `RandomizableContainerBlockEntity`. State lives in an `InstancedLootData` block-entity attachment (§1 Implementation Notes). The common case.
- **Minecart adapter** — wraps `AbstractMinecartContainer` (chest and hopper minecarts). State is the same `InstancedLootData`, registered as a distinct entity-targeted attachment (`INSTANCED_MINECART_LOOT`) so one state class and codec cover every loot-source shape. Interception is `UseEntityCallback.EVENT`. Loot-table nullification, per-player generation, the virtual screen, distance/structure scaling, and persistence are identical to the block path — only the attachment point and the open/close feedback (no lid block event; play the chest open/close sound at the entity's position) differ. A 5-slot hopper minecart is served through a hopper menu, a 27-slot chest minecart through a chest menu. Because a minecart moves, its unlooted indicator (§2) is anchored to the live entity position, not the per-chunk `BlockPos` cache used for static containers.

**Fake-player guard.** Automation mods (quarries, auto-clickers, item routers) open containers through fake `ServerPlayer` proxies, which pass a plain `instanceof ServerPlayer` check. An interaction is treated as a fake-player open — and passed through to vanilla untouched — when the opener has no live client connection (`connection == null`), is absent from the server player list, or is an instance of a recognised fake-player class. Fake openers never generate, retrieve, or mutate an instance, and never trigger loot-table nullification: a machine pointed at a loot container does nothing, leaving every real player's instance intact and the container untouched until a genuine player visits.

### Double Chest Handling

Double chests are two block entities sharing a visual container. When a player interacts with either half:
- Detect the double chest via `ChestBlock.getConnectedDirection()`.
- Both halves are checked for the attachment. Loot is generated and stored on the **primary half** (the half with the smaller BlockPos, using lexicographic comparison of x, z, y). The secondary half's attachment stores a redirect marker pointing to the primary.
- The virtual UI is 54 slots (full double chest) served from the primary half's instanced inventory.
- Both halves fire `blockEvent` for the lid animation.

### Loot Table Nullification

After the first player triggers loot generation for a container, the vanilla `lootTable` and `lootTableSeed` fields on the `RandomizableContainerBlockEntity` are set to `null`. This is critical:
- **Hopper exploit prevention:** Vanilla's `unpackLootTable()` is called whenever a hopper or comparator interacts with the container. Without nullification, a hopper adjacent to the container would generate and extract the global loot, bypassing the instancing system.
- **The original loot table ResourceLocation is preserved** in the attachment (`originalLootTable` field) so it remains available for future player generations.
- **Loot table seed** is also preserved in the attachment. Each player's generation uses this seed combined with their UUID and a refresh salt to produce deterministic-but-unique results per player. The salt is the player's refresh count for that container; it is `0` (and so a no-op) unless `randomizeLootOnRefresh` is enabled, in which case each refresh re-rolls fresh-but-reproducible loot rather than repeating the prior contents.

### Hopper Interaction

Once a container's vanilla loot table has been nullified:
- Hoppers see an **empty container** (the vanilla inventory is empty; instanced inventories live in the attachment).
- This is the correct behavior — hoppers should not extract per-player instanced loot.
- If a player places items into a non-instanced container that happens to be a loot chest they've already opened, those items exist only in their instance and are not hopper-extractable.

### Comparator Output

Comparator output for instanced containers reads **zero** (empty vanilla inventory). This is an acceptable trade-off — the alternative (faking a signal) would require per-player redstone state, which is not possible in vanilla's redstone model.

### Container Destruction

If a player breaks an instanced loot container:
- The block drops as normal (vanilla behavior).
- All instanced loot data (the attachment) is lost. This is intentional — the physical container is destroyed.
- Players who have taken items from the container keep those items (they're in their inventory). Players who hadn't visited yet lose access to that container's potential loot.

### Creative Mode

- Instancing still applies in creative mode. Creative players can use the `/prosperity reset` command to clear instanced data for a specific container.

### Implementation Notes

- Instanced-loot attachment: `InstancedLootData`, a **persistent Fabric data attachment** (`AttachmentRegistry.builder().persistent(CODEC)…`) attached to `RandomizableContainerBlockEntity`. Persistence rides the block entity's own `createNbt`/`read` seam, so it serializes with the chunk and never leaks to the client update tag (Fabric strips attachments from vanilla block entities' sync NBT). Contains:
  - `Map<UUID, NonNullList<ItemStack>> playerInventories` — per-player loot, holding an entry only while a player has **uncollected** items. When a player closes a container they have looted clean, their emptied inventory is evicted from this map so a high-traffic container does not accumulate one stored inventory per visitor indefinitely; their `lastGeneratedTick` stays put as the "has visited" marker, so the container still reads as looted for them (no fresh loot, indicator unchanged) until a refresh clears it. The `lastGeneratedTick`/`refreshCount` maps still grow one small long-valued entry per distinct player.
  - `ResourceKey<LootTable> originalLootTable` — preserved after nullification (the block entity's own loot-table key type, so it copies in/out without conversion).
  - `long originalSeed` — preserved after nullification.
  - `boolean generated` — whether any player has triggered generation (and the vanilla loot table has been nullified).
  - `Map<UUID, Long> lastGeneratedTick` — per-player absolute game time of generation, for loot refresh (§8).
  - cached `String tierName` / `ResourceLocation structure` — resolved scaling state for notifications and tooltips (§3, §6).
  - `BlockPos redirect` — on a double chest's secondary half, points at the primary half that holds the shared inventory.
  - The attachment is latent: a naturally-placed storage container has none until loot is generated, so it stays byte-identical to vanilla.
  - **Dirtying invariant:** mutating the attached value in place (e.g. updating `playerInventories`) must be followed by `blockEntity.setChanged()` — only `setAttached(...)` auto-marks the block entity dirty. Every write path goes through one helper that re-attaches or calls `setChanged()`, so no mutation can silently fail to persist.
- A parallel attachment covers the minecart shape: an **entity** attachment (`INSTANCED_MINECART_LOOT`) on `AbstractMinecartContainer`, reusing the same `InstancedLootData` state and codec as the block-entity attachment, so it flows through the same generation, nullification, scaling, and refresh code paths via the container adapter (see Container Adapters).
- Virtual container screen: `SimpleInventory` wrapped in a `SimpleMenuProvider`. The `AbstractContainerMenu` subclass syncs slot changes back to the attachment on close (followed by `setChanged()`).
- `UseBlockCallback.EVENT` handler checks: (1) block entity exists, (2) is `RandomizableContainerBlockEntity`, (3) has a loot table OR has the attachment with `generated=true`. If none of these, pass through to vanilla.
- Mixin into `RandomizableContainer#unpackLootTable()` (the default method the block entity inherits) as a safety net — if the attachment exists and `generated=true`, skip vanilla generation entirely. This catches edge cases where vanilla code calls unpack directly. A parallel safety-net mixin covers the minecart shape: the chest-vehicle unpack method on `ContainerEntity`.

---

## 2. Visual Indicators

Client-side visual markers on unlooted containers so players can identify which containers they haven't opened yet.

### Problem

In vanilla (and even with instanced loot), players cannot tell whether they've already looted a container without opening it. In large structures (strongholds, mansions, mineshafts), this leads to repeated backtracking and re-checking.

### Behavior

- **Unlooted containers** (player has never opened this instanced container) display a small sprite overlay hovering above the block.
- **Looted containers** (player has opened and received their instanced loot) display no indicator.
- **Non-loot containers** (placed by players, no loot table) display no indicator.

### Visual Design

- A small 2D sprite rendered in world space, centered 0.25 blocks above the container's top face, always facing the camera (billboard).
- Sprite: a four-point sparkle that pulses over a 4-frame animated strip (16×16 per frame, stored as a 16×64 sheet, `assets/prosperity/textures/overlay/unlooted.png`, source `art/glyphs/unlooted-sparkle.glyph`). Gold body with diamond-cyan core to evoke treasure.
- Subtle bobbing animation (sinusoidal Y offset, ±0.05 blocks, 2-second period).
- Renders through walls up to **8 blocks** (configurable) — useful in mineshafts where containers are behind walls. Beyond 8 blocks, occluded containers are hidden.
- Maximum render distance: **48 blocks** (configurable). Beyond this, indicators are not rendered for performance.
- Fade-out: indicators fade to transparent over the last 8 blocks of render distance (smooth disappearance, not a hard cutoff).

### Rendering Approach

- Rendered via `WorldRenderEvents.LAST` (Fabric Rendering API).
- Uses `VertexConsumer` on a `RenderType` with translucency and depth testing disabled for the through-wall range, depth testing enabled beyond it.
- **Sodium/EBE compatibility:** This approach does not touch block rendering, chunk meshing, or block entity rendering. It is a post-pass overlay — fully compatible with any block renderer.
- **Iris/shader compatibility:** Rendering in `LAST` happens after the main scene pass. Shaders may apply post-processing (bloom, etc.) to the overlay. This is acceptable and requires no workaround.

### Data Sync

- The server does not push unlooted container positions to the client. Instead:
  - When a chunk is loaded on the client, the client sends a lightweight request for instanced container positions in that chunk.
  - The server responds with a list of `BlockPos` entries in that chunk where the requesting player has **not** yet generated loot, along with the container type (for sizing the indicator).
  - The client caches this data per-chunk and invalidates it when: (a) the player opens a container, (b) a chunk is unloaded, (c) the player receives a sync packet indicating a container has been broken.
- **Packet: `UnlootedContainersS2C`** — sent per-chunk, contains a list of entries relative to the chunk origin: the in-chunk XZ packed into one byte (`(relX << 4) | relZ`), the world Y as a short, and the container's slot count as a VarInt (the client derives single-vs-double from `slots == 54`).
- **Packet: `ContainerLootedS2C`** — sent when the player opens an instanced container, so the client removes the indicator.
- **Packet: `ContainerRemovedS2C`** — sent when a loot container is broken, so all clients remove the indicator.

### Implementation Notes

- Client-side rendering class: `UnlootedOverlayRenderer`, registered via `WorldRenderEvents.LAST`.
- Chunk data cache: `Map<ChunkPos, Set<BlockPos>>` on the client, populated from `UnlootedContainersS2C` packets.
- The server-side handler for the chunk request iterates the chunk's block entities, filters for those carrying `InstancedLootData`, and checks the player's UUID against the attachment's map.
- Container minecarts move, so they are tracked per-entity rather than through the per-chunk `BlockPos` cache: the server includes proxy-managed minecarts the requesting player has not generated in the chunk response, and the client anchors their indicator to the live entity position, refreshing it as the entity moves or is removed.
- Performance target: smooth rendering with 200+ indicators in view. The billboard sprite is a single quad per indicator — GPU cost is trivial.

---

## 3. Distance-Based Loot Scaling

Loot quality and quantity scale with distance from world spawn. Replaces the core functionality of BetterLoot.

### Problem

Vanilla loot tables produce the same quality loot at any distance from spawn. A chest 100 blocks from spawn contains the same tier of items as one 10,000 blocks away. This removes the incentive to explore further — and in difficulty-scaled worlds (e.g. Tribulation), the risk/reward ratio breaks because risk scales up but rewards stay flat.

### Behavior

When instanced loot is generated for a player (section 1, step 4), a **distance scaling modifier** is applied to the loot generation context:

1. **Calculate distance** — Euclidean distance from the container's `BlockPos` to world origin (`0, 0` in the XZ plane, Y ignored). World origin is used rather than world spawn because spawn can be moved by commands or datapacks, and distance scaling should represent absolute geography, not a movable reference point.
2. **Determine tier** — The distance falls into a configurable tier bracket.
3. **Apply modifier** — The tier's multiplier affects loot generation.

### Distance Tiers

| Tier | Distance (blocks) | Stack Multiplier | Quality Modifier | Description |
|---|---|---|---|---|
| Local | 0 – 999 | 1.0x | 0 | Baseline. Vanilla loot unchanged. |
| Frontier | 1,000 – 2,999 | 1.5x | +1 | Noticeably more of each item. |
| Wilderness | 3,000 – 5,999 | 2.0x | +2 | Double stack sizes. Worth the trip. |
| Outlands | 6,000 – 9,999 | 2.75x | +3 | Nearly triple. Significantly better. |
| Depths | 10,000+ | 3.5x | +4 | Best possible loot. Endgame territory. |

All tier boundaries and multipliers are configurable.

### Quantity Scaling — Stack Size

The stack multiplier scales the **count of each generated item stack**, not the number of rolls or pools:
- After the loot table resolves normally, each item stack in the result has its count multiplied: `newCount = floor(originalCount * stackMultiplier)`.
- **Stackable items only.** Items with a max stack size of 1 (tools, weapons, armor, enchanted books) are not affected. The multiplier targets consumables and materials — iron ingots, arrows, gold, food, etc.
- **Capped at max stack size (64).** A stack of 24 arrows at Depths tier → `floor(24 * 3.5) = 84` → capped to 64.
- **Minimum count preserved.** The multiplier never reduces a stack below its original count (relevant at 1.0x baseline, but future-proofs against sub-1.0 multipliers if configured).
- Example: 8 iron ingots at Wilderness → `floor(8 * 2.0) = 16`. 3 diamonds at Outlands → `floor(3 * 2.75) = 8`. 1 enchanted book at Depths → unchanged (non-stackable).

### Quality Scaling

The quality modifier adjusts the **luck** parameter passed to loot table generation:
- Vanilla loot tables use `luck` to bias quality conditions (`random_chance_with_looted_enchantment`, weighted random selections, etc.).
- The quality modifier is added to the player's effective luck value for this generation.
- This stacks with the player's `generic.luck` attribute and any other luck sources (see section 4).
- Higher luck biases loot toward rarer entries in loot pools that use `quality` weights.

### Nether and End

- **Nether:** Distance is calculated from the container's Nether coordinates (not multiplied by 8). Nether loot tables are typically higher quality by default, so the same tier thresholds apply but the effective scaling is less dramatic due to the dimension's compressed geography.
- **End:** All End containers are treated as **Depths tier** (maximum scaling), regardless of distance. The End is endgame content — scaling it by distance from the origin portal would arbitrarily penalize end cities near the main island.

### Implementation Notes

- Quality scaling (luck) is applied by modifying the `LootParams` before the loot table is resolved.
- Quantity scaling (stack size) is applied as a post-processing step after loot table resolution: iterate the generated `List<ItemStack>`, check `getMaxStackSize() > 1`, multiply count, clamp to max stack size.
- The distance calculation and tier lookup are performed once per loot generation (when the player first opens the container) and cached in the attachment alongside the generated inventory.
- Tier data stored in config as an ordered list of `{minDistance, stackMultiplier, qualityModifier}` objects. The list is walked from highest to lowest distance; first match wins.

---

## 4. Loot Modifier API

An extensible hook that allows other mods to inject custom attributes into the loot generation context. Replaces the functionality of LootIntegrations.

### Problem

Loot generation in vanilla considers only the loot table definition and a fixed context (luck, position, killing entity). Mods that add RPG-style attributes (skill levels, perks, class bonuses) have no way to influence loot quality without wholesale loot table replacement. This leads to incompatible, overlapping loot modifications across mods.

### Design

A Fabric-style event callback (`LootModifierCallback.EVENT`) that fires **after** distance scaling (section 3) but **before** the loot table is resolved. Registered listeners receive a mutable context object and can adjust the loot generation parameters.

### API Surface

```java
public interface LootModifierCallback {
    Event<LootModifierCallback> EVENT = EventFactory.createArrayBacked(
        LootModifierCallback.class,
        (listeners) -> (context) -> {
            for (LootModifierCallback listener : listeners) {
                listener.onModifyLoot(context);
            }
        }
    );

    void onModifyLoot(LootModifierContext context);
}
```

```java
public interface LootModifierContext {
    // The player receiving the loot
    ServerPlayer player();

    // The container's position
    BlockPos containerPos();

    // The loot table being resolved
    ResourceLocation lootTable();

    // Current effective luck (base + distance scaling + attribute)
    float luck();
    void setLuck(float luck);

    // Additive luck bonus (applied on top of current luck)
    void addLuck(float bonus);

    // Current stack size multiplier (from distance scaling)
    float stackMultiplier();
    void setStackMultiplier(float multiplier);

    // Multiply the existing stack multiplier
    void multiplyStacks(float factor);

    // Custom data bag for inter-mod communication
    CompoundTag customData();
}
```

### Default Registration — Vanilla Luck Attribute

Prosperity registers its own listener at default priority that reads the player's `generic.luck` attribute and adds it to the context's luck value. This ensures vanilla luck (from potions, equipment, etc.) always participates in loot generation, even when other mods are also modifying the context.

```java
LootModifierCallback.EVENT.register(context -> {
    double vanillaLuck = context.player()
        .getAttributeValue(Attributes.LUCK);
    context.addLuck((float) vanillaLuck);
});
```

### Example: External RPG Mod Integration

An external RPG mod (e.g. a hypothetical "Meridian Skills" addon) can register a listener:

```java
LootModifierCallback.EVENT.register(context -> {
    int lootSkillLevel = SkillManager.getLevel(context.player(), Skills.PROSPECTING);
    // Each level of Prospecting adds 0.5 effective luck
    context.addLuck(lootSkillLevel * 0.5f);
    // High Prospecting also boosts stack sizes slightly
    if (lootSkillLevel >= 10) {
        context.multiplyStacks(1.1f);
    }
});
```

### Event Ordering

- Listeners fire in registration order (Fabric default).
- Prosperity's own distance scaling and vanilla luck listeners register at initialization. External mods register during their own `onInitialize()`, which runs after Prosperity's due to mod load order (or they can declare a dependency to ensure ordering).
- All listeners see and can modify the cumulative state — a later listener sees the luck value after earlier listeners have adjusted it.

### Custom Data Bag

The `customData()` `CompoundTag` is an unstructured key-value store for inter-mod communication during a single loot generation event. Use cases:
- An RPG mod writes a `"prospecting_level"` key; a loot table condition (also from the RPG mod) reads it.
- A quest mod writes a `"quest_bonus"` flag; a loot modifier from the same mod reads it to inject quest-specific items.
- Prosperity itself does not read or write to this bag — it exists purely for third-party use.

### Implementation Notes

- `LootModifierCallback` is a Fabric `Event` (not a custom event bus). Registration is type-safe and follows the standard Fabric event pattern.
- `LootModifierContext` is created fresh for each loot generation, populated with the player, position, loot table, and the post-distance-scaling values.
- The context is passed to all listeners, then its final `luck` value is used to build the `LootParams` for loot table resolution, and the final `stackMultiplier` is applied as a post-processing step on the generated items.
- The API classes (`LootModifierCallback`, `LootModifierContext`) are in a separate `api` subpackage (`com.rfizzle.prosperity.api`) and are annotated with `@ApiStatus.Stable` to signal they are safe for external mods to depend on.

---

## 5. Loot Table Injection

Datapack-driven system to add custom items to existing vanilla loot tables based on distance tier.

### Problem

Vanilla loot tables are static definitions. Distance scaling (section 3) adjusts quantity and quality, but the *pool of possible items* stays the same — a desert temple chest at 10,000 blocks rolls the same item list as one at 500 blocks, just with better odds. True loot progression needs tier-exclusive items that only appear at higher distances, giving players concrete rewards for pushing further.

### Behavior

Custom loot entries are defined in datapack files and injected into vanilla loot table resolution at runtime. Each entry specifies:
- The **target loot table** to inject into (e.g. `minecraft:chests/simple_dungeon`).
- The **minimum distance tier** required for the entry to be eligible.
- An optional **dimension filter** restricting the entry to specific dimensions.
- The **item(s)** to add, with full data component support.
- An optional **weight** controlling how often the entry appears relative to the pool it joins.

### Datapack Schema

Files at `data/prosperity/loot_injections/<name>.json`:

```json
{
  "replace": false,
  "injections": [
    {
      "target": "minecraft:chests/simple_dungeon",
      "min_tier": "frontier",
      "entries": [
        {
          "item": "minecraft:enchanted_book",
          "count": 1,
          "components": {
            "minecraft:stored_enchantments": {
              "levels": { "minecraft:sharpness": 3 }
            }
          },
          "weight": 5
        }
      ]
    },
    {
      "target": "minecraft:chests/stronghold_corridor",
      "min_tier": "outlands",
      "dimensions": [ "minecraft:the_nether" ],
      "entries": [
        {
          "item": "minecraft:netherite_upgrade_smithing_template",
          "count": 1,
          "weight": 1
        }
      ]
    }
  ]
}
```

- `replace`: If `true`, replaces all Prosperity injections for the affected target loot tables. Does not affect vanilla entries.
- `target`: `ResourceLocation` of the vanilla loot table to inject into.
- `min_tier`: Minimum distance tier name (matches config tier names: `local`, `frontier`, `wilderness`, `outlands`, `depths`). Entry is only eligible if the container is at or above this tier.
- `dimensions`: Optional list of dimension IDs the entry is restricted to (e.g. `["minecraft:the_nether"]`). Omitted or empty matches any dimension. Composes with `min_tier` — both gates must pass for the entry to be eligible.
- `requires_mods`: Optional list of mod IDs that must **all** be loaded for the injection to apply (e.g. `["meridian"]`). Omitted or empty is unconditional. Evaluated at load time only — an injection naming an absent mod is silently dropped (no log spam), so a file can mix unconditional injections with ones scoped to a sibling mod.
- `entries[].item`: Item ID.
- `entries[].count`: Stack count (default 1).
- `entries[].components`: Optional data components (same format as vanilla `/give` and recipe definitions).
- `entries[].enchant_randomly`: Optional enchantment tag ID (with or without a `#` prefix, e.g.
  `"#meridian:rarity/rare"`) making the entry **generative**: at draw time one enchantment is picked
  uniformly from the tag's members and stored on the item at the `level` policy, so a whole catalog is
  covered without enumerating it per enchant. Mutually exclusive with `components` (a file mixing both
  in one entry fails to parse). The pick uses the injection draw's deterministic per-player
  `RandomSource`, so instanced loot stays reproducible. A tag that resolves empty or absent (mod not
  installed, empty tag) drops the entry from the pool *before* weighting — the draw slot falls to the
  remaining eligible entries rather than being wasted.
- `entries[].level`: Level policy for a generative entry, relative to the drawn enchantment's own
  `[min, max]` range: `mid` (the rounded-up midpoint, ⌈max/2⌉, floored at min), `max` (the top level),
  or `uniform` (the default — a uniform draw over the whole range, vanilla `enchant_randomly`
  semantics; the only policy that consumes randomness).
- `entries[].weight`: Relative weight within the loot pool (default 1). Higher weight = more likely to appear. Injected entries compete with existing pool entries.

#### Mod-presence gating

Two complementary gates let an in-jar datapack carry injections that activate only when a sibling mod is present, without shipping split datapacks:

- **`requires_mods`** (above) — per-injection, conjunctive, the minimal form for "this entry needs mod X."
- **`fabric:load_conditions`** — a file-level [Fabric resource conditions](https://docs.fabricmc.net/develop/data-generation/conditions) header, evaluated before the file is parsed. It provides `fabric:and` / `fabric:or` / `fabric:not` / `fabric:all_mods_loaded` for richer logic; an unmet header skips the whole file silently.

```json
{
  "fabric:load_conditions": [
    { "condition": "fabric:all_mods_loaded", "values": [ "meridian" ] }
  ],
  "injections": [
    {
      "target": "minecraft:chests/stronghold_library",
      "min_tier": "outlands",
      "requires_mods": [ "meridian" ],
      "entries": [ { "item": "meridian:guide_book", "weight": 1 } ]
    }
  ]
}
```

Both gates are re-evaluated on every load (`SERVER_STARTING` and `END_DATA_PACK_RELOAD`). Existing injections with neither field are unaffected.

### Built-In Injections

Prosperity ships a default set of injections to make distance scaling feel meaningful out of the box:

| Tier | Injections (`prosperity:all_chests`) |
|---|---|
| Frontier | Sharpness III book, Protection III book, iron horse armor |
| Wilderness | Enchanted golden apple, diamond horse armor, Otherside music disc |
| Outlands | Netherite upgrade template, Sharpness V book, Efficiency IV diamond pickaxe |
| Depths | Netherite upgrade template (×2), Mending book, Silk Touch book, trident |

The default set is conservative — it adds items that already exist in vanilla progression but are normally structure-locked or extremely rare. Pack makers can extend or replace via datapacks.

When [Meridian](https://github.com/rfizzle/meridian) is installed, a Meridian-gated
in-jar file (`meridian_books.json`) adds its full non-curse enchantment catalog
(73 enchants) to the same `prosperity:all_chests` pool as vanilla
`minecraft:enchanted_book` items carrying `meridian:*` enchants in
`stored_enchantments`. Each enchant's home tier follows Meridian's own rarity:

| Meridian rarity | Home tier |
|---|---|
| Common | Frontier |
| Uncommon | Wilderness |
| Rare | Outlands |
| Very Rare (treasure) | Depths |

A multi-level enchant appears twice: at its home tier at mid level (⌈max/2⌉) and
one tier deeper at max level (Very Rare enchants get both at Depths) — so deeper
travel upgrades the same enchants, and the treasure-tagged set (unavailable from
Meridian's enchanting table) makes distant chests a genuine acquisition path.
Every book has weight 1 against the built-in files' weights of 12–60, holding the
Meridian share of injected items to roughly 3% at Frontier rising to ~24% at
Depths. The file carries both gates: a file-level `fabric:load_conditions`
header, so its `meridian:*` enchantment components never reach the registry-aware
codec when Meridian is absent, and `requires_mods` on each injection.

At generation time the authored enchantments on those books are the fallback,
not the served result: `MeridianCompat` (in `compat/meridian`, class-loaded only
behind `isModLoaded("meridian")`) installs the injection manager's
stack-finalizer hook, and any injected `enchanted_book` carrying a `meridian:*`
stored enchantment has its enchantments rolled live via
`MeridianAPI.rollLootEnchantments` — the same rules as Meridian's enchanting
table — at a power derived from the container's distance tier. The tier→power
curve (`MeridianEnchantPower`) is conservative and pure math: index 0 (`local`)
rolls nothing, the first travelled tier rolls at power 8, and the curve ramps
linearly to 30 at the ladder's deepest tier; treasure-tagged enchantments only
roll at the deepest tier, and Meridian's per-enchantment `maxLootLevel` caps the
rolled levels. The roll consumes the injection draw's own deterministic
`RandomSource` (container seed × refresh salt × player UUID), so instanced loot
stays reproducible per player and re-rolls in lockstep with the main loot under
`randomizeLootOnRefresh`. A Meridian call that fails — including the
`LinkageError` from an older Meridian jar without the roll API — is contained
and logged once, leaving the authored static enchantments standing. Books from
the built-in vanilla injection files pass through the hook untouched.

### Wildcard Targets

The special target `"prosperity:all_chests"` injects into every loot table matching `**/chests/**`. This allows pack makers to add items globally without listing every loot table individually.

### Implementation Notes

- Injection data is loaded on `SERVER_STARTING` (and re-loaded on `END_DATA_PACK_RELOAD` for runtime `/reload`) by `LootInjectionManager`, which reads each file with a registry-aware codec so item components deserialize against the loaded enchantment/effect registries.
- At loot generation time (section 1, step 4), after the distance tier is determined, `LootInjectionManager.augment` queries the injection registry for entries matching the container's loot table whose `min_tier` is at or below the resolved tier and whose `dimensions` filter (if any) contains the container's dimension. The dimension is threaded from the generation call site via `ServerLevel#dimension()`.
- Injection is purely additive: the eligible entries form a single weighted pool and exactly one is drawn (deterministically, from the container's per-player seed and refresh salt — so it re-rolls in lockstep with the main loot when `randomizeLootOnRefresh` is on) and placed in a spare slot. Vanilla loot is never displaced — injected rewards sit alongside the rolled items rather than competing with them in a vanilla pool.
- The injection registry is a `Map<ResourceLocation, List<TieredInjection>>` keyed by target loot table, rebuilt wholesale and published atomically on each load. Lookup is O(1) per loot table.
- Wildcard targets are expanded to concrete loot table IDs at load time by scanning the resource manager for loot tables whose path contains a `chests/` segment.

---

## 6. Structure-Specific Scaling

Override distance tiers on a per-structure basis for fine-grained loot control.

### Problem

Distance-based tiers work well as a general rule, but some structures have inherently fixed difficulty regardless of where they generate. An ocean monument at 500 blocks from spawn is just as dangerous as one at 8,000 blocks — and its loot should reflect that. Similarly, a village blacksmith chest near spawn shouldn't get inflated loot just because the village happens to be at 1,500 blocks.

### Behavior

Each structure type can be assigned a **tier override** that replaces (or sets a minimum/maximum for) the distance-calculated tier:

- **Fixed tier:** The structure always uses this exact tier, ignoring distance. Example: ocean monuments → always Wilderness.
- **Minimum tier:** The structure uses at least this tier, even if distance would place it lower. Example: end cities → minimum Outlands (though the End dimension rule already handles this).
- **Maximum tier:** The structure uses at most this tier, even if distance would place it higher. Example: villages → maximum Frontier (prevents inflated loot from village chests at high distances).

### Configuration

Structure overrides are defined in the server config:

```json
{
  "structureOverrides": [
    { "structure": "minecraft:monument",           "mode": "fixed",   "tier": "wilderness" },
    { "structure": "minecraft:stronghold",          "mode": "minimum", "tier": "outlands"   },
    { "structure": "minecraft:village_plains",      "mode": "maximum", "tier": "frontier"   },
    { "structure": "minecraft:village_desert",      "mode": "maximum", "tier": "frontier"   },
    { "structure": "minecraft:village_savanna",     "mode": "maximum", "tier": "frontier"   },
    { "structure": "minecraft:village_snowy",       "mode": "maximum", "tier": "frontier"   },
    { "structure": "minecraft:village_taiga",       "mode": "maximum", "tier": "frontier"   },
    { "structure": "minecraft:ancient_city",        "mode": "minimum", "tier": "outlands"   },
    { "structure": "minecraft:trail_ruins",         "mode": "minimum", "tier": "frontier"   },
    { "structure": "minecraft:trial_chambers",      "mode": "minimum", "tier": "wilderness" }
  ]
}
```

- `structure`: `ResourceLocation` of the structure type (from `BuiltInRegistries.STRUCTURE`). Supports modded structures.
- `mode`: `"fixed"`, `"minimum"`, or `"maximum"`.
- `tier`: Tier name matching the distance tier config.

### Default Overrides

Prosperity ships sensible defaults (shown above). The design principle: **structures with fixed difficulty get fixed or minimum tiers; structures with trivial loot get maximum caps.**

### Structure Detection

When loot is generated for a container, the structure it belongs to must be determined:
- Query `StructureManager.getStructureWithPieceAt(blockPos)` to find the structure containing the container.
- If the container is not inside any structure (e.g. a standalone dungeon spawner chest), no override applies — pure distance scaling.
- If the container is inside multiple structures (rare but possible with overlapping generation), the **most specific** structure wins (the one with the smallest bounding box containing the block).

### Interaction with Distance Scaling

Structure overrides are part of distance scaling, applied **after** the base distance tier is calculated:
1. Calculate distance tier from world origin (section 3).
2. Look up the container's structure in the configured override list.
3. Apply the override mode (tiers compared by `minDistance`):
   - `fixed`: Replace the tier entirely.
   - `minimum`: Use `max(distanceTier, overrideTier)`.
   - `maximum`: Use `min(distanceTier, overrideTier)`.
4. The resolved tier is used for quantity multiplier, quality modifier, and loot injection eligibility.

Because overrides are part of scaling, `enableDistanceScaling = false` suppresses them along with distance bands (the generation falls back to vanilla quantities/quality everywhere), and structure detection is skipped entirely when no overrides are configured.

### Implementation Notes

- Structure overrides are stored in config as a list of `{structure, mode, tier}` objects and matched by structure id at generation time. The list is short and matched only once per container's first generation, so a linear scan is used rather than a derived map; structure detection (`getAllStructuresAt`) dominates the cost. An override naming an unknown mode or a tier the config does not define degrades gracefully to pure distance scaling.
- `StructureManager` access requires the `ServerLevel` — available during loot generation since it happens server-side.
- Structure lookup is cached in the attachment alongside the generated inventory (the structure won't change after generation).
- Modded structures are supported automatically — any `ResourceLocation` in the structure registry works.

---

## 7. Loot Table Blacklist

Config-driven list of loot tables excluded from instancing.

### Problem

Not every loot container benefits from instancing. Some modded containers have custom interaction logic that conflicts with event interception. Some server admins want specific structures to remain first-come-first-served for gameplay reasons. Without a blacklist, the only option is disabling the entire instancing system.

### Behavior

- Loot tables on the blacklist are **completely ignored** by Prosperity's `UseBlockCallback` handler. The container opens with vanilla behavior — global loot, no per-player instances, no visual indicator.
- Distance scaling, loot injection, and all other Prosperity features do not apply to blacklisted containers.
- The blacklist is a list of `ResourceLocation` patterns in the server config.

### Pattern Matching

Entries support two formats:
- **Exact match:** `"minecraft:chests/village/village_weaponsmith"` — matches only this specific loot table.
- **Wildcard:** any entry ending in `*` matches by prefix. `"somebigmod:*"` excludes a whole namespace (useful for blanket-excluding a mod that manages its own container logic); `"minecraft:chests/*"` excludes a whole subtree; a bare `"*"` excludes everything.

### Default Blacklist

Empty by default — all loot containers are instanced. The blacklist is opt-in for server admins who encounter specific conflicts.

### Configuration

```json
{
  "lootTableBlacklist": [
    "somebigmod:*",
    "minecraft:chests/village/village_weaponsmith"
  ]
}
```

### Implementation Notes

- Blacklist is parsed at config load into a `LootBlacklist` matcher cached on the live config: a `HashSet` of exact `namespace:path` ids plus a list of wildcard prefixes. Rebuilt by `ProsperityConfig.clamp()` on every load/reload.
- The matcher is checked against the source's **live** loot table at the interaction gate — both the block `UseBlockCallback` (single and double-chest paths) and the minecart `UseEntityCallback`. A blacklisted container returns `InteractionResult.PASS` (full vanilla behavior) before any instance is generated. Gating on the live table means a fresh blacklisted container is never instanced or nulled, while an already-generated container (live table null) is served normally — instancing cannot be undone retroactively.
- Blacklist check is O(1) for exact matches (HashSet lookup) and O(n) for wildcards (n = number of wildcard entries, typically very small).
- The blacklist is also respected by the visual indicator system — both the block and minecart unlooted scans skip blacklisted containers, so they never show the unlooted sparkle.

---

## 8. Loot Notifications

Action bar messages showing the loot tier and active modifiers when a player opens an instanced container.

### Problem

Distance scaling and loot modifiers are invisible by default. Players have no feedback that the system is working — they can't tell whether the good loot they found is because they're far from spawn or just lucky. Without feedback, the "risk vs. reward" loop that motivates exploration doesn't land.

### Behavior

When a player opens an instanced container **for the first time** (loot generation, not return visit):
- An action bar message appears showing the resolved loot tier and any active modifiers.
- Format: `"✦ Wilderness — 2.0x stacks, +2 quality"`
- The message is brief (action bar, not chat) and non-intrusive.

### Message Components

| Component | Source | Example |
|---|---|---|
| Tier name | Distance scaling (section 3) / structure override (section 6) | "Wilderness" |
| Stack multiplier | Final value after all modifiers | "2.0x stacks" |
| Quality modifier | Final value after all modifiers | "+2 quality" |
| Structure override indicator | If a structure override changed the tier | "(Ocean Monument)" |

### Examples

- `"✦ Frontier — 1.5x stacks, +1 quality"` — basic tier notification.
- `"✦ Outlands — 2.75x stacks, +3 quality (Ancient City)"` — structure override active.
- `"✦ Depths — 3.5x stacks, +4 quality"` — maximum tier, no override.
- `"✦ Local"` — baseline tier, multipliers omitted when default.

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableLootNotifications` | bool | true | Toggle action bar notifications |

### Notification Timing

- Only on **first open** (loot generation). Returning to an already-opened container does not re-show the message.
- Only when instanced loot is active for this container (not blacklisted, not vanilla passthrough).
- If loot refresh (section 9) regenerates loot, the notification fires again on the fresh generation.

### Implementation Notes

- Server-side: after loot generation completes, send the resolved tier data to the player with `ServerPlayer#displayClientMessage(component, true)` — a system-chat packet with `overlay=true` (action bar placement).
- The message is built from the `LootModifierContext` final values, so it reflects all modifiers (distance + structure override + API listeners): the multiplier and quality shown are the post-listener `stackMultiplier` and `luck` (the latter rounded to a whole number), not the raw tier values.
- Assembled from three translation keys: `prosperity.notification.loot_generated` (`✦ %s`, the tier name), `prosperity.notification.modifiers` (` — %sx stacks, +%s quality`, appended only when a value is off its baseline — so the bare tier shows at Local), and `prosperity.notification.structure` (` (%s)`, appended only when a structure override changed the tier from the pure distance band). The multiplier renders in natural-decimal form (`2.0`, `2.75`); structure names resolve through `prosperity.structure.*` with a humanized-path fallback for unmapped (e.g. modded) structures.

---

## 9. Loot Refresh

Containers can regenerate loot after a configurable cooldown, simulating restocking.

### Problem

On long-running servers, all containers eventually get looted by all players, and there's no reason to revisit explored structures. A refresh mechanic keeps exploration relevant over weeks and months of play.

### Behavior

- **Cooldown:** After a player generates loot for a container, a timer starts. Once the cooldown expires, the player's instanced inventory for that container is **cleared** (not regenerated immediately).
- **Next visit:** When the player opens the container after their cooldown has expired, fresh loot is generated as if they'd never visited.
- **Determinism:** By default the re-roll is deterministic — the same player draws the same items the container held before (the roll seed depends only on the preserved seed and their UUID). Enabling `randomizeLootOnRefresh` folds the player's refresh count into the seed as a salt, so each refresh draws different items while staying reproducible across a reload for a given count. The salt advances on every clear (cooldown refresh and `/prosperity reset|refresh` alike).
- **Default cooldown:** 7 in-game days (168,000 ticks), configurable.
- **Per-player:** Each player's cooldown is independent. Player A's loot may refresh while Player B's is still on cooldown.
- **Visual indicator:** When a player's loot has refreshed (cooldown expired, inventory cleared), the gold sparkle reappears on the client (the container is "unlooted" again for this player). A chunk the client requests after expiry lights up from the scan on its own; a low-frequency server sweep covers the gap where a chunk was already loaded when the cooldown elapsed, resending that chunk's indicator set to the player who just crossed the threshold.

### Cooldown Tracking

- Stored in the attachment: `Map<UUID, Long> lastGeneratedTick` — the game tick when each player's loot was last generated.
- On any interaction, the handler checks `currentTick - lastGeneratedTick >= cooldownTicks`. If true, the player's entry in `playerInventories` is removed, and the container is treated as unvisited.
- Cooldown expiry is computed on demand (at the open path and the indicator scan), not per-tick. The only periodic work is the indicator sweep, which runs on a coarse interval (every 600 ticks) and only when both `enableLootRefresh` and `enableVisualIndicators` are on; it sends a packet to a player solely when one of their instances in a loaded, tracked chunk transitions to expired, so a player standing still triggers one resend per container rather than one per tick.

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableLootRefresh` | bool | false | Toggle loot refresh (disabled by default) |
| `lootRefreshDays` | int | 7 | In-game days before loot refreshes |
| `randomizeLootOnRefresh` | bool | false | Re-roll fresh loot on each refresh instead of repeating the same items |

### Implementation Notes

- Cooldown is stored as a game tick value (absolute, not relative). This survives server restarts because Fabric persists the world's game time.
- When `enableLootRefresh` is false, `lastGeneratedTick` is still recorded (no cost) so enabling the feature later retroactively applies to already-looted containers.
- Clearing a player's inventory entry resets the container to "unvisited" for that player. A per-player refresh count is tracked in the attachment and advanced on each clear (it outlives the cleared inventory). It is the salt for `randomizeLootOnRefresh`; it is always tracked, so toggling the option on applies to subsequent refreshes without migration.

---

## 10. Jade/WTHIT Container Details

Tooltip overlay showing loot status and scaling information when looking at instanced containers.

### Problem

Players looking at a container have no way to know its loot status without opening it. The visual indicator (section 2) communicates "unlooted" vs. "looted" at a glance, but doesn't convey distance tier, refresh timing, or why this container's loot is special. Jade and WTHIT are the standard way to surface block-level information.

### Tooltip Information

When looking at a container that has (or had) a loot table, the Jade/WTHIT tooltip shows:

| Line | Condition | Example |
|---|---|---|
| Loot status | Always | "Unlooted" / "Looted" / "Refreshed" |
| Distance tier | When distance scaling is enabled | "Wilderness tier (2.0x stacks)" |
| Structure override | When a structure override is active | "Ancient City — min. Outlands" |
| Refresh timer | When loot refresh is enabled and container is looted | "Refreshes in: 2d 14h" |
| Blacklisted | When container's loot table is blacklisted | "Vanilla loot (not instanced)" |

### Loot Status States

- **Unlooted** — Player has never opened this container. Gold text.
- **Looted** — Player has opened and generated loot. Gray text.
- **Refreshed** — Player's loot has expired and new loot is available. Green text.
- **Vanilla** — Container is blacklisted; vanilla behavior applies. White text.

### Refresh Timer Format

- Displayed as `Xd Yh` (days and hours) when more than 1 hour remaining.
- Displayed as `Xm` (minutes) when less than 1 hour remaining.
- Not shown when loot refresh is disabled or the container is unlooted.

### Implementation Notes

- Jade plugin: `IWailaPlugin` registering a `IBlockComponentProvider` for blocks with `RandomizableContainerBlockEntity`.
- WTHIT plugin: parallel implementation via `waila_plugins.json`.
- Server-side data provider sends: loot status (enum), distance tier name + multipliers, structure override (if any), last generated tick (for refresh timer calculation), blacklist status.
- Both plugins are optional dependencies — feature is simply absent if neither is installed.
- Data is sent per-look (standard Jade/WTHIT server data request pattern), not pushed proactively.

---

## 11. Loot Index (EMI / REI / JEI Integration)

A searchable catalog of loot table contents integrated into recipe viewers. Shows what items can drop from which structures, filterable by distance tier.

### Problem

Vanilla provides no way to see what a structure's loot table contains without opening the data files or consulting a wiki. With Prosperity adding distance-based scaling and loot injection, the effective loot pool changes based on where you are — making discovery even harder. Players need an in-game reference that answers "what can I find in a stronghold at Outlands tier?"

### Loot Index View

A single, browseable list of **all** loot table entries across all structures and tiers.

**Entry format (one row per item source):**

```
[Structure Icon]  [Output Item]    Loot Table: chests/simple_dungeon    Tier: Frontier+
```

- Structure icon: representative item for the structure (e.g. mossy cobblestone for dungeons, prismarine for monuments, deepslate for ancient cities). Mapped in a registry class.
- Items rendered as standard recipe viewer item slots (hoverable for full tooltip).
- Tier badge shows the minimum tier where this entry is available. Entries from vanilla loot tables (no tier restriction) show "Any tier."

**Search integration:**
- Fully indexed by the recipe viewer's search. Typing "mending" shows all loot sources that can drop Mending books. Typing "netherite" shows structures and tiers where netherite items are available.
- Bidirectional: search by output item to find where it drops.

**Filtering:**

Each filter axis is a marker item registered as a recipe-viewer workstation/catalyst and attached to the matching rows as an invisible ingredient; viewing that item's "uses" narrows the index to those rows. The same mechanism backs all three viewers (EMI, REI, JEI) and all three axes, so the filter logic — which markers a row carries — lives once in the shared layer. Markers are vanilla items disjoint from the structure icons so the three axes never alias.

- **By structure** — The structure's representative icon item. Viewing a structure icon (e.g. the stronghold's) shows only that structure's loot.
- **By distance tier** — A per-tier marker item. Viewing a tier's marker shows every entry obtainable at that distance: rows gated at that tier or a shallower one, plus all "Any tier" vanilla rows.
- **By source** — A Vanilla and an Injected marker item. Viewing one scopes the index to that origin (base loot-table entries, or Prosperity additions from section 5); the unfiltered category shows both.

### Structure Icon Mapping

| Structure | Representative Item |
|---|---|
| Dungeon | `minecraft:mossy_cobblestone` |
| Mineshaft | `minecraft:rail` |
| Stronghold | `minecraft:end_portal_frame` |
| Village | `minecraft:emerald` |
| Desert Pyramid | `minecraft:sandstone` |
| Jungle Pyramid | `minecraft:mossy_cobblestone` |
| Ocean Monument | `minecraft:prismarine` |
| Woodland Mansion | `minecraft:dark_oak_log` |
| End City | `minecraft:purpur_block` |
| Buried Treasure | `minecraft:heart_of_the_sea` |
| Shipwreck | `minecraft:oak_boat` |
| Ruined Portal | `minecraft:crying_obsidian` |
| Bastion Remnant | `minecraft:blackstone` |
| Nether Fortress | `minecraft:nether_bricks` |
| Ancient City | `minecraft:sculk` |
| Trail Ruins | `minecraft:decorated_pot` |

Modded structures use their namespace's icon item if registered, otherwise a generic chest icon.

### Injected Entry Display

Entries added by Prosperity's loot injection system (section 5) are visually distinct:
- A small Prosperity icon (gold sparkle, same as the unlooted indicator) in the corner of the entry.
- Hovering shows: "Added by Prosperity at [tier]+ tier."
- This lets players distinguish vanilla drops from mod-added drops.

### Data Source

- Loot table contents are extracted from the running server's reloadable loot registry at runtime — not hardcoded. The index walks each table's pools and entries (item entries, tag entries expanded, nested-table references and composite groups recursed) to enumerate its item sources, so it automatically picks up datapack modifications.
- Built server-side on `SERVER_STARTING` and after `/reload`, then published as an immutable snapshot the viewers read. Singleplayer's integrated server populates it in-JVM for the client viewers. On a remote dedicated server the client has no loot data, so the server syncs the assembled index to each client (after the config on join, and re-broadcast after `/reload`) via the `LootIndexS2CPayload`; the client publishes it into the same snapshot the viewers read. The payload is bounded to 8192 rows (oversize indexes truncate with a warning). The integrated host ignores the sync to keep its full in-JVM snapshot. When the synced index lands, EMI and JEI are force-refreshed so they reflect the server's rows regardless of whether the sync beat their own list build — EMI through its internal reload reached by reflection, JEI through its public `IRecipeManager` runtime API (the new rows are hidden-then-re-added so a `/reload` replaces rather than stacks them). REI exposes no safe programmatic reload (only fragile staged-pipeline internals), so its tab refreshes on rejoin or a manual resource reload; it is correct on first join, since the sync lands before it builds its list.
- Prosperity loot injections are loaded from the injection registry (section 5); injected entries carry their `min_tier`, vanilla entries show "Any tier."
- Loot table → structure mapping uses a hardcoded vanilla map: most vanilla structure→loot links live in Java (legacy structures, and the dungeon worldgen feature), not in data, so they cannot be scanned. Tables the map does not cover — modded or otherwise unknown — still appear, bucketed under a generic "Other" structure (chest icon), and are logged once at build so a pack author can assign them a structure via the `lootTableStructures` config map.

### Multi-Loader Support

The loot index is implemented as three parallel plugins sharing a common data layer:

| Viewer | Plugin Interface | Priority |
|---|---|---|
| EMI | `EmiPlugin` + `EmiRecipeCategory` | Primary — most popular on Fabric |
| REI | `REIClientPlugin` + `DisplayCategory` | Secondary |
| JEI | `IModPlugin` + `IRecipeCategory` | Tertiary — for players using JEI on Fabric |

A shared `LootIndexDataSource` class builds the index once; each plugin adapter wraps it for its viewer's API.

### Implementation Notes

- All three viewers are compile-only optional dependencies.
- Plugin classes registered via respective entrypoint mechanisms (EMI: `emi` entrypoint in `fabric.mod.json`, REI: `rei_client` entrypoint, JEI: `@JeiPlugin` annotation).
- Loot data is rebuilt on resource reload (captures datapack changes).
- Custom `EmiRecipeCategory` / `DisplayCategory` / `IRecipeCategory` named "Loot Tables". The category tab icon reuses the mod brand icon (`assets/prosperity/icon.png`) scaled to the 16×16 category slot — the suite convention rather than a bespoke chest glyph.
- Each loot table entry is one recipe entry. The recipe viewer handles search indexing automatically once items are registered as outputs.
- Structure icon mapping stored in a registry class (`StructureIcons`) with a `Map<ResourceLocation, Item>`. Modded structures fall back to `Items.CHEST`.

---

## 12. Container Protection

Optional protection for world-generated loot containers to prevent griefing.

### Problem

With instanced loot, a single player breaking a world-gen chest destroys every player's instanced inventory for that container. On shared servers, this enables griefing — one player can systematically break dungeon chests and erase loot for everyone else, with no way to undo it.

### Behavior

When enabled, world-gen loot containers that still hold unclaimed loot receive increased break resistance:
- **Mining speed reduction:** Breaking takes **4x longer** than normal (configurable multiplier). A chest that normally breaks instantly takes ~2 seconds. This signals "this is deliberate" and prevents accidental breaks.
- **Hard lock (optional):** With `protectionUnbreakable`, a protected container is fully unbreakable in survival (like bedrock) instead of merely slow, and it is also **blast-proof** — TNT, creepers, and other explosions cannot destroy it. It cannot be removed until its loot is claimed. The slow-break mode (flag off) leaves explosions alone, staying a speed bump rather than a wall.
- **Feedback:** An action-bar warning (the "open it instead of breaking it" form, or a "can't be broken" form under `protectionUnbreakable`), a small particle burst, and a subtle anvil-land sound cue play when a player starts breaking a protected container, reinforcing that something is different.
- **Still creative-bypassable:** Players in creative mode break instantly as normal, in both modes.

### Scope

- Only applies to Prosperity-managed loot containers — a container with a non-blacklisted loot table, or one that has generated an instance from one. Player-placed storage chests and blacklisted loot tables are never affected.
- A managed container is protected while it still has unclaimed loot, whether or not anyone has opened it yet: a freshly generated, never-opened loot chest is protected (including in singleplayer).
- If `enableContainerProtection` is false (default), containers break at normal speed.
- Once the container has been opened by **all online players** (everyone has generated their instance) it holds no pending loot, so protection is lifted — breaking is normal speed.

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableContainerProtection` | bool | false | Toggle container break protection |
| `protectionBreakMultiplier` | float | 4.0 | Mining speed multiplier for protected containers |
| `protectionUnbreakable` | bool | false | Make protected containers fully unbreakable in survival instead of merely slower |

### Implementation Notes

- A common mixin on `BlockBehaviour#getDestroyProgress(BlockState, Player, BlockGetter, BlockPos)` divides the returned per-tick mining progress by the protection multiplier. `ContainerProtection.breakMultiplier` supplies the divisor. When `protectionUnbreakable` is on, a protected container reports `Float.POSITIVE_INFINITY` and the mixin zeroes the progress instead, so the break gate never trips — the container is unbreakable in survival, exactly as a block with a `-1` destroy speed (bedrock) is.
- Protected check (`ContainerProtection.isProtectedServer`): `enableContainerProtection` on, breaker not creative, the block is a `RandomizableContainerBlockEntity` that is a managed loot container (its live loot table — or, once generated, the original key preserved on the `InstancedLootData` — is non-null and not blacklisted), and loot is still pending. Loot is pending when no instance has generated yet (no one has looted), or, once generated, while at least one online player has not generated their instance. An emptied container (every online player has generated) is not protected — a player who has looted their instance clean has claimed it and no longer counts as pending, even though their emptied inventory is no longer stored.
- The `InstancedLootData` attachment is server-only, so the mixin evaluates protection authoritatively only where `level` is a `ServerLevel`; the server independently gates the actual break (`getDestroyProgress x (ticks+1) >= 0.7`), so the slowdown is enforced even against an unmodified client. To slow the client's cracking animation to match, the client queries the server at break-start (`QueryProtectionC2S` → `ProtectionResultS2C` carrying the multiplier) and the mixin's client branch divides by that answer.
- The break-start cue (an action-bar warning plus a quiet `ANVIL_LAND` sound and a small particle burst) fires from a server-side `AttackBlockCallback`, throttled per player so mashing attack does not spam it.
- Explosion immunity (`protectionUnbreakable` only) is a multi-target mixin on `getBlockExplosionResistance` in both `ExplosionDamageCalculator` and `EntityBasedExplosionDamageCalculator` (the latter backs every entity-sourced blast — TNT, creepers, end crystals). For a protected container it reports `Float.MAX_VALUE`, driving the explosion's ray power negative so the block is never added to the destroy set, the same way obsidian and bedrock survive. `ContainerProtection.isExplosionProof` gates it and is a no-op unless both protection flags are on.
- Chest/hopper minecarts are entities with no `getDestroyProgress`, so this block-only protection does not cover them.

---

## 13. Mob Loot Scaling

Distance-based scaling applied to mob drops, extending the tier system beyond containers.

### Problem

With Prosperity's container loot scaling, chests get better at higher distances — but mob drops stay flat. A zombie at 10,000 blocks drops the same 0–2 rotten flesh as one at 100 blocks. This creates an inconsistent reward signal: the world tells you "further = better loot from chests" but mobs contradict it. For players also running Tribulation (harder mobs at distance), the imbalance is worse — more risk, same mob drops.

### Behavior

When a player kills a mob, the mob's drop loot table is processed with the same distance tier system used for containers:

1. **Calculate distance** — Euclidean distance from the mob's death position to world origin (XZ plane).
2. **Determine tier** — Same tier brackets as container scaling (section 3).
3. **Apply stack multiplier** — Same stack size scaling as containers: each stackable item drop has its count multiplied by the tier's `stackMultiplier`, floored, capped at max stack size.
4. **Apply quality modifier** — The tier's `qualityModifier` is added to the `luck` value in the `LootParams` used for the mob's loot table, biasing toward rarer drops.

### Scope

- **Hostile mobs only.** Passive mobs (cows, pigs, chickens) are not affected — their drops are farming resources, not exploration rewards. Scaling them would just inflate passive farms.
- **Mob type filter:** Applied to mobs in `MobCategory.MONSTER`, so modded hostiles and the Wither are covered automatically (see the implementation note). The Ender Dragon is excluded — its bespoke death-drop path never reaches `dropFromLootTable`.
- **Player kills only.** Mobs that die from environmental damage, other mobs, or despawning do not receive scaling. The `LootContext` must have a `LAST_DAMAGE_PLAYER` parameter.

### Loot Modifier API Integration

Mob loot scaling fires `LootModifierCallback.EVENT` the same way container loot does. External mods that registered listeners for container loot automatically affect mob loot too — the API is context-agnostic. The `LootModifierContext` includes:
- `player()` — the killing player.
- `containerPos()` — the mob's death position (reused field, semantically "loot source position").
- `lootTable()` — the mob's loot table `ResourceLocation`.

### Tribulation Interop

When Tribulation is co-installed, it can register a `LootModifierCallback` listener to further boost mob drops based on the player's difficulty level. This is Tribulation's responsibility — Prosperity exposes the hook, Tribulation decides what to do with it. Example:

```java
// In Tribulation's initializer, only if Prosperity is loaded
LootModifierCallback.EVENT.register(context -> {
    int playerLevel = TribulationState.getLevel(context.player());
    // Higher difficulty level = slightly more drops
    if (playerLevel >= 100) {
        context.multiplyStacks(1.0f + (playerLevel / 1000.0f));
    }
});
```

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableMobLootScaling` | bool | true | Toggle distance scaling for mob drops |

### Nether and End

Same dimension rules as container scaling:
- **Nether:** Distance calculated from Nether coordinates (not multiplied by 8).
- **End:** All mob kills in the End use the maximum configured tier.

### Implementation Notes

- A mixin on `LivingEntity#dropFromLootTable(DamageSource, boolean)` — the method all standard mob death loot funnels through — carries the scaling. Three coordinated injectors share one decision resolved once at `HEAD`: `@Inject` runs the gate and fires `LootModifierCallback` (via the `MobLootScaling` helper), `@ModifyArg` on `LootParams.Builder#withLuck` replaces vanilla's `player.getLuck()` with the event's final luck, and `@ModifyArg` on the `LootTable#getRandomItems(…, Consumer)` drop consumer wraps it to scale each rolled stack. `withLuck` and the consumer both sit inside the player-kill branch, so a non-scalable kill leaves the drop byte-identical to vanilla and fires no event.
- The gate and the loot-modifier fire live in `MobLootScaling.resolve`, the entity parallel of `LootScaling.resolveForGeneration`; the death position is the `LootModifierContext` `containerPos()` and the tier is the ungated geographic `LootScaling.resolveTier`, so the Nether's raw coordinates and the End's max tier carry over for free.
- Stack scaling reuses `LootScaling.scaledCount` — multiply each stackable stack's count, floor, cap at the item's max stack — identical to container scaling.
- The hostile-mob check is `MobCategory.MONSTER`, not a hardcoded entity list, so modded hostiles and the Wither are included automatically. The Ender Dragon uses a bespoke death-drop path that never reaches `dropFromLootTable`, so it is excluded.
- `enableMobLootScaling` gates this feature independently of `enableDistanceScaling` (which gates only container generation) — they are separate toggles for separate loot sources.

---

## 14. Tier HUD Badge

Persistent on-screen HUD element showing the player's current distance tier.

### Problem

Distance tiers are invisible during normal gameplay. The action bar notification (section 8) only fires when opening a container. Players exploring have no ambient awareness of which tier they're in — they can't tell whether they've crossed into Wilderness territory without opening a chest or running `/prosperity info`.

### Behavior

A small badge rendered in a corner of the screen showing the current distance tier:
- **Icon:** A treasure-chest pixel-art icon unique to Prosperity, rendered 16×16 (authored at 32×32 for HUD-STANDARD glyph density and blitted down; source `art/glyphs/hud_icon.glyph`).
- **Text:** The tier name (e.g. "Wilderness") rendered next to the icon.
- **Background:** Semi-transparent dark rectangle behind the icon + text, with padding.
- **Tier color:** The text color changes based on the current tier.
- **Updates in real-time** as the player moves. Tier is recalculated from the player's current XZ position each frame (cheap — just a distance calculation and tier lookup).

### Tier Colors

| Tier | Color | Hex |
|---|---|---|
| Local | White | `0xFFFFFFFF` |
| Frontier | Green | `0xFF55FF55` |
| Wilderness | Yellow | `0xFFFFFF55` |
| Outlands | Orange | `0xFFFF8C00` |
| Depths | Purple | `0xFFAA55FF` |

### Tier Transition Animation

When the player crosses a tier boundary, the badge briefly flashes:
- Text color lerps from gold (`0xFFFFD700`) to the new tier color over 1.5 seconds.
- This draws attention to the transition without being intrusive.
- Same animation approach as Tribulation's level-up color lerp.

### Visual Convention — Overhaul Suite Alignment

The badge follows a shared visual convention so that multiple overhaul mods' HUD elements look cohesive when installed together:

- **Anchor:** Configurable corner (default: top-left). All overhaul mods should default to the same corner.
- **Stacking order:** Each mod has a priority index that determines its vertical position. Stacking order from top: Tribulation (priority 0), Prosperity (priority 1), Mercantile (priority 2), Meridian (priority 3).
- **Offset calculation:** Each badge renders at `anchorY + (badgeHeight + spacing) * priority`. Badge height is the HUD-STANDARD 20px slot (16px icon + 2px vertical padding top and bottom), spacing is 2px.
- **Badge dimensions:** Icon (16×16 rendered, from a 32×32 texture) + 3px gap + text + 4px horizontal padding on each side, 2px vertical padding.
- **Background:** `0x80000000` (50% opacity black) — same as Tribulation's current background.
- **Font:** Minecraft's default font with shadow.
- **No frame texture.** Each badge is self-contained — no shared frame that looks empty when only one mod is installed.

### Priority Detection

Each mod checks for the presence of other overhaul mods at client init (via `FabricLoader.getInstance().isModLoaded()`) and calculates its offset accordingly. If Tribulation isn't installed, Prosperity renders at priority 0 (top position). The priority list is hardcoded per mod — no runtime negotiation needed.

### Dimension Handling

- **Overworld:** Shows the calculated tier normally.
- **Nether:** Shows the tier based on Nether coordinates (same as loot scaling).
- **End:** Shows "Depths" (always max tier, same as loot scaling).

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableTierHud` | bool | true | Toggle tier HUD badge |
| `hudAnchor` | enum | TOP_LEFT | HUD corner: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT |
| `hudOffsetX` | int | 4 | Horizontal offset from anchor in pixels |
| `hudOffsetY` | int | 4 | Vertical offset from anchor in pixels |

### Implementation Notes

- Client-side only. Rendered via `HudRenderCallback` (Fabric API).
- Tier is calculated from `player.getX()` / `player.getZ()` — no server communication needed for position.
- Tier config (tier boundaries) is synced from server to client on join (same config sync mechanism used for other features).
- Icon texture: `assets/prosperity/textures/gui/hud_icon.png` (32×32, blitted down to 16×16), authored through the `/glyph` pipeline with its `.glyph` source at `art/glyphs/hud_icon.glyph`.
- Transition animation: store `lastTierChangeTime` and current/previous tier. On each render, if `currentTime - lastTierChangeTime < 1500ms`, lerp text color from gold to tier color.
- Priority offset: `int priority = 0; if (FabricLoader.getInstance().isModLoaded("tribulation")) priority++;` — Prosperity always renders below Tribulation if present.

---

## 15. Commands

### `/prosperity` Command Tree

All commands use the `prosperity` root. Admin commands require **operator level 2**.

| Command | Permission | Description |
|---|---|---|
| `/prosperity info` | Any player | Shows your loot scaling tier for your current position (distance, tier name, multipliers) |
| `/prosperity info <player>` | Op level 2 | Shows another player's loot scaling tier at their position |
| `/prosperity reset <pos>` | Op level 2 | Clears all instanced loot data for the container at the given position. All players' instances are removed. |
| `/prosperity reset <pos> <player>` | Op level 2 | Clears a specific player's instanced loot at the given position |
| `/prosperity reset around [radius] [player]` | Op level 2 | Clears instanced loot for every container in loaded chunks within `radius` blocks of the command source (default 128, max 256), optionally scoped to one player |
| `/prosperity refresh <pos>` | Op level 2 | Forces a loot refresh for the container at the given position (all players) |
| `/prosperity refresh <pos> <player>` | Op level 2 | Forces a loot refresh for a specific player at the given position |
| `/prosperity refresh around [radius] [player]` | Op level 2 | Forces a loot refresh for every container in loaded chunks within `radius` blocks of the command source (default 128, max 256), optionally scoped to one player |
| `/prosperity reload` | Op level 2 | Reloads config from disk and syncs to all connected clients |

### Command Feedback

- `/prosperity info` output example: `"Distance: 4,521 blocks — Wilderness tier (2.0x stacks, +2 quality)"`
- `/prosperity reset` confirms: `"Cleared instanced loot at [x, y, z] for all players (4 instances removed)"`
- All feedback uses translation keys (`command.prosperity.*`).

### Implementation Notes

- Register via `CommandRegistrationCallback`.
- `/prosperity info` calculates the player's current chunk position, determines the distance tier, and formats the tier data.
- `/prosperity reset`/`refresh` read the attachment(s) at the target position — or every loaded container within the `around` radius — clear the specified entries, and resend the affected chunks' `UnlootedContainersS2C` set to tracking clients (full per-player replace), so a container that is unlooted again re-lights rather than being dropped. The `around` form scans only loaded chunks (never force-loads) and bounds the radius at 256 blocks.
- `/prosperity reload` re-reads `config/prosperity.json` and pushes updated values to all connected clients via a config sync packet.

---

## 16. Trial Chamber Scaling

Distance-based scaling applied to trial chamber reward sources — vault loot (normal and ominous) and trial spawner ejected rewards. Scaling only, no instancing: vanilla vaults already gate rewards per player, so instancing them would add nothing.

### Behavior

- **Vault loot** — when a player inserts a key, the reward roll's luck is replaced with the loot-modifier event's final value (tier quality + the player's `generic.luck` + any API listener) and each rolled stackable stack is multiplied by the tier's `stackMultiplier` (floored, capped at max stack size). Normal and ominous vaults are both covered — each is a `VaultConfig` with its own loot table on the same roll path.
- **Trial spawner rewards** — the ejected reward roll is scaled the same way for the player being rewarded. Vanilla ejects one roll per detected player and then removes the head of the detected set; that head is the player the roll is attributed to.
- **Structure override** — the roll position runs through the standard tier pipeline (`LootScaling.resolveForGeneration`), so the `minecraft:trial_chambers` entry in `structureOverrides` participates. The shipped default raises trial chambers to at least the `wilderness` tier.
- **Loot Modifier API** — `LootModifierCallback.EVENT` fires once per roll with the vault/spawner position as `containerPos()` and the rolled table as `lootTable()`, so API listeners compose exactly as they do for containers and mob drops.
- **Notification** — the tier action-bar notification (section 8) shows on a successful vault open, consistent with container generation. Spawner ejections stay silent — they fire on a timer with no per-player open moment.

### Vanilla Gating Untouched

Key consumption, the per-player rewarded set, and vault re-locking all live outside the hooked roll methods and are not modified. The vault display-item cycling roll is likewise untouched.

### Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `enableTrialChamberScaling` | bool | true | Toggle distance scaling for trial chamber vault and spawner rewards |

Gated on `enableTrialChamberScaling` **and** `enableDistanceScaling` — this is an extension of distance scaling, not an independent loot source. With either off, trial chamber loot is byte-identical to vanilla and no event fires.

### Implementation Notes

- The gate and the loot-modifier fire live in `TrialChamberScaling.resolve`, the trial chamber parallel of `MobLootScaling.resolve`; the two mixins are pure plumbing.
- `VaultBlockEntityServerMixin` hooks `VaultBlockEntity.Server#resolveItemsToEject` — the one method every vault unlock funnels through, called only from `tryInsertKey` with the opening player. Three coordinated injectors: `@Inject(HEAD)` resolves the decision, `@ModifyArg` on `LootParams.Builder#withLuck` replaces vanilla's `player.getLuck()` with the event's final luck, and `@Inject(RETURN)` scales the rolled stacks in place and sends the notification on a non-empty roll.
- `TrialSpawnerMixin` hooks `TrialSpawner#ejectReward`: `@Inject(HEAD)` attributes the roll to the head of the spawner's detected-player set (via a `TrialSpawnerData` accessor), `@ModifyArg` on `LootTable#getRandomItems` swaps in a `LootParams` carrying the final luck (vanilla rolls this table with no luck at all), and `@ModifyArg` on `DefaultDispenseItemBehavior#spawnItem` scales each dispensed stack.
- Stack scaling reuses `LootScaling.scaledCount`, identical to container and mob scaling.
- Existing configs gain the `minecraft:trial_chambers` default override via a v1 → v2 config migration that appends it only when no entry for the structure exists — a hand-tuned or deliberately removed entry is respected.

---

## 17. Prospector's Compass

A held compass item whose needle points at the nearest container the holder has not yet looted — the directional complement to the sparkle indicators (section 2), answering "where should I go next?" beyond the indicators' render distance.

### Behavior

- **Targeting** — the needle points at the nearest position in the client's unlooted-container cache (`UnlootedIndicatorCache`), the per-player set the server already computes in `UnlootedContainers.scanChunk`. Blacklist, double-chest anchoring, and refresh-expiry rules are therefore inherited: a blacklisted container is never a target, and a refreshed container becomes one again. Reach is the client's loaded-chunk radius.
- **Per-player** — two players holding the compass at the same spot see different needles, because each client's cache reflects its own loot history.
- **Retargeting** — looting or breaking the target evicts it from the cache (existing `ContainerLootedS2C`/`ContainerRemovedS2C` flow) and the needle swings to the next nearest candidate. The current target is sticky within a 2-block hysteresis so the needle does not flicker between near-equidistant containers.
- **No candidates** — the needle spins randomly, exactly like a vanilla compass outside its dimension (vanilla `CompassItemPropertyFunction` behavior).
- **Obtainability** — injected into chest loot via the bundled `loot_injections/prospectors_compass.json` at `min_tier: frontier`, weight 8. No crafting recipe. Uncommon rarity, stack size 1.
- **Peek-panel readout** — carrying a compass anywhere in the inventory adds a `Nearest: <blocks> <bearing>` line to the peek panel's "Nearby unlooted" pillar: the rounded distance and 8-way cardinal bearing to the same plain-nearest target the needle selects (extended over loot minecarts, which the pillar also lists), with the target's tier suffixed in its tier color. The line is absent with no compass or no candidates in range; the pillar's empty state is unchanged. Bearing math (`LootDetailPanelMath.bearing8`) is pure and under JUnit.
- **Out of scope** — pointing at ungenerated structures, GUIs/waypoints/maps, and loot minecart targets.

### Implementation Notes

- `ProsperityItems.PROSPECTORS_COMPASS` is the mod's only registered item (a plain `Item` — no server-side behavior), placed in the Tools & Utilities creative tab after the vanilla compass.
- Needle rotation is the vanilla `angle` item property: `ProspectorsCompassClient.register()` installs a `CompassItemPropertyFunction` whose `CompassTarget` reads the indicator cache, reusing vanilla's wobble and random-spin logic wholesale. Target selection (`selectTarget`) is a pure static function under JUnit.
- The model mirrors the vanilla compass's 32-frame `angle` override ladder; the textures are the vanilla frames with the casing remapped to the design-system gold ramp (dial face, outline, and red needle stay vanilla) so the item reads instantly as "a compass, but for loot".

---

## Configuration

All features are independently toggleable via ModMenu / Cloth Config screen and a JSON config file (`config/prosperity.json`), created with defaults on first launch. `configVersion` is **2**; `ProsperityConfigMigrator` runs ordered JSON-level migrations on the raw file (before deserialize) so renamed or restructured keys carry forward, and the file is re-saved when a migration runs. Unknown/missing fields are filled with defaults and clamped to valid ranges by `clamp()` after load; a corrupted file falls back to defaults and is left untouched.

### Server Config

| Key | Type | Default | Description |
|---|---|---|---|
| `enableInstancedLoot` | bool | true | Master toggle for instanced loot |
| `enableVisualIndicators` | bool | true | Toggle client-side unlooted indicators |
| `indicatorRenderDistance` | int | 48 | Max render distance for indicators (blocks) |
| `indicatorXrayDistance` | int | 8 | Distance indicators render through walls (blocks) |
| `enableDistanceScaling` | bool | true | Toggle distance-based loot scaling |
| `distanceTiers` | list | (see below) | Ordered list of distance tier definitions |
| `structureOverrides` | list | (see below) | Per-structure tier overrides |
| `lootTableBlacklist` | list | [] | Loot tables excluded from instancing (exact or namespace wildcard) |
| `enableLootInjection` | bool | true | Toggle datapack-driven loot injection |
| `enableLootNotifications` | bool | true | Toggle action bar tier notifications |
| `enableLootRefresh` | bool | false | Toggle loot refresh |
| `lootRefreshDays` | int | 7 | In-game days before loot refreshes per player |
| `randomizeLootOnRefresh` | bool | false | Re-roll fresh loot on each refresh instead of repeating the same items |
| `enableContainerProtection` | bool | false | Toggle container break protection |
| `protectionBreakMultiplier` | float | 4.0 | Mining speed multiplier for protected containers |
| `protectionUnbreakable` | bool | false | Make protected containers fully unbreakable in survival instead of merely slower |
| `enableMobLootScaling` | bool | true | Toggle distance scaling for mob drops |
| `enableTrialChamberScaling` | bool | true | Toggle distance scaling for trial chamber vault and spawner rewards |
| `endAlwaysMaxTier` | bool | true | Treat all End containers as max distance tier |
| `lootTableStructures` | map | {} | Loot index (§11): loot-table id → structure id overrides for tables the hardcoded vanilla map does not cover |

#### Default Distance Tiers

```json
[
  { "minDistance": 0,     "stackMultiplier": 1.0,  "qualityModifier": 0 },
  { "minDistance": 1000,  "stackMultiplier": 1.5,  "qualityModifier": 1 },
  { "minDistance": 3000,  "stackMultiplier": 2.0,  "qualityModifier": 2 },
  { "minDistance": 6000,  "stackMultiplier": 2.75, "qualityModifier": 3 },
  { "minDistance": 10000, "stackMultiplier": 3.5,  "qualityModifier": 4 }
]
```

#### Default Structure Overrides

```json
[
  { "structure": "minecraft:monument",           "mode": "fixed",   "tier": "wilderness" },
  { "structure": "minecraft:stronghold",          "mode": "minimum", "tier": "outlands"   },
  { "structure": "minecraft:village_plains",      "mode": "maximum", "tier": "frontier"   },
  { "structure": "minecraft:village_desert",      "mode": "maximum", "tier": "frontier"   },
  { "structure": "minecraft:village_savanna",     "mode": "maximum", "tier": "frontier"   },
  { "structure": "minecraft:village_snowy",       "mode": "maximum", "tier": "frontier"   },
  { "structure": "minecraft:village_taiga",       "mode": "maximum", "tier": "frontier"   },
  { "structure": "minecraft:ancient_city",        "mode": "minimum", "tier": "outlands"   },
  { "structure": "minecraft:trail_ruins",         "mode": "minimum", "tier": "frontier"   },
  { "structure": "minecraft:trial_chambers",      "mode": "minimum", "tier": "wilderness" }
]
```

### Client Config

| Key | Type | Default | Description |
|---|---|---|---|
| `showIndicators` | bool | true | Client-side toggle for overlay rendering |
| `enableTierHud` | bool | true | Toggle tier HUD badge |
| `hudAnchor` | enum | TOP_LEFT | HUD corner: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT |
| `hudOffsetX` | int | 4 | Horizontal offset from anchor in pixels |
| `hudOffsetY` | int | 4 | Vertical offset from anchor in pixels |

---

## Compatibility

### Required

- Fabric Loader >=0.16.10
- Fabric API (the Data Attachment API module carries per-player loot state — no third-party persistence dependency)
- Minecraft 1.21.1

### Optional Integrations

- **ModMenu + Cloth Config** — Config screen
- **Jade** — Container tooltip: loot status, distance tier, structure override, refresh timer (section 10)
- **WTHIT** — Same as Jade (parallel plugin)
- **EMI / REI / JEI** — Searchable loot index with structure/tier/source filtering and injection display (section 11)

### Mod Compatibility

- **Sodium** — Full compatibility. No block rendering is modified. Visual indicators use `WorldRenderEvents.LAST`.
- **Enhanced Block Entities (EBE)** — Full compatibility. Same reason as Sodium — container block entities are never replaced or subclassed.
- **Iris/shaders** — Visual indicators render in the `LAST` event, after the main scene. Shader post-processing may affect indicator appearance (bloom, color grading). No workaround needed.
- **Tribulation** — Distance scaling complements Tribulation's mob scaling. Both use distance from origin. Players facing harder mobs at greater distances also receive better loot.
- **Lootr** — **Incompatible.** Prosperity replaces Lootr's functionality entirely. Both mods cannot be loaded simultaneously (detected at init, logged as error, Prosperity disables its instancing if Lootr is present).

---

## Implementation Order

Features are ordered by dependency and complexity. Infrastructure comes first, then the core loop, then extensions.

### Phase 0: Infrastructure

1. **Config system** — `ProsperityConfig` class with all server + client fields, JSON serialization, Cloth Config screen builder, `ModMenuIntegration`.
2. **Data attachment registration** — `InstancedLootData` type + Codec, registered as a persistent block-entity attachment on `RandomizableContainerBlockEntity` (and the parallel entity attachment type for minecarts).
3. **Networking infrastructure** — Packet registration, `CustomPayload` types for all S2C and C2S packets.
4. **Command registration** — `/prosperity` command tree (section 15).

### Phase 1: Core Instanced Loot

5. **Instanced loot system** (section 1) — `UseBlockCallback` handler, attachment population, virtual container screen, animation sync, loot table nullification, double chest handling, hopper safety mixin, container adapters (minecart coverage), fake-player guard.

### Phase 2: Visual Feedback

6. **Visual indicators** (section 2) — Client-side overlay renderer, chunk-based data sync packets, indicator cache.

### Phase 3: Loot Scaling

7. **Distance-based loot scaling** (section 3) — Distance calculation, tier lookup, quantity/quality application, dimension handling.
8. **Structure-specific scaling** (section 6) — Structure detection via `StructureManager`, override map, interaction with distance tiers.
9. **Loot modifier API** (section 4) — `LootModifierCallback` event, `LootModifierContext`, vanilla luck registration.

### Phase 4: Loot Content

10. **Loot table injection** (section 5) — Datapack schema, resource reload listener, tier-gated injection into loot pools, built-in defaults.
11. **Loot table blacklist** (section 7) — Config-driven exclusion, pattern matching, integration with UseBlockCallback and visual indicators.

### Phase 5: Player Feedback

12. **Loot notifications** (section 8) — Action bar messages on first open, tier + modifier display, structure override indicator.
13. **Tier HUD badge** (section 14) — Client-side HUD rendering, tier color, transition animation, overhaul suite stacking convention.
14. **Loot refresh** (section 9) — Cooldown tracking, lazy expiration check, indicator re-appearance.

### Phase 6: Extended Features

15. **Container protection** (section 12) — Break speed multiplier for instanced containers, creative bypass.
16. **Mob loot scaling** (section 13) — Distance tier applied to mob drops on player kill, hostile-only filter, Loot Modifier API integration.

### Phase 7: Mod Integrations

17. **Jade/WTHIT plugin** (section 10) — Container tooltip: loot status, distance tier, structure override, refresh timer.
18. **Loot index** (section 11) — EMI/REI/JEI plugin. Shared `LootIndexDataSource`, three viewer adapters, structure/tier/source filtering, injection display.

---

## Sound Design

Sounds stay **vanilla** where the cue is organic — chest lids, barrel lids, and
XP pickup are physical sounds vanilla already nails, so synthesis would only make
them feel fake. Custom synthesized cues (via the `/sfx` pipeline) are added where
a sound benefits from its own identity, per concord `design/DESIGN-SYSTEM.md` §9.
The current cues all map to vanilla events:

### Sound Mapping

| Feature | Event | Vanilla Sound |
|---|---|---|
| Instanced container — open | Chest open | `minecraft:block.chest.open` |
| Instanced container — close | Chest close | `minecraft:block.chest.close` |
| Instanced container — open (barrel) | Barrel open | `minecraft:block.barrel.open` |
| Instanced container — close (barrel) | Barrel close | `minecraft:block.barrel.close` |
| Loot generated (first open) | Experience pickup | `minecraft:entity.experience_orb.pickup` |

---

## Localization

All user-facing text uses translation keys in `assets/prosperity/lang/en_us.json`.

### Key Conventions

| Pattern | Example | Used For |
|---|---|---|
| `prosperity.tier.*` | `prosperity.tier.wilderness` | Distance tier display names (action bar, HUD badge) |
| `prosperity.structure.*` | `prosperity.structure.monument` | Structure display names in notifications |
| `prosperity.notification.*` | `prosperity.notification.loot_generated` | Action bar loot notifications |
| `prosperity.config.*` | `prosperity.config.enable_distance_scaling` | Cloth Config screen labels |
| `prosperity.config.*.tooltip` | `prosperity.config.enable_distance_scaling.tooltip` | Cloth Config field descriptions |
| `command.prosperity.*` | `command.prosperity.info` | Command feedback messages (incl. `/prosperity info` output) |
| `prosperity.jade.*` | `prosperity.jade.status.looted` | Jade/WTHIT tooltip lines (status, tier, override, refresh timer) |
| `prosperity.loot_index.*` | `prosperity.loot_index.injected` | EMI/REI/JEI loot index UI |
| `category.prosperity.*` / `emi.category.prosperity.*` | `category.prosperity.loot_tables` | Recipe-viewer category title |

Parameterized messages use `String.format` style (`%s`, `%d`) — e.g. `"prosperity.tier.info": "%s tier (%.1fx stacks, +%d quality)"`.

---

## Rendering Compatibility

Visual indicator features (section 2) use client-side world rendering. These must work with common rendering mods.

### Approach

- Use **Fabric Rendering API** event `WorldRenderEvents.LAST` for all custom world rendering.
- The overlay is a textured quad (billboard sprite) per container — rendered via `VertexConsumer` on a custom `RenderType` with the indicator texture.
- **No block rendering modifications.** No mixins into chunk building, block entity renderers, or model loaders.
- **Sodium/EBE compatibility** is guaranteed because the overlay is entirely decoupled from block rendering.
- **Iris/shader compatibility:** The overlay renders after the main scene. Shader post-processing (bloom, tone mapping) may affect indicator appearance — this is expected and not a bug.

---

## Testing Strategy

### Unit Tests (JUnit + `fabric-loader-junit`)
Fast, no Minecraft runtime needed. Located in `src/test/`.

- Config parsing and serialization (round-trip JSON, default values, migration from older config versions)
- Distance tier calculation (boundary values, descending tier walk, single-tier config, empty config)
- Structure override resolution (fixed/minimum/maximum modes, missing structure, overlapping structures)
- Stack multiplier math (floor behavior, max stack size cap, non-stackable items excluded, edge cases)
- Loot modifier context (luck stacking, stack multiplication, custom data isolation)
- Loot injection datapack parsing (component format, tier gating, replace flag, wildcard targets)
- Blacklist pattern matching (exact match, namespace wildcard, empty blacklist)
- Attachment Codec serialization (round-trip NBT via the attachment, empty inventory, multiple players, double chest redirect)
- Cooldown expiration logic (boundary tick values, disabled refresh, retroactive enable)
- HUD priority calculation (with/without Tribulation loaded, correct stacking offset)

### Gametests (Fabric Gametest API)
Require a running server instance. Located in `src/gametest/`.

- Instanced loot: two players open same chest, verify each gets independent loot
- Loot table nullification: open chest, verify vanilla `lootTable` is null, verify hopper cannot extract
- Double chest: open double chest, verify 54-slot inventory, verify both halves animate
- Loot refresh: set short cooldown, generate loot, advance game time, verify loot regenerates
- Distance scaling: place containers at known distances, generate loot, verify tier multipliers applied
- Structure override: place container in structure with fixed tier, verify override applied
- Loot injection: place container at Frontier+ distance with injection configured, verify injected item appears in loot pool
- Blacklist: add loot table to blacklist, verify container opens with vanilla behavior, verify no indicator
- Loot notification: open instanced container, verify action bar message sent with correct tier
- Container protection: enable protection, verify break speed is reduced on instanced container, verify creative bypasses
- Mob loot scaling: kill hostile mob at known distance, verify stack sizes scaled, verify passive mob unaffected
- Container destruction: break instanced container, verify the attachment data is gone
- Commands: `/prosperity reset` clears instanced data, `/prosperity info` returns correct tier

### Manual Testing
Features that require visual/UI verification:

- Visual indicator rendering (sprite appearance, bobbing animation, through-wall behavior, fade-out)
- Indicator sync (indicator disappears after opening, reappears after refresh)
- Loot notification appearance and formatting on action bar
- Tier HUD badge (position, tier color, transition animation when crossing tier boundaries)
- HUD stacking with Tribulation installed (correct vertical ordering, no overlap)
- Jade/WTHIT tooltip rendering (all status states, refresh timer countdown, structure override display)
- EMI/REI/JEI loot index (search integration, structure/tier/source filtering, injected entry display)
- Sodium/EBE/Iris visual compatibility

---

## Future Considerations (Out of Scope for v0.1)

- **Shared HUD library** — Extract the overhaul suite HUD convention into a standalone library mod that handles layout, stacking, and toggle. Each overhaul mod registers its badge; the library renders them.
- **Loot history** — Track what a player has received from instanced containers across all sessions. Surface as a GUI or command.
- **Party loot mode** — Players in a party (via a social mod) share a single loot instance instead of each getting their own.
- **Trapped chest behavior** — Instanced trapped chests could trigger redstone only on first open per player.
- **Container locking** — Players can lock their instanced inventory to prevent accidental item removal, with a distinct visual indicator.
- **Loot preview** — Sneak-click to peek at partial loot without committing to generation.
- **Fishing loot scaling** — Extend distance tier scaling to fishing loot tables.
