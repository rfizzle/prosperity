---
name: mc-testing-mock
description: Mock player helpers in Fabric Gametest (MockPlayers.serverPlayerInLevel replica for the deprecated makeMockServerPlayerInLevel, plus makeMockPlayer). TRIGGER proactively when writing or editing *GameTest.java that needs a player instance, or when discussing mock players, player positioning, connection null checks, or player.discard() in gametest context. ALSO trigger when reviewing gametest code that uses ServerPlayer or Player in a test, or when writing production code that must distinguish real players from fake/automation players (FakePlayer guards).
---

The user is writing or reviewing Fabric gametest code that needs a mock player. Apply this guidance to avoid repeated lookups of how the connected-`ServerPlayer` replica and `makeMockPlayer` work.

## Two mock player factories

Gametests need one of two distinct mock players. They are NOT interchangeable: a lightweight client-side `Player` stub, or a fully connected `ServerPlayer` registered in the player list.

### Connected `ServerPlayer` — `MockPlayers.serverPlayerInLevel(helper)`

```java
ServerPlayer player = MockPlayers.serverPlayerInLevel(helper);
```

`GameTestHelper.makeMockServerPlayerInLevel()` is `@Deprecated(forRemoval = true)` in MC 1.21.1, and neither vanilla nor the Fabric gametest API ships a non-deprecated replacement. Compiling against it emits `[removal]` warnings that a future MC/Fabric bump turns into a hard break. The current way to get a connected server player is a small local gametest helper — a `MockPlayers.serverPlayerInLevel(helper)` — that reproduces the vanilla method's construction faithfully using only public, non-deprecated APIs, so no access widener is needed.

**The faithful replica — five steps (MC 1.21.1):**

1. Create a `GameProfile` with `UUID.randomUUID()` and name `"test-mock-player"`
2. Create a `CommonListenerCookie` via `CommonListenerCookie.createInitial(profile, false)`
3. Construct a **`ServerPlayer` subclass** with the helper's `ServerLevel`, its server, and the cookie's `gameProfile()` + `clientInformation()`, **overriding `isSpectator()` to return `false` and `isCreative()` to return `true`** (the vanilla method forces these; a bare `ServerPlayer` would report spectator/non-creative and silently change gameplay-gated behavior)
4. Create a real `Connection(PacketFlow.SERVERBOUND)` and back it with `new EmbeddedChannel(connection)` — the embedded channel absorbs packets so `connection.send(...)` paths work instead of NPEing
5. Call `server.getPlayerList().placeNewPlayer(connection, player, cookie)` — this **fully registers the player** in the server's player list, sets up `ServerGamePacketListenerImpl`, and adds the player to the level

```java
public static ServerPlayer serverPlayerInLevel(GameTestHelper helper) {
    GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
    CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

    ServerLevel level = helper.getLevel();
    MinecraftServer server = level.getServer();
    ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return true;
        }
    };

    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(connection);   // absorbs sent packets; no real client
    server.getPlayerList().placeNewPlayer(connection, player, cookie);
    return player;
}
```

