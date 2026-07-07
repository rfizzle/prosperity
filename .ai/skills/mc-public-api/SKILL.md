---
name: mc-public-api
description: Build and consume a Concord mod's public API the suite way (the Concord API Standard) — one stable com.rfizzle.<mod>.api package with a read-only static facade, reflection-backed client accessors with sentinels, provider/callback mutation under host-side error isolation, array-backed Fabric events, and classload-isolated soft-dependency consumption (isModLoaded guards, fail-open reflective probes, catch-Throwable degradation, c: convention tags). TRIGGER when creating or editing any class under an api/ package, an @Stable-marked type, a *Callback.java event, a provider interface, a compat/<modid>/ integration consuming a sibling mod, a c: convention tag, or when the user mentions exposing/consuming a mod API or integrating two mods.
---

The user is exposing functionality to other mods, or consuming a sibling's. The
Concord API Standard exists so integration is **additive and optional, never
load-bearing**: no mod may require another to load, and no feature may break when
a sibling is absent. The whole standard is summarized below — this skill is the
normative reference for member repos, which don't carry a separate standards doc.

## The package rule

Each mod publishes exactly one stable surface: **`com.rfizzle.<mod>.api`**.

- Everything inside `api` is stable and documented; everything outside it is
  internal and may change without notice in any release.
- Entity/player attachments, mixin interfaces, and manager classes are **not**
  API even when technically `public`. If a sibling needs that data, add an
  accessor to the `api` package — don't point consumers at internals.
- `api` code is **duplicated per mod, never shared as a runtime jar**
  (convention over dependency — a shared library would make one member load-bear
  on another).

## The `@Stable` marker

`org.jetbrains.annotations.ApiStatus` has **no `Stable` member** — it only ships
`Internal`, `Experimental`, etc. So each mod declares its own empty marker in its
`api` package and applies it to every API type. (Internal types that tooling
might surface still carry the real `@ApiStatus.Internal`.)

```java
package com.rfizzle.tribulation.api;
import java.lang.annotation.*;

/** Marks a type as part of the stable public API surface (Concord API Standard):
 *  stable across patch/minor, breaking changes only with a major bump + changelog. */
@Documented @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
public @interface Stable {}
```

## Read-only static facade

The facade is a `final` class with a private constructor and static accessors
that **return values and never mutate** the owning mod's state. Gameplay reads
are **server-authoritative** — they resolve from the mod's SavedData / attachments
/ managers.

```java
@Stable
public final class TribulationAPI {
    private TribulationAPI() {}

    /** Authoritative, server-side only. */
    public static int getLevel(ServerPlayer player) {
        return PlayerDifficultyState.getOrCreate(player.server).getLevel(player.getUUID());
    }
    public static OptionalInt getScaledTier(Entity entity) {
        Integer tier = entity.getAttached(TribulationAttachments.SCALED_TIER);
        return tier != null ? OptionalInt.of(tier) : OptionalInt.empty();
    }
    /** Read config-driven thresholds rather than hardcoding defaults — they're tunable. */
    public static int[] getTierThresholds() { /* from config */ }
}
```

## Client-safe accessors (reflection-backed, sentinel return)

Anything callable from **common code** that reads **client** state must not
reference client-only classes directly. Resolve the client method once into a
cached `MethodHandle` and return a **documented sentinel** when unavailable
(server side, or the client class absent). Safe to call unconditionally from
either side.

```java
public static int getClientLevel() {
    if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return -1;  // sentinel
    MethodHandle h = CLIENT_LEVEL.resolve();
    if (h == null) return -1;
    try { return (int) h.invokeExact(); } catch (Throwable t) { return -1; }
}

private static final ClientAccessor CLIENT_LEVEL =
        new ClientAccessor("com.rfizzle.tribulation.client.ClientTribulationState", "getLevel", int.class);

private static final class ClientAccessor {     // resolve-once, memoized, hot-path safe
    private volatile boolean resolved; private volatile MethodHandle handle;
    private final AtomicBoolean logged = new AtomicBoolean(false);
    MethodHandle resolve() {
        if (resolved) return handle;
        synchronized (this) {
            if (resolved) return handle;
            try { handle = MethodHandles.publicLookup().findStatic(
                    Class.forName(className), methodName, MethodType.methodType(returnType)); }
            catch (Throwable t) { if (logged.compareAndSet(false, true)) LOGGER.warn("accessor {}.{} unavailable", className, methodName, t); }
            resolved = true; return handle;
        }
    }
}
```

The HUD coordination accessors (`isHudVisible()` / `getHudHeight()`) follow
exactly this pattern — see the `mc-hud` skill for how siblings consume them.

## The one sanctioned mutation: provider / callback

