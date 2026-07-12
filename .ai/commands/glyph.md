---
description: Design a Concord pixel-art glyph as an ASCII grid and render it to a PNG — single sprite, a 16/32/64/128/256 size ladder, or an animated strip.
argument-hint: <motif description> [mod] [sizes: 16,32,…] [animated]
allowed-tools: Read, Write, Bash(python3 .ai/skills/mc-textures/scripts/glyph.py:*)
---

You are designing a **pixel-art glyph** for a Concord Minecraft mod, then
rendering it to a PNG with `.ai/skills/mc-textures/scripts/glyph.py`. The pattern: *you* lay out the
sprite as a character grid (which you do reliably); the script deterministically
rasterizes it (which you don't). The whole craft is in the grid.

`.ai/skills/mc-textures/scripts/glyph.py` is the single, zero-dependency renderer — stdlib only, runs
anywhere Python 3 does. It handles all three modes below.

## Request

$ARGUMENTS

If no mod is named, ask which mod the glyph is for (its palette decides the
accents) — or proceed palette-neutral if the user says it's not mod-specific.
Default to a **single 16×16 sprite** unless the request asks for multiple sizes
or animation.

## Step 1 — Pin the palette

The renderer knows the design-system colors as named tokens — run
`python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors` to see them. Use the **named tokens**
in your legend (e.g. `mercantile.emerald`, `ink`, `gold`), not raw hex, so the
glyph stays tied to the system. A mod's accents never appear in another mod's
glyph.

## Step 2 — Design the grid

Honor the design-system glyph conventions:

- **One motif object**, centered and readable at native size. Silhouette first:
  the shape must read before any shading. At the smallest size you author, if you
  can't tell what it is, simplify the *shape* — never the shading.
- **Shade the form — don't flat-fill.** This is what separates a crafted sprite
  from a flat cartoon sticker. Hard pixels (no blur), but render volume: pick a
  light direction and give every surface a tonal **ramp** of 3–5 steps from one
  base hue — highlight → midtone → core shadow → occlusion — plus a rim/edge light
  where forms turn away. "Limited palette" caps the base *hues* (≈3–5), **not** the
  tones: a shaded 32px sprite legitimately runs 20–50 colors, almost all of them
  ramp steps. A flat single-tone fill inside the outline is the cartoony failure
  mode — avoid it on any surface big enough to hold a ramp.
- **Match detail to the size you authored.** 16px: silhouette plus one shading
  step — keep it tight. 32px: a full 3-step ramp per surface, selective interior
  anti-aliasing on curves, dithering only where a ramp step is too coarse. 64px+:
  full form rendering, 4–5 step ramps, contact/cast shadow.
- **Outline selectively.** Wrap the silhouette in `ink` (`#0a0a0a`) so it reads
  against any HUD background. *Inside* the motif, separate forms with a dark tone
  of the material itself, not more pure black — a uniform black box around every
  interior edge flattens the volume back into a sticker.
- **One glowing accent**, the mod's signature. The brighter accent (`*-bright`,
  `gold`, `ember`) sparingly for highlights; the base accent for the body.
- `.` is transparent. Keep at least a 1px transparent margin unless the motif
  intentionally bleeds to the edge.

### Canvas size by asset role

The native size you author *is* the detail budget — bigger canvas, more room for
ramps. Pick it from what the asset is, not from the slot it ends up in:

| Asset role | Author native | Detail target | Notes |
|---|---|---|---|
| HUD / status glyph | **32** | shaded form — ramps + rim light | the richest of the small slots; it carries the mod's identity. The game blits the 32 master at ~16 (`hud_icon.png` is 32×32). |
| Block texture | **32** (16 only for a plain repeating pattern) | shaded, tiling | bleeds to all four edges and tiles; more surface area earns more shading. |
| Decorated / hero item | **32** | shaded form | author at 32 and let the slot display it small. |
| Plain inventory item | 16 (32 if it rewards detail) | midtone + 1–2 steps | simple tools, ingredients. |
| Tiny pip / indicator | 16 | silhouette + 1 step | Jade dots, charge ticks. |
| Mod icon / store / hero art | **32 native → upscale** to 128/256 | richest hand-draw | rides the size ladder (below). |

