---
name: mc-compat
description: Integrate Concord mods with recipe-viewer mods (EMI, REI, JEI) on Fabric — shared data layer, thin per-viewer adapters, and keeping the viewers' lists fresh when mod data is synced or reloaded at runtime. TRIGGER when creating or editing any class under a compat/emi, compat/rei, or compat/jei package; implementing EmiPlugin, REIClientPlugin, or JEI's IModPlugin; declaring EMI/REI/JEI dependencies in gradle; or when the user mentions recipe viewers / loot index / "show my recipes in JEI/EMI/REI".
---

The user is wiring a mod's data into one or more recipe viewers (EMI, REI, JEI).
Two things decide whether this goes well: keeping the integration *thin and
shared* so three plugins don't fork into three copies of the same logic, and
solving the **reactive-refresh problem** — making a viewer notice when the mod's
data is built or synced at runtime rather than at viewer-load time. The second
half is the hard part, and each viewer gives you a very different amount of help
with it.

## Architecture: shared data layer, thin per-viewer adapters

Build the displayable data **once**, in a viewer-agnostic source, and let each
plugin be a thin adapter over it. A plugin should never class-load another
viewer's types, and cross-viewer logic (labels, markers, sorting, filtering)
lives in one place so it can't drift between viewers.

```java
// Viewer-agnostic — no EMI/REI/JEI imports anywhere in this package.
public final class LootIndexDataSource {
    public static List<IndexEntry> snapshot() { ... }   // plain data, immutable copy
    public static Component label(IndexEntry e) { ... }  // shared formatting helper
}
```

Each plugin reads `snapshot()` and maps it to that viewer's display type:

```java
// compat/jei — adapter only
for (IndexEntry e : LootIndexDataSource.snapshot()) {
    registration.addRecipes(YOUR_TYPE, List.of(toJeiDisplay(e)));
}
```

### Dependencies (gradle)

Declare each viewer `modCompileOnly` against its **API artifact**, plus
`modLocalRuntime` for the full mod so it loads in the dev client. List viewers
under `suggests` in `fabric.mod.json`, never `depends` — a missing viewer must
not gate the mod.

```gradle
// EMI — the :api classifier is the compile surface; full jar at dev runtime.
modCompileOnly "dev.emi:emi-fabric:${emi_version}:api"
modLocalRuntime "dev.emi:emi-fabric:${emi_version}"

// REI — split into api + default-plugin; needs Architectury at runtime.
modCompileOnly "me.shedaniel:RoughlyEnoughItems-api-fabric:${rei_version}"
modLocalRuntime "me.shedaniel:RoughlyEnoughItems-default-plugin-fabric:${rei_version}"
modLocalRuntime "me.shedaniel:RoughlyEnoughItems-fabric:${rei_version}"

// JEI — compile against the -api jar; pull the full runtime jar non-transitive
// to avoid Loom "duplicate input class" warnings.
modCompileOnly "mezz.jei:jei-${mc}-fabric-api:${jei_version}"
modLocalRuntime("mezz.jei:jei-${mc}-fabric:${jei_version}") { transitive = false }
```

### Registration entrypoints

Each viewer is discovered through a fabric.mod.json entrypoint, not
`META-INF/services`:

| Viewer | Entrypoint key | Plugin interface |
|---|---|---|
| EMI | `emi` | `EmiPlugin` |
| REI | `rei_client` | `REIClientPlugin` |
| JEI | `jei_mod_plugin` | `IModPlugin` |

JEI's `@JeiPlugin` annotation is load-bearing on Forge/NeoForge only; keep it on
the Fabric plugin for cross-loader parity, but the `jei_mod_plugin` entrypoint is
what Fabric actually reads.

### Category titles: EMI reads a lang key, REI/JEI take a Component

The three viewers name a category differently, and EMI's way is the one that
silently breaks. REI and JEI take the title as an explicit `Component` you pass
at construction — a missing translation surfaces loudly at that call site:

```java
// REI / JEI — you hand it the title directly.
new ReiEnchantmentBrowserCategory(id, Component.translatable("gui.mymod.browser.title"));
```

EMI takes **no title argument**. An `EmiRecipeCategory` derives its display name
from the lang key `emi.category.<namespace>.<path>`, resolved from the category
`ResourceLocation`. Miss that key and EMI shows the raw key on screen
(`emi.category.mymod.enchant…`) with nothing at the call site to catch it:

```java
// id → emi.category.mymod.enchantments must exist in en_us.json, or the raw key shows.
new EmiRecipeCategory(MyMod.id("enchantments"), ICON);
```

