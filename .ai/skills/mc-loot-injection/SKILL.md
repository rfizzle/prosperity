---
name: mc-loot-injection
description: Injecting drops into vanilla loot in Fabric Minecraft mods — the escalation ladder from datagen tables through LootTableEvents.MODIFY and death events to a death-tick mixin, plus codec-backed live-config loot conditions. TRIGGER when adding a drop to a vanilla mob, block, or chest table: registering a LootTableEvents.MODIFY listener, writing a custom LootItemCondition, choosing between a loot pool and a death handler, or when a boss (e.g. the ender dragon) drops nothing because its death never rolls a table.
---

The user is making something drop from vanilla content — a mob kill, a block
break, a chest. Success means two choices made correctly: the **lowest rung on
the escalation ladder** that can actually observe the drop moment, and a gate
that reads **live config at roll time** so a config reload changes drop rates
on the very next kill, with no datapack reload.

## The escalation ladder

Always use the lowest rung that works. Each step down trades data-driven-ness
for reach.

| # | Situation | Tool | Why this rung |
|---|-----------|------|---------------|
| 1 | You own the table (your block, your entity, your chest) | Plain loot-table JSON via datapack/datagen | Fully data-driven; server owners and packs can override it with zero code |
| 2 | Adding drops to a vanilla (or other mod's) table | `LootTableEvents.MODIFY` — layer extra pools on | Keeps the vanilla table intact; your pools ride alongside, and datapack replacements can opt out |
| 3 | Drop logic a loot table can't express (inspect kill circumstances, mutate world state, cross-entity logic) | `ServerLivingEntityEvents.AFTER_DEATH` | Full Java control with the entity and damage source in hand |
| 4 | Death bypasses both — no table rolled, no death event fired | Entity mixin at the terminal death frame (e.g. `tickDeath`) | Last resort; see `mc-mixin-craft` for the mechanics |

Rung 4 exists because some vanilla deaths are hardcoded. The ender dragon is
the canonical case: it never routes through `LivingEntity.die()` (so
`AFTER_DEATH` never fires) and its death sequence rolls **no loot table** (so
a datapack `entities/ender_dragon` table would sit inert). Only a mixin into
`EnderDragon.tickDeath()` at the terminal frame can observe the kill. Keep the
mixin body a one-liner that delegates to a plain static method — that matters
for testing (below).

## Rung 2: `LootTableEvents.MODIFY`

Match the target table by `ResourceKey`, guard with `source.isBuiltin()`, and
append pools — never rebuild the table.

```java
public final class TrophyLootHandler {

    private static final ResourceKey<LootTable> WARDEN_TABLE =
            EntityType.WARDEN.getDefaultLootTable();

    public static void register() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (!WARDEN_TABLE.equals(key) || !source.isBuiltin()) {
                return;
            }
            modify(tableBuilder, registries);
        });
    }

    // Split out so unit tests can exercise the mutation without Fabric's event pipeline.
    static void modify(LootTable.Builder tableBuilder, HolderLookup.Provider registries) {
        // Pool A — the guaranteed (base-chance) drop
        tableBuilder.withPool(LootPool.lootPool()
                .setRolls(UniformGenerator.between(1.0F, 1.0F))
                .add(LootItem.lootTableItem(MymodRegistry.TROPHY))
                .when(() -> new TrophyDropCondition(TrophyDropCondition.Kind.DROP_CHANCE)));

        // Pool B — the looting-scaled bonus drop
        tableBuilder.withPool(LootPool.lootPool()
                .setRolls(UniformGenerator.between(1.0F, 1.0F))
                .add(LootItem.lootTableItem(MymodRegistry.TROPHY))
                .when(() -> new TrophyDropCondition(TrophyDropCondition.Kind.LOOTING_BONUS)));
    }
}
```

Two conventions here are load-bearing:

- **The `isBuiltin()` guard is the opt-out contract.** `MODIFY` fires for
  every loaded table, including ones a datapack has replaced. Guarding on
  `source.isBuiltin()` means a server owner who fully replaces the table has
  thereby opted out of your injection — respect that; don't re-add your pools
  on top of their replacement.
- **Two-pool layout, not one pool with fancy rolls.** One pool for the
  base-chance drop, one for the looting-scaled bonus. Each pool rolls its
  condition independently, so the math stays legible and each half is tunable
  (and testable) on its own.

## Live config at roll time: a codec-backed condition

Do not gate pools with vanilla `LootItemRandomChanceCondition` — that bakes
the chance into the table at MODIFY time, and MODIFY only re-fires on a
datapack reload. Instead write a custom `LootItemCondition` whose
`test(LootContext)` reads the live config singleton (the `mc-config` skill) on
**every roll**. An operator edits the config, runs the mod's reload command,
and the next kill uses the new numbers.

```java
public record TrophyDropCondition(Kind kind) implements LootItemCondition {

    public enum Kind implements StringRepresentable { DROP_CHANCE, LOOTING_BONUS; /* ... */ }

    public static final MapCodec<TrophyDropCondition> CODEC =
            RecordCodecBuilder.mapCodec(inst -> inst.group(
                    Kind.CODEC.fieldOf("kind").forGetter(TrophyDropCondition::kind)
            ).apply(inst, TrophyDropCondition::new));

    public static final LootItemConditionType TYPE = new LootItemConditionType(CODEC);

    @Override
    public LootItemConditionType getType() { return TYPE; }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        // Declare params you read (e.g. ATTACKING_ENTITY for looting) so the
        // vanilla table validator can warn on a mismatched table shape.
        return kind == Kind.LOOTING_BONUS ? Set.of(LootContextParams.ATTACKING_ENTITY) : Set.of();
    }

    @Override
    public boolean test(LootContext context) {
        MymodConfig config = Mymod.getConfig();       // live read, every roll
        if (config == null) return false;              // pre-init: decline, don't NPE
        float chance = clamp(resolveChance(context, config));  // clamp to [0,1]
        return chance > 0.0F && context.getRandom().nextFloat() < chance;
    }
}
```

Details that bite:

- **Registration and class-init ordering.** Register the type at mod init:
  `Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, Mymod.id("trophy_drop"), TrophyDropCondition.TYPE)`.
  This must run before your MODIFY listener emits the condition — which can
  happen immediately on server start when loot tables load. Expose the type as
  a field on your registry class (`public static final LootItemConditionType
  TROPHY_DROP = TrophyDropCondition.TYPE;`) so registration follows the same
  pattern as every other entry and callers never depend on the condition
  class's initializer running as a side effect.
- **Clamp the chance to `[0, 1]`** before drawing — a mid-reload,
  half-written config can briefly hold out-of-range values.
- **Looting scaling:** pull the attacker from
  `LootContextParams.ATTACKING_ENTITY` and resolve the looting level through
  `context.getResolver()` at roll time. No attacker → level 0 → chance 0.

## Gametesting drops

When the live trigger can't run inside a bounded gametest structure, **drive
the extracted effect body directly** and assert on the result. A real ender
dragon can't complete its ten-second death sequence in a test structure
without being culled — so the mixin body delegates to a static
`dropTrophy(level, x, y, z)` routine, the gametest calls that routine, waits a
couple of ticks, and counts item entities of the expected type (exactly one).
The mixin injection point itself is verified against the vanilla source and by
manual play; the test guards the payload, which is the part with logic.

For config-sensitive rolls, gametests may rewrite the config file and invoke
the reload path, restoring the original bytes in a `finally`. Give each
config-mutating test a unique `batch` — batches run strictly sequentially, so
concurrent tests never observe another test's mutated config.

## Guardrails

- Never skip a ladder rung downward for convenience: a mixin where a loot pool
  works costs you datapack compatibility and pack-dev trust for nothing.
- Never rebuild or replace a vanilla table in MODIFY — append pools only.
- Always guard MODIFY listeners with `source.isBuiltin()`; a datapack that
  replaces the table has opted out of your injection.
- Never gate a config-tunable drop with vanilla random-chance conditions — the
  value freezes at MODIFY time. Use a codec-backed condition that reads config
  in `test(LootContext)`.
- Register your `LootItemConditionType` during mod init, before the first
  datapack load; a MODIFY listener emitting an unregistered type fails at
  table serialization.
- Declare every context param your condition reads in
  `getReferencedContextParams()` so the table validator stays honest.
- Keep mixin drop hooks one-line delegations to static routines so gametests
  can exercise the payload without the live entity.
