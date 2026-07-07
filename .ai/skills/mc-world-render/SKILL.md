---
name: mc-world-render
description: Draw in-world overlays the suite way — camera-facing billboards in WorldRenderEvents.LAST with custom translucent render types (xray vs occluded) that compose with Sodium/Iris/EBE, tick-driven particle visualizers that stay cheap via distance + view-cone culling, distance-LOD spacing, a per-tick particle budget, and a request throttle, and atmosphere tinting (sky/fog/celestial/ambient-light moods) via tiny RETURN-inject mixins that delegate to pure, server-gated blend classes. TRIGGER when creating or editing a *OverlayRenderer.java, *RenderTypes.java, a WorldRenderEvents registration, an in-world marker/highlight/link visualizer, particle-spawning client tick code, a sky/fog/moon-tint or ambient-light mixin (*SkyColorMixin, *FogMixin, *MoonTintMixin, a LightTexture darkness inject), or a *ClientEffects blend class; or when the user mentions highlighting blocks/entities in the world, an xray overlay, in-world markers, or tinting the sky/fog/moon/lighting for a world mood.
---

The user is changing how **the world** looks — drawing something anchored to a
block or entity, or tinting the whole atmosphere — not drawing on the screen
(that's the `mc-hud` skill) and not registering a particle type (that's
`mc-registration`). Three techniques, picked by what you're changing:

| You're changing… | Use | Reference |
|---|---|---|
| a sprite/quad hovering on a block or entity | **billboard in `WorldRenderEvents.LAST`** + custom `RenderType` | Prosperity unlooted-container sparkle |
| streams of motes / status pips between points | **tick-driven particle spawns** with culling + budget | Mercantile workstation-link visualizer |
| the sky/fog/moon/light mood of the whole world | **RETURN-inject tint mixins** + pure blend class | Tribulation Blood Moon, Oppressive Nights |

Both must stay cheap (they run per frame / per tick over potentially many anchors)
and **compose with rendering mods** — never fight Sodium/Iris by hooking chunk or
block-entity rendering.

## Billboards in `WorldRenderEvents.LAST`

`LAST` is a post-pass over the finished scene that touches no block/chunk/BE
rendering, so it composes with Sodium, EBE, and Iris. Build the quad
**camera-relative on the world renderer's identity pose** (the same frame entities
use) and rotate it by the camera orientation to billboard.

```java
public static void register() { WorldRenderEvents.LAST.register(UnlootedOverlayRenderer::render); }

private static void render(WorldRenderContext context) {
    if (/* feature off */ cacheEmpty || context.world() == null) return;
    if (!(context.consumers() instanceof MultiBufferSource.BufferSource bufferSource)) return;

    Camera camera = context.camera();
    Vec3 cam = camera.getPosition();
    Quaternionf cameraRotation = camera.rotation();
    float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
    PoseStack pose = context.matrixStack();

    boolean drewAny = false;
    for (BlockPos anchor : cache) {
        drewAny |= renderBillboard(pose, context.consumers(), cam, cameraRotation,
                anchor.getX() + 0.5, anchor.getY() + 1.0 + HOVER, anchor.getZ() + 0.5, /* fade/uv */);
    }
    if (drewAny) {                                  // flush our custom render types once
        bufferSource.endBatch(ProsperityRenderTypes.xray());
        bufferSource.endBatch(ProsperityRenderTypes.occluded());
    }
}

private static boolean renderBillboard(PoseStack pose, MultiBufferSource consumers, Vec3 cam,
        Quaternionf cameraRotation, double ax, double ay, double az, /* ... */) {
    double dx = ax - cam.x, dy = ay - cam.y, dz = az - cam.z;
    double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
    float alpha = (float) IndicatorMath.fadeAlpha(distance, renderDistance);   // distance fade
    if (alpha <= 0f) return false;
    int a = Math.clamp((long)(alpha * 255f), 0, 255);

    VertexConsumer buf = consumers.getBuffer(
            distance <= xrayDistance ? ProsperityRenderTypes.xray() : ProsperityRenderTypes.occluded());
    pose.pushPose();
    pose.translate(dx, dy + bob, dz);              // camera-relative anchor
    pose.mulPose(cameraRotation);                  // billboard toward the camera
    Matrix4f m = pose.last().pose();
    buf.addVertex(m, -HALF, -HALF, 0).setUv(0, v1).setColor(255,255,255,a);
    buf.addVertex(m,  HALF, -HALF, 0).setUv(1, v1).setColor(255,255,255,a);
    buf.addVertex(m,  HALF,  HALF, 0).setUv(1, v0).setColor(255,255,255,a);
    buf.addVertex(m, -HALF,  HALF, 0).setUv(0, v0).setColor(255,255,255,a);
    pose.popPose();
    return true;
}
```

**Anchor moving entities to their partial-tick-interpolated position**
(`entity.getPosition(partialTick)`), resolve them by network id each frame, and
**silently skip** an id whose entity is gone — don't keep a stale reference.

