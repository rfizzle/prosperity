---
name: mc-screen
description: Build or extend a Minecraft Fabric GUI screen the suite way — a container/menu screen or a bespoke info screen, drawn entirely through GuiGraphics in the standard render order, with GL state paired and reset, layout math kept pure and testable, and vanilla screens extended through a single consolidated render-TAIL mixin inject so draw order is explicit. The screen pipeline flushes for you (unlike a HUD callback — see mc-hud). TRIGGER when creating or editing a *Screen.java (extends Screen / AbstractContainerScreen), a *ScreenLayout class, a *PanelRenderer drawn inside a screen, a @Mixin into a vanilla screen's render / renderBg / init, an addRenderableWidget / AbstractWidget, or when the user mentions a GUI screen, container/menu screen, custom inventory GUI, or drawing a panel/overlay inside a screen.
---

The user is building or extending an in-game **screen** — a container/menu GUI, a
bespoke info screen, or custom drawing injected into a vanilla screen. A screen is
**not** a HUD surface: it is rendered by the vanilla screen pipeline, which flushes
the buffer source at the end of the frame for you, so a screen needs **no manual
`graphics.flush()`**. (A `HudRenderCallback` overlay *does* — see the `mc-hud`
skill.) What a screen owes instead is the right **render order**, **GuiGraphics-only
drawing**, and **clean GL state**.

Menu/screen split (server-side `AbstractContainerMenu` vs client-side `Screen`) and
registering the `MenuType` belong to `mc-registration`; config-editor screens
(Cloth/ModMenu) belong to `mc-config`. This skill is the client-side **rendering**
of a bespoke or extended screen.

## The render pass and its order

`Screen.render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick)` runs in
a fixed order — keep to it so your content layers correctly:

```java
@Override
public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
    renderBackground(gfx, mouseX, mouseY, partialTick);  // dim + blur behind the GUI
    super.render(gfx, mouseX, mouseY, partialTick);      // panel bg + widgets
    renderTooltip(gfx, mouseX, mouseY);                  // ALWAYS last, sits on top
}
```

For an `AbstractContainerScreen`, the panel itself is split across two overrides —
mind the **different parameter order**:

- `renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY)` — blit the
  panel background texture (`partialTick` comes *first* here).
- `renderLabels(GuiGraphics gfx, int mouseX, int mouseY)` — text in **menu space**
  (origin already translated to the panel's top-left; don't re-add `leftPos`/`topPos`).

Tooltips render **after** `super.render` so they land above everything, including
your own panels. Never draw a tooltip mid-pass.

## Draw only through GuiGraphics

Every draw goes through `GuiGraphics` — `blit` / `blitSprite` (textures), `fill`
(rects), `drawString` (text), `renderItem` (item stacks), `renderTooltip`. This is
the batchable path a batching optimizer (**ImmediatelyFast**) and framebuffer-reading
effects (**Blur+**, post shaders) are built to handle.

- **Never** draw with raw `RenderSystem.setShaderTexture` + `Tessellator` /
  `BufferBuilder` quads. That manual immediate-mode path is exactly what a batching
  mod drops or captures — the sprite silently vanishes under ImmediatelyFast.
- A screen **does not** call `graphics.flush()` — the pipeline flushes at frame end.
  The one ordering subtlety: a translucent overlay drawn *over* a `renderItem` must
  come **after** the item draw in code (the item flushes its own model batch), so a
  "ghosted preview" tint blits after `renderItem`, not before.

```java
// tinted sprite: set color, blit, reset — all in the same breath
gfx.setColor(1.0F, 0.84F, 0.25F, 1.0F);
gfx.blit(TEXTURE, x, y, u, v, w, h, TEX_W, TEX_H);
gfx.setColor(1.0F, 1.0F, 1.0F, 1.0F);
```

## GL state hygiene

Deferred batching means state you set may not be active when the batch actually
draws. Set state through `GuiGraphics`, scoped tightly, and **always reset it**:

- Every `gfx.setColor(r, g, b, a)` is paired with a `gfx.setColor(1, 1, 1, 1)`.
- Every `gfx.pose().pushPose()` is paired with a `gfx.pose().popPose()`.
- Never stash a bound texture or blend mode across draws expecting it to persist.

