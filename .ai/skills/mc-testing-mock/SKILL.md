---
name: mc-testing-mock
description: Mock player helpers in Fabric Gametest — the canonical MockPlayers class (connected replica, the Connected record exposing the packet channel, spectator variant, and retire/retireLeaked teardown), plus makeMockPlayer. TRIGGER proactively when writing or editing *GameTest.java that needs a player instance, or when discussing mock players, player positioning, connection null checks, outbound-packet assertions, or mock-player teardown in gametest context. ALSO trigger when reviewing gametest code that uses ServerPlayer or Player in a test, or when writing production code that must distinguish real players from fake/automation players (FakePlayer guards).
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

1. Create a `GameProfile` with `UUID.randomUUID()` and a mod-namespaced mock name
2. Create a `CommonListenerCookie` via `CommonListenerCookie.createInitial(profile, false)`
3. Construct a **`ServerPlayer` subclass** with the helper's `ServerLevel`, its server, and the cookie's `gameProfile()` + `clientInformation()`, **overriding `isSpectator()` to return the requested spectator flag (`false` for the plain replica) and `isCreative()` to return `true`** (the vanilla method forces these; a bare `ServerPlayer` would report spectator/non-creative and silently change gameplay-gated behavior)
4. Create a real `Connection(PacketFlow.SERVERBOUND)` and back it with `new EmbeddedChannel(connection)` — the embedded channel absorbs packets so `connection.send(...)` paths work instead of NPEing. **Keep the channel**: it is the only handle on what the server sent this player
5. Call `server.getPlayerList().placeNewPlayer(connection, player, cookie)` — this **fully registers the player** in the server's player list, sets up `ServerGamePacketListenerImpl`, and adds the player to the level

#### The canonical `MockPlayers`

One class per mod, in the gametest source set (`com.rfizzle.<mod>.gametest.util.MockPlayers`),
with this shape. The private `connectedInLevel` does the construction once; the public factories
are the three ways tests need it, and `retire`/`retireLeaked` are the teardown, explained under
"Cleanup" below.

```java
public final class MockPlayers {

    /**
     * Profile name for this mod's mocks. Namespaced, because a sweep matches on it and
     * several mods can share a gametest level — an unnamespaced "test-mock-player" lets
     * one mod's sweep retire another mod's live player.
     */
    private static final String MOCK_NAME = MyMod.MOD_ID + "-test-mock-player";

    /** A connected player plus the embedded channel its outbound packets land in. */
    public record Connected(ServerPlayer player, EmbeddedChannel channel) {
    }

    private MockPlayers() {
    }

    /** The connected replica; spawns near world spawn — teleport as needed. */
    public static ServerPlayer serverPlayerInLevel(GameTestHelper helper) {
        return connectedServerPlayerInLevel(helper).player();
    }

    /** Same replica, with the packet-absorbing channel exposed for outbound assertions. */
    public static Connected connectedServerPlayerInLevel(GameTestHelper helper) {
        return connectedInLevel(helper, false);
    }

    /** A connected replica that reports as a spectator. */
    public static Connected spectatorServerPlayerInLevel(GameTestHelper helper) {
        return connectedInLevel(helper, true);
    }

    /** Fully retires a connected mock: awake, out of the player list, entity discarded. */
    public static void retire(ServerPlayer player) {
        if (player.isRemoved()) {
            return;                       // PlayerList#remove is not idempotent
        }
        if (player.isSleeping()) {
            player.stopSleepInBed(true, true);
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getPlayerList().remove(player);
        }
        player.discard();
    }

    /** Retires any mock this mod leaked into the helper's level. See the batch caveat below. */
    public static void retireLeaked(GameTestHelper helper) {
        for (ServerPlayer player : List.copyOf(helper.getLevel().players())) {
            if (MOCK_NAME.equals(player.getGameProfile().getName())) {
                retire(player);
            }
        }
    }

    private static Connected connectedInLevel(GameTestHelper helper, boolean spectator) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), MOCK_NAME);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return spectator;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return new Connected(player, channel);
    }
}
```

Guard its faithfulness with a gametest — assert the returned player has a live connection, is
in the player list, is in the level, `isCreative()`, and `!isSpectator()` — so a later
"simplification" to a bare `new ServerPlayer(...)` fails loudly instead of silently breaking
the connection-dependent tests.

**The spectator variant is a spectator through `isSpectator()` only.** It is still placed with
the server's default `GameType` and still overrides `isCreative()` to `true`, so
`getGameModeForPlayer()` reports whatever the server default is. Production code that gates on
`isSpectator()` — the common case — is exercised correctly. Code that gates on
`GameType.SPECTATOR` or on `!isCreative()` is *not*, and a test using this mock will pass while
running the wrong branch. Check which predicate the code under test uses before reaching for it.

**Returning the channel is the point of the record.** Without it, a test that needs to assert on
an outbound packet has to dig the channel back out of the player by reflection — through
`ServerCommonPacketListenerImpl.connection` and then `Connection.channel`, two private fields
whose names a mapping change can break, in a helper that has to `helper.fail(...)` on
`NoSuchFieldException`. `Connected.channel()` hands back the same object the factory just
created. Reflection-based channel extraction is superseded; a mod carrying one should delete it
in favour of `connectedServerPlayerInLevel`.

