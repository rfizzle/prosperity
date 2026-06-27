---
name: mc-hud
description: Build a conforming persistent HUD element the suite way (the Concord HUD Standard) — a 20px icon+bar badge registered via HudRenderCallback, anchored with pure testable offset math, hidden by the four standard visibility rules, and stacked under higher-priority sibling mods through reflection-backed coordination accessors rather than hardcoded heights. TRIGGER when creating or editing a *HudOverlay.java, HudMath, a HudRenderCallback registration, the isHudVisible()/getHudHeight() coordination accessors, or when the user mentions a HUD slot, HUD element, on-screen badge, or stacking a HUD against another mod.
---

The user is rendering a persistent on-screen HUD element. The Concord HUD Standard
is summarized in full below — this skill is the normative reference for member
repos, which don't carry a separate standards doc. The hard part is **cross-mod
stacking**: each mod renders its own element and must offset past higher-priority
siblings *without* depending on them or hardcoding their size.

## Whether a mod gets a slot at all

A mod takes a HUD slot **only if it has persistent ambient state the player needs
while walking around** (a level, a standing, a tier that changes as you play).
Everything else belongs in screens, tooltips (Jade/WTHIT — see `mc-tooltips`), or
recipe viewers (`mc-compat`). **Opting out is conformant; future members default
to no slot.** Record a no-slot decision in the mod's `design/DESIGN.md`.

## Slot registry

Fixed priority, top to bottom. Elements shift up to fill gaps when a
higher-priority mod is absent or its HUD is disabled. **New slots are assigned
here by appending — never by renumbering.**

| Slot | Mod | Content |
|---|---|---|
| 1 | Tribulation | 16×16 skull glyph tinted by tier, 2px level-progress bar |
| 2 | Mercantile | Reputation tier glyph, tier-tinted progress bar |
| 3 | Prosperity | Loot distance tier glyph, tier-color tint |
| — | Meridian | No slot, by design |

## Visual spec

- **20px standard element height, 2px gap** between stacked elements. (A 16px
  icon + 1px gap + 2px bar = 19px rounds into the 20px box.)
- 16×16 mod glyph; optional short label in the **vanilla Minecraft font** only,
  white with standard drop shadow; optional 2px progress bar under the glyph.
- **State tinting is the element's only decoration** — no custom fonts, no ornate
  frames, no animation beyond a color tint and a brief transition lerp.
- The glyph is a **purpose-built 16×16 texture**, not a downscaled vanilla item
  render (those go muddy at 16px). Author it via the texture pipeline and commit
  its `.glyph` source beside the master (see the `mc-textures` skill).

## Registration

```java
public final class TribulationHudOverlay implements HudRenderCallback {
    @Override public void onHudRender(GuiGraphics graphics, DeltaTracker delta) { /* ... */ }
}
// at client init:
HudRenderCallback.EVENT.register(new TribulationHudOverlay());
```

## The four visibility rules

Hidden during **all** of: F1/`hideGui`, any open screen, spectator mode, and the
death screen (checked via `isDeadOrDying` so it also hides in the ticks between
death and the screen opening). All four are required. Fold the config gate and
(if applicable) the server-synced gate into the same predicate, and **reuse that
predicate** as the coordination accessor (below).

```java
public static boolean isHudVisible() {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) return false;
    if (mc.options.hideGui) return false;          // F1
    if (mc.screen != null) return false;           // any open screen
    if (mc.player.isSpectator()) return false;     // spectator
    if (mc.player.isDeadOrDying()) return false;   // death screen + the ticks before it
    TribulationConfig c = Tribulation.getConfig();
    return c != null && c.hud != null && c.hud.enabled;
}
```

## Anchor + offset math (keep it pure and testable)

Default anchor **top-left**, configurable to any corner via an `Anchor` enum
(`TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`) plus pixel
`offsetX`/`offsetY` (default 4px from each edge). Offsets measure **inward from
the anchored edge** so the badge keeps its corner distance at any screen size.
Keep this math Minecraft-free in a `HudMath`-style class so it unit-tests without
bootstrapping the client (see `mc-mod-testing`).

```java
public static int computeOriginX(Anchor anchor, int screenW, int offsetX, int badgeW) {
    return switch (anchor) {
        case TOP_LEFT, BOTTOM_LEFT  -> offsetX;
        case TOP_RIGHT, BOTTOM_RIGHT -> screenW - offsetX - badgeW;
    };
}
public static int computeOriginY(Anchor anchor, int screenH, int offsetY, int badgeH, int stackOffset) {
    return switch (anchor) {
        case TOP_LEFT, TOP_RIGHT     -> offsetY + stackOffset;        // shift down from a top anchor
        case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - offsetY - badgeH - stackOffset; // up from a bottom anchor
    };
}
```