Nothing in `api` mutates the owning mod's state, with one exception: the host
defines a **provider slot** (or fires an event carrying a mutable context), calls
*out* at a defined moment, and the guest adjusts the result. The guest never
reaches into the host. Two iron rules:

- **Last-writer-wins `volatile` slot**, defaulted to a no-op identity provider.
- **Error isolation is the host's job.** A provider that throws or returns a
  non-finite value is caught and the host falls back to its configured default. A
  misbehaving integration must never crash or corrupt the host.

```java
private static volatile ArmorDropChanceProvider armorDropChanceProvider =
        (mob, tier, slot, stack, def) -> def;                 // no-op default

public static void setArmorDropChanceProvider(ArmorDropChanceProvider p) {
    if (p != null) armorDropChanceProvider = p;               // last writer wins
}

/** Internal use: host calls this; a misbehaving provider never breaks mob spawning. */
public static float resolveArmorDropChance(Entity mob, int tier, EquipmentSlot slot, ItemStack stack, float def) {
    try {
        float resolved = armorDropChanceProvider.resolve(mob, tier, slot, stack, def);
        return Float.isFinite(resolved) ? resolved : def;     // reject NaN/Inf
    } catch (Exception e) {
        LOGGER.warn("Armor drop-chance provider threw; using default", e);
        return def;
    }
}

@FunctionalInterface public interface ArmorDropChanceProvider {
    float resolve(Entity mob, int tier, EquipmentSlot slot, ItemStack stack, float defaultChance);
}
```

## Events

Fabric `Event` objects, array-backed, named `<Mod><Thing>Callback`, **fired
server-side** at state changes, with old + new values for scalar changes. The
array-backed invoker is the natural place the host iterates listeners — keep it
simple; per-listener error isolation belongs on provider slots that feed back
into host logic.

```java
@Stable
public interface TribulationLevelCallback {
    Event<TribulationLevelCallback> EVENT = EventFactory.createArrayBacked(TribulationLevelCallback.class,
        listeners -> (player, oldLevel, newLevel) -> {
            for (TribulationLevelCallback l : listeners) l.onLevelChanged(player, oldLevel, newLevel);
        });
    void onLevelChanged(ServerPlayer player, int oldLevel, int newLevel);
}
```

Document **every** trigger in the callback's Javadoc (e.g. "fired on playtime
progression, death relief, Shatter Shard use, and `/tribulation set`") so a
consumer knows exactly when it runs.

## Extension interfaces (all-default opt-in)

When you want a *block or item* to participate in mod behavior without a datapack
entry, expose an `@Stable` interface whose methods are **all defaulted**. A type
can implement it as a pure marker (inheriting registry-backed behavior) or
override for dynamic behavior. This is the read side's mirror of the provider
pattern — the host calls the interface; the implementer supplies data.

```java
@Stable
public interface IEnchantingStatProvider {
    default EnchantingStats getStats(Level level, BlockPos pos, BlockState state) {
        return EnchantingStatRegistry.lookup(level, state);   // sensible default; override to customize
    }
}
```

## Consuming a sibling — soft dependency only

Sibling Concord jars resolve straight from their GitHub release jars — the
Modrinth projects are not publicly resolvable, so `maven.modrinth:<sibling>`
coordinates won't resolve. Declare an **artifact-only ivy repository** over
GitHub Releases, scoped so it never shadows a real Maven group (the full
repository-hygiene recipe lives in the `mc-gradle-builds` skill):

```gradle
repositories {
    ivy {
        name = 'GitHubReleases'
        url = 'https://github.com'
        patternLayout {
            artifact '/[organisation]/[module]/releases/download/v[revision]/[module]-[revision].jar'
        }
        metadataSources { artifact() }        // artifact-only: no POM exists
        content { includeGroup 'rfizzle' }    // never shadows a real Maven group
    }
}

dependencies {
    modCompileOnly "rfizzle:tribulation:${project.tribulation_version}"   // never `depends` in fabric.mod.json
}
```

```java
if (FabricLoader.getInstance().isModLoaded("tribulation")) {
    TribulationCompat.register();   // adapter only class-loaded behind this guard
}
```

- Every call site is guarded by `isModLoaded("<modid>")`, **or** lives in a class
  only class-loaded behind such a guard.
- Integration code lives in `compat/<modid>/` packages that fail gracefully when
  the target is absent.
- Conditional **data** (recipes, trade entries, loot that references a sibling's
  items) uses Fabric resource conditions keyed on the sibling's mod id.

### Classload isolation

The JVM must never be asked to resolve a sibling's classes unless the guard has
already passed. Two sanctioned shapes:

- **Adapter class:** every foreign reference lives in a `compat/<modid>/` class
  whose `register()` is only reached behind the `isModLoaded` guard (the shape
  above). Nothing outside the guard may even *name* the class in a way that
  forces resolution of the sibling's types.
- **Nested `Api` holder:** when the compat entry point itself must be callable
  with the sibling absent (it returns the un-integrated defaults), confine the
  foreign references to a nested static holder class. The outer class loads
  freely; the holder — and with it the sibling's classes — only resolves once
  the guard passed.

```java
public final class TribulationCompat {
    public static EffectivePylonLimits effectiveLimits(ServerLevel level, BlockPos pos, MercantileConfig config) {
        EffectivePylonLimits base = new EffectivePylonLimits(config.pylonMaxGolems, config.pylonDetectionRadius);
        if (!FabricLoader.getInstance().isModLoaded("tribulation")) return base;
        try {
            ServerPlayer nearest = nearestSurvivalPlayer(level, pos, config);   // no sibling types yet
            if (nearest == null) return base;
            return scaledLimits(tierFor(Api.effectiveLevel(nearest), Api.tierThresholds()), config);
        } catch (Throwable t) {
            if (LOGGED.compareAndSet(false, true)) LOGGER.warn("Tribulation API call failed; using configured defaults", t);
            return base;
        }
    }

    /** The ONLY class that touches TribulationAPI. Kept nested so the JVM never resolves
     *  the sibling's classes unless the isModLoaded guard above has already passed. */
    private static final class Api {
        static int effectiveLevel(ServerPlayer p) { return TribulationAPI.getEffectiveLevel(p); }
        static int[] tierThresholds()             { return TribulationAPI.getTierThresholds(); }
    }
}
```

### Catch `Throwable`, degrade to vanilla

Wrap the **whole integration body** in `catch (Throwable)`, not `Exception`: an
older sibling jar that predates a method surfaces the miss as a `LinkageError`,
which must degrade the host to its un-integrated behavior — never crash it. Log
once via `AtomicBoolean.compareAndSet` and return the default on every failure
path. Both code paths above and the probe below follow this posture.

### The fail-open reflective probe

For optional **behavioral** queries — "does the sibling currently own this
behavior?" — where the sibling may be present but predate the accessor, skip the
compile-time reference entirely and probe with `MethodHandles.publicLookup()
.findStatic` against the sibling's stable `api` package. The probe **fails
open**: absent mod, absent class/method, or a throwing call all mean "the
sibling does not own this — handle it yourself."

```java
/** The sibling's config is hot-reloadable, so re-query per death — never cache the answer. */
public static boolean isSoulInventoryActive() {
    if (!FabricLoader.getInstance().isModLoaded("tribulation")) return false;   // fail open
    MethodHandle h = resolve();          // findStatic against com.rfizzle.tribulation.api.TribulationAPI,
    if (h == null) return false;         // memoized like the client accessor above; null = older jar, fail open
    try {
        return (boolean) h.invokeExact();
    } catch (Throwable t) {
        if (LOGGED.compareAndSet(false, true)) LOGGER.warn("{}.{} threw; handling locally", API_CLASS, API_METHOD, t);
        return false;                    // fail open
    }
}
```

Resolve the *handle* once (same memoized double-checked pattern as the client
accessor); never cache the *answer* — the sibling's config can change between
events. The cross-mod HUD offset worked example in the `mc-hud` skill is this
same pattern applied to render coordination.

### Pure cores: keep the mapping math sibling-free

The tier mapping, key derivation, and scaling math that a compat adapter feeds
sibling data into live in **pure static methods (or a dedicated `*Keys` class)
with zero third-party imports**, so they unit-test without the sibling jar on
the classpath. The glue class holds the foreign references; the core holds the
logic.

```java
/** Pure — no FTB import, so it unit-tests without the FTB Teams jar on the classpath. */
@Nullable
public static String keyFor(@Nullable UUID teamId, boolean partyTeam) {
    return (teamId == null || !partyTeam) ? null : "ftbteams:" + teamId;
}
```

Two invariants for the math:

- **The un-integrated config is the floor.** Scaling clamps so integration can
  only add on top of the configured base, never shrink below it (e.g.
  `Math.clamp(base + tier * bonus, base, cap)` with the cap itself healed to
  `max(base, cap)`).
- **Defaults on every failure path** — absent sibling, no data in range, a
  throwing call: all return the configured base values.

### Provider chains: when several siblings can answer

