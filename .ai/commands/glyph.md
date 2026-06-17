---
description: Design a Concord pixel-art glyph as an ASCII grid and render it to a PNG — single sprite, a 16/32/64/128/256 size ladder, or an animated strip.
argument-hint: <motif description> [mod: meridian|mercantile|tribulation|prosperity] [sizes: 16,32,…] [animated]
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

- **One motif object**, centered and readable at native size. A HUD glyph is
  tiny — silhouette first, detail second. If you can't tell what it is at 16px,
  simplify.
- **Dark-stone outline.** Wrap the motif in an `ink` (`#0a0a0a`) 1px outline so
  it reads against any HUD background, the way vanilla item sprites do.
- **One glowing accent**, the mod's signature. The brighter accent (`*-bright`,
  `gold`, `ember`) sparingly for highlights; the base accent for the body.
- **Pixel-art discipline.** Limited palette (≈3–5 colors), hard pixels, no
  anti-aliasing or gradients — the script renders exactly the cells you write. Aim for a
  high-quality custom sprite that reads as Minecraft, not a deference to vanilla art.
- `.` is transparent. Keep at least a 1px transparent margin unless the motif
  intentionally bleeds to the edge.

Write the spec to `scripts/examples/<mod>-<motif>.glyph` (or a path the user
gives). Format — a `legend:` mapping single chars to colors, then a `frame:` (or
`grid:`) of exactly N rows × N chars. `#` begins a comment anywhere; don't use it
as a legend key. The full format (with a worked example) is documented in the
`SPEC FORMAT` header of `.ai/skills/mc-textures/scripts/glyph.py`.

## Step 3 — Render and review

```bash
python3 .ai/skills/mc-textures/scripts/glyph.py scripts/examples/<name>.glyph
```

This writes `<name>.png` (the true master) and `<name>@16x.png` (a 256px
nearest-neighbor preview). **Read the @16x preview back** and judge it honestly
against the motif. Then iterate the grid until it's right — fixing pixel art is
fast: edit the `.glyph` and re-run. Show the user the preview each iteration.

---

## Mode: a size ladder (16 / 32 / 64 / 128 / 256)

When the user wants the motif at several sizes, **author the small tiers natively
and nearest-neighbor upscale the large ones** — upscaling adds no detail (each
source pixel becomes an N×N block), and hand-authoring past ~64px is impractical
(128²=16 384 cells, 256²=65 536):

| Tier  | How                            | Why |
|-------|--------------------------------|-----|
| 16px  | author native (16×16)          | HUD/Jade silhouette — vanilla item size |
| 32px  | author native (32×32)          | favicon/recipe — room for a 2nd shading step |
| 64px  | author native (64×64), *optional* | richest hand-drawn master; upscale from 32 if the motif doesn't reward it |
| 128px | **upscale** the richest native | mod-icon size (`art/icon-128.png`) |
| 256px | **upscale** the richest native | store/hero size |

Rule: **author native up to the richest tier you'll hand-draw (32 always, 64 if
it earns it); upscale everything above it, always by an integer factor** (32px
master → 64=×2, 128=×4, 256=×8). The native tiers are *separate drawings of the
same motif* — same silhouette and palette, more fidelity as size grows, never a
different object.

Author one `.glyph` per native tier (`…-16.glyph`, `…-32.glyph`, `…-64.glyph`),
render each, then mint the high tiers with `--scale-to` (a real master, **not** a
`@Nx` preview):

```bash
python3 .ai/skills/mc-textures/scripts/glyph.py scripts/examples/<mod>/<motif>-32.glyph             # native 32 master
python3 .ai/skills/mc-textures/scripts/glyph.py scripts/examples/<mod>/<motif>-32.glyph --scale-to 128 -o scripts/examples/<mod>/<motif>-128.png
python3 .ai/skills/mc-textures/scripts/glyph.py scripts/examples/<mod>/<motif>-32.glyph --scale-to 256 -o scripts/examples/<mod>/<motif>-256.png
```

`--scale-to N` refuses any N that isn't an integer multiple of the source grid —
that guard protects the hard pixel edges, so don't work around it. If an upscaled
tier looks too blocky for its use, author the next native tier down rather than
switching to smooth scaling (smoothing breaks the pixel-art aesthetic).

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

The design system masters a HUD glyph at `art/hud-icon-16.png` and a mod icon at
`art/icon-128.png` in the *mod's* repo, with derived copies in
`docs/`/`assets/`. This concord repo is the design hub, not a mod — generate here
under `scripts/examples/` for review; when the user approves, the final PNGs
belong in the target mod's `art/`. Confirm each destination with the user rather
than assuming.

**Commit the `.glyph` beside each master.** Every committed texture ships its source: the
`.glyph` lands next to its PNG with the same basename
(`art/hud-icon-16.png` ↔ `art/hud-icon-16.glyph`; one `.glyph` per natively-authored
ladder tier), so the master stays re-renderable for minor edits.

Keep going until the glyph reads clearly at native size and matches the mod's
identity. Show the user the preview each iteration.
