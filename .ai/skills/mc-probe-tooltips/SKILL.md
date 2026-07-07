---
name: mc-probe-tooltips
description: Integrate Concord mods with probe-tooltip viewers (Jade, WTHIT) on Fabric — one shared server-side NBT writer plus a pure formatter per surface, thin per-viewer adapters, and the asymmetric discovery wiring. TRIGGER when creating or editing classes under a compat/jade or compat/wthit package; implementing IWailaPlugin, IServerDataProvider, IDataProvider, or a block/entity component provider; editing waila_plugins.json or a "jade" entrypoint in fabric.mod.json; declaring Jade/WTHIT gradle dependencies; or when the user mentions Jade, WTHIT, WAILA, probe tooltips, or the "looking at" overlay.
---

The user is surfacing block or entity data in the probe overlay — the tooltip
Jade and WTHIT draw over whatever the crosshair points at. Two things decide
success: keeping **one viewer-agnostic core per tooltip surface** so the two
adapters cannot drift apart, and picking the right **provider shape** so the
data actually reaches clients on a dedicated server. This is the probe-viewer
sibling of mc-compat — same shared-data-layer doctrine, different viewer
family. (Item hover tooltips are a different surface — see mc-tooltips.)

## Architecture: shared core, thin adapters

Each tooltip surface (one block-entity type, one entity type) gets a single
core class in a viewer-agnostic package (`compat/common`) with two halves and
**zero Jade/WTHIT imports**:

- a **server-side writer** — packs the state into the probe's `CompoundTag`
  under mod-namespaced keys, gated by config, presence-flagged;
- a **pure formatter** — `CompoundTag → List<Component>` (or `List<String>`),
  registry-safe, unit-testable at Tier 1 with no viewer jars on the classpath.

```java
// compat/common — no Jade or WTHIT imports anywhere in this package.
public final class CropBoostTooltip {

    static final String KEY_PRESENT = "mymod:present";
    static final String KEY_TIER    = "mymod:tier";
    static final String KEY_BOOST   = "mymod:boost";

    private CropBoostTooltip() {}

    /** Server side. Writes nothing (no presence flag → empty tooltip) when gated off. */
    public static void writeServerData(CompoundTag tag, ServerLevel level, Player player,
            BlockPos pos, BlockEntity be) {
        if (!MyModConfig.get().enableCropBoost) return;            // gate ONCE, here
        if (!(be instanceof CropBoosterBlockEntity booster)) return;
        tag.putBoolean(KEY_PRESENT, true);
        tag.putString(KEY_TIER, booster.tier().name());
        tag.putDouble(KEY_BOOST, booster.boostFactor());
    }

    /** Client side. Pure tag → lines; the tag is data, not trusted state. */
    public static List<Component> buildLines(CompoundTag tag) {
        List<Component> lines = new ArrayList<>();
        if (!tag.getBoolean(KEY_PRESENT)) return lines;            // inert on other targets
        String tier = tag.getString(KEY_TIER);
        lines.add(Component.translatableWithFallback(
                "mymod.probe.tier." + tier.toLowerCase(Locale.ROOT), tier));
        lines.add(Component.translatable("mymod.probe.boost",
                String.format(Locale.ROOT, "%.1f", tag.getDouble(KEY_BOOST))));
        return lines;
    }
}
```

Namespace every key (`mymod:...`): both viewers merge all providers' data into
one shared tag per target, so bare keys can collide with another mod's. The
presence flag lets a component provider registered on a broad class (e.g.
`BaseEntityBlock`) stay inert on every target the writer skipped. Because both
adapters delegate to the same writer and formatter, the tooltip is **identical
across viewers by construction** — the only per-viewer code unpacks the
accessor.

## Decision: server data provider vs tooltip-only

| The data is… | Shape |
|---|---|
| Per-player / per-look state, server-only attachments, config-derived, or computed on request | **Server data provider** (Jade `IServerDataProvider`, WTHIT `IDataProvider`) — the probe's request/response packet carries the tag to the client; the component provider reads it back. |
| Already on the client — BE fields synced via `getUpdateTag()` / chunk packets | **Tooltip-only component provider** — read the client-side BE directly; no data provider, no packet. |

