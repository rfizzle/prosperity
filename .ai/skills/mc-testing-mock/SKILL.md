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

Mock players should be discarded at the end of the test to avoid leaking into subsequent tests:

```java
player.discard();
helper.succeed();
```

For connected replica players, `discard()` removes the entity from the level. The player list entry may linger but is harmless in gametest context since each test gets a fresh server state.

For manager cleanup (e.g., `FollowManager`), explicitly stop/clear state before discarding:

```java
FollowManager.stopFollowing(villager);
player.discard();
helper.succeed();
```

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
// assert PASS-through + no state, discard, then repeat with MockPlayers.serverPlayerInLevel(helper)
// and assert the real player still triggers first-visit behavior
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
