---
name: mc-textures
description: What a good Concord texture looks like and how to produce one through the .glyph pixel-art pipeline — the craft reference and quality bar for icons, HUD glyphs, item/block sprites, and retextured mobs. TRIGGER when creating or editing any in-game texture or UI sprite (anything under assets/<mod>/textures/, art/*.png, a HUD/Jade glyph, a mod/store icon), or when authoring or editing a .glyph spec.
---

You are making (or judging) a **custom** texture for a Concord mod — this skill is the craft
reference for making a good one. One craft rule to carry in: never *downscale* a vanilla
item render into a small slot — it goes muddy; author a purpose-built glyph at the target
size instead.

## What "good" means

A texture is conformant when it:

- **is pixel art with rendered form** — hard pixels (no blur), but shade for volume:
  every surface gets a 3–5 step tonal ramp from one base hue (highlight → midtone →
  core shadow → occlusion) plus a rim light, with selective interior anti-aliasing and
  dithering. "Limited palette" caps the base *hues* (≈3–5), not the *tones* — a shaded
  32px sprite legitimately runs 20–50 colors. A flat single-tone fill reads as a
  cartoony sticker; that is the failure mode to design against.
- **uses the design-system palette** — reference colors as named tokens, never raw hex
  (`python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors` dumps them: shared neutrals like `ink`,
  `bone`, `gold`, plus per-mod accents like `mercantile.emerald`). A mod's accents never
  appear in another mod's art.
- **reads as Minecraft** — sits naturally beside vanilla sprites at the same size. Wrap
  the motif in an `ink` (`#0a0a0a`) 1px outline so it reads against any background.
  Silhouette first, detail second.
- **is legible at its target size** — design the glyph *for* the size it ships at, and
  pick that authoring size by the asset's role: author at **32px** wherever detail reads
  (HUD glyphs, blocks, decorated/hero items) and let the slot display it small; reserve
  native 16px for tiny pips or motifs that gain nothing from extra detail. If you can't
  tell what it is at native size, simplify the shape. Don't *resample* a large drawing
  down into a small slot — it goes muddy; author for the slot instead.
- **stays on one motif (sprites)** — one object per glyph, centered, with a 1px transparent
  margin unless it intentionally bleeds to the edge. *Block* textures are the exception —
  they bleed to all four edges and tile (see below).

## Block textures: tiling and faces

A block texture isn't a free-standing motif — it repeats across a surface and wraps a cube,
so the centered-motif and transparent-margin rules above are *sprite* rules. A block
**bleeds to all four edges** and must tile.

- **Side faces tile and join at the corners.** Design the sides as one texture whose **right
  edge continues into its left edge** and **top into bottom** with no visible seam when
  copies sit adjacent. Going around the block, each side's right edge meets the next side's
  left edge — so a side that tiles cleanly left-to-right also corners cleanly. Verify with
  `--tile-preview`: it renders a 2×2 tiled `@2x2.png` — read it back and check the seams
  and the shared corner.
- **Top and bottom are separate textures**, not the side repeated. Design them to agree with
  the **top and bottom edges of the side faces** so the seam where a side meets the cap
  reads continuously — the side's top trim lines up with the top face's perimeter, and
  likewise at the bottom.

`examples/block-stone-bricks.glyph` is a tileable **side** reference (running-bond brick:
the offset courses carry the bond across the left/right seam and corners).

## The pipeline

Author textures as ASCII-grid **`.glyph` specs** and let
`.ai/skills/mc-textures/scripts/glyph.py` rasterize them deterministically — you lay out
the character grid (which a model does reliably), the script renders exactly those cells
(no drift, no hallucinated pixels). The renderer is stdlib-only (zero dependencies) and
ships beside this skill, so it runs anywhere the skill is vendored; the `/glyph` slash
command drives it end to end.

Spec shape — a `legend:` mapping single chars to colors, then one or more `frame:` grids
of N×N legend chars (`.` = transparent, `#` starts a comment). One `frame:` = a static
sprite; multiple `frame:` blocks + a `frametime:` = an animated texture — packaged as a
vanilla strip + `.mcmeta`, or as standalone per-frame PNGs for a code-driven texture (see
**Animated textures** below). `--scale-to N` mints a true high-res master
by integer nearest-neighbor upscale — the honest way to fill the large tiers (128/256) of
a size ladder from a small native master. Full format + worked example: the `SPEC FORMAT`
header of `.ai/skills/mc-textures/scripts/glyph.py`, and the `/glyph` command. Reference
specs ship beside this skill under `.ai/skills/mc-textures/examples/` — a `sprite-coin`
(centered motif, `ink` outline, transparent margin), a `block-stone-bricks` (tileable
full-bleed side face), a `skull-shaded` (the **shaded-form quality bar**: a 32px
glyph rendered with tonal ramps, rim light, and a selective outline — ~49 colors over a
few base hues, the opposite of a flat fill), and an `anim-sparkle` (the animated-spec
reference: four pulse frames + `frametime:`).

```bash
G=.ai/skills/mc-textures/scripts/glyph.py
python3 $G SPEC.glyph                  # render + preview
python3 $G --list-colors              # named palette
python3 $G SPEC.glyph --scale-to 128 -o out-128.png   # upscaled master
python3 $G --from-png MASTER.png      # raster -> .glyph spec (transcription)
```

Always **read the rendered `@Nx` preview back** and judge it honestly against the motif,
then iterate the grid — fixing pixel art is fast (edit the `.glyph`, re-run).

## Generated specs: `.gen.py` authoring

Some grids are impractical to hand-type — a 128/256px master (16k–65k cells), a
geometrically regular motif (rings, radial rays, dithered gradients), or a spec that
embeds an existing raster. For those, author a **generator**: a Python script at
`art/glyphs/<name>.gen.py` that computes the grid and writes `art/glyphs/<name>.glyph`.
Both files are committed — the `.gen.py` is the source of the `.glyph`, and the `.glyph`
stays the render input (`glyph.py` treats it like any hand-authored spec). Re-touching a
generated texture means editing the generator, re-running it, and re-rendering; never
hand-patch its emitted grid. Two flavors:

- **Procedural** — the script computes pixels mathematically (draw the ring, place the
  rays, dither the gradient) and assigns legend chars itself. Most generated logos and
  icons are this.
- **Image transcription** — `glyph.py --from-png art/<name>.png` turns a finished raster
  master into a spec directly (stdlib PNG decode, no external tools; square, 8-bit,
  non-interlaced). Fully transparent pixels become `.`; each remaining distinct color is
  assigned the next char from a fixed token pool, in first-seen order. Legend colors are
  emitted as raw hex (`#RRGGBB`, or `#RRGGBBAA` when partial alpha exists) — the
  named-token rule governs hand-authored accents, not transcribed masters. The emitted
  spec re-renders pixel-identical to the input, verified before it is written. A custom
  `.gen.py` reads pixels itself only when transcription composes with procedure —
  stamping a raster into a computed frame.

Transcription is how a raster that predates its spec joins the repeatability rule: run
it once, review the emitted `.glyph`, and from then on the spec is the source of truth.

## Animated textures: pick the packaging by who animates it

An animated glyph (2+ `frame:` blocks + a `frametime:`) ships one of two ways — chosen by
*what advances the frames*, not by preference:

- **The vanilla atlas animates it → strip + `.mcmeta`** (the default output). A block or
  item sprite sits on the block/item atlas, and Minecraft's own texture-animation system
  cycles the frames from the `.mcmeta`. A 16×N vertical strip beside a `<name>.png.mcmeta`
  is the correct, idiomatic packaging there.
- **Your code animates it → standalone per-frame PNGs** (`glyph.py --split-frames` →
  `<name>_0.png`, `<name>_1.png`, …). A texture you bind yourself — a custom `RenderType`
  billboard/overlay, a HUD icon, a GUI blit — is *not* on the atlas, so the vanilla
  animator never runs: your code picks the frame index and samples the whole texture. Ship
  each frame as its own PNG with **no strip and no `.mcmeta`**.

**Never hand-slice frames out of a directly-bound strip.** The `.mcmeta` still declares "N
frames of 16×16," and a resource/texture mod that honours that declaration on a non-atlas
texture collapses your 16×N strip into an animated 16×16 sprite — your per-frame UV window
then samples a sliver of a single frame and stretches it over the quad, so the animation
renders as a vertical smear. Standalone frames carry nothing for a loader to reinterpret.
Bind the frame whose index your own tick counter selects, and take the cadence from the
spec's `frametime`.

## Companion `.glyph` files (the repeatability rule)

`art/glyphs/` holds the committed `.glyph` source of truth (a size ladder commits one
`.glyph` per natively-authored tier; a generated spec commits its `.gen.py` beside the
`.glyph` it emits). Rendered PNGs beside the specs are **not** kept:
render there for review — the PNGs, GIFs, and `.mcmeta` in `art/glyphs/` are throwaway
and gitignored — then ship the final PNG to `src/main/resources/assets/<mod>/textures/…`,
the only committed copy (web `docs/` copies are likewise rendered from the spec). The
`.glyph` re-renders reproducibly, so re-touching a texture means editing the spec and
re-rendering — never hand-patching pixels.

## Quick checklist

- [ ] Pixel art: hard edges, limited palette, design-system named tokens, no foreign
      mod's accents
- [ ] `ink` outline, single centered motif, legible at native size
- [ ] Rendered via `.ai/skills/mc-textures/scripts/glyph.py`; preview read back and judged
- [ ] Animated? Strip + `.mcmeta` only when the atlas animates it; a code-bound texture
      ships `--split-frames` standalone frames (no strip, no `.mcmeta`)
- [ ] `.glyph` source committed in `art/glyphs/`; the shipping master in
      `assets/<mod>/textures/…` (renders in `art/glyphs/` are gitignored throwaways)
- [ ] Generated spec? The `.gen.py` committed beside the `.glyph` it emits; edits go
      through the generator, never the emitted grid
- [ ] Derived web `docs/` copies re-rendered from the spec, not hand-copied
