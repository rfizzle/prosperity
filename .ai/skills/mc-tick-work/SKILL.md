---
name: mc-tick-work
description: Lifecycle and cost discipline for server gameplay event handlers and periodic tick work in Fabric Minecraft mods — handler shape, SERVER_STOPPED resets, budgeted sweeps, and orphan cleanup. TRIGGER when writing or editing a *Handler.java, *Manager.java, or *Sweep.java that registers Fabric events (ServerTickEvents, ServerLivingEntityEvents, ServerEntityEvents, ServerPlayConnectionEvents), when adding per-tick or interval-gated gameplay logic, or when a static collection tracks entities/players/blocks across ticks.
---

The user is writing server-side gameplay code driven by Fabric events — a tick
handler, a death/damage hook, a periodic sweep, or a manager that tracks state
across ticks. Two things decide success: **the tick budget** (a server running
the feature pays proportionally to what's active, and a server not using it
pays nothing) and **lifecycle hygiene** (nothing leaks across server restarts,
world reloads, or player disconnects, and nothing strands persistent entities
after a crash). Thread-safety of the maps themselves is the `mc-shared-state`
skill's territory — this skill owns the event wiring, scheduling, cost
budgeting, and cleanup around them.

## The handler shape

A handler is a `final` class with a private constructor and one public entry
point: a static `register()` that wires its Fabric events. The mod initializer
calls each `register()` once in `onInitialize`, so all event wiring is
greppable from one place.

```java
public final class FrostAuraHandler {

    private FrostAuraHandler() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(FrostAuraHandler::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
    }
    ...
}
```

Inside a callback, wrap the effect body in a per-handler `try/catch (Exception)`
with a contextual `LOGGER.error`, so one broken handler logs and skips instead
of killing the server tick (or entity load, or death event) for every listener:

```java
static void onEntityLoad(Entity entity, ServerLevel world) {
    if (!(entity instanceof Mob mob)) return;
    try {
        processMob(mob, world);
    } catch (Exception e) {
        MyMod.LOGGER.error("Failed to apply frost aura to {}", mob, e);
    }
}
```

Keep the cheap type/config bail-outs *outside* the try — the catch is for the
effect logic, not the filtering.

## Lifecycle hygiene

**Every static collection gets a paired reset.** A static map that tracks
players, entities, cooldowns, or positions survives the `MinecraftServer`
instance — across gametest server restarts and world reloads in the same JVM,
stale entries from the previous world resurface as ghost state. The rule is
mechanical: the same `register()` that wires the tick event also wires
`ServerLifecycleEvents.SERVER_STOPPED` to clear every static collection and
zero every counter the class owns.

```java
public static void register() {
    ServerTickEvents.END_SERVER_TICK.register(MyHandler::onServerTick);
    ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
        tickCounter = 0;
        trackedBlocks.clear();
        cooldowns.clear();
    });
}
```

**Per-player state also clears on disconnect.** Keyed-by-UUID maps drop the
player's entries in `ServerPlayConnectionEvents.DISCONNECT`, so a long-running
server doesn't accumulate one entry per player who ever joined:

```java
ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
        airJumpSpent.remove(handler.player.getUUID()));
```

**Client mirror.** Any static client cache populated from server packets
(synced registries, server-pushed config) clears in
`ClientPlayConnectionEvents.DISCONNECT`, so joining a different server never
shows the previous server's data.

## Periodic work: pick the cheapest shape

Not every recurring job needs a tick callback at all. Choose by what the work
actually reacts to:

| Shape | When | Cost profile |
|-------|------|--------------|
| **Interval-gated sweep** | State changes by *time passing* (cooldowns expiring) and someone must be notified | Zero work most ticks; one bounded scan every N ticks, acting only on transitions |
| **Opportunistic prune** | Stale data only matters when its container is touched anyway | Zero standing cost; piggybacks on an existing choke point, never scans the world |
| **Transient self-pruning tracker** | Soft, time-windowed memory that need not survive a restart | Entries age out on read; `clear()` on server stop; deliberately not persisted |

**Interval-gated sweep.** Gate on a counter so most ticks return immediately,
and process only *transitions since the previous sweep* — never the full
current state. A condition that has been true for ten sweeps gets acted on
once, at the sweep where it crossed:

```java
private static final int SWEEP_INTERVAL_TICKS = 600;
private static int tickCounter;

private static void onServerTick(MinecraftServer server) {
    if (++tickCounter < SWEEP_INTERVAL_TICKS) return;
    tickCounter = 0;
    ...
    long previous = PREVIOUS_SWEEP_TICK.getOrDefault(level.dimension(), now);
    PREVIOUS_SWEEP_TICK.put(level.dimension(), now);
    if (previous >= now) return; // first sweep or time didn't advance: nothing crossed
    // act only on entries whose deadline fell in (previous, now]
}
```

Size the interval to the phenomenon: a day-scale cooldown does not need a
20-tick sweep; 600 ticks (half a minute) is ample.

**Opportunistic prune.** When stale entries only cost anything at the moment
their container is used, prune inside the existing use path instead of adding
any scan. Do a read-only precheck first and return before mutating (and
dirtying) anything when nobody qualifies. Data nobody touches is never pruned —
which is fine, because untouched data has no carrying cost.

