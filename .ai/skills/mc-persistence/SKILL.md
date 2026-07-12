---
name: mc-persistence
description: Persist mod state to disk the suite way — vanilla-persisted channels (scoreboard tags, namespaced attribute modifiers) for per-mob flags and stats, Fabric Attachments + Mojang Codec for per-entity/player/block-entity data, SavedData for per-world global state, and block-entity NBT with client sync — funneling writes through one dirtying choke point and keeping the serialized footprint bounded and deterministic. TRIGGER when creating or editing an *Attachments.java, a SavedData subclass, a class with a Codec/CODEC for saved data, block-entity saveAdditional/loadAdditional, per-mob state via entity.addTag or a mod-namespaced AttributeModifier, or any code that reads/writes data that must survive world reload (player progression, per-mob flags, per-block state, world-global counters).
---

The user is storing state that must survive a world reload. This is distinct from
in-memory shared state (see the `mc-shared-state` skill) — here the question is
how bytes hit disk and come back intact. Four homes, picked by **what the data
is attached to** — try the cheapest first:

| Data is per… | Use | Reference |
|---|---|---|
| mob — a boolean flag or a stat delta | **vanilla channels**: scoreboard tag / `AttributeModifier` | Tribulation `AbilityManager`, `MobScalingHandler` |
| player / entity / block-entity instance (structured) | **Fabric Attachment** + Codec | Mercantile `PlayerData`, Prosperity `InstancedLootData` |
| world / dimension (one global blob) | **SavedData** | Tribulation `PlayerDifficultyState`, Prosperity `PlayerLastSeenState` |
| block (lives in the BE, syncs to client) | **block-entity NBT** | Meridian library block entities |

Whichever custom home you pick, the same disciplines apply: **one write choke
point** that marks the owner dirty, a **Codec/NBT that is forward-compatible and
bounded**, and **deterministic serialization** so saves diff cleanly and tests
are stable. The vanilla channels need none of that — which is the point.

## Let vanilla persist it — scoreboard tags + attribute modifiers

Before building any attachment or SavedData plumbing, check whether the state
fits a channel vanilla already saves in the mob's own NBT. Per-mob booleans and
stat deltas need **zero custom NBT, codec, or sync code** — the entity
serializes them with its chunk, and every surface (mixin, tooltip, command)
probes them the same symmetric way.

**Scoreboard tags** (`entity.addTag`) carry boolean state: "this mob has ability
X", "this mob is variant Y", "this mob was already processed". Namespace every
tag with the mod id — tags share one global string space and are visible to
`/tag` and target selectors. The tag — not an attribute modifier — is the
canonical "mob has ability X" signal for abilities expressed through vanilla
mechanics that no attribute can capture (arrow effects, potion type, roar
radius, door breaking, beam charge):

```java
/** Scoreboard tags read by the ability mixins and the probe-tooltip collector. */
public static final String TAG_SLOWNESS_ARROWS = "tribulation_slow2";
public static final String TAG_POISON_ARROWS = "tribulation_poison2";
public static final String TAG_DOOR_BREAKING = "tribulation_door_break";
// applied: mob.addTag(TAG_DOOR_BREAKING);
// probed:  mob.getTags().contains(TAG_DOOR_BREAKING)  — mixins, tooltips, commands alike
```

A processed-flag tag **doubles as the idempotence guard across chunk reloads**.
`ENTITY_LOAD` fires on every chunk load, not just first spawn — check the tag
before rolling and the mob's state is frozen at spawn:

```java
public static final String PROCESSED_TAG = "tribulation_processed";

static void onEntityLoad(Entity entity, ServerLevel world) {
    if (!(entity instanceof Mob mob)) return;
    if (mob.getTags().contains(PROCESSED_TAG)) return;   // already rolled — frozen at spawn
    // ... roll once, then mob.addTag(PROCESSED_TAG)
}
```

**Mod-namespaced `AttributeModifier` ids** carry stat-shaped state: the modifier
both *applies* the effect (vanilla recomputes the attribute) and *is* the
persisted record. Presence-probe by scanning attribute instances for the
namespaced id:

```java
private static boolean hasModifier(Mob mob, ResourceLocation id) {
    for (Holder<Attribute> attr : attributeHolders()) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst != null && inst.getModifier(id) != null) return true;
    }
    return false;
}
```

The decision table:

| State shape | Use | Probe |
|---|---|---|
| Boolean ability / variant / processed flag, queried by id | scoreboard tag | `getTags().contains(TAG)` |
| Numeric stat that should apply automatically (speed, KB resist, health) | namespaced `AttributeModifier` | `hasModifier(mob, id)` |
| Structured, synced, or codec-shaped data | Fabric Attachment (below) | attachment API |

One caveat: a variant whose stat delta can be **zero** leaves no modifier to
probe — give it an always-present tag as well (a skeleton variant with a 0
health malus carries no attribute modifier).

## Fabric Attachments — per-entity / player / block-entity

Register attachment types at mod init. Use `.persistent(CODEC)` to save,
`.initializer(...)` for the default value, and `.copyOnDeath()` for player data
that should survive respawn. Primitive flags can use a vanilla codec directly,
with a sentinel default for "unset".

```java
public class MercantileAttachments {
    public static final AttachmentType<PlayerData> PLAYER_DATA = AttachmentRegistry.<PlayerData>builder()
            .persistent(PlayerData.CODEC)
            .copyOnDeath()                                 // carry across respawn
            .initializer(PlayerData::new)
            .buildAndRegister(Mercantile.id("player_data"));

    public static final long SENTRY_PYLON_POS_UNSET = Long.MIN_VALUE;
    public static final AttachmentType<Long> SENTRY_PYLON_POS = AttachmentRegistry.<Long>builder()
            .persistent(Codec.LONG)
            .initializer(() -> SENTRY_PYLON_POS_UNSET)     // sentinel = "not set"
            .buildAndRegister(Mercantile.id("sentry_pylon_pos"));

    public static void init() {}                           // call from onInitialize to force class-load
}
```

One state class + codec can ride **multiple loot sources** by registering it as
several attachment types (Prosperity attaches the same `InstancedLootData` to
block-entity containers and to container minecarts).

## The write choke point + dirtying discipline

In-place mutation of an attachment **does not** mark its owner dirty. Funnel
every write through one helper that mutates then dirties, so no call site can
forget. A **block entity needs `setChanged()`**; an **entity serializes with its
chunk every save**, so it doesn't.

```java
/** The single write choke point for block-entity loot data. */
public static InstancedLootData update(BlockEntity be, Consumer<InstancedLootData> mutation) {
    InstancedLootData data = be.getAttachedOrCreate(INSTANCED_LOOT);
    mutation.accept(data);
    be.setChanged();                 // in-place attachment mutation does NOT auto-dirty the BE
    return data;
}

/** Same data on a minecart — an entity already serializes with its chunk, so no dirty call. */
public static InstancedLootData update(AbstractMinecartContainer cart, Consumer<InstancedLootData> mutation) {
    InstancedLootData data = cart.getAttachedOrCreate(INSTANCED_MINECART_LOOT);
    mutation.accept(data);
    return data;
}
```

## Codec authoring for persisted records

A persisted Codec must tolerate **old saves missing new fields** and produce a
**byte-stable** result. The rules:

- Every field is `optionalFieldOf(name, default)` — a save written before the
  field existed deserializes to the default instead of failing.
- **Clamp/validate in the constructor**, so a tampered or out-of-range save can't
  produce an invalid object.
- **Bound every collection** and evict FIFO, so a runaway map can't bloat the
  save (and document the footprint at the cap).
- `xmap` a `Set` to a sorted `List` for stable on-disk order; use
  `ItemStack.OPTIONAL_CODEC` for stacks; sort map/collection serialization by a
  deterministic key (e.g. UUID string).

