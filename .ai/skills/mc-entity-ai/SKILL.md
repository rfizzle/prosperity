---
name: mc-entity-ai
description: Modify vanilla mob AI in Fabric Minecraft mods at the right surgery level — goal-selector Goal injection for classic mobs, BehaviorBuilder brain behaviors for villagers/piglins, or path-node evaluation mixins for movement capability. TRIGGER when adding or changing mob behavior: writing a Goal subclass, injecting into registerGoals, touching Brain/BehaviorControl/MemoryModuleType, adjusting WalkNodeEvaluator/GroundPathNavigation, or when a villager/piglin ignores an added Goal.
---

The user is modifying how a vanilla mob behaves. The whole job is picking the
correct **surgery level** for the mob's AI architecture and then following that
level's discipline. The classic failure is picking the wrong level — most often
bolting a `Goal` onto a brain-driven mob and watching the brain's walk targets
fight (and usually beat) it every tick.

Mixin mechanics themselves (stub constructors, `modid$` prefixes, priorities,
mixins.json) are owned by **mc-mixin-craft** — apply that skill for the how of
every injection below; this skill owns *where* to inject and what the AI code
must look like.

## The decision table

| Mob's AI architecture | Examples | Correct surgery | Wrong move |
|---|---|---|---|
| Goal selector (classic) | iron golems, zombies, most animals | Inject custom `Goal`s into `goalSelector`/`targetSelector` | Trying to plumb memories into a mob with no brain schedule |
| Brain / behavior tree | villagers, piglins, axolotls, frogs | `BehaviorBuilder` behavior added to the mob's activity packages | Adding a `Goal` — the brain keeps writing `WALK_TARGET` and overrides your navigation |
| Neither — the mob *can't move* the way you need | any pathfinding mob | Node-evaluation mixins on `WalkNodeEvaluator` / `GroundPathNavigation` | Steering the mob manually every tick from a behavior |

Two subtleties:

- **Villagers keep a goal selector.** Brain mobs still *have* an (empty)
  `goalSelector`, and goals added to it do run — but the brain also runs, so a
  goal only works if you actively suppress the brain's competing outputs (erase
  `WALK_TARGET`/`LOOK_TARGET` while your goal is engaged, sparing survival
  activities like `Activity.PANIC` and `Activity.RAID`). That is a legitimate
  hybrid for player-driven overrides (e.g. a follow mode), not a license to
  skip the brain for ambient behavior.
- **Movement capability is a third axis, not a variant of the first two.** If
  the mob *decides* correctly but physically cannot make the trip (closed fence
  gate, ladder, stair lip, open water), the fix belongs in path-node
  evaluation, not in more AI.

## Level 1 — Goal injection (classic mobs)

Mixin into the mob's `registerGoals` at TAIL (or the constructor tail if the
mob registers goals elsewhere) and add goals at **explicit priorities chosen
against vanilla's table** — document why:

```java
@Mixin(IronGolem.class)
public abstract class IronGolemMixin extends AbstractGolem {

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void mymod$addGoals(CallbackInfo ci) {
        IronGolem self = (IronGolem) (Object) this;
        // Priority 0 — above vanilla MeleeAttackGoal (1) and MoveTowardsTargetGoal (2),
        // so returning home preempts chasing. Inert (canUse false) on unmarked golems.
        this.goalSelector.addGoal(0, new ReturnHomeGoal(self));
        // Lower number than vanilla's target goal at slot 3 so ours wins acquisition.
        this.targetSelector.addGoal(2, new CustomTargetGoal(self));
    }
}
```

Gate registration behind config where the feature is toggleable, and make the
goal **inert by default**: `canUse()` returns false unless the mob is opted in
(a tag, a manager entry), so vanilla behavior is untouched for every other
instance of that mob in the world.

### Goal discipline

- **`canUse` vs `canContinueToUse` do different jobs.** `canUse` is the
  (potentially expensive) engagement check — resolve the target, validate it,
  cache it in a field. `canContinueToUse` is the cheap per-tick keep-alive —
  check the cached state, distances, and abort conditions. Don't re-run the
  full search there.
- **Recalc cooldowns.** Never call `navigation.moveTo(...)` every tick — repath
  on an interval (10–20 ticks) via a countdown field reset in `start()`:

  ```java
  @Override
  public void tick() {
      if (--recalcCooldown > 0) return;
      recalcCooldown = RECALC_INTERVAL;
      if (mob.distanceToSqr(target) > STOP_DISTANCE_SQR) {
          mob.getNavigation().moveTo(target, SPEED);
      } else {
          mob.getNavigation().stop();
      }
  }
  ```