When in doubt, use the server-data shape: singleplayer's integrated server
masks a missing sync, so the bug only shows on a dedicated server. Per-player
lines ("you have looted this", "your cooldown") can **only** use it — the
server-data request is the one path that knows which player is looking.

## Jade adapter (~30 lines)

One enum implements both halves and only delegates:

```java
// compat/jade — adapter only.
public enum CropBoostJadeProvider
        implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)) return;
        CropBoostTooltip.writeServerData(tag, level, accessor.getPlayer(),
                accessor.getPosition(), accessor.getBlockEntity());
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        for (Component line : CropBoostTooltip.buildLines(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "crop_boost");
    }
}
```

```java
@WailaPlugin("mymod")
public final class MyModJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CropBoostJadeProvider.INSTANCE,
                CropBoosterBlockEntity.class);       // data keys on the BE class
    }
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CropBoostJadeProvider.INSTANCE,
                CropBoosterBlock.class);             // component keys on the Block class
    }
}
```

Jade walks the class hierarchy, so registering on a vanilla superclass (e.g.
`RandomizableContainerBlockEntity`) covers chests, barrels, shulkers,
dispensers at once — the writer's own type check does the filtering.

## WTHIT adapter (~30 lines + two 10-line plugins)

```java
// compat/wthit — adapter only.
public enum CropBoostWthitProvider
        implements IDataProvider<CropBoosterBlockEntity>, IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendData(IDataWriter data, IServerAccessor<CropBoosterBlockEntity> accessor,
            IPluginConfig config) {
        CropBoosterBlockEntity be = accessor.getTarget();
        CropBoostTooltip.writeServerData(data.raw(), accessor.getLevel(),
                accessor.getPlayer(), be.getBlockPos(), be);
    }

    @Override
    public void appendBody(ITooltip tooltip, IBlockAccessor accessor, IPluginConfig config) {
        for (Component line : CropBoostTooltip.buildLines(accessor.getData().raw())) {
            tooltip.addLine(line);
        }
    }
}
```

WTHIT splits registration by side — a common plugin registers data providers,
a client plugin registers body components:

```java
public final class MyModWthitCommonPlugin implements IWailaCommonPlugin {
    @Override public void register(ICommonRegistrar registrar) {
        registrar.blockData(CropBoostWthitProvider.INSTANCE, CropBoosterBlockEntity.class);
    }
}
public final class MyModWthitClientPlugin implements IWailaClientPlugin {
    @Override public void register(IClientRegistrar registrar) {
        registrar.body(CropBoostWthitProvider.INSTANCE, CropBoosterBlock.class);
    }
}
```

### Entity targets

Same shape, entity flavor — the writer takes the entity instead of a BE:

| | Jade | WTHIT |
|---|---|---|
| Provider interfaces | `IServerDataProvider<EntityAccessor>` + `IEntityComponentProvider` | `IDataProvider<Mob>` + `IEntityComponentProvider` |
| Server target | `accessor.getEntity()` | `accessor.getTarget()` |
| Registration | `registerEntityDataProvider` / `registerEntityComponent`, both on the entity class | `ICommonRegistrar.entityData` / `IClientRegistrar.body`, both on the entity class |

## Discovery is asymmetric

| Viewer | Discovery | Plugin interface(s) |
|---|---|---|
| Jade | `"jade"` entrypoint in `fabric.mod.json` | `IWailaPlugin` (`register` = common, `registerClient` = client) |
| WTHIT | `waila_plugins.json` at the resource root — **not** a `fabric.mod.json` entrypoint | `IWailaCommonPlugin` + `IWailaClientPlugin` |

Jade: `"entrypoints": { "jade": ["com.example.mymod.compat.jade.MyModJadePlugin"] }`
in `fabric.mod.json`. `@WailaPlugin("mymod")` is Jade's annotation-based
discovery on other loaders; on Fabric the entrypoint is load-bearing — keep
the annotation for parity.

