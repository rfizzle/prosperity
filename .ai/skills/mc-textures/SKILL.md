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

- **is pixel art** — hard pixels, no anti-aliasing and no smooth gradients (dither for
  shading), a limited palette (≈3–5 colors for a glyph).
- **uses the design-system palette** — reference colors as named tokens, never raw hex
  (`python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors` dumps them: shared neutrals like `ink`,
  `bone`, `gold`, plus per-mod accents like `mercantile.emerald`). A mod's accents never
  appear in another mod's art.
- **reads as Minecraft** — sits naturally beside vanilla sprites at the same size. Wrap
  the motif in an `ink` (`#0a0a0a`) 1px outline so it reads against any background.
  Silhouette first, detail second.
- **is legible at its target size** — design the 16px glyph *for* 16px. If you can't tell
  what it is at native size, simplify. Don't shrink a large drawing into a small slot.
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
sprite; multiple `frame:` blocks + a `frametime:` = an animated texture (vertical strip +
`.mcmeta` sidecar, exactly vanilla packaging). `--scale-to N` mints a true high-res master
by integer nearest-neighbor upscale — the honest way to fill the large tiers (128/256) of
a size ladder from a small native master. Full format + worked example: the `SPEC FORMAT`
header of `.ai/skills/mc-textures/scripts/glyph.py`, and the `/glyph` command. Reference
specs ship beside this skill under `.ai/skills/mc-textures/examples/` — a `sprite-coin`
(centered motif, `ink` outline, transparent margin) and a `block-stone-bricks` (tileable
full-bleed side face). The concord repo's `scripts/examples/` holds more, mod-specific ones.

```bash
G=.ai/skills/mc-textures/scripts/glyph.py
python3 $G SPEC.glyph                  # render + preview
python3 $G --list-colors              # named palette
python3 $G SPEC.glyph --scale-to 128 -o out-128.png   # upscaled master
```

Always **read the rendered `@Nx` preview back** and judge it honestly against the motif,
then iterate the grid — fixing pixel art is fast (edit the `.glyph`, re-run).

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
- [ ] `.glyph` source committed beside the master (same basename) in `art/`
- [ ] Master in `art/`, derived copies refreshed in `assets/`/`docs/`