State tinting and the gold→tier transition lerp are also pure color math
(`lerpColor(from, to, t)` over ARGB channels; a `lastChangeMs` far in the past
yields the steady color, so there's no flash on first render).

## Cross-mod slot stacking — the coordination mechanism

There is **no shared HUD manager and no shared library.** Each mod renders its own
element and computes its own offset by summing the **height contribution** of
every *higher-priority loaded* sibling, queried each render pass. Coordination
happens through two client-safe accessors every HUD-bearing mod exposes.

### Provider side — expose your contribution

Expose `isHudVisible()` / `getHudHeight()` on the overlay (the reflection targets)
and re-export them from the `api` package, reflection-backed from common code
(see `mc-public-api`). The height contribution is the **20px element + 2px gap**
when visible, 0 otherwise.

```java
/** Reflection target for the api accessor — keep the static, no-arg signature stable. */
public static int getHudHeightContribution() {
    return isHudVisible() ? STANDARD_ELEMENT_HEIGHT + STACK_GAP : 0;   // 20 + 2
}
```

### Consumer side — offset past higher-priority siblings by reflection

Resolve the sibling's accessors **once**, cache the handles, and **degrade
gracefully**: 0 when the sibling is absent; a documented legacy fallback when the
sibling is present but predates the accessors; the live value otherwise.

```java
static final class TribulationOffset {
    private static boolean resolveAttempted;
    private static MethodHandle isHudVisibleHandle, getHudHeightHandle;
    private static final int LEGACY_FIXED_OFFSET = 22;   // pre-accessor behavior

    static int current() {
        if (!FabricLoader.getInstance().isModLoaded("tribulation")) return 0;   // absent → no reservation
        resolveOnce();
        if (isHudVisibleHandle == null || getHudHeightHandle == null) return LEGACY_FIXED_OFFSET; // older sibling
        try {
            if (!(boolean) isHudVisibleHandle.invokeExact()) return 0;
            return Math.max(0, (int) getHudHeightHandle.invokeExact());
        } catch (Throwable t) { return LEGACY_FIXED_OFFSET; }  // misbehaving → don't overlap slot 1
    }
    private static void resolveOnce() {
        if (resolveAttempted) return;
        resolveAttempted = true;
        try {
            Class<?> api = Class.forName("com.rfizzle.tribulation.api.TribulationAPI");
            var lookup = MethodHandles.publicLookup();
            isHudVisibleHandle = lookup.findStatic(api, "isHudVisible", MethodType.methodType(boolean.class));
            getHudHeightHandle = lookup.findStatic(api, "getHudHeight", MethodType.methodType(int.class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            isHudVisibleHandle = getHudHeightHandle = null;     // older sibling without accessors
        }
    }
}
```

**Stacking applies within an anchor.** Sibling anchors aren't queryable (only
visibility/height are), so reserve sibling height **only at your default
`TOP_LEFT`** — the slot registry's canonical position. A user who moves your
element to another corner opts out of stacking against the default-placed sibling.

```java
static int stackOffsetFor(Anchor anchor, int siblingHeight) {
    return anchor == Anchor.TOP_LEFT ? siblingHeight : 0;
}
```

A **hardcoded sibling height** or bare `isModLoaded` displacement is
**non-conformant** — it goes stale the moment the user disables or moves the
sibling's HUD. The legacy fixed offset above is only a transitional fallback for a
sibling release that predates the accessors. The ~80 lines of offset logic are
deliberately duplicated per mod (convention over dependency).

## Conformance checklist

- [ ] Slot registered in the registry table above (or a no-slot decision recorded
      in `design/DESIGN.md`).
- [ ] 20px element + 2px gap; visual spec respected; vanilla font only.
- [ ] Glyph is a purpose-built texture with its `.glyph` source committed beside
      the master — not a downscaled vanilla item.
- [ ] Anchor + pixel-offset config; default top-left, 4px.
- [ ] All four visibility rules implemented.
- [ ] `isHudVisible()` / `getHudHeight()` exposed in the `api` package,
      reflection-safe from common code.
- [ ] Offset computed from sibling accessors — no hardcoded sibling heights.
- [ ] `AGENTS.md` declares "conforms to Concord HUD Standard v1".

## Guardrails

- **Never** hardcode a sibling's HUD height or displace by bare `isModLoaded` —
  query its `getHudHeight()` accessor each render pass.
- **Never** skip any of the four visibility rules; check `isDeadOrDying`, not just
  the death screen.
- **Never** use a custom font or a downscaled vanilla item render for the glyph.
- **Never** introduce a shared HUD manager/library — each mod renders and offsets
  independently (convention over dependency).
- **Always** reserve sibling height only at the default anchor; moving to another
  corner opts out of stacking.
- **Always** resolve sibling accessors once and cache the handles; degrade to 0
  (absent) or the documented legacy fallback (older sibling), never throw on the
  render path.
- **Always** keep the anchor/color math Minecraft-free so it unit-tests, and
  reuse the visibility predicate as the `isHudVisible()` accessor.