- **Self-cancel through the owning manager, not by returning false forever.**
  When the goal detects its own end state (target gone, too far, arrived), it
  clears the authoritative state (`FollowManager.stopFollowing(mob)`, synched
  entity-data flag off) so every other observer agrees the mode ended.
- **Declare Flags honestly.** `setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))` for
  a goal that navigates and looks; `Flag.MOVE` only if it never touches look
  control; target goals extending `NearestAttackableTargetGoal` handle flags
  themselves. Wrong flags mean vanilla goals run concurrently and fight yours.
- **Clean up in `stop()`.** Null cached targets and `navigation.stop()` — the
  selector calls `stop()` when a higher-priority goal preempts you.
- Prefer extending a vanilla goal (`NearestAttackableTargetGoal`, etc.) over
  reimplementing its scan; override `getFollowDistance()` /
  `getTargetSearchArea(...)` and wrap `canUse()` with your opt-in check.

## Level 2 — Brain behaviors (villagers, piglins)

Write the behavior as a **declarative `BehaviorBuilder`** keyed on memory
presence — the memory conditions are the trigger, the trigger body returns
`true` only when it acted:

```java
public class ClimbLadder {
    public static BehaviorControl<LivingEntity> create() {
        return BehaviorBuilder.create(
            instance -> instance.group(
                instance.present(MemoryModuleType.PATH)   // only runs while pathing
            ).apply(instance, pathAccessor -> (level, entity, tick) -> {
                if (!MyModConfig.get().enablePathfindingFixes
                        || !MyModConfig.get().enablePathfindingLadders) {
                    return false;   // double config gate — master + feature flag
                }
                if (!entity.onClimbable()) return false;

                Path path = instance.get(pathAccessor);
                if (path.notStarted() || path.isDone()) return false;
                // ... act on path.getNextNode(), return true when handled
                return false;
            })
        );
    }
}
```

- Use `instance.present(...)` for memories that must exist,
  `instance.registered(...)` + `instance.tryGet(...)` for optional ones
  (e.g. `NEAREST_LIVING_ENTITIES`).
- **Double config gating**: the behavior object is registered unconditionally,
  so the runtime check inside the trigger is what honors config — gate on both
  the master switch and the feature flag, every invocation.
- Behaviors are stateless singletons per mob *type*, not per mob — per-run
  state captured in the closure (`MutableObject`, a `Set` of positions) is
  shared across all mobs using that instance; keep it keyed by position/path
  identity, not by "the" mob.

Inject into the activity packages via a RETURN inject that rebuilds the
immutable list (the vanilla lists are `ImmutableList` — you cannot `add`):

```java
@Mixin(VillagerGoalPackages.class)
public abstract class VillagerGoalPackagesMixin {
    @Inject(method = "getCorePackage", at = @At("RETURN"), cancellable = true)
    private static void mymod$addBehaviors(VillagerProfession profession, float speed,
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> cir) {
        cir.setReturnValue(
            ImmutableList.<Pair<Integer, ? extends BehaviorControl<? super Villager>>>builder()
                .addAll(cir.getReturnValue())
                .add(Pair.of(0, ClimbLadder.create()))
                .build());
    }
}
```

Pick the package by lifecycle: the core package runs in every activity;
work/meet/rest packages only during their scheduled activity.

## Level 3 — Path-node surgery (movement capability)

Two targets: `WalkNodeEvaluator` decides what each block *is* to the
pathfinder; `GroundPathNavigation` decides when paths may update.

Rules that keep this safe:

- **One RETURN inject per target method.** If you need several path-type
  adjustments on `getPathType`, merge them into a single injection with
  ordered branches over a snapshot of the original value — two separate
  injects on the same method have ambiguous ordering, and the second sees the
  first's output as "vanilla":

  ```java
  @Inject(method = "getPathType(Lnet/minecraft/world/level/pathfinder/PathfindingContext;III)Lnet/minecraft/world/level/pathfinder/PathType;",
          at = @At("RETURN"), cancellable = true)
  private void mymod$adjustPathType(PathfindingContext ctx, int x, int y, int z,
                                    CallbackInfoReturnable<PathType> cir) {
      if (!(this.mob instanceof Villager)) return;      // narrowest mob gate first
      if (!MyModConfig.get().enablePathfindingFixes) return;
      PathType original = cir.getReturnValue();          // snapshot before any branch writes
      if (original == PathType.FENCE && /* closed fence gate */ ...) {
          cir.setReturnValue(PathType.DOOR_WOOD_CLOSED); // pathable-with-interaction
      } else if (original == PathType.WATER && ...) {
          cir.setReturnValue(PathType.BLOCKED);
      }
  }
  ```