### Custom render types

Pair an **xray** type (no depth test — visible through walls within a near range)
with an **occluded** type (LEQUAL depth — hidden behind geometry beyond it). Make
both translucent, unculled, and **write no depth** so indicators never occlude one
another, and `NO_LIGHTMAP` if the sprite is emissive (full-bright glow).

```java
private static RenderType create(String name, RenderStateShard.DepthTestStateShard depthTest) {
    var state = RenderType.CompositeState.builder()
        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
        .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
        .setDepthTestState(depthTest)               // NO_DEPTH_TEST (xray) vs LEQUAL_DEPTH_TEST (occluded)
        .setCullState(RenderStateShard.NO_CULL)
        .setWriteMaskState(RenderStateShard.COLOR_WRITE)   // color only — no depth write
        .setLightmapState(RenderStateShard.NO_LIGHTMAP)    // emissive
        .createCompositeState(false);
    return RenderType.create(MOD_ID + ":" + name, DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS, 256, state);
}
```

## Tick-driven particle visualizers

For motes/pips, spawn particles each client tick — but a naive loop over every
anchor tanks the frame rate. Gate, throttle, cull, LOD, and budget:

- **Activation gate** — only run when the feature is on *and* its trigger holds
  (e.g. the player is holding a specific item). Clear all state when it turns off.
- **Request throttle** — if you query the server for data, throttle the C2S
  request to **match the server's cooldown** (don't ask faster than it answers).
- **Per-tick budget** — cap total spawns per tick (`MAX_PARTICLES_PER_TICK`) and
  bail when hit, so a huge data set can't flood.
- **Distance cull** — skip anchors beyond render range (compare squared distance).
- **View-cone cull** — skip anchors outside the view cone via a dot-product
  threshold, so off-screen anchors cost nothing.
- **Distance LOD** — widen particle spacing with camera distance so far lines
  aren't oversampled.