When more than one sibling can answer the same question (e.g. two party mods
each know the player's group), the host holds a **`CopyOnWriteArrayList` of
providers** instead of a single slot — registration at mod init and the
server-thread query never contend. First non-null (and non-blank) answer wins;
each provider is invoked under `try/catch (Throwable)` with once-only logging,
so one broken adapter never breaks the chain.

```java
private static final List<PartyGroupProvider> PROVIDERS = new CopyOnWriteArrayList<>();

@Nullable
public static String partyGroupOverride(ServerPlayer player) {
    for (PartyGroupProvider provider : PROVIDERS) {     // registration order
        String key;
        try {
            key = provider.groupKey(player);
        } catch (Throwable t) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) LOGGER.warn("Party group provider threw; skipping", t);
            continue;
        }
        if (key != null && !key.isBlank()) return key;  // first non-null, non-blank wins
    }
    return null;                                        // caller falls back to its default
}
```

Precedence is **deterministic by registration order in `onInitialize`** — the
guards run top to bottom, so whichever adapter registers first wins — and that
ordering is documented right there at the registration site (e.g. "with both
party mods present OPAC, registered above, takes precedence").

### Convention tags: `c:` interop with zero code

For behaviors that several mods implement independently, a shared tag in the
`c:` namespace is the interop channel — no mod references another's ids at all.
Every mod with behavior X contributes its own entry to the tag; every mod
honors the tag. Example: the keep-on-death contract is `#c:soulbound` — Meridian
adds `meridian:tether`, Tribulation adds `tribulation:soulbound`, and each mod's
death handling keys off the tag, so a sibling's enchant is honored without ever
naming it:

```java
/** The c: convention tag for the keep-on-death contract. */
public static final TagKey<Enchantment> SOULBOUND = TagKey.create(
        Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("c", "soulbound"));
```

When both mods would act on the same item, one **ownership rule** arbitrates
(the fail-open probe above: Tribulation owns the death when its soul-inventory
is active; Meridian stands down) so the player never receives the item twice.

### Test the integration

- **Unit-test the pure core** exhaustively — tier mapping inclusivity, clamps,
  the floor invariant — with no sibling jar on the test classpath.
- **Gametest the compat entry point** in a live world. With the sibling absent
  from the gametest classpath, assert the fallback yields exactly the configured
  defaults and nothing throws; when the sibling is on the gametest classpath,
  gametest the integrated path too.

## Versioning

- The `api` package is stable across **patch and minor** versions of the owner.
- A breaking change requires a **major version bump** and a changelog entry
  naming the broken signature.
- **Additive growth (new accessors, new events) is always allowed and expected** —
  design a minimal surface, extend later.

## Conformance checklist

A mod conforms to the Concord API Standard when:

- [ ] All externally consumable surface lives in `com.rfizzle.<mod>.api`, marked
      with the mod's local `@Stable`.
- [ ] No `api` method mutates mod state outside the provider/callback pattern.
- [ ] Every provider/callback invocation is wrapped in host-side error isolation
      (catch throws; reject non-finite returns; fall back to default).
- [ ] The mod's own sibling integrations use `modCompileOnly` + `isModLoaded`
      guards in `compat/<modid>/` packages, with foreign references classload-
      isolated (adapter class or nested `Api` holder) and whole integration
      bodies catching `Throwable`.
- [ ] Compat mapping/scaling cores are pure (zero sibling imports) and
      unit-tested without the sibling jar on the classpath.
- [ ] Client-reading accessors callable from common code are reflection-backed
      with documented sentinels.
- [ ] Events are Fabric `Event`s named `<Mod><Thing>Callback` with every trigger
      documented.
- [ ] README has a developer/API section with the gradle + guard example.
- [ ] `AGENTS.md` declares "conforms to Concord API Standard".

## Guardrails

- **Never** use `@ApiStatus.Stable` — it doesn't exist. Declare and apply the
  local `api.Stable` marker.
- **Never** ship `api` as a shared runtime library — duplicate it per mod.
- **Never** point a consumer at an internal class (attachment, manager, mixin
  interface). Add an `api` accessor instead.
- **Never** let a provider/callback throw or return NaN/Inf into host logic —
  catch and fall back to the default. A guest must not be able to crash the host.
- **Never** reference a sibling's `api.*` outside an `isModLoaded` guard or a
  class only loaded behind one; **never** declare a sibling under `depends`.
- **Never** catch only `Exception` around a sibling call — an older sibling jar
  surfaces a missing method as `LinkageError`; catch `Throwable` and fall back.
- **Never** cache a fail-open probe's answer — the sibling's config may be
  hot-reloadable; resolve the handle once, re-query the answer per event.
- **Never** reference a client-only class from a common-code API accessor — go
  through a reflection-backed handle with a sentinel.
- **Always** make API reads server-authoritative; client accessors exist for
  rendering only and must never let a client influence server state.
- **Always** document every event trigger and grow the surface additively.