- **RETURN, not HEAD.** A HEAD inject that computes its own answer clobbers
  vanilla's collision, hazard, swim, and door validation. At RETURN you see
  vanilla's verdict and adjust only the cases you own.
- **Substitute nodes only when vanilla gave up.** In `findAcceptedNode`-style
  hooks, only offer your replacement when the vanilla return was `null` or a
  `BLOCKED` node — if vanilla produced a walkable node, its validation stands.
  Re-check malus (`getPathfindingMalus(...) >= 0`) and step height before
  substituting.
- Extra neighbors (`getNeighbors` RETURN) must respect the array: bounds-check
  against `nodes.length`, skip `closed` nodes, and return the updated count.
- Navigation gates (`canUpdatePath` RETURN) should only ever *widen*: return
  early if vanilla already said true, then allow your extra case (e.g.
  `mob.onClimbable()`).

Node surgery only makes terrain *pathable*; if traversing it needs an action
(opening the gate, climbing the ladder), pair it with a Level 2 behavior that
watches `MemoryModuleType.PATH` and performs the interaction.

## Gametesting AI

Pathfinding claims are testable without flaky wandering: build a bounded
structure, spawn the mob, and assert on the **computed path**, not on the mob
walking it.

```java
@GameTest(template = EMPTY_STRUCTURE)
public void fenceGatePassable(GameTestHelper helper) {
    buildFloor(helper);                       // wall off everything except the gate
    helper.setBlock(2, 1, 2, Blocks.OAK_FENCE_GATE.defaultBlockState());
    Villager villager = helper.spawn(EntityType.VILLAGER, 2, 1, 0);
    helper.runAfterDelay(2, () -> {           // let the entity finish spawning
        Path path = villager.getNavigation().createPath(
                helper.absolutePos(new BlockPos(2, 1, 4)), 0);
        helper.assertTrue(path != null, "Villager should find a path");
        helper.assertTrue(pathContains(path, helper.absolutePos(new BlockPos(2, 1, 2))),
                "Path should go through fence gate");
        helper.succeed();
    });
}
```

- Assert both directions: the feature test (`pathContains` the gate) and the
  avoidance test (path exists but does *not* contain the water block).
- Test the config-off path too: flip the flag, run in its own `batch`, restore
  the original value in a `finally`, and assert vanilla behavior returned.
- Structure coordinates are relative — convert with `helper.absolutePos(...)`
  before comparing against path nodes.

## Guardrails

- **Never** add ambient behavior to a brain mob via a `Goal` — use a
  `BehaviorBuilder` behavior in the right activity package. A `Goal` on a
  brain mob is acceptable only for a player-driven override mode, and then you
  must erase `WALK_TARGET`/`LOOK_TARGET` while it's engaged, sparing survival
  activities (panic, hide, raid).
- **Never** register a goal without an explicit priority chosen against
  vanilla's registrations, with a comment saying which vanilla goals it must
  beat or yield to.
- **Never** ship a goal that isn't inert by default — `canUse()` must gate on
  the opt-in condition so unrelated instances of the mob keep vanilla AI.
- **Never** call `navigation.moveTo(...)` every tick — repath on a cooldown.
- **Always** split expensive acquisition into `canUse` and keep
  `canContinueToUse` cheap; cache the target in `canUse`.
- **Always** declare accurate `Flag`s and clean up (`stop()` nulls state and
  stops navigation).
- **Always** double-gate brain behaviors on config *inside* the trigger —
  registration happens once at class-load; only the runtime check honors a
  toggle.
- **Never** stack multiple `@Inject(at = @At("RETURN"))` adjustments on the
  same node-evaluator method — merge into one injection with a snapshot of the
  original return value.
- **Never** compute path types at HEAD — adjust vanilla's verdict at RETURN so
  its collision/hazard validation is preserved; substitute nodes only when
  vanilla returned `null` or `BLOCKED`.
- **Always** gate node-evaluator mixins on the narrowest mob check first
  (`this.mob instanceof Villager`) — these run for every pathfinding mob in
  the world.
- **Always** gametest pathfinding changes with bounded structures asserting on
  `createPath(...)` output, including a config-disabled case that restores the
  flag in `finally`.
