---
name: mc-networking
description: Implement client-server networking for Fabric Minecraft mods (custom packets, payload types, stream codecs, sync patterns). TRIGGER when creating or editing *Payload.java, *Packet.java, *Networking.java, PayloadTypeRegistry, StreamCodec, ServerPlayNetworking, or ClientPlayNetworking.
---

The user is implementing networking code in a Fabric mod. Apply this guidance whenever custom payloads, stream codecs, or network handlers are being written or modified.

## Custom payload as a Java record

Define payloads as records implementing `CustomPacketPayload`. Each payload needs a `Type` and a `StreamCodec`:

```java
public record EnchantmentInfoPayload(
        Map<ResourceKey<Enchantment>, EnchantmentInfo> info
) implements CustomPacketPayload {

    public static final Type<EnchantmentInfoPayload> TYPE =
            new Type<>(MyMod.id("enchantment_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnchantmentInfoPayload> CODEC =
            StreamCodec.of(EnchantmentInfoPayload::encode, EnchantmentInfoPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, EnchantmentInfoPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.info.size());
        for (var entry : payload.info.entrySet()) {
            ResourceKey.streamCodec(Registries.ENCHANTMENT).encode(buf, entry.getKey());
            EnchantmentInfo.STREAM_CODEC.encode(buf, entry.getValue());
        }
    }

    private static EnchantmentInfoPayload decode(RegistryFriendlyByteBuf buf) {
        int size = ByteBufCodecs.VAR_INT.decode(buf);
        Map<ResourceKey<Enchantment>, EnchantmentInfo> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(
                    ResourceKey.streamCodec(Registries.ENCHANTMENT).decode(buf),
                    EnchantmentInfo.STREAM_CODEC.decode(buf));
        }
        return new EnchantmentInfoPayload(map);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

## StreamCodec composition

Common codec building blocks in `ByteBufCodecs`:

```java
// Primitives
ByteBufCodecs.VAR_INT          // int (variable-length)
ByteBufCodecs.BOOL             // boolean
ByteBufCodecs.STRING_UTF8      // String
ByteBufCodecs.FLOAT            // float
ByteBufCodecs.DOUBLE           // double

// Collections
ByteBufCodecs.collection(ArrayList::new, elementCodec)  // List<T>
ByteBufCodecs.map(HashMap::new, keyCodec, valueCodec)   // Map<K,V>

// Optional
ByteBufCodecs.optional(innerCodec)   // Optional<T>

// Minecraft types
BlockPos.STREAM_CODEC          // BlockPos
ItemStack.STREAM_CODEC         // ItemStack (registry-friendly)
ResourceLocation.STREAM_CODEC  // ResourceLocation
ResourceKey.streamCodec(registryKey)  // ResourceKey<T>

// Composite (for record-like payloads)
StreamCodec.composite(
        ByteBufCodecs.VAR_INT, MyPayload::x,
        ByteBufCodecs.VAR_INT, MyPayload::y,
        ByteBufCodecs.STRING_UTF8, MyPayload::name,
        MyPayload::new
)
```

For simple payloads, prefer `StreamCodec.composite` over manual encode/decode:

```java
public record BlockHighlightPayload(BlockPos pos, int color) implements CustomPacketPayload {
    public static final Type<BlockHighlightPayload> TYPE =
            new Type<>(MyMod.id("block_highlight"));