So: **every `EmiRecipeCategory` you register needs a matching
`emi.category.<namespace>.<path>` entry in the lang file.** When the same logical
category exists across viewers, point all three at the same wording (reuse the
REI/JEI title's string, or duplicate its value) so the label doesn't drift
between viewers.

## The reactive-refresh problem

Viewers build their lists when they load — at client startup or join. If your
displayable data is built or **synced at runtime** (server-authoritative data
arriving over the network, or changed by a live `/reload`), the viewer can show
stale or empty data.

This only matters on a **remote dedicated server**. An integrated/singleplayer
client already holds the full data in-JVM, so the viewer's static registration
sees it. Two failure windows on a dedicated server:

1. **First join** — a race between your sync packet landing and the viewer
   rebuilding its list. The static registration may run before the data arrives.
2. **Live `/reload` while connected** — the viewer never notices the data
   changed; it built its list once and won't rebuild on its own.

How much each viewer helps differs sharply.

### JEI — public, stable runtime API. Preferred pattern.

JEI exposes a clean, supported way to add recipes after load. Capture the
runtime in your plugin and add fresh data when it lands:

```java
public final class YourJeiPlugin implements IModPlugin {
    private volatile IJeiRuntime runtime;

    @Override public void onRuntimeAvailable(IJeiRuntime r) { this.runtime = r; }
    @Override public void onRuntimeUnavailable() { this.runtime = null; }

    public void onDataSynced(List<IndexEntry> fresh) {
        IJeiRuntime r = this.runtime;
        if (r == null) return;
        IRecipeManager rm = r.getRecipeManager();
        rm.hideRecipes(YOUR_TYPE, previouslyAdded); // idempotent — clear last set
        List<YourDisplay> next = fresh.stream().map(this::toJeiDisplay).toList();
        rm.addRecipes(YOUR_TYPE, next);
        this.previouslyAdded = next;
    }
}
```

- Store the runtime in a `volatile` field; clear it in `onRuntimeUnavailable()`.
- `IRecipeManager.addRecipes(RecipeType, List)` is `@since 9.5.0`, with no
  `@ApiStatus.Internal` or `@Experimental` — fully supported.
- It is **additive only**. To *replace* a set on a live reload, track the
  previously-added list and `hideRecipes(type, previous)` first, so the operation
  is idempotent. On first join this is clean because the static
  `registerRecipes` ran against an empty snapshot.
- Hide-then-add is safe even when your display objects are value-equal records:
  JEI's hidden set is `Collections.newSetFromMap(new IdentityHashMap<>())`, so it
  matches by **identity**. A re-sync produces fresh instances, so hiding the old
  ones never suppresses the new ones. (The downside of identity-hiding: the
  hidden old instances linger in JEI's list — there is no `removeRecipes` — so the
  list grows by one index's worth per live reload. Bounded by reloads-per-session;
  negligible in practice.)
- Catalysts registered in `registerRecipeCatalysts` are register-time only and
  won't update at runtime — fine when they derive from static config.

### EMI — internal entry point only. Guard and latch.

EMI's reload trigger, `dev.emi.emi.runtime.EmiReloadManager.reloadRecipes()`, is
absent from the `:api` artifact. Reach it by **reflection** (MethodHandles), in a
wrapper class that holds no EMI imports so it's safe to load when EMI is absent:

```java
// No EMI imports — safe to class-load even when EMI isn't present.
final class EmiReloadBridge {
    private static volatile boolean available = true;

    static void requestReload() {
        if (!available) return;
        try {
            Class<?> mgr = Class.forName("dev.emi.emi.runtime.EmiReloadManager");
            MethodHandles.lookup()
                .findStatic(mgr, "reloadRecipes", MethodType.methodType(void.class))
                .invoke();
        } catch (Throwable t) {
            available = false; // latch off — degrade to rejoin-refresh
        }
    }
}
```

Latching off on the first failure means a future rename or removal degrades
gracefully to rejoin-refresh instead of throwing on every sync. Rated: works,
but the least stable path — it depends on an internal symbol.

### REI — no clean public reactive path. Do not force a reload.

REI offers no supported way to push a fresh browse-all list after load. Do not
reach for the internal plumbing:

- `DisplayRegistry.add()` is meant for `registerDisplays` during the reload
  pipeline, not for ad-hoc runtime additions.
- `PluginManager.view()` is `@ApiStatus.Internal`; `Reloadable.startReload()` and
  the staged reload hooks are `@ApiStatus.Experimental` — fragile internal
  machinery.
- `DynamicDisplayGenerator` / `DisplayVisibilityPredicate` are keyed to
  per-item lookups, not full category-browse listings, so they don't fit a
  "browse all" index.

Accept **rejoin / F3+T refresh** and **document the limitation**. (REI *does*
drive its own lists reactively for genuine recipes via
`DisplayRegistry.registerRecipeFiller(...)` — see below — but that only works for
data that lives in the vanilla `RecipeManager` as `Recipe<?>`. It cannot consume
non-recipe data like loot tables.)

### Stability summary

| Viewer | Live-reload API | Stability | Recommendation |
|---|---|---|---|
| JEI | `IRecipeManager.addRecipes` / `hideRecipes` | Public, stable (`@since 9.5.0`) | Use it. Idempotent via hide-then-add. |
| EMI | `EmiReloadManager.reloadRecipes()` | Internal, reflective | Guard and latch off on first failure. |
| REI | `DisplayRegistry` reload internals | Internal / experimental | Avoid. Rejoin-refresh and document. |

## `registerRecipeFiller` deep-note (REI)

`DisplayRegistry.registerRecipeFiller(...)` is a **reactive binding over the
vanilla `RecipeManager`**: you hand REI a converter from a recipe type to a
display, and REI pulls the recipes itself and re-runs the converter whenever the
recipe manager syncs or `/reload` fires. You get reactivity for free — but only
because vanilla already syncs and reloads the recipe manager.

It is the right tool **only when your viewer data is genuine `Recipe<?>` entries
in the recipe manager**. It is REI-only (EMI and JEI have no equivalent filler
concept), and its signature churns across REI majors — REI 16.x for MC 1.21.1;
the filler was reworked into `RecipeManagerConsumer.beginRecipeFiller` in REI 19.x
for MC 1.21.5+. For synthetic catalogs (loot indexes, custom non-recipe data) it
does not apply — use the snapshot + per-viewer refresh approach above.

## Best-practice checklist

| Check | What to do |
|---|---|
| Shared data source | One viewer-agnostic `snapshot()` + shared label/marker helpers. |
| Thin adapters | Each plugin maps `snapshot()` to its display type; no cross-viewer imports. |
| EMI category names | Every `EmiRecipeCategory` needs an `emi.category.<namespace>.<path>` lang entry; REI/JEI take an explicit `Component` title. Keep wording consistent across viewers. |
| Dependencies | `modCompileOnly` API + `modLocalRuntime` full jar; `suggests`, never `depends`. |
| Refresh gating | Only refresh on a dedicated server. Skip when an integrated server is present — don't overwrite the full in-JVM snapshot with a capped synced copy. |
| JEI refresh | `addRecipes` after capturing the runtime; idempotent via `hideRecipes(previous)` then add. |
| EMI refresh | Reflective `EmiReloadManager.reloadRecipes()`, guarded, latching off on first failure, no EMI imports in the bridge. |
| REI refresh | Rejoin / F3+T; document the limitation. Use `registerRecipeFiller` only for real `Recipe<?>` data. |
| Thread | Run the refresh on the client thread (`client.execute(...)`). |
| Cleanup | Clear synced state and the captured runtime on disconnect. |

## Version notes

- **Fabric 1.21.1:** JEI 19.x, REI 16.x, EMI 1.1.x.
- JEI `IRecipeManager.addRecipes` / `hideRecipes` are `@since 9.5.0` and stable.
- REI's recipe-filler API is REI 16.x on 1.21.1; it was reworked into
  `RecipeManagerConsumer.beginRecipeFiller` in REI 19.x for MC 1.21.5+. Pin the
  signature to the REI major you compile against.
- EMI's `EmiReloadManager` lives outside the `:api` artifact — treat it as an
  internal symbol that may move between EMI versions.

## Guardrails

- **Never** let one viewer plugin class-load another viewer's types. Keep all
  shared logic in the viewer-agnostic data source.
- **Always** add an `emi.category.<namespace>.<path>` lang entry for every EMI
  category you register — EMI resolves the display name from that key (there's no
  title argument), so a missing entry shows the raw key in-game.
- **Never** declare a viewer under `depends`. Use `suggests` and `modCompileOnly`
  + `modLocalRuntime` so a missing viewer can't gate the mod.
- **Never** force a refresh on an integrated/singleplayer client. The in-JVM
  snapshot is already complete; a runtime refresh can only replace it with a
  capped synced copy.
- **Always** make the JEI refresh idempotent — `hideRecipes(previous)` before
  `addRecipes`, tracking the last set you added.
- **Always** guard the EMI reflective bridge and latch it off on first failure;
  keep that class free of EMI imports so it's safe to load when EMI is absent.
- **Never** reach into REI's `@ApiStatus.Internal` / `@Experimental` reload
  machinery to force a browse-all refresh. Document the rejoin requirement.
- **Always** run viewer refreshes on the client thread and clear the captured
  runtime + synced state on disconnect.