- **Re-emit cooldown** — for one persistent marker per position, track a
  per-position cooldown matching the particle's lifetime so exactly one is alive
  at a time (and off-screen positions don't burn a slot — they re-check next tick).

```java
public static void tick(Minecraft client) {
    LocalPlayer player = client.player; ClientLevel level = client.level;
    if (player == null || level == null) { resetState(); return; }
    boolean active = isEnabled() && isHoldingBell(player);     // activation gate
    if (!active) { clearStateAndReturn(); return; }

    if (!wasActive || ++ticksSinceRequest >= REQUEST_INTERVAL_TICKS) {  // throttle, matches server cooldown
        ClientPlayNetworking.send(new RequestWorkstationMapC2SPayload());
        ticksSinceRequest = 0;
    }
    wasActive = true;
    var payload = ClientMercantileData.getWorkstationMap();
    if (payload != null) spawnParticles(client, level, player, payload);
}

// inside spawnParticles, per anchor:
if (spawned >= MAX_PARTICLES_PER_TICK) return;                 // budget
if (!withinRange(to, eye)) continue;                          // distance cull (squared)
if (!inViewCone(to, eye, view)) continue;                    // view-cone cull
// LOD: step grows with camera distance
double step = Math.min(MAX_STEP_BLOCKS, BASE_STEP_BLOCKS * (1.0 + camDist / 16.0));

private static boolean inViewCone(Vec3 point, Vec3 eye, Vec3 view) {
    Vec3 to = point.subtract(eye);
    double len = to.length();
    if (len < 1e-3) return true;
    return (to.x*view.x + to.y*view.y + to.z*view.z) / len > VIEW_DOT_THRESHOLD;  // ~ -0.35 ≈ wide front cone
}
```

## Atmosphere tinting (world moods)

For a whole-world mood — a blood-red event sky, oppressive night darkness —
don't draw anything at all. Nudge the color values **at vanilla's own
computation points**: one tiny client mixin per channel (~20 lines each) that
RETURN-injects (or `@ModifyArgs` at the exact call) and immediately delegates
to a pure static blend class. **All math lives outside the mixin.**

| Channel | Vanilla computation point |
|---|---|
| sky color | `ClientLevel#getSkyColor` — `@At("RETURN")` |
| fog color | `FogRenderer#setupColor` — the final unconditional `RenderSystem.clearColor` call |
| sun/moon tint | `LevelRenderer#renderSky` — `@ModifyArgs` on the `setShaderColor` before the celestial quads |
| ambient light | `LightTexture#calculateDarknessScale` — `@At("RETURN")`, add to the darkness scale |

```java
@Mixin(ClientLevel.class)
public abstract class EventSkyColorMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void mymod$tintSkyColor(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (!EventClientEffects.isTintActive(self)) return;
        cir.setReturnValue(EventClientEffects.tintSky(cir.getReturnValue()));
    }
}
```

That is the *entire* mixin. The `*ClientEffects` blend class owns the gate and
the color curves:

```java
public final class EventClientEffects {
    private static final float SKY_BLEND = 0.65f;
    private static final float FOG_BLEND = 0.3f;        // gentler — see below

    /** True when the given level should render the mood right now. */
    public static boolean isTintActive(ClientLevel level) {
        return ClientMyModState.isEventActive()             // only true if the server broadcast it
                && level != null
                && level.dimension() == Level.OVERWORLD;    // dimension check
    }

    public static Vec3 tintSky(Vec3 sky) {
        return new Vec3(blend(sky.x, SKY_RED, SKY_BLEND), /* green, blue */);
    }

    static double blend(double base, double target, float factor) {
        return base + (target - base) * factor;
    }
}
```

**The server decides; the client only renders.** The tint flag is only ever
true when the server broadcast it — the server folds its client-effects config
toggle into the sync — and the blend class adds a local dimension check.
Clients never self-activate a world mood. When the server syncs an *intensity*
rather than a flag, clamp it client-side (Tribulation caps oppressive-night
darkness at 0.6) so a misconfigured or malicious server can't black out the
screen; a local client-config opt-out belongs in the same gate.

**Fog composes with the sky — tint it gentler.** Vanilla's clear-air fog path
derives part of its color from the *already-tinted* sky color, so the fog
blend must be a deliberate top-up, not a second full pull (Tribulation: sky
0.65, fog 0.3). Also bail when `camera.getFluidInCamera() != FogType.NONE` so
water/lava fog stays vanilla.

**Why this stays Sodium/Iris/shader-safe:** there is no render-pipeline
surgery — no custom sky renderer, no pass replacement — only adjusting color
values at the points vanilla computes them, which rendering mods read
downstream. The ambient-light channel likewise rides the vanilla
Darkness-effect scale, so it composes with sky/block light, night vision,
gamma, and the "Darkness Pulsing" accessibility slider.

**Unit-test the blend class** — it's plain arithmetic. Intensity 0 must be a
vanilla passthrough, full intensity must land on the target hue, and values in
between must be monotonic. Tribulation's
`EnvironmentalPressureClientEffectsTest` is the model: noon → 0, midnight →
full, smooth dusk ramp, stays in `[0,1]`, caps oversized server values.

## Keep the math pure

Distance fade, the hover bob, the animation-frame index, and view-cone math are
all Minecraft-free arithmetic — keep them in an `IndicatorMath`-style class so they
unit-test from `src/test` without bootstrapping the client (see the
`mc-mod-testing` skill). The renderer/visualizer becomes a thin shell that feeds
camera/anchor data into pure functions.

## Best-practice checklist

| Check | What to do |
|---|---|
| Compose with mods | Draw in `WorldRenderEvents.LAST`; never hook chunk/BE rendering. |
| Billboard | Camera-relative on the identity pose; `mulPose(camera.rotation())`. |
| Moving anchors | Interpolate with partial tick; resolve by id each frame; skip gone entities. |
| Render types | Translucent, no-cull, no depth write; xray (no depth test) + occluded (LEQUAL). |
| Flush | `endBatch(type)` once after the loop, only if something was drawn. |
| Particle gate | Feature toggle + trigger (held item); clear all state when inactive. |
| Throttle | C2S request interval matches the server cooldown. |
| Cull + budget | Distance-squared cull, view-cone cull, `MAX_PARTICLES_PER_TICK`. |
| LOD | Widen spacing with camera distance. |
| Tint mixins | Tiny RETURN-injects at vanilla color points; delegate straight to a blend class. |
| Mood gate | Server-broadcast flag + dimension check; clamp synced intensity client-side. |
| Fog blend | Gentler than sky (fog derives from the tinted sky); skip submerged cameras. |
| Pure math | Fade/bob/frame/cone/blend math in a Minecraft-free class with unit tests. |

## Guardrails

- **Never** hook block-entity or chunk rendering to draw an overlay — use
  `WorldRenderEvents.LAST` so Sodium/Iris/EBE aren't disturbed.
- **Never** loop over every anchor unconditionally — distance-cull, cone-cull, and
  cap spawns per tick.
- **Never** request server data faster than the server's cooldown serves it.
- **Never** hold a stale entity reference for a moving anchor — resolve by id each
  frame and skip when absent.
- **Never** write depth from an indicator quad — indicators would occlude each
  other; use `COLOR_WRITE` only.
- **Never** replace the sky/fog renderer to tint the atmosphere — adjust the
  color values at vanilla's own computation points so Sodium/Iris/shaders read
  them unchanged downstream.
- **Never** let a client self-activate a world mood — gate every tint on the
  server-broadcast flag, and clamp any server-synced intensity.
- **Never** put color math in a tint mixin — the mixin is a one-call delegate
  to a pure blend class.
- **Always** clear visualizer state (caches, cooldowns, last-request tick) when
  the feature deactivates or the player disconnects.
- **Always** keep fade/bob/cone math pure and unit-tested; the renderer is a thin
  shell over it.