    public static final StreamCodec<ByteBuf, BlockHighlightPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BlockHighlightPayload::pos,
                    ByteBufCodecs.VAR_INT, BlockHighlightPayload::color,
                    BlockHighlightPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

## Payload type registration

Register both the payload type (with its codec) and the handler. Registration must happen during mod initialization.

### Server-to-client (S2C)
```java
public class MyNetworking {
    public static void registerPayloads() {
        // Register the payload type + codec
        PayloadTypeRegistry.playS2C().register(
                EnchantmentInfoPayload.TYPE,
                EnchantmentInfoPayload.CODEC);
    }
}
```

### Client-to-server (C2S)
```java
PayloadTypeRegistry.playC2S().register(
        MyRequestPayload.TYPE,
        MyRequestPayload.CODEC);
```

## Handler registration

### Server-side handler (for C2S packets)
```java
// In onInitialize():
ServerPlayNetworking.registerGlobalReceiver(
        MyRequestPayload.TYPE,
        (payload, context) -> {
            ServerPlayer player = context.player();
            // Already on the server thread if using context.player().server.execute(...)
            context.player().server.execute(() -> {
                // Safe to mutate world state here
            });
        });
```

### Client-side handler (for S2C packets)
```java
// In ClientModInitializer.onInitializeClient():
ClientPlayNetworking.registerGlobalReceiver(
        EnchantmentInfoPayload.TYPE,
        (payload, context) -> {
            // Run on the render thread via the client
            context.client().execute(() -> {
                EnchantmentInfoRegistry.setClientData(payload.info());
            });
        });
```

## Sync patterns

### Sync-on-join
Send full server state to each player when they connect:

```java
// In onInitialize():
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    ServerPlayer player = handler.getPlayer();
    ServerPlayNetworking.send(player, buildFullSyncPayload());
});
```

### Reload-resync
Re-send state when datapacks or config are reloaded:

```java
ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
    if (!success) return;
    rebuildServerState();
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        ServerPlayNetworking.send(player, buildFullSyncPayload());
    }
});
```

### Server-started rebuild
Rebuild data after the server finishes loading:

```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    rebuildServerState();
});
```

### Combined lifecycle pattern
Most mods need all three. Wire them in sequence:

```java
public static void registerLifecycleHandlers() {
    ServerLifecycleEvents.SERVER_STARTED.register(server -> rebuildAndSync(server));
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, rm, ok) -> {
        if (ok) rebuildAndSync(server);
    });
    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
        ServerPlayNetworking.send(handler.getPlayer(), buildFullSyncPayload());
    });
}

private static void rebuildAndSync(MinecraftServer server) {
    rebuildServerState();
    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
        ServerPlayNetworking.send(p, buildFullSyncPayload());
    }
}
```

## Sending packets

### Server to specific player
```java
ServerPlayNetworking.send(player, new MyPayload(data));
```

### Client to server
```java
ClientPlayNetworking.send(new MyRequestPayload(data));
```

## Payload size limits

Vanilla limits custom payload size to ~1 MB. For larger data:
- Split into multiple payloads (chunked sync)
- Send a "begin" packet, N "chunk" packets, and an "end" packet
- The receiver assembles chunks before processing

For most mods, the 1 MB limit is never hit. Don't chunk unless you're syncing large data sets (thousands of entries).

## C2S validation — never trust the client

Server-side network handlers MUST replicate ALL validation from the corresponding client-side mixin or screen code. A malicious client skips all client-side checks. For every C2S handler, verify:

- **Permission/state checks** — player has required items, is in the right state, entity is valid and within range
- **Entity ID validation** — the entity exists, is the correct type, and is close enough to interact with
- **Config gating** — if the feature has an `enableX` toggle, check it server-side too

```java
ServerPlayNetworking.registerGlobalReceiver(
        FollowToggleC2SPayload.TYPE,
        (payload, context) -> {
            ServerPlayer player = context.player();
            context.player().server.execute(() -> {
                if (!MercantileConfig.get().enableFollowMode) return;
                Entity entity = player.serverLevel().getEntity(payload.villagerEntityId());
                if (!(entity instanceof Villager villager)) return;
                if (player.distanceToSqr(villager) > 36.0) return; // 6 block range
                if (villager.isBaby()) return;
                // ... validated — proceed with toggle
            });
        });
```

## C2S rate limiting

C2S packets that trigger expensive server work (POI queries, trade generation, entity spawning) need per-player cooldowns. Without them, a malicious client can spam packets and DoS the server.

```java
private static final Map<UUID, Long> LAST_CYCLE = new ConcurrentHashMap<>();
private static final long COOLDOWN_MS = 500;

// In handler:
long now = System.currentTimeMillis();
Long last = LAST_CYCLE.get(player.getUUID());
if (last != null && now - last < COOLDOWN_MS) return;
LAST_CYCLE.put(player.getUUID(), now);
```

## Client state cleanup on disconnect

Register `ClientPlayConnectionEvents.DISCONNECT` to clear all client-side data caches. Without this, maps grow unboundedly across reconnects to different servers, and stale data from server A can briefly flash when joining server B.

```java
// In ClientModInitializer.onInitializeClient():
ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
    ClientMercantileData.clear();
});
```

## S2C payload size guards

Cap collection sizes when decoding S2C payloads. A malicious server can OOM the client with unbounded lists. Use `readUtf(maxLen)` for known-short string fields.

```java
private static MyPayload decode(RegistryFriendlyByteBuf buf) {
    int size = ByteBufCodecs.VAR_INT.decode(buf);
    if (size > 1024) throw new DecoderException("Collection too large: " + size);
    // ...
}
```

## Guardrails

- **Never** mutate world state directly in a network handler callback. Network handlers run on netty threads. Use `server.execute()` or `client.execute()` to schedule work on the main thread.
- **Always** register both the payload type (via `PayloadTypeRegistry`) AND the handler (via `*PlayNetworking.registerGlobalReceiver`). Missing either causes silent drops or crashes.
- **Always** register S2C payload types in the common initializer (both sides need to know the type), but register the client handler in `ClientModInitializer` only.
- **Never** reference client-only classes (screens, renderers) in a payload class that lives in the common source set. The payload record and codec go in `main/`; the client handler goes in `client/`.
- **Always** use `RegistryFriendlyByteBuf` (not raw `ByteBuf`) when the payload contains registry-backed objects like `ItemStack`, `Holder`, or `ResourceKey`.
- **Never** trust C2S packet contents without server-side validation. Replicate all permission, state, range, and config checks from the client-side code in the server handler.
- **Always** add per-player cooldowns on C2S packets that trigger expensive server operations (POI queries, trade generation, world reads).
- **Always** register `ClientPlayConnectionEvents.DISCONNECT` to clear client-side data caches. Maps that survive reconnect grow unboundedly and leak stale data.
- **Always** cap collection sizes and string lengths when decoding S2C payloads. Use `readUtf(maxLen)` for known-short fields and reject oversized collections early.

## Version notes

- **1.20.5+:** The Fabric Networking API moved to `PayloadTypeRegistry` + `StreamCodec`. Older tuple-based registration is removed.
- **1.21+:** `RegistryFriendlyByteBuf` is required for any codec touching registry objects. Plain `ByteBuf` codecs work only for primitives and non-registry types.
