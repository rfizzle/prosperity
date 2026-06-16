---
name: mc-textures
description: What a good Concord texture looks like and how to produce one through the .glyph pixel-art pipeline — the quality bar for icons, HUD glyphs, item/block sprites, and retextured mobs. TRIGGER when creating or editing any in-game texture or UI sprite (anything under assets/<mod>/textures/, art/*.png, a HUD/Jade glyph, a mod/store icon), when authoring or editing a .glyph spec, or when deciding whether to ship custom art versus a vanilla sprite.
---

You are making (or judging) a texture for a Concord mod. **Custom, high-quality textures
are encouraged** across the suite — there is a clean pipeline to good art (below), so the
bar is *quality and coherence*, not vanilla purity. The one hard cosmetic rule is the
vanilla **font** (never a custom font in any GUI/HUD/tooltip); textures are open. The
normative spec is **concord's `design/DESIGN-SYSTEM.md` §8** (this skill is vendored into
member repos; that doc lives in the concord repo) — this skill is the craft reference
behind it.

## When to ship custom art vs. a vanilla sprite

Default to **custom**. Ship a vanilla sprite only when it is genuinely already the right
image (e.g. a trade UI literally showing an emerald). Never *downscale* a vanilla item
into a 16px slot — vanilla item renders go muddy at that size; author a purpose-built
glyph instead.

## What "good" means

A texture is conformant when it:

- **is pixel art** — hard pixels, no anti-aliasing and no smooth gradients (dither for
  shading), a limited palette (≈3–5 colors for a glyph).
- **uses the design-system palette** — reference colors as named tokens, never raw hex
  (`python3 scripts/glyph.py --list-colors` dumps them: shared neutrals like `ink`,
  `bone`, `gold`, plus per-mod accents like `mercantile.emerald`). A mod's accents never
  appear in another mod's art (DESIGN-SYSTEM §2 rule 1).
- **reads as Minecraft** — sits naturally beside vanilla sprites at the same size. Wrap
  the motif in an `ink` (`#0a0a0a`) 1px outline so it reads against any background.
  Silhouette first, detail second.
- **is legible at its target size** — design the 16px glyph *for* 16px. If you can't tell
  what it is at native size, simplify. Don't shrink a large drawing into a small slot.
- **stays on one motif** — one object per glyph, centered, with a 1px transparent margin
  unless it intentionally bleeds to the edge.

## The pipeline

Author textures as ASCII-grid **`.glyph` specs** and let `scripts/glyph.py` rasterize
them deterministically — you lay out the character grid (which a model does reliably), the
script renders exactly those cells (no drift, no hallucinated pixels). The renderer is
stdlib-only (zero dependencies) and lives in the **concord repo**; the `/glyph` slash
command drives it end to end. In a member repo, check concord out as a sibling to run it.

Spec shape — a `legend:` mapping single chars to colors, then one or more `frame:` grids
of N×N legend chars (`.` = transparent, `#` starts a comment). One `frame:` = a static
sprite; multiple `frame:` blocks + a `frametime:` = an animated texture (vertical strip +
`.mcmeta` sidecar, exactly vanilla packaging). `--scale-to N` mints a true high-res master
by integer nearest-neighbor upscale — the honest way to fill the large tiers (128/256) of
a size ladder from a small native master. Full format + worked example: the `SPEC FORMAT`
header of `scripts/glyph.py`, and the `/glyph` command. Existing specs to copy from live
under `scripts/examples/`.

```bash
python3 scripts/glyph.py scripts/examples/<mod>/<motif>.glyph        # render + preview
python3 scripts/glyph.py --list-colors                               # named palette
python3 scripts/glyph.py SPEC.glyph --scale-to 128 -o out-128.png    # upscaled master
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

- [ ] Custom art chosen over a downscaled vanilla sprite (unless the vanilla image is
      literally correct)
- [ ] Pixel art: hard edges, limited palette, design-system named tokens, no foreign
      mod's accents
- [ ] `ink` outline, single centered motif, legible at native size
- [ ] Rendered via `scripts/glyph.py`; preview read back and judged
- [ ] `.glyph` source committed beside the master (same basename) in `art/`
- [ ] Master in `art/`, derived copies refreshed in `assets/`/`docs/`