**Transient self-pruning tracker.** For soft deterrents and grace windows, keep
a plain in-memory map, drop entries as they age out inside the read method
itself, and accept that a restart forgets it. Document that this is deliberate
in the class javadoc, or the next reader will "fix" it with persistence.

## Cost budgeting

- **Config-gate before any work.** Check the feature toggles at the top of the
  sweep and return — a server using neither feature pays nothing beyond the
  counter increment.
- **Bound scans by players × view distance.** Never iterate all loaded chunks
  or all entities in a level. Walk each online player's tracked chunk square:

```java
ChunkPos origin = player.chunkPosition();
for (int dx = -viewDistance; dx <= viewDistance; dx++) {
    for (int dz = -viewDistance; dz <= viewDistance; dz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(origin.x + dx, origin.z + dz);
        if (chunk == null) continue; // not loaded — skip, never force-load
        ...
    }
}
```

  `getChunkNow` returns `null` for unloaded chunks; the sweep must skip them,
  never trigger a load.
- **Scale with players, not world population.** A per-tick effect that only
  applies to something a player is riding/wearing should walk the player list
  and inspect each player, not scan entities.
- **Throttle expensive effects inside a per-tick loop.** Cheap effects (a
  potion refresh, a velocity nudge) can run every tick; anything doing an AABB
  entity query or a block scan gets a modulo gate (`tickCounter % 20 == 0`),
  with the period chosen per effect.
- **Track reverts with tick deadlines, not schedulers.** Temporary world
  changes (a block converted for N ticks) go in a `position → placedTick` map;
  each tick, iterate it (early-return when empty), revert entries past their
  deadline, and verify the world still holds your block before writing — a
  player may have mined it.

## Persistent-entity orphan cleanup

A manager that spawns a persistent entity (armor stand, marker, mount) and
tracks it in memory has a crash hole: a hard crash mid-lifetime saves the
entity to the world with no tracker left to remove it. Close it with a
scoreboard tag plus an `ENTITY_LOAD` discard:

```java
private static final String BODY_TAG = "mymod_frost_body";

public static void register() {
    ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
        if (entity instanceof ArmorStand stand
                && stand.getTags().contains(BODY_TAG)
                && !isTracked(stand)) {
            stand.discard(); // orphan from a crashed session
        }
    });
}
```

**Ordering is load-bearing:** `addFreshEntity` fires `ENTITY_LOAD`
synchronously, so a freshly spawned entity must be added to the tracking
collection *before* `addFreshEntity` — otherwise the cleanup hook sees your
own live entity as an orphan and discards it at birth.

## Sync only on change

For a value recomputed every tick or every sweep but sent to clients, cache the
last-sent value per player and send only on change. Drop the cache entry on
disconnect so a rejoining player gets a fresh send:

```java
private static final Map<UUID, Float> LAST_SENT = new ConcurrentHashMap<>();

public static boolean sync(ServerPlayer player, float value) {
    Float last = LAST_SENT.get(player.getUUID());
    if (last == null ? value == 0f : last == value) return false;
    ServerPlayNetworking.send(player, new PressurePayload(value));
    LAST_SENT.put(player.getUUID(), value);
    return true;
}
```

Treat "never sent" as equal to the default value so idle players cost zero
packets. After a config reload, re-run the sync for every online player so
toggles take effect immediately.

## Testability seam

Extract the effect body as a static routine separate from the live trigger, so
gametests can drive it when the trigger can't run inside a bounded test
structure (a boss death sequence that outlives the test, a player-riding
condition with no real player). Package-private or a `*ForTest` accessor is
enough:

```java
// Live path: mixin/event calls this at the real trigger.
// Gametest path: calls it directly with explicit arguments.
public static void dropCore(ServerLevel level, double x, double y, double z) { ... }
```

Keep pure math (chances, thresholds, curves) in a plain class with no Fabric
imports so it stays reachable from JUnit without a game.

## Guardrails

- **Never** leave a static collection without a `SERVER_STOPPED` reset. It
  leaks across gametest server restarts and world reloads in the same JVM.
- **Never** let one handler's exception escape into the event dispatch — wrap
  the effect body in `try/catch (Exception)` with a contextual `LOGGER.error`.
- **Never** do per-tick work for a time-scale phenomenon. Interval-gate the
  sweep and act only on transitions since the previous sweep.
- **Never** force-load chunks from a sweep. Iterate loaded chunks only, via
  `getChunkNow` with a `null` skip.
- **Never** scan all entities or all chunks when the effect is anchored to
  players — bound by online players × view distance.
- **Never** spawn a tracked persistent entity before registering it in the
  tracker — `addFreshEntity` fires `ENTITY_LOAD` synchronously and the orphan
  cleanup will discard it.
- **Always** config-gate at the top of the sweep so a server with the feature
  off pays nothing.
- **Always** pair per-player UUID maps with a `DISCONNECT` removal, and client
  caches with a client `DISCONNECT` clear.
- **Always** verify the world state before reverting a tracked block — the
  player may have changed it since you did.
- **Always** document a deliberately non-persisted tracker as deliberate.
- Thread-safety of the maps themselves (compound operations, volatile
  snapshots, cache coherence) is covered by the `mc-shared-state` skill.