`serverPlayerInLevel(helper)` stays as the convenience overload because most tests never touch
the channel. Reach for `connectedServerPlayerInLevel` only when asserting on what the server
sent, and `spectatorServerPlayerInLevel` when the test needs a spectator left out of a feature's
accounting or its broadcasts.

Reading an outbound packet off the channel:

```java
MockPlayers.Connected connected = MockPlayers.connectedServerPlayerInLevel(helper);
try {
    // ... drive the behavior that sends to this player
    ClientboundSetActionBarTextPacket sent = connected.channel().readOutbound();
    helper.assertTrue(sent != null, "the server sent an action-bar packet");
} finally {
    MockPlayers.retire(connected.player());
}
```

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

## Cleanup: always retire

Scope is the connected replica. `makeMockPlayer` and directly constructed `ServerPlayer` instances are never added to the level or the player list, so they need no cleanup.

The framework never removes a mock player for you — `GameTestInfo#succeed()` and the batch sweep in `StructureUtils` both filter `Player` instances out of their bounds sweep. A connected replica that is not retired stays in the level, ticked for the rest of the run and holding a chunk ticket, *and* stays in the player list. Two costs ride along with that tick: its advancement listeners stay registered, so every criterion the mod fires keeps evaluating against a player no test is watching, and its `EmbeddedChannel` accumulates every broadcast the server sends it — a channel a test never reads is a channel nothing drains. An assertion that throws before a trailing cleanup call skips it entirely, so the guarantee is weakest exactly when the suite is unhealthy and the output is hardest to read.

Retire in a `finally`, so cleanup survives a failing assertion:

```java
ServerPlayer player = MockPlayers.serverPlayerInLevel(helper);
try {
    player.teleportTo(...);
    helper.assertTrue(..., "...");
    helper.succeed();
} finally {
    MockPlayers.retire(player);
}
```

`helper.succeed()` belongs inside the `try`. `GameTestHelper#succeed()` sets a done flag and returns — it does not throw a control-flow sentinel — so the `finally` runs and the test still passes.

Wrap the body the player is used in, not the whole method reflexively. Setup that runs before the player exists has nothing to clean up.

### Retire, don't just discard

`discard()` alone is an incomplete reclaim. It removes the entity from the level and releases
its chunk ticket, but the player stays in `PlayerList` — in `players`, in `playersByUUID`, in
the stats and advancement maps, and in every broadcast the server makes to "online players".
A suite that only discards accumulates ghost entries across the shared test server, and any
later test whose assertions depend on who is online reads a player count inflated by every
mock that ran before it.

The full reclaim is `PlayerList#remove` followed by `discard()` — `retire(player)` in the
canonical class above. Three details in it are load-bearing:

**It must be idempotent.** `PlayerList#remove` has no `isRemoved()` guard and calls
`save(player)` unconditionally, so a second call rewrites the player `.dat`, the stats JSON, and
the advancements JSON. The two idioms in this skill collide in any method that uses both — a
`finally` that retires plus a success-path retire inside a polled callback — so the guard is not
hypothetical. `Entity.setRemoved` *is* idempotent, which is why the trailing `discard()` needs no
guard of its own.

**Wake the player before removing it.** `PlayerList#remove` does nothing about sleep, and a
removed sleeping player leaves `SleepStatus` counting someone who is gone —
`ServerLevel.tick` then evaluates `areEnoughSleeping(...)` against stale numbers for the rest of
the run. Pass `true` for *both* arguments: the second is what triggers
`ServerLevel.updateSleepingPlayerList()`, so `stopSleepInBed(true, false)` clears the player's
own flag and skips the refresh that makes it correct. Vanilla's own `stopSleeping()` passes
`(true, true)`. Wake while the player is still in `level.players()`, or
`updateSleepingPlayerList()` returns early on an empty list and the recompute never happens.

**The disk-write objection does not hold.** `discard()` leaves the player in
`PlayerList.players`, so `MinecraftServer.stopServer` → `PlayerList.saveAll()` saves every leaked
mock at shutdown anyway — and since each mock carries a `UUID.randomUUID()`, nothing dedups on
either side. It is one save per mock either way; `remove()` moves the write into the run rather
than adding one. (rfizzle/cultivation#100 proposes this change suite-wide; the decision is not
yet recorded there, and its measurements cover only the discard-only side.)

For suites whose assertions depend on the player count, sweep with `retireLeaked(helper)` rather
than trusting that every earlier test cleaned up — a test that timed out mid-poll did not.

**`retireLeaked` needs a batch to itself.** It reads `helper.getLevel().players()`, which is
level-wide, and `GameTestRunner.runBatch` spawns the structures for an entire batch and then
adds every test in it to the ticker at once — same-batch tests run *concurrently in one level*.
Called from a test that shares its batch, the sweep retires a sibling test's live player out from
under it. Give any test that sweeps a `batch` value of its own. Copy the player list before
iterating, since `retire` mutates it, and note the sweep is single-dimension: a mock left in
another level survives it.