## Layout math stays pure and testable

Keep screen geometry — panel origin, slot rows, scrollbar position, hit-testing —
in a Minecraft-free `*ScreenLayout` class of static helpers so it unit-tests without
bootstrapping the client (see `mc-mod-testing`). The screen and any injected panel
read the **same** layout helpers, so the button, the panel, and its hover region
can't drift apart.

```java
public final class MerchantScreenLayout {
    public static int overlayX(int screenW) { return (screenW - OVERLAY_WIDTH) / 2; }
    public static int overlayY(int screenH) { /* ... */ }
    public static boolean pointInClose(int mx, int my, int screenW, int screenH) { /* ... */ }
}
```

## Widgets

Add buttons and controls with `addRenderableWidget(...)` in `init()`; a custom
control extends `AbstractWidget` and renders through `GuiGraphics` in
`renderWidget`. Position widgets from the same `*ScreenLayout` helpers so they
follow the container when the window resizes, and reposition per frame (in a
`render` HEAD hook) if the anchor moves (in-panel vs. pop-out overlay).

## Extending a vanilla screen (mixin)

To add to a vanilla screen (e.g. `MerchantScreen`), `@Mixin` a class that
`extends AbstractContainerScreen<TheMenu>` and inject:

- `@Inject(method = "init", at = @At("TAIL"))` — register your widgets once.
- `@Inject(method = "render", at = @At("HEAD"))` — per-frame state: update button
  visibility, reposition to follow the active container.
- **One** `@Inject(method = "render", at = @At("TAIL"))` — draw *all* your overlays.

**Consolidate every render-TAIL draw into a single inject.** Two separate
`@Inject(..., at = @At("TAIL"))` hooks on the same `render` fire in mixin-priority
order, which is **not** a stable, readable z-order — a panel and the tooltip meant
to sit on top of it can silently swap. One inject that calls, in order, your panels
then your tooltips makes the layering explicit and local:

```java
@Inject(method = "render", at = @At("TAIL"))
private void mymod$renderOverlays(GuiGraphics gfx, int mouseX, int mouseY, float pt, CallbackInfo ci) {
    mymod$renderPins(gfx, mouseX, mouseY);
    mymod$renderInfoPanel(gfx);
    mymod$renderOverlay(gfx, mouseX, mouseY, pt);
    mymod$renderTooltips(gfx, mouseX, mouseY);   // last, on top of the above
}
```

Injection mechanics (`@Unique` prefixing, accessor mixins, targeting an obfuscated
method) are `mc-mixin-craft`'s domain.

## Reference implementations

- **Bespoke / container screens** — Meridian's `EnchantmentLibraryScreen` (an
  `AbstractContainerScreen` with `renderBg`/`renderLabels`/scrollbar),
  `MeridianEnchantmentScreen`, and `EnchantingInfoScreen` (a plain `Screen`).
- **Extending a vanilla screen** — Mercantile's `MerchantScreenMixin` with
  `MerchantScreenLayout` (pure math), `MerchantInfoPanelRenderer`, and
  `TradePinRenderer`, all drawing through `GuiGraphics` inside the screen frame.

## Guardrails

- **Never** draw a screen with raw `RenderSystem.setShaderTexture` +
  `Tessellator`/`BufferBuilder` quads — use `GuiGraphics`, or a batching optimizer
  (ImmediatelyFast) and framebuffer-reading effects (Blur+, post shaders) drop or
  capture the geometry.
- **Never** leave a `setColor`/`pushPose` unreset — pair every one with its reset.
- **Never** split screen overlays across multiple `render`-TAIL mixin injects — one
  consolidated inject with an explicit panels-then-tooltips order.
- **Never** hand-add `leftPos`/`topPos` inside `renderLabels` — it already renders in
  menu space.
- **Always** render tooltips last, after `super.render`, so they sit on top.
- **Always** keep screen geometry in a Minecraft-free `*ScreenLayout` so it unit-tests
  and the screen and its injected panels read one source of truth.
- **Do not** add a manual `graphics.flush()` in a `Screen` — the pipeline flushes for
  you; the flush rule is a `HudRenderCallback` concern (see `mc-hud`).