Keep this in one gametest utility per mod (e.g. a `MockPlayers` class in the mod's gametest source set) and guard its faithfulness with a gametest — assert the returned player has a live connection, is in the player list, is in the level, `isCreative()`, and `!isSpectator()` — so a later "simplification" to a bare `new ServerPlayer(...)` fails loudly instead of silently breaking the connection-dependent tests.

**Key properties:**
- `player.connection` is **non-null** — has a real `ServerGamePacketListenerImpl`
- Player is registered in `PlayerList` — appears in `server.getPlayerList().getPlayers()`
- Player is added to the `ServerLevel` — visible to entity queries and proximity checks
- Player spawns near **world spawn** (typically 0,0,0 area), **NOT in the test structure region**
- Has Fabric attachment support — `player.getAttachedOrCreate(...)` works
- Can create command source stacks — `player.createCommandSourceStack()` works
- Packets sent via `player.connection.send(...)` go to the `EmbeddedChannel` (no real client)

**When to use:** Any test that needs a `ServerPlayer` with working:
- Fabric attachments (`MercantileAttachments.PLAYER_DATA`)
- Network interaction (mixin hooks that call `connection.send(...)`)
- Command execution (command source stack)
- Level-aware proximity checks (entity in level)
- Villager trading interaction via `Villager.startTrading(Player)`
- Follow mode, reputation events, damage sources

### `makeMockPlayer(GameType)` — lightweight client-side Player stub

```java
Player player = helper.makeMockPlayer(GameType.SURVIVAL);
```

**What it does internally (MC 1.21.1):**

1. Creates a `GameProfile` with `UUID.randomUUID()` and name `"test-mock-player"`
2. Constructs an anonymous `Player` subclass (`GameTestHelper$1`) via `new Player(level, BlockPos.ZERO, 0f, profile)`
3. Sets the `GameType` on the player abilities

**Key properties:**
- Returns `Player`, NOT `ServerPlayer` — no server-side features
- `connection` field does **not exist** on `Player` (only on `ServerPlayer`)
- Player is **NOT** added to the level or player list
- Has a working `Inventory` — `player.getInventory()` works
- Has working abilities — `player.getAbilities()` reflects the `GameType`
- Spawns at `BlockPos.ZERO` with 0 rotation
- Supports `setTradingPlayer()` on villagers

**When to use:** Tests that only need:
- An inventory holder (bulk trading, item manipulation)
- A `MerchantMenu` participant (`new MerchantMenu(0, player.getInventory(), villager)`)
- Game mode checks
- Anything that only needs the `Player` base class

## Critical: player positioning

Both factories place the player away from the test structure. **Always teleport.**

```java
// connected server player — use teleportTo or moveTo
ServerPlayer player = MockPlayers.serverPlayerInLevel(helper);

// Option A: teleport to an absolute position derived from the test structure
BlockPos abs = helper.absolutePos(new BlockPos(0, 2, 1));
player.teleportTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);

// Option B: teleport to a villager's position (proximity tests)
player.moveTo(villager.position().add(1, 0, 0));

// Option C: teleport to exact villager coords (must be within range)
player.teleportTo(villager.getX(), villager.getY(), villager.getZ());
```

`moveTo()` sets position + rotation. `teleportTo()` sets position only. Both work; use `moveTo` when rotation matters, `teleportTo` otherwise.

**For proximity-based tests** (e.g., `PROXIMITY_RANGE = 16 blocks`), the player MUST be within range of the target entity. A player at world spawn and a villager in the test structure will be too far apart.

## Connection null guards in production code

Because `MockPlayers.serverPlayerInLevel(helper)` creates a player with a real connection, and the `EmbeddedChannel` silently absorbs packets, mixin hooks that call `player.connection.send(...)` will work without crashing.

However, if production code has `if (serverPlayer.connection == null) return;` guards (defensive coding for test contexts or edge cases), be aware that:
- `MockPlayers.serverPlayerInLevel(helper)` players will pass this guard (connection is non-null)
- Directly constructed `ServerPlayer` instances (via `new ServerPlayer(...)`) will have `connection == null`

**Pattern seen in this project:** `CommandGameTest` constructs `ServerPlayer` directly (bypassing the connected replica) for lightweight command tests:

```java
var player = new ServerPlayer(server, helper.getLevel(),
        new GameProfile(UUID.randomUUID(), "TestPlayer"), ClientInformation.createDefault());
```

This player has `connection == null`, is NOT in the player list, but supports attachments and command source stacks. Use this when you don't need network functionality and want to avoid the overhead of `placeNewPlayer()`.

## Cleanup: always discard

Scope is the connected replica. `makeMockPlayer` and directly constructed `ServerPlayer` instances are never added to the level or the player list, so they need no cleanup.

The framework never removes a mock player for you — `GameTestInfo#succeed()` and the batch sweep in `StructureUtils` both filter `Player` instances out of their bounds sweep. A connected replica that is not discarded stays in the level, ticked for the rest of the run and holding a chunk ticket. An assertion that throws before a trailing `player.discard()` skips it entirely, so the guarantee is weakest exactly when the suite is unhealthy and the output is hardest to read.

Discard in a `finally`, so cleanup survives a failing assertion:

```java
ServerPlayer player = MockPlayers.serverPlayerInLevel(helper);
try {
    player.teleportTo(...);
    helper.assertTrue(..., "...");
    helper.succeed();
} finally {
    player.discard();
}
```

`helper.succeed()` belongs inside the `try`. `GameTestHelper#succeed()` sets a done flag and returns — it does not throw a control-flow sentinel — so the `finally` runs and the test still passes.

Wrap the body the player is used in, not the whole method reflexively. Setup that runs before the player exists has nothing to clean up.

`discard()` removes the entity from the level and releases its chunk ticket. The player list entry stays; clearing it means `PlayerList#remove`, which this rule does not require.

Cultivation's `MockPlayerDiscardTest` shows one way to hold this rule for a suite, as a tier-1 source scan.

### One `finally` per method

Where the method already owns a `try`/`finally` for another reason — restoring a config field, disarming a process-wide listener, stopping a manager — the discard folds into that `finally` rather than nesting a second one. The acquisition sits above the `try` so the `finally` can name it:

```java
boolean saved = CultivationConfig.get().enableBroadcastSowing;
ServerPlayer player = rakeWith(helper, Items.WHEAT_SEEDS, 9);
try {
    CultivationConfig.get().enableBroadcastSowing = false;
    helper.assertTrue(sow(helper, player, FARM) == InteractionResult.PASS,
            "with the toggle off the rake is inert");
    helper.succeed();
} finally {
    CultivationConfig.get().enableBroadcastSowing = saved;
    player.discard();
}
```

Order the `finally` body process-wide restore first, `discard()` last, when the restore is a plain field assignment that cannot throw: a suite-wide toggle left flipped poisons every later test, while a leaked player costs only ticks. When the restore itself can throw — `FollowManager.stopFollowing(villager)`, a `Files.write` of the original config file — nest it so the discard still runs:

```java
} finally {
    try {
        FollowManager.stopFollowing(villager);
    } finally {
        player.discard();
    }
}
```

State the test switches on rather than saves — a break-protection denier, a temporary listener — is armed inside the `try`, so the `finally` that disarms it is the one that already discards the player.

### When the player outlives the synchronous body

A test that schedules a deferred callback and still uses the player inside it must not wrap the scheduling code in a discarding `finally`. Those methods return as soon as they register the callback, so the `finally` would run before the callback ever runs and remove the player out from under the test. Where the discard goes instead depends on which family the method belongs to.

**One-shot — `runAfterDelay`, `runAtTickTime`, `startSequence`.** The callback fires exactly once, so it carries the `try`/`finally` itself:

```java
boolean saved = InstinctConfig.get().enableFlocking;
InstinctConfig.get().enableFlocking = false;
ServerPlayer driver = wheatHolder(helper, new BlockPos(1, 2, 3));   // teleports and hands it wheat
try {
    helper.runAfterDelay(30, () -> {
        try {
            helper.assertTrue(..., "...");
            helper.succeed();
        } finally {
            InstinctConfig.get().enableFlocking = saved;
            driver.discard();
        }
    });
} catch (Throwable t) {
    InstinctConfig.get().enableFlocking = saved;
    driver.discard();
    throw t;
}
```

The acquisition sits above the outer `try` so the `catch` can name it. That `catch`-and-rethrow covers a failure in the synchronous portion — setup between the acquisition and the scheduling call — which would otherwise leave the toggle flipped and the player leaked. Catch `Throwable`, not `RuntimeException`: an `Error` from a helper whose signature drifted must not escape with process-wide state still mutated.

**Polled — `succeedWhen`, `succeedIf`, `succeedOnTickWhen`, `onEachTick`.** The runnable is re-polled every tick and its assertion exception is swallowed until either the poll succeeds or the test times out, so a `finally` inside it would discard on the first failing poll. Discard on the success path only, as the last statements of the callback:

```java
ServerPlayer driver = wheatHolder(helper, new BlockPos(1, 2, 3));
Cow cow = helper.spawn(EntityType.COW, new BlockPos(13, 2, 3));
helper.succeedWhen(() -> {
    helper.assertTrue(flockingGoal(cow).getTemptedPlayer() == driver,
            "flocking tempts a cow 12 blocks out");
    cow.discard();
    driver.discard();
});
```

A polled test that times out leaks its player — there is no further callback to run the discard. That residual is bounded to one player per failing test and is the price of the deferral; it is not a reason to move the discard into a `finally` that would fire on every unsuccessful poll.

Config isolation is `mc-mod-testing`'s rule, and its synchronous save/restore form assumes the assertion runs before the test method returns. A deferred test is the carve-out: the flag has to stay set across the delay, so the restore moves into the callback alongside the discard.

## Fake-player guard in production code

Automation mods (block breakers, deployers, container openers) act through a synthetic `ServerPlayer` — Fabric API's `net.fabricmc.fabric.api.entity.FakePlayer` or a mod's own stand-in. Production code that grants player-facing behavior (first-visit rewards, progression, one-shot messages) must classify these as non-players, or a hopper-with-a-face farms player-only behavior.

**No single check suffices.** The reliable predicate is the union of three, each catching a different fake-player flavor:

```java
public static boolean isFakePlayer(ServerPlayer player) {
    if (player instanceof FakePlayer) {
        return true;  // Fabric's fake player has a non-null synthetic connection — the next check misses it
    }
    if (player.connection == null) {
        return true;  // fake players from other implementations (and direct new ServerPlayer(...)) have no network handler
    }
    MinecraftServer server = player.getServer();
    return server == null || !server.getPlayerList().getPlayers().contains(player);
    // the universal catch: a genuine player is always in the player list; a fake never is,
    // even when it borrows a real player's profile — identity comparison, not UUID lookup
}
```

Centralise this in one utility (see `FakePlayers.isFakePlayer` in prosperity's `loot` package) and gate **every** player-facing gameplay grant on it. Ad-hoc single checks (`player instanceof FakePlayer` alone, as in meridian's `BlockDropsMixin`) miss the other two flavors.

**Gametest coverage** — the guard needs two behavioral proofs, not just a predicate unit check:

1. A fake interaction is **inert**: no grant, no state mutation, vanilla behavior passes through untouched.
2. A **subsequent real** interaction still receives first-visit behavior — the fake must not burn the one-shot.

```java
FakePlayer fake = FakePlayer.get(helper.getLevel());   // real Fabric fake player, not a mock
fake.teleportTo(...);                                   // then drive the real event/callback path
// assert PASS-through + no state, then repeat with MockPlayers.serverPlayerInLevel(helper)
// and assert the real player still triggers first-visit behavior; the replica is discarded in
// a finally per the cleanup rule above. Do NOT discard the fake player: FakePlayer.get(...)
// returns a per-world cached instance that is never added to the level or the player list, and
// removal is sticky — discarding it hands a dead instance to the next test that asks for one.
```

See prosperity's `FakePlayerGuardGameTest` for the full pattern, driven through `UseBlockCallback.EVENT.invoker()` exactly as a live interaction fires.

**Interaction with the mock factories above:** `makeMockPlayer(...)` and directly constructed `ServerPlayer` instances trip this guard (no connection, not in the player list). Only `MockPlayers.serverPlayerInLevel(helper)` yields a connected, player-list-registered player that classifies as real. Any test exercising player-gated behavior MUST use it for the "real player" role.

## Reflection for private methods

When tests need to call private methods (e.g., `tickProximity`, `startTrading`), use reflection with proper error handling:

```java
java.lang.reflect.Method method;
try {
    method = TargetClass.class.getDeclaredMethod("methodName", ParamType.class);
    method.setAccessible(true);
} catch (NoSuchMethodException e) {
    helper.fail("TargetClass.methodName not found — signature changed? " + e);
    return;
}
try {
    method.invoke(instance, args);  // null for static methods
} catch (java.lang.reflect.InvocationTargetException e) {
    helper.fail("methodName threw: " + e.getCause());
    return;
} catch (IllegalAccessException e) {
    helper.fail("Could not invoke methodName: " + e);
    return;
}
```

Always provide a diagnostic message referencing the method name so signature changes are caught immediately.

## Quick decision table

| Need | Use |
|------|-----|
| Fabric attachments (PlayerData) | `MockPlayers.serverPlayerInLevel(helper)` |
| Network/packet interaction | `MockPlayers.serverPlayerInLevel(helper)` |
| Command source stack from a player | `MockPlayers.serverPlayerInLevel(helper)` or direct `new ServerPlayer(...)` |
| Proximity/range checks | `MockPlayers.serverPlayerInLevel(helper)` + teleport |
| Villager trading (startTrading mixin) | `MockPlayers.serverPlayerInLevel(helper)` |
| Inventory-only (bulk trade menus) | `makeMockPlayer(GameType.SURVIVAL)` |
| MerchantMenu construction | `makeMockPlayer(GameType.SURVIVAL)` |
| Lightweight, no network needed | Direct `new ServerPlayer(...)` |
| Real-player role vs a fake-player guard | `MockPlayers.serverPlayerInLevel(helper)` |
| Fake-player role vs a fake-player guard | `FakePlayer.get(level)` |
