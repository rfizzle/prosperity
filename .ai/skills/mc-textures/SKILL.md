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
  left edge — so a side that tiles cleanly left-to-right also corners cleanly. Verify by
  eye: replicate the tile in a 2×2 grid and check the seams and the shared corner.
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
full-bleed side face), and a `skull-shaded` (the **shaded-form quality bar**: a 32px
glyph rendered with tonal ramps, rim light, and a selective outline — ~49 colors over a
few base hues, the opposite of a flat fill). The concord repo's `scripts/examples/` holds
more, mod-specific ones.

```bash
G=.ai/skills/mc-textures/scripts/glyph.py
python3 $G SPEC.glyph                  # render + preview
python3 $G --list-colors              # named palette
python3 $G SPEC.glyph --scale-to 128 -o out-128.png   # upscaled master
```

Always **read the rendered `@Nx` preview back** and judge it honestly against the motif,
then iterate the grid — fixing pixel art is fast (edit the `.glyph`, re-run).

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

Every committed texture master ships its `.glyph` source **beside it**, same basename:
`art/hud-icon-16.png` ↔ `art/hud-icon-16.glyph` (a size ladder commits one `.glyph` per
natively-authored tier). The `.glyph` is the source of truth — minor edits re-render in
seconds instead of hand-patching pixels, and the master is reproducible from the spec
alone. Masters live in the mod's `art/`; `assets/` and web `docs/` hold derived copies.
Re-touching a texture recreates it through its `.glyph`.

## Quick checklist

- [ ] Pixel art: hard edges, limited palette, design-system named tokens, no foreign
      mod's accents
- [ ] `ink` outline, single centered motif, legible at native size
- [ ] Rendered via `.ai/skills/mc-textures/scripts/glyph.py`; preview read back and judged
- [ ] Animated? Strip + `.mcmeta` only when the atlas animates it; a code-bound texture
      ships `--split-frames` standalone frames (no strip, no `.mcmeta`)
- [ ] `.glyph` source committed beside the master (same basename) in `art/`
- [ ] Master in `art/`, derived copies refreshed in `assets/`/`docs/`