A tier-1 source scan can hold this rule for a whole suite — cultivation's
`MockPlayerDiscardTest` shows the shape. Read it for the scanning technique only: it still
matches the literal token `".discard()"` and requires it inside a `finally`, so a suite that
adopts `MockPlayers.retire(player)` *fails* it. Adopting this rule means moving the matched
token to `MockPlayers.retire(<name>)` — matched on the argument rather than the receiver — and
replacing the javadoc paragraph that records the superseded "the player-list entry is
deliberately left in place" rationale. Both are in rfizzle/cultivation#100's acceptance
criteria.

### One `finally` per method

Where the method already owns a `try`/`finally` for another reason — restoring a config field, disarming a process-wide listener, stopping a manager — the retire folds into that `finally` rather than nesting a second one. The acquisition sits above the `try` so the `finally` can name it:

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
    MockPlayers.retire(player);
}
```

Order the `finally` body process-wide restore first, `retire(...)` last, when the restore is a plain field assignment that cannot throw: a suite-wide toggle left flipped poisons every later test, while a leaked player costs only ticks and a stale player-list entry. When the restore itself can throw — `FollowManager.stopFollowing(villager)`, a `Files.write` of the original config file — nest it so the retire still runs:

```java
} finally {
    try {
        FollowManager.stopFollowing(villager);
    } finally {
        MockPlayers.retire(player);
    }
}
```

State the test switches on rather than saves — a break-protection denier, a temporary listener — is armed inside the `try`, so the `finally` that disarms it is the one that already retires the player.

### When the player outlives the synchronous body

A test that schedules a deferred callback and still uses the player inside it must not wrap the scheduling code in a retiring `finally`. Those methods return as soon as they register the callback, so the `finally` would run before the callback ever runs and remove the player out from under the test. Where the retire goes instead depends on which family the method belongs to.

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
            MockPlayers.retire(driver);
        }
    });
} catch (Throwable t) {
    InstinctConfig.get().enableFlocking = saved;
    MockPlayers.retire(driver);
    throw t;
}
```

The acquisition sits above the outer `try` so the `catch` can name it. That `catch`-and-rethrow covers a failure in the synchronous portion — setup between the acquisition and the scheduling call — which would otherwise leave the toggle flipped and the player leaked. Catch `Throwable`, not `RuntimeException`: an `Error` from a helper whose signature drifted must not escape with process-wide state still mutated.

**Polled — `succeedWhen`, `succeedIf`, `succeedOnTickWhen`, `onEachTick`.** The runnable is re-polled every tick and its assertion exception is swallowed until either the poll succeeds or the test times out, so a `finally` inside it would retire the player on the first failing poll. Retire on the success path only, as the last statements of the callback:

```java
ServerPlayer driver = wheatHolder(helper, new BlockPos(1, 2, 3));
Cow cow = helper.spawn(EntityType.COW, new BlockPos(13, 2, 3));
helper.succeedWhen(() -> {
    helper.assertTrue(flockingGoal(cow).getTemptedPlayer() == driver,
            "flocking tempts a cow 12 blocks out");
    cow.discard();
    MockPlayers.retire(driver);
});
```

A polled test that times out leaks its player — there is no further callback to run the retire. That residual is bounded to one player per failing test and is the price of the deferral; it is not a reason to move the retire into a `finally` that would fire on every unsuccessful poll. It is, however, exactly what `retireLeaked(helper)` is for: a later test whose assertions depend on the player count sweeps first rather than inheriting the timed-out test's ghost.

Config isolation is `mc-mod-testing`'s rule, and its synchronous save/restore form assumes the assertion runs before the test method returns. A deferred test is the carve-out: the flag has to stay set across the delay, so the restore moves into the callback alongside the retire.

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
// and assert the real player still triggers first-visit behavior; the replica is retired in
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
| Asserting on an outbound packet | `MockPlayers.connectedServerPlayerInLevel(helper)`, then `channel().readOutbound()` |
| A spectator left out of a feature's accounting | `MockPlayers.spectatorServerPlayerInLevel(helper)` |
| Command source stack from a player | `MockPlayers.serverPlayerInLevel(helper)` or direct `new ServerPlayer(...)` |
| Proximity/range checks | `MockPlayers.serverPlayerInLevel(helper)` + teleport |
| Villager trading (startTrading mixin) | `MockPlayers.serverPlayerInLevel(helper)` |
| Inventory-only (bulk trade menus) | `makeMockPlayer(GameType.SURVIVAL)` |
| MerchantMenu construction | `makeMockPlayer(GameType.SURVIVAL)` |
| Lightweight, no network needed | Direct `new ServerPlayer(...)` |
| Real-player role vs a fake-player guard | `MockPlayers.serverPlayerInLevel(helper)` |
| Fake-player role vs a fake-player guard | `FakePlayer.get(level)` |