```json
// src/main/resources/waila_plugins.json
{
    "mymod:wthit": {
        "entrypoints": {
            "common": "com.example.mymod.compat.wthit.MyModWthitCommonPlugin",
            "client": "com.example.mymod.compat.wthit.MyModWthitClientPlugin"
        },
        "side": "*",
        "required": { "wthit": "*" }
    }
}
```

The `"required": {"wthit": "*"}` block is why this needs no mod-loaded guard:
WTHIT itself reads the manifest, so when WTHIT is absent the `compat/wthit`
classes never load. (WTHIT also honors a legacy `wthit:plugins` custom key in
`fabric.mod.json` with the single-plugin `IRegistrar` API; use the manifest's
sided split instead.) List both viewers under `suggests`, never `depends`.

## Dependencies (gradle)

```gradle
// Jade — no api split is published; compile-only links the full jar but only
// the snownee.jade.api surface is referenced. Full jar in the dev client.
modCompileOnly "maven.modrinth:jade:${jade_version}"
modLocalRuntime "maven.modrinth:jade:${jade_version}"

// WTHIT — compile against the API split only; discovered via waila_plugins.json
// at runtime. No modLocalRuntime: Jade is the probe in the dev client, and two
// probe overlays loaded together double-render the same box. To eyeball the
// WTHIT side in-game, swap which probe is in the dev runtime — don't run both.
modCompileOnly "mcp.mobius.waila:wthit-api:${wthit_version}"
```

## Testing

- **Tier 1 (pure JUnit), no viewer jars:** the formatter takes a `CompoundTag`
  and returns lines, so tests hand-build tags and pin exact strings — null tag,
  missing presence flag, each status branch, numeric formatting edges, unknown
  values falling back to raw text.
- **Gametests for the wiring:** place the block (or spawn the mob) plus a mock
  server player, call `writeServerData` directly, and assert through the
  formatter. The core is callable headlessly precisely because it takes
  resolved game objects (`ServerLevel`, `Player`, `BlockPos`, `BlockEntity`)
  rather than a probe accessor; the adapters are too thin to hide bugs.

## Version notes

- **Fabric 1.21.1:** Jade 15.x (`snownee.jade.api`), WTHIT 12.x
  (`mcp.mobius.waila.api`).
- WTHIT 12 registration is sided: `IWailaCommonPlugin`/`ICommonRegistrar` for
  `blockData`/`entityData`, `IWailaClientPlugin`/`IClientRegistrar` for `body`.
  WTHIT matches against the target's class hierarchy; body providers accept
  block or block-entity classes — mirror whatever the data registration keys on.
- Jade block data providers key on the **BlockEntity** class; block components
  on the **Block** class. Entity providers key on the entity class both halves.

## Guardrails

- Never import Jade or WTHIT types in the shared core; only `compat/jade` and
  `compat/wthit` touch viewer APIs, so a missing viewer never class-loads them.
- Namespace every NBT key (`mymod:...`) and always write a presence flag; the
  formatter returns empty without it, keeping broad registrations inert on
  unrelated targets.
- Gate features once, in the shared writer — never in adapters or the
  formatter. A gated-off write leaves no presence flag, which empties the
  tooltip in every viewer at once.
- Keep both adapters pure delegation. A line formatted inside one adapter is
  drift the other viewer will never show.
- Never put per-player data in a tooltip-only provider — only the server-data
  request knows which player is looking. When unsure, use the server-data
  shape; singleplayer masks missing sync.
- Registry and lang lookups in the formatter must fall back
  (`ResourceLocation.tryParse`, air-item checks, `translatableWithFallback`) —
  the tag crossed the network and is data, not trusted state.
- Discover WTHIT through `waila_plugins.json`, not a `fabric.mod.json`
  entrypoint; Jade through the `jade` entrypoint. `suggests`, never `depends`.
- Don't load both probes in one dev runtime — Jade in `modLocalRuntime`, WTHIT
  compile-only.