Rule: **default to authoring at 32 whenever detail reads** (HUD, blocks,
hero/decorated items) and ship that 32 master directly — Minecraft renders
higher-resolution HUD/item/block textures and displays them at slot size.
"Downsizing" here means *displayed* small, **not** pixel-downscaled into a 16px
file (resampling a 32px drawing down to 16px goes muddy — don't). Reserve native
16 for motifs that must read as crisp 16px blocks, or that gain nothing from the
extra detail.

Write the spec to `art/glyphs/<name>.glyph` (or a path the user gives). The
`.glyph` is the committed source of truth; the rendered PNGs are throwaway
review artifacts (step 3); the shipped master lands in the mod's asset tree on
approval (step 4). Format — a `legend:`
mapping single chars to colors, then a `frame:` (or `grid:`) of exactly N rows ×
N chars. `#` begins a comment anywhere; don't use it as a legend key. The full
format (with a worked example) is documented in the `SPEC FORMAT` header of
`.ai/skills/mc-textures/scripts/glyph.py`.

## Step 3 — Render and review

Render the spec — the derived PNGs land beside it in `art/glyphs/`, where the
suite `.gitignore` already ignores every render (`*.png`, `*.gif`, `*.mcmeta`):

```bash
python3 .ai/skills/mc-textures/scripts/glyph.py art/glyphs/<name>.glyph
```

This writes `<name>.png` (the render) and `<name>@16x.png` (a 256px
nearest-neighbor preview) beside the spec, and prints read-back stats: opaque
color count, edge margin vs. full bleed, and the largest single-tone region —
with warnings when a surface reads as a flat fill, an edge is half-bled, or the
legend mixes two mods' accents. For a tiling block texture, add
`--tile-preview` and read the `@2x2.png` back to check seams and the shared
corner. **Read the @16x preview back** and
judge it honestly against the motif. Then iterate the grid until it's right —
fixing pixel art is fast: edit the `.glyph` and re-run. Show the user the
preview each iteration. These rendered PNGs are throwaway review artifacts —
gitignored, so they can sit beside the source without ever being committable.

---

## Mode: a size ladder (16 / 32 / 64 / 128 / 256)

When the user wants the motif at several sizes, **author the small tiers natively
and nearest-neighbor upscale the large ones** — upscaling adds no detail (each
source pixel becomes an N×N block), and hand-authoring past ~64px is impractical
(128²=16 384 cells, 256²=65 536):

| Tier  | How                            | Why |
|-------|--------------------------------|-----|
| 16px  | author native (16×16)          | smallest readable slot — tiny pips, plain symbols |
| 32px  | author native (32×32)          | the default authoring size for HUD glyphs, blocks, and detailed items — room for full shading ramps |
| 64px  | author native (64×64), *optional* | richest hand-drawn master; upscale from 32 if the motif doesn't reward it |
| 128px | **upscale** the richest native | mod-icon size |
| 256px | **upscale** the richest native | store/hero size |

Rule: **author native up to the richest tier you'll hand-draw (32 always, 64 if
it earns it); upscale everything above it, always by an integer factor** (32px
master → 64=×2, 128=×4, 256=×8). The native tiers are *separate drawings of the
same motif* — same silhouette and palette, more fidelity as size grows, never a
different object.

Author one `.glyph` per native tier (`…-16.glyph`, `…-32.glyph`, `…-64.glyph`) in
`art/glyphs/`, render each, then mint the high tiers with `--scale-to` (a real
master, **not** a `@Nx` preview). Review renders land beside the specs
(gitignored):

```bash
python3 .ai/skills/mc-textures/scripts/glyph.py art/glyphs/<name>-32.glyph                                       # native 32
python3 .ai/skills/mc-textures/scripts/glyph.py art/glyphs/<name>-32.glyph --scale-to 128 -o art/glyphs/<name>-128.png
python3 .ai/skills/mc-textures/scripts/glyph.py art/glyphs/<name>-32.glyph --scale-to 256 -o art/glyphs/<name>-256.png
```

`--scale-to N` refuses any N that isn't an integer multiple of the source grid —
that guard protects the hard pixel edges, so don't work around it. If an upscaled
tier looks too blocky for its use, author the next native tier down rather than
switching to smooth scaling (smoothing breaks the pixel-art aesthetic).

To spec an existing raster master, run `glyph.py --from-png master.png` — it
emits a `.glyph` that re-renders pixel-identical. A grid too large or too
regular to hand-type (a 128/256 master, rings/rays/gradients) is authored as a
**generator** — an `art/glyphs/<name>.gen.py` that emits the `.glyph`, both
committed. See "Generated specs" in the `mc-textures` skill.

## Mode: animation

Add more than one `frame:` block (all the same size) plus a `frametime:`
directive (ticks/frame). The renderer emits a vertical sprite **strip** (N × N·F)
and a `<name>.png.mcmeta` sidecar — exactly the vanilla animated-texture
packaging. Design the motion as a short loop (e.g. a pulse: small → medium →
large → medium).

Rendering an animated spec writes two previews: a horizontal **filmstrip**
(`@16x.png`, every frame side-by-side — read this back to judge each frame) and
an **animated PNG** (`@16x-anim.png`, full alpha, real motion — send it to the
user to watch the loop). `--scale-to` also works on animated specs (it upscales
the whole strip + mcmeta), so an animated motif can ride the size ladder too.

---

## Step 4 — Place the master

Two locations, no duplication:

- **Source** — the `.glyph` is committed in `art/glyphs/<name>.glyph` (step 2),
  one per natively-authored ladder tier. This is the re-renderable deliverable.
- **Master** — the shipped PNG. On approval, render it **straight into the mod's
  resource tree**, `src/main/resources/assets/<mod>/textures/…` (the exact
  subpath depends on the texture's role — confirm it with the user). The PNGs
  beside the spec in `art/glyphs/` are gitignored review artifacts, never the
  shipped copy.

So the review renders (step 3) stay uncommitted beside the spec — once the glyph
is approved, re-render with `-o` pointing at the final `assets/<mod>/textures/…`
path.

Keep going until the glyph reads clearly at native size and matches the mod's
identity. Show the user the preview each iteration.
