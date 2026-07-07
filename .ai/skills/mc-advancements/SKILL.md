---
name: mc-advancements
description: Custom advancement criteria (SimpleCriterionTrigger) and tutorial/milestone advancement trees for Fabric Minecraft mods — trigger and predicate design, registration order, fire-site discipline, and tests that assert the grant. TRIGGER when creating or editing a SimpleCriterionTrigger subclass, a *Criteria/*Triggers registry class, advancement JSON under data/<modid>/advancement/, a FabricAdvancementProvider, or when an advancement never grants, grants for the wrong player, or leaves gaps in a chain.
---

The user is adding custom advancement criteria and a milestone or tutorial
advancement tree. Four things decide whether this works: the criteria are
**registered before any advancement JSON loads**, each milestone's predicate is
shaped so the right action grants exactly the right advancement, triggers fire
**only from already-gated server-side success paths**, and tests assert the
advancement was actually *granted* — not merely that the handler ran.

## The trigger class: `SimpleCriterionTrigger`

A custom criterion is a `SimpleCriterionTrigger<T>` whose `TriggerInstance` is
a record implementing `SimpleCriterionTrigger.SimpleInstance`. The instance is
the JSON side (what an advancement declares); the public `trigger` method is
the code side (what a handler fires). The base class matches every listening
instance for that player against your predicate.

```java
public class TierReachedTrigger extends SimpleCriterionTrigger<TierReachedTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, int reachedTier) {
        this.trigger(player, instance -> instance.matches(reachedTier));
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, int minTier)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                ExtraCodecs.NON_NEGATIVE_INT.fieldOf("min_tier").forGetter(TriggerInstance::minTier)
        ).apply(i, TriggerInstance::new));

        public static TriggerInstance forTier(int minTier) {
            return new TriggerInstance(Optional.empty(), minTier);
        }

        public boolean matches(int reachedTier) {
            return reachedTier >= minTier;  // threshold, never equality — see below
        }
    }
}
```

Two predicate rules carry the whole design:

- **`optionalFieldOf` means absent-always-passes.** Give every parameter an
  `Optional` field and have `matches` skip absent ones. Each advancement then
  sets only the field it cares about, and one trigger backs several milestone
  families (e.g. `tier` / `min_containers` / `min_structures` on a single
  loot-generation trigger, with a static factory per shape).
- **Thresholds use `>=`, never `==`.** A `min_tier <= N` match means a single
  multi-tier jump grants every satisfied link in the chain at once instead of
  permanently skipping the intermediate advancements.

For milestones with no condition beyond "this player did the thing", you don't
need a predicate — or a custom class. Vanilla's `PlayerTrigger` is exactly a
no-condition player trigger; register plain instances of it under your own ids
and fire with `trigger(player, instance -> true)`.

## Decision: one class + many ids, or one parameterized criterion

| Milestone shape | Choose | Why |
|---|---|---|
| N distinct actions, each just "player did it" | One no-condition trigger class (or vanilla `PlayerTrigger`), **one instance per milestone**, each registered under its own id | Firing an instance grants only its milestone; no predicate to write, test, or get wrong |
| One event, milestones differ by a parameter (tier reached, running total, variety count) | One registered trigger with optional predicate fields; one `TriggerInstance` per advancement in the JSON | One id to register, one call site to fire, one predicate matrix to unit test — the JSON decides which milestones a fire satisfies |

Don't parameterize unrelated actions into one trigger (`"action": "deposit"`
vs `"extract"`) — those are the first shape: same class, separate ids.
Parameters are for *magnitudes* of the same event.

## Registration: `TRIGGER_TYPES`, before the JSON loads

Register into `BuiltInRegistries.TRIGGER_TYPES` directly (the **mc-registration**
skill's "Custom advancement triggers" section shows the hook). Vanilla's
`CriteriaTriggers.register(String, T)` is not a dependable surface — it is
package-private outside 1.21.1 — but it delegates to this same registry, so a
datagen'd advancement and the live server resolve the identical trigger id.

```java
public final class ModCriteria {
    public static final TierReachedTrigger TIER_REACHED = new TierReachedTrigger();
    public static final PlayerTrigger SHARD_USED = new PlayerTrigger();    // vanilla class, mod id
    public static final PlayerTrigger CORE_CHARGED = new PlayerTrigger();

    private static boolean registered = false;

    private ModCriteria() {}

    public static void register() {
        if (registered) return;  // onInitialize, datagen bootstrap, and gametest setup may all call this
        registered = true;
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, MyMod.id("tier_reached"), TIER_REACHED);
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, MyMod.id("shard_used"), SHARD_USED);
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, MyMod.id("core_charged"), CORE_CHARGED);
    }
}
```

The order contract: `ModCriteria.register()` runs in `onInitialize`, **before
any advancement JSON referencing these triggers is deserialized** — advancement
loading resolves each criterion's `"trigger"` id against `TRIGGER_TYPES`, and
an unknown id fails the whole advancement. The idempotent guard matters because
the same `register()` is also reached from datagen bootstrap (so the provider
can call `createCriterion`) and from test setup; a second `Registry.register`
of the same id throws.

## Fire-site discipline

Fire a trigger from the **already-feature-gated, server-side success path** —
the point after every early return (config toggle, blacklist, cooldown,
permission) has been passed. A disabled feature then simply never fires its
trigger; the criteria layer needs no toggle checks of its own, ever.

```java
if (!level.isClientSide) {
    pylon.addFuel(1);
    // ... the feature's real work ...
    if (player instanceof ServerPlayer serverPlayer) {
        ModCriteria.PYLON_FUELED.trigger(serverPlayer);      // last line of the success branch
    }
}
```

- **Prefer the choke point.** If several code paths perform the action, fire
  from the single method they all funnel through, so "counts real uses only" is
  inherited rather than re-implemented per path.
- **Player-driven only.** Automated paths (hopper transfers, machine-driven
  work) reach the same state change without a player and must grant nothing;
  the `instanceof ServerPlayer` guard enforces it. Fire from the take/commit
  step, never a result preview.
- **Derived milestones ride the mod's own public API**, with edge detection so
  a crossing fires exactly once:

```java
// onInitialize — dogfooding: the advancement listens to the same callback
// the mod exposes to other mods.
PowerChangedCallback.EVENT.register((player, oldPower, newPower) -> {
    int threshold = PowerTier.EMPOWERED.minScore();
    if (oldPower < threshold && newPower >= threshold) {
        ModCriteria.EMPOWERED.trigger(player);
    }
});
```

## Tree design

One tab: a **root granted by a vanilla trigger** every installer hits (a `tick`
criterion pops the "mod installed" toast on first join; a themed vanilla
trigger like `minecraft:villager_trade` works when the mod extends a vanilla
loop), with **custom-trigger children** hanging off it in chains. Background
texture on the root only. Frames escalate: `task` for steps, `goal` for chapter
ends, `challenge` to cap a chain.

Generate the tree with a `FabricAdvancementProvider` (wire-up and provider
ordering are the **mc-datagen** skill; output lands in
`data/<modid>/advancement/` and is never hand-edited):

```java
AdvancementHolder root = Advancement.Builder.advancement()
        .display(new ItemStack(ModItems.CORE), title("root"), description("root"),
                BACKGROUND, AdvancementType.TASK, true, false, false)  // toast, no chat, not hidden
        .addCriterion("tick", CriteriaTriggers.TICK.createCriterion(
                new PlayerTrigger.TriggerInstance(Optional.empty())))
        .save(consumer, MyMod.id("root").toString());

Advancement.Builder.advancement()
        .parent(root)
        .display(new ItemStack(Items.IRON_SWORD), title("tier_2"), description("tier_2"),
                null, AdvancementType.TASK, true, false, false)        // no background off-root
        .addCriterion("reach_tier_2", ModCriteria.TIER_REACHED.createCriterion(
                TierReachedTrigger.TriggerInstance.forTier(2)))
        .save(consumer, MyMod.id("tier_2").toString());
```

Titles and descriptions are always translatable —
`advancements.<modid>.<name>.title` / `.description` — with matching `en_us.json`
entries for every advancement. Hand-written JSON is fine for a small static
tree; set `"sends_telemetry_event": false` there (it is also the parse default
when the field is absent — the flag exists for vanilla's own telemetry, and a
modded id has no business in it).

## Testing at every tier

Test names below follow the suite's tier ladder (the **mc-mod-testing** skill).

| Tier | What it proves | How |
|---|---|---|
| 1 — fabric-loader-junit | Predicate matrix: thresholds inclusive-and-floored, exact matches reject others, absent fields don't gate | Call `matches(...)` directly; `Bootstrap.bootStrap()` in `@BeforeAll` because loading the instance initializes codecs that reference vanilla predicate codecs |
| 1 — fabric-loader-junit | Codec round-trip **per instance shape** (one per static factory) | `CODEC.encodeStart(JsonOps.INSTANCE, ...)` then `parse`, assert equal |
| 3 — gametest | The in-game action grants the advancement | Drive the real success path with a mock player; assert `isDone()` — criteria are mod-registered content, so this can't be a Tier-1 test |
| 3 — gametest | Isolation | A bystander player is *not* granted; automated (no-player) paths grant nothing |
| 3 — gametest (hand JSON only) | Shipped JSON stays valid | Parse each file with `Advancement.CODEC` via `RegistryOps`; check the file roster and that every id has non-blank lang keys |

The gametest helpers that make grant-assertions reliable:

```java
private ServerPlayer spawnListeningPlayer(GameTestHelper helper) {
    ServerPlayer player = helper.makeMockServerPlayerInLevel();
    // Reloading against the live manager guarantees the freshly-registered trigger
    // has an advancement listener for this player before the first fire.
    player.getAdvancements().reload(helper.getLevel().getServer().getAdvancements());
    return player;
}

private static void assertGranted(GameTestHelper helper, ServerPlayer player, String path) {
    AdvancementHolder holder = helper.getLevel().getServer().getAdvancements().get(MyMod.id(path));
    helper.assertTrue(holder != null, "advancement " + path + " should be loaded (datagen output present)");
    helper.assertTrue(player.getAdvancements().getOrStartProgress(holder).isDone(),
            "advancement " + path + " should be granted");
}
```

The null check on the holder catches stale or missing datagen output as a real
test failure instead of an NPE. The bystander test is two mock players, one
action: `assertGranted(actor)` plus the inverted assertion on the bystander.
For threshold chains, also assert the *next* link is still not done (10 grants
`volume_10`, not `volume_50`).

## Version notes

- **1.21.x:** advancement JSON lives under `data/<modid>/advancement/`
  (singular). `SimpleInstance` requires the `player()` accessor returning
  `Optional<ContextAwarePredicate>`; encode it with
  `EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player")`.
- `CriteriaTriggers.register(String, T)` is public on 1.21.1 but
  package-private on other 1.21.x versions; registering into `TRIGGER_TYPES`
  directly is the version-proof spelling.
- `Advancement.Builder.advancement()` sets `sends_telemetry_event: true` in
  datagen output; the JSON codec default when the field is absent is `false`.

## Guardrails

- **Always** register criteria in `onInitialize` before any advancement JSON
  loads, and keep `register()` idempotent — datagen and test bootstrap reach it
  too, and a duplicate `Registry.register` throws.
- **Never** add feature-toggle checks at a trigger call site. Fire from the
  already-gated server-side success branch so a disabled feature simply never
  fires.
- **Never** fire from preview or automated paths (result previews, hopper
  transfers). Guard with `instanceof ServerPlayer` and fire at the commit step.
- One **instance per milestone** for no-condition triggers — a shared instance
  under one id can't tell milestones apart.
- Threshold predicates use `>=`, never equality; every optional predicate field
  passes when absent (`optionalFieldOf` + a `matches` that skips empty fields).
- Gametests assert the advancement is **granted** (`isDone()`), include a
  bystander-isolation case, and spawn players through the
  `getAdvancements().reload(...)` trick so listeners exist before the fire.
- Every advancement ships `advancements.<modid>.*` title and description lang
  keys; keep `sends_telemetry_event` false in hand-written JSON.
- Datagen output is never hand-edited — change the provider and rerun
  `runDatagen` (see **mc-datagen**).
