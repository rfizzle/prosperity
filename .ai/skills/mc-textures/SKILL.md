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
  (`python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors` groups them by owner:
  shared neutrals like `ink` and `bone`, shared material tones like `metal.gold`, and
  per-mod accents like `mercantile.emerald`). A mod's accents never appear in another
  mod's art, and the bare spelling of one is still that mod's — `crimson` is
  Tribulation's exactly as `tribulation.crimson` is, and the renderer says so either
  way. Only the `metal.` tones are free to appear anywhere: a brass pivot is a
  material, not a brand. The tonal steps a shaded surface needs are **ramp steps off
  those tokens** — `mercantile.emerald+1` toward the highlight, `mercantile.emerald-2`
  toward shadow — so shading stays inside the palette instead of scattering raw hex
  through the legend. `--ramp <token>` prints a ready-made ramp as paste-ready legend lines, and
  `--snap-palette` reports the nearest token for each raw-hex entry in an existing legend;
  steps cool and saturate going down, warm and pale going up, which is what makes a
  ramp read as light on a form rather than as a dimmer switch. A raw-hex legend entry
  draws a note; a master that genuinely sits outside the system — a transcribed
  raster, a hand-painted neutral like `examples/skull-shaded.glyph` — says so with
  `palette: free` instead of quietly ignoring the rule.
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
  margin. The margin earns its keep on the item/block atlas, where it stops a neighbour
  bleeding in, and in a slot, where it gives the item room. Art the margin doesn't serve
  says so in the spec with an `edge:` line (see **Say what the texture is**) — a lock
  whose shackle grows out of the frame is `edge: shaped`, and a spark that spends every
  one of its 8×8 pixels on the motif is a `kind: particle`. *Block* textures are the
  standing exception — they bleed to all four edges and tile (see below).

## Block textures: tiling and faces

A block texture isn't a free-standing motif — it repeats across a surface and wraps a cube,
so the centered-motif and transparent-margin rules above are *sprite* rules. A block
**bleeds to all four edges** and must tile.

- **Side faces tile and join at the corners.** Design the sides as one texture whose **right
  edge continues into its left edge** and **top into bottom** with no visible seam when
  copies sit adjacent. Going around the block, each side's right edge meets the next side's
  left edge — so a side that tiles cleanly left-to-right also corners cleanly. Every
  `kind: block` texture is measured for this automatically: the render reports what share
  of each join jumps beyond the texture's own interior gradients, and warns past a quarter
  of the join's length. Note that a *hard* edge at the seam is not itself a fault — vertical
  stripes tile perfectly — which is why the seam is judged against the jumps the texture
  already contains rather than against zero. `--tile-preview` adds two pictures: a 2×2
  `@2x2.png` (does the pattern repeat and corner correctly) and a seam-centred `@seam.png`
  (the texture rolled by half, so both joins cross the middle of the image — a seam shows
  up there as a line through the centre). A full-bleed face that never repeats is a
  `kind: cap` or a `kind: ui` (a panel), not a block — what makes a cap a cap is that it
  never tiles, so a decorative side face that deliberately doesn't repeat is one too, not
  just a block's top and bottom.
- **Top and bottom are separate textures**, not the side repeated. Design them to agree with
  the **top and bottom edges of the side faces** so the seam where a side meets the cap
  reads continuously — the side's top trim lines up with the top face's perimeter, and
  likewise at the bottom.

`examples/block-stone-bricks.glyph` is a tileable **side** reference (running-bond brick:
the offset courses carry the bond across the left/right seam and corners).

## Say what the texture is

Every spec declares a **`kind:`** — because the same pixels mean different things and
each kind earns different checks (`--list-kinds` prints the table):

| kind | what it is | checked for |
|---|---|---|
| `sprite` | a centred motif on transparency — items, HUD glyphs, pips | transparent margin, `ink` outline, flat fill |
| `particle` | a spark, mote, or pip whose motif fills the canvas it is given | flat fill |
| `block` | a tiling side face; repeats against copies of itself | full bleed, seam continuity, flat fill |
| `cap` | a full-bleed face that never repeats — a block top or bottom | full bleed, flat fill |
| `ui` | a panel, plate, or 9-slice frame | nothing geometric — a flat field is the design |
| `atlas` | a sheet read through UV sub-windows, never drawn as one face | flat fill — nothing geometric, since no window is the whole canvas |
| `icon` | mod, store, or hero art, read in a launcher rather than on a HUD | flat fill |

A spec with no `kind:` is classified from its edge geometry, which cannot tell a tiling
block from a cap, a UI plate, or an atlas — they all bleed — so it gets a note asking for
the declaration and only the checks that hold regardless.