```java
public static final Codec<PlayerData> CODEC = RecordCodecBuilder.create(instance ->
    instance.group(
        Codec.INT.optionalFieldOf("score", 0).forGetter(PlayerData::getScore),
        UUIDUtil.CODEC.listOf()
            .<Set<UUID>>xmap(LinkedHashSet::new, ArrayList::new)         // stable, order-preserving
            .optionalFieldOf("curedVillagers", Set.of()).forGetter(PlayerData::getCuredVillagers),
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT)
            .optionalFieldOf("tradeStats", Map.of()).forGetter(PlayerData::getTradeStats)
        // ... more optional fields
    ).apply(instance, PlayerData::new));

public PlayerData(int score, Set<UUID> curedVillagers, Map<UUID,Integer> tradeStats /*...*/) {
    this.score = Math.clamp(score, MIN_SCORE, MAX_SCORE);            // clamp on construct
    LinkedHashSet<UUID> cv = new LinkedHashSet<>(curedVillagers);    // bound + FIFO-evict
    Iterator<UUID> it = cv.iterator();
    while (cv.size() > MAX_CURED_VILLAGERS && it.hasNext()) { it.next(); it.remove(); }
    this.curedVillagers = cv;
    // ...
}
```

**Defend a many-arg positional codec apply target with a test-only builder.** A
`apply(instance, PlayerData::new)` over 14 same-typed args will silently compile
with two ints swapped. Keep the positional constructor as the codec target, but
give tests a fluent `@VisibleForTesting` builder so a swap can't pass:

```java
@VisibleForTesting public static Builder builder() { return new Builder(); }
// PlayerData.builder().dailyTradeRep(3).dailyCycleRep(7).build()  ← named, swap-proof
```

## SavedData — per-world global state

