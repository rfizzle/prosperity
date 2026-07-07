---
name: mc-commands
description: Design the /<mod> Brigadier admin/debug command surface for a Concord mod — tree layout, per-node permission tiering, localization scoping, routing mutations through the persistent state manager, and keeping the published command reference in lockstep. TRIGGER when creating or editing a *Command.java or *Commands.java class, calling Commands.literal / .requires(hasPermission) / IntegerArgumentType, adding a "/mod reload" or debug subcommand, editing site/pages/commands.json, or when the user says "add a command", "op-only", "command permissions", or "command reference".
---

The user is building or extending a mod's `/mymod` command surface. Two things
decide whether it holds up: **every mutation must route through the mod's
manager or persistent state object** (so dirty flags, callbacks, and client
resyncs fire), and **permission gating is per-node, not per-tree** — the same
verb often has an open self-serve form and an op-gated cross-player form, and
a blanket `.requires` on the root gets both wrong.

## Tree shape: one root, verb groups

Everything lives under a single literal named after the mod. Group related
verbs under a shared literal (`hearts`, `reputation`, `pins`) rather than
flattening into `mymod-heartsreset`-style roots. Registration itself is a
one-liner in `onInitialize` via `CommandRegistrationCallback` — see the
**mc-registration** skill for the hook; this skill covers everything inside it.

```java
public final class MyModCommand {
    public static final String ROOT = MyMod.MOD_ID;

    private MyModCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("info")
                        .executes(MyModCommand::runInfoSelf)          // perm 0: self-serve read
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2)) // perm 2: cross-player query
                                .executes(MyModCommand::runInfoOther)))
                .then(Commands.literal("set")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value",
                                                IntegerArgumentType.integer(ScoreData.MIN_SCORE, ScoreData.MAX_SCORE))
                                        .executes(MyModCommand::runSet))))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(MyModCommand::runReload)));
    }
}
```

Keep the whole tree in one `register` method: the layout is the command
surface's table of contents, and reviewers (and the published reference) read
it top to bottom.

## Permission tiering: per node, with deliberate escape hatches

| Verb class | Gate | Example |
|---|---|---|
| Read about **yourself** | none (perm 0) | `/mymod info`, `/mymod stats` |
| Read about **another player** | `.requires(hasPermission(2))` on the `player` argument node | `/mymod info <player>` |
| Any **mutation** | `.requires(hasPermission(2))` on the verb literal | `/mymod set`, `/mymod reload`, `/mymod reset` |
| **Shedding capped state you own** | none — deliberate escape hatch | `/mymod pins remove <i>`, `/mymod pins clear` |

The gate goes on the exact node where privilege starts. A group literal like
`hearts` can be open for the self form while `hearts <player>` carries the
`.requires` on the argument node — Brigadier evaluates `requires` per node
during both tab-completion and execution, so ordinary players never even see
the gated branch.

The escape-hatch row is a real policy, not an oversight: a verb that only
*removes* the caller's own capped state (pinned entries, tracked slots) stays
ungated **and** skips the feature-enabled config check, so players can always
shed slots even while an admin has the feature toggled off. Gate the *listing*
verb on the config flag; leave the *shedding* verb open.

## Localization scoping: translatable for players, literal for op telemetry

Player-facing output — anything a perm-0 player can trigger — goes through
translatable `command.<mod>.*` keys so a localized client never sees English:

```java
src.sendSuccess(() -> Component.translatable("command.mymod.info",
        name, score, tierName), false);           // read: no admin broadcast
src.sendSuccess(() -> Component.translatable("command.mymod.set",
        target.getDisplayName(), newValue), true); // mutation: broadcast to ops/log
```

The `sendSuccess` boolean is the admin-broadcast flag: `false` for reads,
`true` for mutations so they land in the op feed and server log. Failures use
`src.sendFailure(...)` and return `0`.

Op-gated dense diagnostics (`config` dumps, `debug` factor breakdowns,
`inspect` modifier listings) may stay `Component.literal` with
`String.format(Locale.ROOT, ...)`. This is deliberate scoping, not laziness:
they are operator telemetry no ordinary player can reach, their format strings
are dense numeric tables with no player-facing value a translation would
serve, and keeping them literal keeps the lang file honest. State the policy
in the command class javadoc so the boundary is auditable.

## Route mutations through the manager — never poke fields

Every admin mutation goes through the same manager or persistent-state entry
point gameplay uses, so `setDirty()`, change callbacks, and HUD/client
resyncs all fire exactly as they would in normal play:

```java
private static int runSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
    int value = IntegerArgumentType.getInteger(ctx, "value");
    // Routed through ScoreManager so the change fires ScoreChangedCallback,
    // persists via setDirty(), and resyncs the target's HUD.
    int applied = ScoreManager.setScore(target, value);
    ctx.getSource().sendSuccess(() -> Component.translatable(
            "command.mymod.set", target.getDisplayName(), applied), true);
    return Command.SINGLE_SUCCESS;
}
```

If the command handler is the only caller that would need to remember "also
sync the client, also fire the event", the sync belongs inside the manager
method, not the handler. A command that writes a field directly is a bug even
when it happens to work — it silently skips persistence and desyncs HUDs.

## Argument bounds from data-model constants

Derive `IntegerArgumentType` bounds from the same constants the data model
enforces, so Brigadier rejects garbage at parse time and the bounds can never
drift from the model:

```java
// Wide enough to traverse the full range in one call; narrow enough that
// Brigadier rejects Integer.MAX_VALUE noise at parse time.
private static final int ADD_MIN_DELTA = ScoreData.MIN_SCORE - ScoreData.MAX_SCORE;
private static final int ADD_MAX_DELTA = ScoreData.MAX_SCORE - ScoreData.MIN_SCORE;

Commands.argument("value", IntegerArgumentType.integer(ScoreData.MIN_SCORE, ScoreData.MAX_SCORE))
Commands.argument("amount", IntegerArgumentType.integer(ADD_MIN_DELTA, ADD_MAX_DELTA))
Commands.argument("index", IntegerArgumentType.integer(1, ScoreData.MAX_PINNED))
```

A relative verb (`add`) gets delta bounds computed from the absolute ones;
an index verb gets `(1, MAX)`. Never write a bare `integer()` on a bounded
domain.

## Bounded operator scans

A verb that sweeps the world (`reset around [radius]`) needs two hard limits,
both constants on the command class:

```java
static final int DEFAULT_RADIUS = 128;
/** Hard cap — bounds the chunk scan an operator can trigger. */
static final int MAX_RADIUS = 256;

Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
```

Iterate **loaded chunks only** via `level.getChunkSource().getChunkNow(cx, cz)`
and skip `null` — the scan must never force-load. Walk the chunk's
`getBlockEntities()` map and distance-filter with `center.distSqr(pos)`
against the squared radius; batch any per-chunk client resend once per
affected chunk, not per block entity.

## `/mymod reload` semantics

Reload means *reload and re-propagate*, not just re-read the file:

```java
private static int runReload(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack src = ctx.getSource();
    try {
        MyMod.reloadConfig();
    } catch (Exception e) {
        MyMod.LOGGER.error("Config reload failed via command", e);
        src.sendFailure(Component.translatable("command.mymod.reload_failed",
                String.valueOf(e.getMessage())));
        return 0;
    }
    // Re-push the config sync payload AND every piece of dependent client
    // state, so a flipped feature toggle reaches clients now — not at the
    // next join or the next incidental sync.
    MyModNetworking.syncConfigToAll(src.getServer());
    for (ServerPlayer player : src.getServer().getPlayerList().getPlayers()) {
        HudStateManager.resync(player);
    }
    src.sendSuccess(() -> Component.translatable("command.mymod.reload"), true);
    return Command.SINGLE_SUCCESS;
}
```

Catch the reload exception, log it, and `sendFailure` — a typo in the config
file must produce a readable failure, not a server-log-only stack trace with
a silent success message.

## Testability: pure helpers below, gametest above

Split every command into Brigadier plumbing (thin) and plumbing-free logic
(everything else), and test each at its natural tier:

| Layer | What lives there | Test |
|---|---|---|
| Formatting/ordering helpers | `static` methods over primitives and plain collections, no `ServerPlayer`/`Level` — ideally no Minecraft imports at all | Tier-1 JUnit |
| Core operations | `public static`/package-private entry points like `applySet(...)`, `clearRadius(...)` — real state, no `CommandContext` | Gametest calls them directly |
| Wiring | tree registration, per-node gates | Gametest walks the dispatcher |

The dispatcher gametest asserts both existence and gating without executing
anything:

```java
var root = server.getCommands().getDispatcher().getRoot().getChild("mymod");
var nonOp = server.createCommandSourceStack().withPermission(0);
var op = server.createCommandSourceStack().withPermission(2);
helper.assertTrue(root.getChild("info").canUse(nonOp), "info should be public");
helper.assertFalse(root.getChild("set").canUse(nonOp), "set should deny non-ops");
helper.assertTrue(root.getChild("set").canUse(op), "set should allow ops");
```

For a seam that has side-effecting output (like reload), extract a
`MessageSink` functional interface so the core runs in JUnit with a recording
sink and the command layer only maps `(success, message)` to
`sendSuccess`/`sendFailure`.

## Published reference stays in lockstep

The mod's site carries a full command reference (`site/pages/commands.json`):
one table row per leaf — command, permission level (0 or 2), one-line
description — plus usage examples with representative output. Any change to
the tree (new verb, moved gate, renamed argument) lands in the same PR as the
matching `commands.json` edit. The permission column must mirror the actual
`.requires` placement, including split verbs (`/mymod info` at 0, `/mymod
info <player>` at 2 are separate rows).

## Guardrails

- Never gate the whole tree with one root-level `.requires` — permission is
  per node, and self-serve reads must stay at perm 0.
- Never mutate state fields directly from a command handler. Route through the
  manager/persistent-state method so `setDirty()`, callbacks, and client
  resyncs fire.
- Never write a bare `IntegerArgumentType.integer()` for a bounded domain —
  derive min/max from the data-model constants.
- Never force-load chunks in a scan verb: `getChunkNow` + null-skip, radius
  capped by a `MAX_RADIUS` constant enforced at parse time.
- `/mymod reload` must re-push sync payloads and dependent client state to all
  players, and must `sendFailure` (not throw) on a broken config file.
- Player-facing output is translatable `command.<mod>.*`; only op-gated dense
  diagnostics may stay `Component.literal`, and the class javadoc states that
  scoping.
- Mutations `sendSuccess(msg, true)` (op broadcast); reads pass `false`.
- Keep a shed-your-own-capped-state verb ungated and independent of the
  feature's config toggle.
- Extract formatting/ordering into plumbing-free static helpers with Tier-1
  JUnit; cover tree registration and gates with a dispatcher gametest.
- Update `site/pages/commands.json` in the same change as any tree edit.