A kind implies what the motif does at the canvas border: a sprite sits inside a margin, a
block or a cap bleeds. Art that means to do otherwise says so with an **`edge:`** line —
`margin` (a clean 1px transparent ring), `shaped` (the motif deliberately meets some
edges), or `bleed` (opaque all the way round). Declaring it settles the question the way
`palette: free` settles the palette one: the tool asks once, the spec answers, and the
answer is measured against the grid rather than taken on trust — a spec claiming
`edge: bleed` over art that doesn't bleed is told so. The one thing a declaration cannot
do is excuse a block from bleeding: a side face that stops short of its border shows the
void where copies meet, whatever the spec intended.

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
python3 $G --ramp mercantile.emerald  # tonal ramp as legend lines
python3 $G SPEC.glyph --scale-to 128 -o out-128.png   # upscaled master
python3 $G SPEC.glyph --verify        # shipped master still matches the spec?
python3 $G --from-png MASTER.png      # raster -> .glyph spec (transcription)
```

Always **read the rendered `@Nx` preview back** and judge it honestly against the motif,
then iterate the grid — fixing pixel art is fast (edit the `.glyph`, re-run). The render
also measures the grid against this quality bar and says where it falls short. **Warnings**
are quality-bar violations — a surface left as a flat fill, a silhouette with no `ink`
outline, a border neither the spec's `kind:` nor its `edge:` accounts for, a join that
would seam when tiled, an animation frame identical to the one before it, a legend that
mixes two mods' accents. The mix is what the renderer can see: a glyph built entirely
from a foreign mod's accents holds to one identity and passes, so that one is on you.
**Notes** are advisory — raw hex where a token would do, a missing `kind:`. Fix the
warnings; work through the notes as you touch the art. It reports the motif's detached pieces too — a
glint or a hanging link is deliberate, a stray pixel is not, and only you can tell which
you meant.

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

A generator that shells out to ImageMagick passes `-define png:exclude-chunk=date,tIME`.
Without it `convert` stamps wall-clock metadata into the PNG, so re-running the generator
to confirm the art is still correct dirties the file with a timestamp-only diff —
identical image bytes, different file, and a `git status` that lies about what changed.

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
  each frame as its own PNG with **no strip and no `.mcmeta`**. Record that in the spec
  with **`frames: split`** (`strip` is the default): packaging is a property of the
  deliverable, and a `--verify` that had to be reminded of it on the command line would
  look for a strip nobody meant to write.

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

Every spec carries a **`ships:`** line naming that shipped path — one per shipped file,
so a size ladder declares each tier (`ships: docs/img/icon-128.png 128`) — and that is
what holds the rule up: `glyph.py SPEC.glyph --verify` re-renders the spec and compares
it pixel for pixel against the assets that shipped, and `glyph.py --verify-all` runs
that across every spec under `art/glyphs/`, at any depth (exit non-zero, so it belongs in
CI). A hand-patched PNG, a stale asset behind an edited spec, or a `.mcmeta` whose
frametime no longer matches all fail the check as **drift**; a spec that no longer parses
fails as **malformed**; and a spec whose render needs a tool this machine doesn't have
fails as **blocked** — the art was never compared either way, so passing it would be a
green nobody earned. The three are counted and labelled apart so a CI wrapper can say
which happened. A spec with no `ships:` line is reported as unlinked — it has no declared
deliverable, so nothing holds it to anything.

A tier runs either way from the native grid. Upward is always nearest-neighbor: an
integer multiple, mechanically enlarged, which is how the 128/256 tiers of a texture
ladder are minted. Downward is the direction a mod-icon ladder runs — one 512 master
deriving its 256 and 128 copies — and how it resamples is the spec's call, declared with
**`downscale:`**:

- **`box`** (the default) is the built-in alpha-weighted area average. It needs no
  external tool and is exact at a whole factor: a master that is itself an upscale of a
  smaller grid averages straight back to that grid's pixels.
- **`point`, `triangle`, `catrom`, `mitchell`, `lanczos`** hand the frame to
  **ImageMagick**, which is worth reaching for when the master is painted or traced
  rather than authored cell by cell, and which resamples any ratio rather than only whole
  factors. Either entry point serves — v7's `magick` or v6's `convert` — and which one a
  machine has cannot change the shipped file: the resized pixels come back and are
  re-encoded by the renderer's own writer, so ImageMagick's PNG bytes are never what
  ships. A `convert` that turns out to be some other program (on Windows it is the
  system's filesystem tool) is rejected rather than run.

The spec picks the engine so the machine can't: were it chosen by whatever happens to be
installed, two checkouts would mint different pixels and each would call the other's
output drift. The trade for an ImageMagick tier is that its pixels are ImageMagick's — a
version that resamples differently reads as drift, and a repo without ImageMagick reports
that ladder as blocked rather than verified. A spec that wants the check to outlive its
toolchain stays on `box`.

## Quick checklist

- [ ] Pixel art: hard edges, limited palette, design-system named tokens (ramp steps
      for the tonal range, not raw hex), no foreign mod's accents under either
      spelling — a shared material belongs to `metal.`, not to a borrowed accent
- [ ] `ink` outline, single centered motif, legible at native size
- [ ] `kind:` declared, and an `edge:` line wherever the motif meets the border on purpose
- [ ] Rendered via `.ai/skills/mc-textures/scripts/glyph.py`; preview read back and judged
- [ ] Animated? Strip + `.mcmeta` only when the atlas animates it; a code-bound texture
      declares `frames: split` and ships standalone frames (no strip, no `.mcmeta`)
- [ ] `.glyph` source committed in `art/glyphs/`; the shipping master in
      `assets/<mod>/textures/…` (renders in `art/glyphs/` are gitignored throwaways)
- [ ] `ships:` names that shipping path, and `--verify` passes on it
- [ ] Generated spec? The `.gen.py` committed beside the `.glyph` it emits; edits go
      through the generator, never the emitted grid
- [ ] Derived web `docs/` copies re-rendered from the spec, not hand-copied