For one blob per world (or per dimension), subclass `SavedData`. Create lazily
through the dimension's `DimensionDataStorage`, back the map with a
`ConcurrentHashMap` (so lazy `computeIfAbsent` and the save iteration can't race),
and **serialize deterministically** (sort by UUID). Raise the dirty flag **only
when a field actually changed**, and factor the state transition into a **pure
static helper** so it's unit-testable without a server (see `mc-mod-testing`).

**The factory's `DataFixTypes` must be non-null.** On 1.21.1,
`DimensionDataStorage` calls `dataFixTypes.update(...)` unconditionally on read,
so a `null` type NPEs inside the storage's *swallowed* load — `computeIfAbsent`
silently hands back a fresh empty state every restart, and the data loss leaves
no stack trace. Any `SAVED_DATA_*` constant is benign *on 1.21.1*: `save` stamps
the current DataVersion, so the fixer short-circuits without touching the tag.
Re-verify the choice on every MC version bump — a real datafixer registered for
that type between the save's version and the target would run against your tag
and could silently drop it (Prosperity `PlayerLastSeenState`).

On load, skip a malformed entry with a warn rather than aborting — and skip
rather than let a missing field default in the *unsafe* direction (`getLong` on
an absent tag reads 0, e.g. "absent since world start").

```java
public class PlayerDifficultyState extends SavedData {
    public static final SavedData.Factory<PlayerDifficultyState> FACTORY =
            new SavedData.Factory<>(PlayerDifficultyState::new, PlayerDifficultyState::load,
                    DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);   // non-null — see hazard above
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public static PlayerDifficultyState getOrCreate(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_KEY);
    }
    public PlayerData getPlayerData(UUID uuid) { return data.computeIfAbsent(uuid, k -> new PlayerData()); }

    public int incrementTick(UUID uuid, int amount, int levelUpTicks, int maxLevel) {
        PlayerData pd = getPlayerData(uuid);
        int oldLevel = pd.level, oldTick = pd.tickCounter;
        int gained = applyTicks(pd, amount, levelUpTicks, maxLevel);   // pure, testable
        if (pd.level != oldLevel || pd.tickCounter != oldTick) setDirty();  // dirty only on real change
        return gained;
    }

    static int applyTicks(PlayerData pd, int amount, int levelUpTicks, int maxLevel) { /* pure math */ }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        var entries = new ArrayList<>(data.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));  // deterministic order
        for (var e : entries) { /* write each entry */ }
        tag.put(NBT_PLAYERS_KEY, list);
        return tag;
    }
    public static PlayerDifficultyState load(CompoundTag tag, HolderLookup.Provider registries) {
        // skip malformed entries with a warn rather than aborting the whole load
    }
}
```

## Block-entity NBT + client sync

A block entity persists through `saveAdditional`/`loadAdditional`, and pushes a
client copy through `getUpdateTag` + `getUpdatePacket` — the receiving client
routes the packet body back through `loadAdditional`, so the same read path serves
disk and network. Use `TAG_*` string constants for keys (one typo otherwise
silently drops a field). Detailed registration of the BE/block lives in the
`mc-registration` skill.

```java
@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    // tag.put(TAG_POINTS, ...);
}
@Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);   // body = getUpdateTag → loadAdditional on client
}
```

## Gotchas

- **Latent attachments stay byte-identical to vanilla until first used.** An
  attachment registered on a vanilla block entity / entity adds nothing to disk
  until something attaches a value — a naturally-placed chest has no attachment
  bytes. So **gate interception on the trigger condition (e.g. the loot table),
  not on attachment presence** — `getAttached(...)` is null for the common case.
- **The Fabric Attachment API is `@ApiStatus.Experimental`.** Pin the Fabric API
  version in `gradle.properties` and re-check the attachment API on every
  MC/Fabric bump.
- **`copyOnDeath()` only matters for player attachments** — set it for data that
  should survive respawn (reputation, progression); omit it for anything that
  should reset on death.
- **Attachment writes are not auto-dirtied** (the choke-point rule above) and
  attachment mutation is **thread-confined** to the owner's thread — keep all
  reads/writes on the server thread for server-side data.
- **`SavedData.setDirty()` only when state changed**, not on every accessor — and
  conversely, dirty even when no "interesting" boundary was crossed if a field
  still mutated (e.g. a counter zeroes at max level).

## Best-practice checklist

| Check | What to do |
|---|---|
| Cheapest tier first | Per-mob boolean → namespaced scoreboard tag; per-mob stat → namespaced `AttributeModifier`; attachments only for structured/synced data. |
| Pick the home | Attachment (per entity/BE), SavedData (per world), BE NBT (per block). |
| DataFixTypes | Non-null `SAVED_DATA_*` constant in every `SavedData.Factory`; re-verify on MC bump. |
| Write choke point | One `update(owner, mutation)` helper; `setChanged()` for BEs, none for entities. |
| Forward-compat | Every codec field `optionalFieldOf(name, default)`; skip malformed NBT entries with a warn. |
| Bounded | Cap every collection, FIFO-evict, document the footprint at the cap. |
| Deterministic | Sort serialized collections (UUID/key); `xmap` Set→sorted List. |
| Many-arg codec | Keep positional constructor as the apply target; add a `@VisibleForTesting` builder for tests. |
| Pure mutators | Factor state transitions into static helpers for unit tests; dirty only on real change. |
| Latent gate | Intercept on the trigger condition, not on `getAttached() != null`. |
| Tests | Codec round-trip + a migration test when the format changes (`mc-mod-testing`). |

## Guardrails

- **Never** mutate an attachment in place without going through the choke point
  that calls `setChanged()` on a block entity — the change won't persist.
- **Never** gate behavior on attachment presence for a latent attachment — a
  fresh vanilla container has none; gate on the real trigger.
- **Never** write a non-optional codec field for persisted data — an older save
  missing it fails to load the whole object. Use `optionalFieldOf` with a default.
- **Never** key persisted player data by username — usernames are unique but not
  permanent. Key by `player.getUUID()`; a renamed player must keep their data.
- **Never** serialize a map/set in iteration order — sort it, or saves churn and
  tests flake.
- **Never** leave a collection unbounded in persisted data — cap and evict.
- **Never** build attachment/codec plumbing for a per-mob boolean or stat delta
  that a namespaced tag or attribute modifier already persists for free.
- **Never** pass a `null` `DataFixTypes` to a `SavedData.Factory` — on 1.21.1 the
  load NPEs (swallowed) and every restart silently gets fresh empty state.
- **Always** clamp/validate in the deserialization constructor; a save file is
  untrusted input.
- **Always** add a round-trip test on the codec, and a migration test when you
  change the serialized format (legacy→new, idempotency, passthrough).
