#!/usr/bin/env python3
"""Render an ASCII glyph spec into a Minecraft-ready PNG sprite.

The idea: a language model lays out a fixed character grid far more reliably
than it emits binary image data. So a glyph is authored as a *spec* — a color
`legend` (one character -> one color) plus one or more square `frame` grids of
those characters, one character per pixel — and this script deterministically
rasterizes it to a true-size PNG plus a scaled nearest-neighbor preview you can
actually see.

Multiple frames produce an **animated** texture. Default packaging is a
vertical sprite strip (16 wide × 16·N tall) plus a `<name>.png.mcmeta` sidecar,
exactly as vanilla Minecraft animated textures are packaged — right for a block
or item sprite the vanilla atlas animates for you. For a texture your own code
binds and advances a frame at a time (a custom render type, a HUD icon, a GUI
blit), pass `--split-frames` to write each frame as a standalone `<name>_<i>.png`
with no strip and no `.mcmeta`, so nothing in a resource pipeline can reinterpret
the strip as a 16×16 animated sprite and break your hand-sliced UVs. Two previews
come with either packaging: a horizontal filmstrip (every frame side-by-side, to
eyeball each one) and an `@Nx-anim` **animated PNG** (full RGBA, true alpha, real
motion — to watch the loop).

`--scale-to N` mints a real master at another tier of the ladder: a multiple of
the native grid upscales nearest-neighbor — the honest way to fill the large
tiers of a 16/32/64/128/256 ladder — and a smaller N downscales, which is the
direction a mod-icon ladder runs (a 512 master deriving its 256 and 128 copies).
Both work for static glyphs and animated strips alike.

The downscale is what the spec's `downscale:` line selects, and the *spec*
selects it so that the machine can't: `box` (the default) is a built-in
alpha-weighted area average, exact at an integer factor and reproducible
anywhere Python runs; any other filter — `point`, `triangle`, `catrom`,
`mitchell`, `lanczos` — hands the frame to **ImageMagick**, which also lifts the
integer-factor restriction. What that buys is a better-looking ladder for a
painted or traced master; what it costs is that those pixels are ImageMagick's,
so `--verify` will report drift if the installed version resamples differently.
A spec that wants the check to outlive its toolchain stays on `box`.

`--from-png IN.png` runs the pipeline in reverse: it transcribes a finished
raster master into a .glyph spec (transparent pixels -> '.', each distinct
color -> a legend token with a raw-hex entry) so a texture that predates its
spec joins the repeatability rule. The emitted spec re-renders pixel-identical
— verified before it is written.

Zero dependencies: every PNG/APNG is encoded with the stdlib (`zlib` + manual
chunks), so this runs anywhere Python 3 does, no `pip install` required. One
optional external tool: **ImageMagick**, and only for a spec that asks for a
resampling filter the built-in downscale doesn't do (see `downscale:` below).

SPEC FORMAT
-----------
A color `legend:` (shared across all frames) followed by one or more `frame:`
grids. `grid:` is an accepted alias for a single `frame:`. Lines starting with
`#` are comments *outside* a grid; blank lines are ignored. Inside a grid every
non-blank line is a row.

    # prosperity — unlooted-chest sparkle (animated)
    size: 16                # optional; inferred from the grid if omitted
    frametime: 6            # ticks per frame (animated specs only; default 6)
    interpolate: false      # optional; blend between frames
    kind: sprite            # what this texture IS — sprite | particle | block |
                            # cap | ui | atlas | icon. Picks which checks apply
                            # (--list-kinds)
    edge: shaped            # optional; what the motif does at the border when
                            # that isn't its kind's default — margin | shaped |
                            # bleed. Declared once, still measured
    palette: tokens         # default; 'free' for a deliberately off-palette
                            # master (a transcribed raster, a hand-painted one)
    frames: split           # animated specs only; 'strip' (default) ships a
                            # strip + .mcmeta, 'split' ships per-frame PNGs
    downscale: lanczos      # how a `ships:` tier *smaller* than the grid is
                            # resampled — 'box' (default, built-in, exact) or an
                            # ImageMagick filter, which needs ImageMagick
    ships: src/main/resources/assets/prosperity/textures/item/sparkle.png
    ships: docs/img/sparkle-128.png 128   # one line per shipped tier; the
                                          # optional size is that tier — a
                                          # multiple of the grid upscales, a
                                          # divisor averages down

    legend:
      . transparent         # '.' is transparent by convention
      g prosperity.gold     # #ffd700  (hex, or a named Concord token)
      G prosperity.gold+1   # a ramp step off that token — one tone lighter
      d prosperity.cyan-2   # ... and two tones deeper, for the core shadow
      K ink                 # #0a0a0a outline

    frame:                  # frame 1 — <size> rows of <size> legend chars
      ................
      ... (16 rows) ...
    frame:                  # frame 2
      ................
      ... (16 rows) ...

Colors may be:  `transparent` / `none`,  `#RGB`,  `#RRGGBB`,  `#RRGGBBAA`,
a named token from NAMED_COLORS (the Concord design-system palette), or a
**ramp step** off one of those tokens — `<token>+N` toward the highlight,
`<token>-N` toward shadow. Ramp steps are what let a shaded glyph hold to the
named-token rule: the palette names a mod's accents, and the 3–5 tonal steps a
surface needs are derived from them rather than hand-typed as raw hex.
`--ramp <token>` prints a ready-made ramp as legend lines.

Every accent belongs to the mod it is named for, and the shorter spelling of one
belongs there too — `crimson` is Tribulation's exactly as `tribulation.crimson`
is, and a legend that mixes it with another mod's accent is told so either way.
The `metal.` tones are the exception: a material any mod may draw with, owned by
none of them. `--list-colors` groups the palette by who owns what.

`ships:` records where a rendered master belongs in the mod's resource tree —
one line per shipped file, so a size ladder declares every tier it mints from
the one native grid. It is what `--verify` checks against, and what makes the
repeatability rule enforceable: the `.glyph` is the source of truth, the shipped
PNG is derived, and drift between them fails the build instead of passing as a
silent edit.

USAGE
-----
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph                 # -> SPEC.png (+ .mcmeta if animated) + preview
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --split-frames  # -> SPEC_0.png, SPEC_1.png … (code-driven anim; no strip/.mcmeta)
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph -o art/marker.png
    python3 .ai/skills/mc-textures/scripts/glyph.py - < SPEC.glyph             # spec on stdin
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --preview-scale 24 --no-preview
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --tile-preview  # + 2×2 tiled seam check (block textures)
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --verify        # shipped master still matches the spec? (uses ships:)
    python3 .ai/skills/mc-textures/scripts/glyph.py --verify-all               # ... same check over every spec under art/glyphs/, at any depth
    python3 .ai/skills/mc-textures/scripts/glyph.py --ramp mercantile.emerald  # tonal ramp as paste-ready legend lines
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --snap-palette  # nearest token for each raw-hex entry
    python3 .ai/skills/mc-textures/scripts/glyph.py --list-kinds               # texture kinds and the checks each earns
    python3 .ai/skills/mc-textures/scripts/glyph.py --from-png MASTER.png      # raster -> .glyph spec (transcription)
    python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors              # dump the named palette

Every render prints read-back stats and findings at two severities. A
**warning** is a quality-bar violation: a flat fill, a border neither the spec's
`kind:` nor its `edge:` accounts for, an unoutlined silhouette, a join that
would seam when tiled, an animation frame identical to the one before it, a
legend mixing two mods' accents. A **note** is advisory: raw hex where a token
would do, an undeclared kind. Keeping them apart is what stops a hundred palette
notes from burying one real seam. (Malformed specs are neither — they fail
outright.)

Which checks run depends on `kind:`, because the same pixel geometry means
different things: a tiling block side, a single cap, a UI plate and a UV sheet
all bleed to every edge, but only the first can seam, and only the plate is
allowed a flat field. A spec that declares no kind is classified from its edges
and told so. Where the art means to do something other than its kind's default
at the border — a mote that spends all 4×4 of its pixels on the motif, a lock
whose shackle grows out of the frame — an `edge:` line records that once, in
the spec, the way `palette: free` records a deliberate off-palette master. The
declaration is checked against the grid rather than trusted: it settles the
question, it doesn't hide the answer.

A seam is judged against the texture's own gradients rather than in absolute
terms, because that is what the eye does: vertical stripes jump hard at the
wrap and tile perfectly, since the same jump occurs inside; a smooth gradient
jumps no harder and shows a hard line, since nothing inside it jumps at all.

Thresholds are calibrated against the shipped reference specs, so a warning
means something is wrong rather than merely unusual. Detached pieces are
reported but never warned about: real art carries them deliberately — a glint,
a hanging chain link — and nothing in the grid separates those from a stray
pixel, so the count is offered for review instead of being called a defect.

The preview is sized to be *read back*, not blown up: the default factor is
capped so a preview lands near 512px, which is why a 16px sprite previews at
×16 and a 128px master at ×4. Past 64px the ASCII silhouette dump is skipped —
thousands of block characters say less than the PNG does.
"""

import argparse
import json
import math
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

# Concord design-system palette (DESIGN-SYSTEM.md §1–§2, §8). Legend entries may
# reference these names instead of raw hex.
#
# Ownership is what the mixed-accent check reads, and it is derived from this
# table rather than declared beside it: a `<mod>.` prefix names the mod whose
# identity the accent carries, and a bare alias is owned by whichever namespaced
# entries share its exact hex. So `crimson` is Tribulation's however it is
# spelled, and an accent added here teaches the check about itself for free.
#
# The `metal.` namespace is the exception §2 rule 1 allows: a material tone any
# mod may draw with. Gold qualifies because two mods already list it as an accent
# (§2) — identity there is carried by the accent *pair*, not by the metal.
SHARED_NAMESPACES = ("metal",)

NAMED_COLORS = {
    # shared neutrals — the text/surface roles, owned by no mod
    "ink": "#0a0a0a",
    "card": "#1a1a1a",
    "elevated": "#222222",
    "bone": "#e8e0d4",
    "ash": "#a89f93",
    "smoke": "#6b6359",
    # shared material tones — a material rather than a brand; never an accent
    "metal.gold": "#ffd700",
    "metal.gold-deep": "#daa520",
    # per-mod accents
    "meridian.purple": "#7b2fbe",
    "meridian.gold": "#ffd700",
    "meridian.gold-deep": "#daa520",
    "mercantile.emerald": "#50c878",
    "mercantile.emerald-bright": "#6ddb94",
    "tribulation.crimson": "#dc143c",
    "tribulation.ember": "#ff6b35",
    "prosperity.gold": "#ffd700",
    "prosperity.gold-deep": "#daa520",
    "prosperity.cyan": "#4eeaed",
    "respite.moonlight": "#7c8ee8",
    "respite.moonlight-bright": "#a6b4ff",
    "respite.candleglow": "#f2c14e",
    "respite.candleglow-pale": "#ffe29a",
    "distillation.magenta": "#c44dcc",
    "distillation.elixir": "#da79e3",
    "distillation.copper": "#e77c56",
    "distillation.glass": "#afc6ce",
    "cultivation.amber": "#d9a441",
    "cultivation.harvest": "#edc35c",
    "cultivation.leaf": "#7cb342",
    "cultivation.sprout": "#a5d66a",
    "instinct.rose": "#e5709b",
    "instinct.rose-glow": "#f5a8c8",
    "instinct.russet": "#b8622b",
    "instinct.tan": "#d98a4a",
    # bare aliases — the same colours spelled without a prefix. Convenience
    # only: the shorter spelling changes nothing about who owns the colour, and
    # --list-colors names the owner of each.
    "emerald": "#50c878",
    "emerald-bright": "#6ddb94",
    "crimson": "#dc143c",
    "ember": "#ff6b35",
    "diamond": "#4eeaed",
    "arcane": "#7b2fbe",
    "gold": "#ffd700",
    "moonlight": "#7c8ee8",
    "magenta": "#c44dcc",
    "elixir": "#da79e3",
    "candleglow": "#f2c14e",
    "amber": "#d9a441",
    "leaf": "#7cb342",
}


def _alias_targets(token):
    """The namespaced entries a bare token is a shorter spelling of.

    Empty for a namespaced token and for a shared neutral, which names a role no
    mod has an accent for.
    """
    if "." in token:
        return ()
    hex_ = (NAMED_COLORS.get(token) or "").lower()
    return tuple(n for n, v in NAMED_COLORS.items()
                 if "." in n and v.lower() == hex_ and hex_)


def _accent_owners(token):
    """The mods whose identity `token` carries — empty for anything shared.

    A `<mod>.` prefix says it outright. A bare alias is resolved through its hex,
    because the alias block spells accents without their prefix (`arcane` *is*
    `meridian.purple`) and a legend must not shed the ownership rule by picking
    the shorter spelling. A bare token that also names a shared material tone is
    shared: whoever else lists that hex as an accent, the metal is not theirs.
    """
    if "." in token:
        prefix = token.split(".", 1)[0]
        return frozenset() if prefix in SHARED_NAMESPACES else frozenset((prefix,))
    owners, shared = set(), False
    for name in _alias_targets(token):
        prefix = name.split(".", 1)[0]
        if prefix in SHARED_NAMESPACES:
            shared = True
        else:
            owners.add(prefix)
    return frozenset() if shared else frozenset(owners)


# Resolved once: the palette is a constant, and the mixed-accent check runs per
# render rather than per token.
ACCENT_OWNERS = {token: _accent_owners(token) for token in NAMED_COLORS}

TRANSPARENT = (0, 0, 0, 0)
DEFAULT_FRAMETIME = 6  # ticks (0.3s) — a calm, readable default pulse

# Preview sizing. The preview exists to be *read back* and judged, so it is
# sized to a target rather than blown up by a fixed factor: ×16 is right for a
# 16px sprite and absurd for a 128px master (a 2048px image that says nothing
# the 512px one doesn't). An explicit --preview-scale overrides this.
DEFAULT_PREVIEW_SCALE = 16
PREVIEW_MAX_PX = 512
# Past this native size the ASCII silhouette dump stops being a quick read and
# becomes thousands of characters of noise — the preview PNG is the better tool.
ASCII_MAX_SIZE = 64
_DIRECTIVES = ("grid:", "frame:")

# Tonal ramps. The quality bar wants 3–5 tonal steps per surface, and the
# palette rule wants named tokens rather than raw hex — so a legend must be able
# to name a *step* of a token, not just the token. `emerald+2` is two steps
# toward the highlight, `emerald-1` one step toward shadow. Steps hue-shift as
# they go (shadows cool and saturate, highlights warm and desaturate), which is
# how hand-painted pixel art reads as volume instead of as a dimmer switch.
RAMP_MAX_STEP = 4
_SHADOW_V = 0.78    # value multiplier per shadow step
_HIGHLIGHT_V = 0.30  # fraction of the remaining headroom per highlight step
_SHADOW_S = 0.05    # saturation added per shadow step
_HIGHLIGHT_S = 0.09  # saturation removed per highlight step
_HUE_PER_STEP = 7.0  # degrees rotated toward the cool/warm pole per step
_COOL_HUE, _WARM_HUE = 240.0, 50.0

# Legend chars --ramp hands out, keyed by step offset: highlight -> occlusion.
_RAMP_ROLES = {
    3: ("W", "white-hot"),
    2: ("H", "highlight"),
    1: ("L", "light"),
    0: ("M", "midtone"),
    -1: ("S", "core shadow"),
    -2: ("O", "occlusion"),
    -3: ("K", "deep occlusion"),
}

# Legend chars handed out by --from-png transcription, in this order. Excludes
# '.' (transparent), '#' (comment), ':' (directive-shaped rows), and whitespace.
TOKEN_POOL = "".join(dict.fromkeys(
    "@$%&*+=oOxX0123456789"
    "abcdefghijklmnpqrstuvwyz"
    "ABCDEFGHIJKLMNPQRSTUVWYZ"
    "?!~^<>()[]{}|/-_"
))


class SpecError(ValueError):
    """A malformed glyph spec, reported with enough context to fix it."""


class ToolError(RuntimeError):
    """A render step needs an external tool this machine doesn't have.

    Kept apart from SpecError on purpose: the spec is fine and the shipped art
    may be fine — what failed is the environment. A check that reported this as
    drift would accuse the art, and one that swallowed it would pass a spec it
    never actually looked at.
    """


def _directive_value(line):
    """The value of a `key: value` header line, with any trailing comment cut.

    Header values are free text (a path, a word), so unlike a legend entry they
    can't just take the first whitespace token — a path may be followed by a
    size, and either may be followed by a `# note`.
    """
    value = line.split(":", 1)[1]
    return value.split("#", 1)[0].strip()


def _rgb_to_hsv(r, g, b):
    r, g, b = r / 255.0, g / 255.0, b / 255.0
    hi, lo = max(r, g, b), min(r, g, b)
    v, c = hi, hi - lo
    s = 0.0 if hi == 0 else c / hi
    if c == 0:
        h = 0.0
    elif hi == r:
        h = 60.0 * (((g - b) / c) % 6)
    elif hi == g:
        h = 60.0 * ((b - r) / c + 2)
    else:
        h = 60.0 * ((r - g) / c + 4)
    return h, s, v


def _hsv_to_rgb(h, s, v):
    c = v * s
    x = c * (1 - abs(((h / 60.0) % 2) - 1))
    m = v - c
    seg = int(h // 60) % 6
    r, g, b = ((c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x))[seg]
    return tuple(int(round((ch + m) * 255)) for ch in (r, g, b))


def _rotate_hue(h, target, degrees):
    """Rotate hue toward `target` by `degrees`, along the shorter arc."""
    delta = ((target - h + 180.0) % 360.0) - 180.0
    if abs(delta) <= degrees:
        return target % 360.0
    return (h + degrees * (1 if delta > 0 else -1)) % 360.0


def shade(rgba, step):
    """Return `rgba` moved `step` tonal steps (negative = shadow, positive = highlight).

    Shadows darken, gain saturation, and rotate toward the cool pole; highlights
    lift toward white, lose saturation, and rotate toward the warm pole. Alpha is
    carried through untouched. A fully desaturated token (ink, bone, ash) keeps
    its hue — there is no hue to shift.
    """
    if step == 0:
        return rgba
    r, g, b, a = rgba
    h, s, v = _rgb_to_hsv(r, g, b)
    n = abs(step)
    # A fully achromatic base has no hue: rotating from the h=0 default would
    # paint a grey outline red. It only moves in value.
    achromatic = s == 0.0
    if step < 0:
        v *= _SHADOW_V ** n
        if not achromatic:
            s = min(1.0, s + _SHADOW_S * n)
            h = _rotate_hue(h, _COOL_HUE, _HUE_PER_STEP * n)
    else:
        v += (1.0 - v) * (1.0 - (1.0 - _HIGHLIGHT_V) ** n)
        if not achromatic:
            s = max(0.0, s - _HIGHLIGHT_S * n)
            h = _rotate_hue(h, _WARM_HUE, _HUE_PER_STEP * n)
    return _hsv_to_rgb(h, s, min(1.0, v)) + (a,)


def split_ramp_token(token):
    """Split `emerald+2` into ('emerald', 2). Returns (token, 0) when it is not
    a ramp form, and (None, 0) when the base is not a known token.

    Named tokens contain '-' themselves (`emerald-bright`), so a step suffix is
    only recognised when it is a sign followed by digits — and only after an
    exact palette lookup has already failed.
    """
    t = token.strip().lower()
    if len(t) < 3 or t[-1] not in "0123456789":
        return (t if t in NAMED_COLORS else None), 0
    i = len(t) - 1
    while i > 0 and t[i - 1] in "0123456789":
        i -= 1
    sign = t[i - 1]
    if sign not in "+-":
        return (t if t in NAMED_COLORS else None), 0
    base, step = t[: i - 1], int(t[i:])
    if base not in NAMED_COLORS:
        return None, 0
    return base, (step if sign == "+" else -step)


def parse_color(token):
    """Resolve a legend color token to an (r, g, b, a) tuple."""
    t = token.strip().lower()
    if t in ("transparent", "none", "_"):
        return TRANSPARENT
    if t in NAMED_COLORS:
        t = NAMED_COLORS[t]
    elif not t.startswith("#"):
        base, step = split_ramp_token(t)
        if base is None:
            raise SpecError(
                f"unknown color {token!r} — use #hex, a named token, or a ramp "
                f"step like 'mercantile.emerald-2' (run --list-colors)"
            )
        if abs(step) > RAMP_MAX_STEP:
            raise SpecError(
                f"ramp step {token!r} is beyond ±{RAMP_MAX_STEP} — that far from "
                f"the base token the hue is no longer recognisable; pick a "
                f"different base"
            )
        return shade(parse_color(NAMED_COLORS[base]), step)
    if not t.startswith("#"):
        raise SpecError(
            f"unknown color {token!r} — use #hex or a named token "
            f"(run --list-colors)"
        )
    hexpart = t[1:]
    if len(hexpart) == 3:  # #RGB -> #RRGGBB
        hexpart = "".join(c * 2 for c in hexpart)
    if len(hexpart) == 6:
        hexpart += "ff"
    if len(hexpart) != 8 or any(c not in "0123456789abcdef" for c in hexpart):
        raise SpecError(f"bad hex color {token!r} — expected #RGB/#RRGGBB/#RRGGBBAA")
    return tuple(int(hexpart[i : i + 2], 16) for i in (0, 2, 4, 6))


def parse_spec(text):
    """Parse a glyph spec into (legend, frames, declared_size, meta, used_tokens).

    `frames` is a list of grids; each grid is a list of row strings.
    `meta` carries the spec-level directives — frametime / interpolate / ships
    (may be empty).
    `used_tokens` is the set of NAMED_COLORS tokens the legend referenced.
    """
    legend = {}
    frames = []          # list of grids (each a list of row strings)
    current = None       # the grid currently being filled
    declared_size = None
    meta = {}
    used_tokens = set()
    mode = None          # None | "legend" | "grid"

    def flush():
        nonlocal current
        if current is not None:
            frames.append(current)
            current = None

    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        low = line.lower()

        # directives take precedence over grid-row collection
        if low in _DIRECTIVES:
            flush()
            current = []
            mode = "grid"
            continue
        if low == "legend:":
            flush()
            mode = "legend"
            continue
        if low.startswith("size:"):
            try:
                declared_size = int(line.split(":", 1)[1].split()[0])
            except (ValueError, IndexError):
                raise SpecError(f"line {lineno}: size: must be an integer")
            continue
        if low.startswith("palette:"):
            value = _directive_value(line).lower()
            if value not in ("tokens", "free"):
                raise SpecError(
                    f"line {lineno}: palette: must be 'tokens' (the default — "
                    f"legend colors come from the design system) or 'free' "
                    f"(deliberately off-palette: a transcribed raster or a "
                    f"hand-painted master)")
            meta["palette"] = value
            continue
        if low.startswith("kind:"):
            value = _directive_value(line).lower()
            if value not in KINDS:
                raise SpecError(
                    f"line {lineno}: kind: must be one of "
                    + ", ".join(f"{k} ({KIND_HELP[k]})" for k in KINDS))
            meta["kind"] = value
            continue
        if low.startswith("edge:"):
            value = _directive_value(line).lower()
            if value not in EDGES:
                raise SpecError(
                    f"line {lineno}: edge: must be one of "
                    + ", ".join(f"{e} ({EDGE_HELP[e]})" for e in EDGES))
            meta["edge"] = value
            continue
        if low.startswith("frames:"):
            value = _directive_value(line).lower()
            if value not in FRAME_PACKAGINGS:
                raise SpecError(
                    f"line {lineno}: frames: must be 'strip' (the default — a "
                    f"vertical strip plus a .mcmeta, which the vanilla atlas "
                    f"animates) or 'split' (standalone per-frame PNGs, for a "
                    f"texture your own code binds and advances)")
            meta["frames"] = value
            continue
        if low.startswith("downscale:"):
            value = _directive_value(line).lower()
            if value not in DOWNSCALE_FILTERS:
                raise SpecError(
                    f"line {lineno}: downscale: must be 'box' (the default — "
                    f"the built-in area average, exact at a whole factor and "
                    f"needing no external tool) or an ImageMagick filter: "
                    + ", ".join(MAGICK_FILTERS))
            meta["downscale"] = value
            continue
        if low.startswith("ships:"):
            # `ships: <path> [size]` — where a rendered master lands. Repeat the
            # directive once per shipped tier; the optional size is the upscale
            # that tier is minted at, so a size ladder declares every file it
            # produces from the one native grid.
            parts = _directive_value(line).split()
            if not parts:
                raise SpecError(f"line {lineno}: ships: needs a path")
            try:
                tier = int(parts[1]) if len(parts) > 1 else None
            except ValueError:
                raise SpecError(
                    f"line {lineno}: ships: size must be an integer: {line!r}")
            meta.setdefault("ships", []).append((parts[0], tier))
            continue
        if low.startswith("frametime:"):
            try:
                meta["frametime"] = int(line.split(":", 1)[1].split()[0])
            except (ValueError, IndexError):
                raise SpecError(f"line {lineno}: frametime: must be an integer")
            continue
        if low.startswith("interpolate:"):
            val = line.split(":", 1)[1].strip().lower()
            meta["interpolate"] = val in ("true", "1", "yes")
            continue

        if mode == "grid":
            if not line or line.startswith("#"):
                continue  # blank line or comment between frames
            if " " in line:
                raise SpecError(
                    f"line {lineno}: grid rows cannot contain spaces "
                    f"(use '.' for transparent): {raw!r}"
                )
            current.append(line)
            continue

        # outside a grid, blank lines and comments are noise
        if not line or line.startswith("#"):
            continue

        if mode == "legend":
            parts = line.split(None, 1)
            if len(parts) != 2:
                raise SpecError(f"line {lineno}: legend entry needs 'CHAR COLOR': {line!r}")
            char = parts[0]
            # the color is the first token after the char; a trailing
            # '# comment' is dropped. Hex values contain no spaces.
            color = parts[1].split()[0]
            if len(char) != 1:
                raise SpecError(f"line {lineno}: legend key must be one character: {char!r}")
            if char in legend:
                # Silently taking the last definition would repaint every cell
                # using that char, anywhere in the grid, with no sign anything
                # was wrong.
                raise SpecError(
                    f"line {lineno}: legend key {char!r} is already defined — "
                    f"one character maps to one color; pick another char")
            legend[char] = parse_color(color)
            base, _step = split_ramp_token(color)
            if base is not None:  # a ramp step counts as a use of its base token
                used_tokens.add(base)
            elif color.strip().startswith("#"):
                meta.setdefault("raw_hex", []).append((char, color.strip()))
        else:
            raise SpecError(
                f"line {lineno}: unexpected content before a 'legend:' or 'frame:' "
                f"directive: {line!r}"
            )

    flush()
    if not frames or all(not f for f in frames):
        raise SpecError("spec has no frame grids (need a 'frame:' or 'grid:' section)")
    legend.setdefault(".", TRANSPARENT)
    return legend, frames, declared_size, meta, used_tokens


def build_frames(legend, frames_rows, declared_size):
    """Validate every frame and flatten each to a row-major RGBA list.

    Returns (list_of_pixel_lists, size). Every frame must be square and all
    frames must share the same size.
    """
    out = []
    size = None
    for fi, rows in enumerate(frames_rows, 1):
        if not rows:
            raise SpecError(f"frame {fi} is empty")
        height = len(rows)
        widths = {len(r) for r in rows}
        if len(widths) != 1:
            raise SpecError(
                f"frame {fi}: rows have differing widths {sorted(widths)} — "
                f"every row must be the same length"
            )
        width = widths.pop()
        if width != height:
            raise SpecError(f"frame {fi} is {width}×{height}; glyph frames must be square")
        if size is None:
            size = width
        elif width != size:
            raise SpecError(
                f"frame {fi} is {width}×{width} but frame 1 is {size}×{size} — "
                f"all frames must match"
            )
        if declared_size is not None and declared_size != width:
            raise SpecError(f"declared size: {declared_size} but frame {fi} is {width}×{width}")

        pixels = []
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch not in legend:
                    raise SpecError(
                        f"frame {fi} cell ({x},{y}) uses {ch!r}, not in the legend"
                    )
                pixels.append(legend[ch])
        out.append(pixels)
    return out, size


def stack_vertical(frames_px, size):
    """Concatenate frames top-to-bottom into one sprite-strip pixel list."""
    out = []
    for px in frames_px:
        out.extend(px)
    return out, size, size * len(frames_px)


def make_filmstrip(frames_px, size, sep=1, sep_color=(60, 60, 60, 255)):
    """Lay frames left-to-right with a thin separator, for a preview image."""
    n = len(frames_px)
    width = n * size + (n - 1) * sep
    out = []
    for y in range(size):
        for fi, px in enumerate(frames_px):
            for x in range(size):
                out.append(px[y * size + x])
            if fi < n - 1:
                out.extend([sep_color] * sep)
    return out, width, size


def _png_chunk(tag, data):
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def _raw_scanlines(pixels, width, height):
    """Filter-0 (None) scanlines for a row-major RGBA pixel list, pre-compression."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for each scanline
        for x in range(width):
            raw += bytes(pixels[y * width + x])
    return bytes(raw)


def write_png(path, pixels, width, height):
    """Encode an 8-bit RGBA PNG from a row-major pixel list (stdlib only)."""
    body = (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + _png_chunk(b"IDAT", zlib.compress(_raw_scanlines(pixels, width, height), 9))
        + _png_chunk(b"IEND", b"")
    )
    Path(path).write_bytes(body)


def write_apng(path, frames_px, width, height, frametime):
    """Encode an animated PNG from equal-size RGBA frames (stdlib only).

    Full 8-bit RGBA with true alpha — a faithful moving preview of the sprite,
    no palette quantization or checkerboard compositing. Each frame fully
    replaces the canvas (dispose 0 / blend 0 SOURCE). Old viewers that don't
    understand APNG fall back to the first frame, which is a valid PNG.
    """
    delay_num = max(1, frametime)
    delay_den = 20  # Minecraft runs at 20 ticks/second
    chunks = [
        b"\x89PNG\r\n\x1a\n",
        _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)),
        _png_chunk(b"acTL", struct.pack(">II", len(frames_px), 0)),  # 0 plays = loop forever
    ]
    seq = 0
    for i, px in enumerate(frames_px):
        # fcTL: seq, w, h, x, y, delay_num, delay_den, dispose_op, blend_op
        chunks.append(_png_chunk(
            b"fcTL",
            struct.pack(">IIIIIHHBB", seq, width, height, 0, 0, delay_num, delay_den, 0, 0),
        ))
        seq += 1
        data = zlib.compress(_raw_scanlines(px, width, height), 9)
        if i == 0:
            chunks.append(_png_chunk(b"IDAT", data))  # frame 0 is also the default image
        else:
            chunks.append(_png_chunk(b"fdAT", struct.pack(">I", seq) + data))
            seq += 1
    chunks.append(_png_chunk(b"IEND", b""))
    Path(path).write_bytes(b"".join(chunks))


def write_mcmeta(path, data):
    """Write the vanilla animated-texture sidecar next to the sprite strip."""
    Path(path).write_text(json.dumps(data, indent=2) + "\n")


def scale_nearest(pixels, width, height, factor):
    """Nearest-neighbor upscale (keeps pixels crisp, no blur)."""
    out = []
    for y in range(height * factor):
        sy = y // factor
        for x in range(width * factor):
            out.append(pixels[sy * width + (x // factor)])
    return out, width * factor, height * factor


def scale_box(pixels, width, height, factor):
    """Area-average downscale by an integer factor, weighted by alpha.

    Exact for the case that motivates it: when the master is itself a
    nearest-neighbor upscale of a smaller grid, every factor×factor block holds
    one colour and the average gives that colour straight back — so a tier
    derived this way is the same art, not a resample of it. RGB is weighted by
    alpha so a fading edge doesn't drag the transparent side's colour inward,
    and the arithmetic is integer throughout so two machines agree to the pixel.
    """
    out = []
    n = factor * factor
    for y in range(height // factor):
        for x in range(width // factor):
            r = g = b = a = 0
            for dy in range(factor):
                row = (y * factor + dy) * width + x * factor
                for dx in range(factor):
                    px = pixels[row + dx]
                    r += px[0] * px[3]
                    g += px[1] * px[3]
                    b += px[2] * px[3]
                    a += px[3]
            if a == 0:
                out.append(TRANSPARENT)
            else:
                out.append(((r + a // 2) // a, (g + a // 2) // a,
                            (b + a // 2) // a, (a + n // 2) // n))
    return out, width // factor, height // factor


# Memoised (binary_or_None, [rejected paths]) — the identity probe below shells
# out, and an animated spec would otherwise re-run it once per frame.
_MAGICK_LOOKUP = []


def magick_binary():
    """ImageMagick's entry point on this machine, or None.

    v7 installs `magick`; v6 installs `convert`, which many v7 packages keep as
    a compatibility alias. Either is fine — they take the same arguments here,
    and what they hand back is the same image, since the pixels come home to be
    re-encoded by this script's own writer rather than shipped as ImageMagick
    wrote them.

    A candidate is confirmed to *be* ImageMagick before it is used. `convert` is
    a common enough name to collide: on Windows it is the system's filesystem
    conversion utility, and handing that a resize command would fail in a way
    that reads as ImageMagick misbehaving rather than as ImageMagick missing.
    """
    if not _MAGICK_LOOKUP:
        found, rejected = None, []
        for name in ("magick", "convert"):
            path = shutil.which(name)
            if not path:
                continue
            if _is_imagemagick(path):
                found = path
                break
            rejected.append(path)
        _MAGICK_LOOKUP.append((found, rejected))
    return _MAGICK_LOOKUP[0][0]


def _is_imagemagick(path):
    """Does `path` identify itself as ImageMagick? Read-only, and cheap once."""
    try:
        proc = subprocess.run([path, "-version"], capture_output=True,
                              text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return False
    return "ImageMagick" in (proc.stdout or "") + (proc.stderr or "")


def magick_resize(pixels, size, target, filt):
    """Resample one frame to target×target through ImageMagick.

    The frame goes out and comes back as PNG32 — 8-bit RGBA, no palette, no
    16-bit channels — so what returns is directly comparable to what the
    renderer produces itself. `-background none` keeps a fully transparent
    surround from tinting the edge, and the date chunks are excluded so a
    re-render doesn't dirty the file with a timestamp-only diff.
    """
    binary = magick_binary()
    if binary is None:
        rejected = _MAGICK_LOOKUP[0][1] if _MAGICK_LOOKUP else []
        wrong = (f" ({', '.join(rejected)} answered to the name but is not "
                 f"ImageMagick — on Windows 'convert' is the system's "
                 f"filesystem tool)" if rejected else "")
        raise ToolError(
            f"this spec's {target}px tier is resampled with the '{filt}' "
            f"filter, which is ImageMagick's, and ImageMagick is not installed "
            f"(looked for 'magick', then 'convert'){wrong}. Install it, or set "
            f"'downscale: box' to use the built-in area average instead")
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = Path(tmp) / "in.png", Path(tmp) / "out.png"
        write_png(src, pixels, size, size)
        proc = subprocess.run(
            [binary, str(src), "-background", "none", "-filter", filt,
             "-resize", f"{target}x{target}!",
             "-define", "png:exclude-chunk=date,tIME", f"PNG32:{dst}"],
            capture_output=True, text=True)
        if proc.returncode != 0 or not dst.exists():
            detail = (proc.stderr or proc.stdout or "").strip().splitlines()
            raise ToolError(
                f"ImageMagick could not resize to {target}px with the '{filt}' "
                f"filter: {detail[-1] if detail else 'no output'}")
        out, w, h = read_png(dst)
    if (w, h) != (target, target):
        raise ToolError(
            f"ImageMagick returned {w}×{h} for a {target}×{target} tier")
    return out


def scale_tier(frames_px, size, scale_to, downscale="box"):
    """Re-render every frame at `scale_to`, up or down. Returns (frames, size).

    A texture ladder runs upward from a native grid; the mod-icon ladder runs
    the other way, deriving its 256 and 128 copies from a 512 master. Upscaling
    is always nearest-neighbor — anything else would blur pixel art, and there
    is nothing to gain from interpolating pixels that were authored as cells.

    Downscaling is what `downscale:` selects, and it is read off the spec rather
    than off the machine: were the engine chosen by what happens to be
    installed, two checkouts of the same spec would mint different pixels and
    each would call the other's output drift.
    """
    if scale_to > size and scale_to % size == 0:
        factor = scale_to // size
        return [scale_nearest(px, size, size, factor)[0] for px in frames_px], scale_to
    if 0 < scale_to < size:
        # The built-in average is exact at a whole factor and only there; every
        # other downscale — a different filter, or a ratio like 512 -> 200 — is
        # ImageMagick's, which is what `downscale:` is declaring.
        if downscale == "box" and size % scale_to == 0:
            factor = size // scale_to
            return [scale_box(px, size, size, factor)[0] for px in frames_px], scale_to
        return [magick_resize(px, size, scale_to, downscale)
                for px in frames_px], scale_to
    raise SpecError(
        f"a {scale_to}px tier of a {size}px grid is not a whole multiple of it "
        f"— an upscaled tier is an integer multiple of the native size (e.g. "
        f"{size * 2}, {size * 4}), rendered nearest-neighbor. Any tier smaller "
        f"than the grid downscales instead, at whatever ratio 'downscale:' can "
        f"resample")


def read_png(path):
    """Decode an 8-bit PNG to a row-major RGBA pixel list (stdlib only).

    Handles the color types real masters use — gray, RGB, palette (+tRNS),
    gray+alpha, RGBA — and all five scanline filters. Non-interlaced only.
    Returns (pixels, width, height).
    """
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SpecError(f"{path}: not a PNG file")
    width = height = bit_depth = color_type = interlace = None
    plte, trns, idat = None, None, bytearray()
    pos = 8
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(
                ">IIBBBBB", chunk)
        elif tag == b"PLTE":
            plte = [tuple(chunk[i:i + 3]) for i in range(0, len(chunk), 3)]
        elif tag == b"tRNS":
            trns = chunk
        elif tag == b"IDAT":
            idat += chunk
        elif tag == b"IEND":
            break
    if width is None:
        raise SpecError(f"{path}: missing IHDR chunk")
    if bit_depth != 8:
        raise SpecError(f"{path}: only 8-bit PNGs are supported (bit depth {bit_depth})")
    if interlace:
        raise SpecError(f"{path}: interlaced (Adam7) PNGs are not supported — re-export non-interlaced")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise SpecError(f"{path}: unsupported PNG color type {color_type}")

    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    if len(raw) != height * (stride + 1):
        raise SpecError(f"{path}: corrupt PNG (scanline data is the wrong length)")
    pixels = []
    prev = bytearray(stride)
    at = 0
    for _y in range(height):
        ftype = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        if ftype == 1:    # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif ftype != 0:
            raise SpecError(f"{path}: unknown PNG filter type {ftype}")
        prev = line
        for x in range(width):
            o = x * channels
            if color_type == 6:
                pixels.append((line[o], line[o + 1], line[o + 2], line[o + 3]))
            elif color_type == 2:
                pixels.append((line[o], line[o + 1], line[o + 2], 255))
            elif color_type == 0:
                g = line[o]
                pixels.append((g, g, g, 255))
            elif color_type == 4:
                g = line[o]
                pixels.append((g, g, g, line[o + 1]))
            else:  # 3: palette
                idx = line[o]
                if plte is None or idx >= len(plte):
                    raise SpecError(f"{path}: palette index {idx} out of range")
                r, g, b = plte[idx]
                a = trns[idx] if trns is not None and idx < len(trns) else 255
                pixels.append((r, g, b, a))
    return pixels, width, height


def transcribe_png(in_path, out_path):
    """Turn a raster PNG master into a .glyph spec (the --from-png path).

    Fully transparent pixels become '.'; every distinct remaining color gets
    the next TOKEN_POOL char in first-seen order with a raw-hex legend entry
    (#RRGGBB, or #RRGGBBAA when partial alpha exists). The emitted spec
    re-renders pixel-identical to the input. Returns (size, color_count).
    """
    pixels, w, h = read_png(in_path)
    if w != h:
        raise SpecError(f"{in_path}: glyph frames must be square (got {w}×{h})")
    order = []
    for p in pixels:
        if p[3] != 0 and p not in order:
            order.append(p)
    if len(order) > len(TOKEN_POOL):
        raise SpecError(
            f"{in_path}: {len(order)} distinct opaque colors exceed the "
            f"{len(TOKEN_POOL)}-token legend pool — quantize the master first "
            f"(pixel art wants ≲50 colors)")
    tokens = {p: TOKEN_POOL[i] for i, p in enumerate(order)}

    def hexc(p):
        if p[3] == 255:
            return "#{:02x}{:02x}{:02x}".format(*p[:3])
        return "#{:02x}{:02x}{:02x}{:02x}".format(*p)

    lines = [f"# transcribed from {Path(in_path).name} by glyph.py --from-png",
             f"size: {w}",
             "palette: free   # transcribed raster — its colors are the master's,",
             "                # not design-system tokens",
             "", "legend:", "  . transparent"]
    lines += [f"  {tokens[p]} {hexc(p)}" for p in order]
    lines += ["", "frame:"]
    for y in range(h):
        lines.append("  " + "".join(
            "." if pixels[y * w + x][3] == 0 else tokens[pixels[y * w + x]]
            for x in range(w)))
    text = "\n".join(lines) + "\n"

    # Round-trip self-check: the emitted spec must rebuild the exact pixels.
    legend, frames_rows, declared_size, _anim, _used = parse_spec(text)
    rebuilt, _size = build_frames(legend, frames_rows, declared_size)
    if rebuilt[0] != pixels:
        raise SpecError(f"{in_path}: internal error — transcription is not pixel-identical")

    Path(out_path).write_text(text)
    return w, len(order)


def ramp_steps(count):
    """Step offsets for an `count`-step ramp, highlight first, shadow-biased.

    Shading needs more room below the base tone than above it — a lit surface
    has one highlight and several depths of shadow — so an even count spends the
    extra step downward.
    """
    if not 2 <= count <= len(_RAMP_ROLES):
        raise SpecError(f"--ramp-steps must be between 2 and {len(_RAMP_ROLES)}")
    highs = (count - 1) // 2
    return list(range(highs, highs - count, -1))


def format_ramp(token, count):
    """Legend lines for a tonal ramp off `token`, ready to paste into a spec."""
    if token not in NAMED_COLORS:
        raise SpecError(f"unknown token {token!r} — run --list-colors")
    base = parse_color(NAMED_COLORS[token])
    lines = [f"# {count}-step tonal ramp from {token} ({NAMED_COLORS[token]})",
             "legend:"]
    for step in ramp_steps(count):
        char, role = _RAMP_ROLES[step]
        name = token if step == 0 else f"{token}{step:+d}"
        rgba = shade(base, step)
        lines.append(f"  {char} {name:<28} # {'#{:02x}{:02x}{:02x}'.format(*rgba[:3])}  {role}")
    return lines


# How close a token has to be before swapping a raw hex for it is a free win.
# Below ~8 the difference is invisible at sprite scale; past ~24 it is a
# different colour and the choice belongs to whoever drew it.
SNAP_EXACT = 1.0
SNAP_CLOSE = 8.0
SNAP_LIMIT = 24.0


def nearest_token(rgba):
    """The closest palette token or ramp step to a colour.

    Returns (name, distance, colour). Ramp steps are candidates too — that is
    where most legacy shadow and highlight tones actually live, since they were
    hand-mixed off an accent before ramp steps existed.

    Several tokens can hold one colour: #ffd700 is `metal.gold` and both mods
    that list gold as an accent. A tie goes to whichever owns nothing, because a
    raw gold in an arbitrary mod's spec is a material far more often than it is a
    claim on someone's identity — and suggesting the branded spelling would hand
    the author a mixed-accent warning as an improvement.
    """
    best = (None, float("inf"), None, False)
    for token, hex_ in NAMED_COLORS.items():
        base = parse_color(hex_)
        owned = bool(ACCENT_OWNERS.get(token))
        for step in range(-RAMP_MAX_STEP, RAMP_MAX_STEP + 1):
            candidate = shade(base, step) if step else base
            d = _rgb_distance(rgba, candidate)
            if d < best[1] or (d == best[1] and best[3] and not owned):
                name = token if step == 0 else f"{token}{step:+d}"
                best = (name, d, candidate, owned)
    return best[:3]


def snap_palette(raw_hex):
    """Suggest a token for every raw-hex legend entry. Returns report lines.

    Suggestions only: applying one changes the rendered pixels, which would put
    the spec out of step with the master it already shipped. Read the deltas,
    decide, edit — then re-render to the shipped path.
    """
    lines = []
    swaps = exact = 0
    for char, value in raw_hex:
        try:
            rgba = parse_color(value)
        except SpecError:
            continue
        name, dist, colour = nearest_token(rgba)
        hexc = "#{:02x}{:02x}{:02x}".format(*colour[:3])
        if dist <= SNAP_EXACT:
            mark, note = "==", "identical"
            exact += 1
            swaps += 1
        elif dist <= SNAP_CLOSE:
            mark, note = "->", f"Δ{dist:.0f}, indistinguishable at sprite scale"
            swaps += 1
        elif dist <= SNAP_LIMIT:
            mark, note = "~>", f"Δ{dist:.0f}, a visible shift — your call"
        else:
            mark, note = "  ", f"nearest is {name} at Δ{dist:.0f} — genuinely off-palette"
            name = ""
        lines.append(f"  {char} {value:<11s} {mark} {name:<28s} {hexc}  {note}")
    if raw_hex:
        lines.append("")
        lines.append(f"  {swaps} of {len(raw_hex)} entries have a token within "
                     f"Δ{SNAP_CLOSE:.0f} ({exact} exact). Entries with no close "
                     f"token are why 'palette: free' exists.")
        lines.append("  Suggestions only — applying one changes the rendered "
                     "pixels, so re-render to the shipped path afterwards.")
    return lines


def master_artifacts(frames_px, size, meta, out, split_frames=False, scale_to=None):
    """The committed outputs this spec renders to.

    Returns (artifacts, mcmeta) — artifacts as [(path, pixels, width, height)],
    mcmeta as (path, data) or None. Previews are deliberately absent: they are
    throwaway review renders, not part of the deliverable. Both the write path
    and --verify read this, so what gets checked is by construction what gets
    written.

    Packaging comes from the spec's `frames:` directive as well as the
    `--split-frames` flag: a code-bound animation ships standalone frames every
    time it is rendered, and a check that only knew about the flag would expect
    a strip nobody meant to write.
    """
    nframes = len(frames_px)
    split_frames = split_frames or meta.get("frames") == "split"
    if split_frames and nframes == 1:
        raise SpecError(
            "split-frame packaging needs an animated spec (2+ frames)")

    if scale_to is not None and scale_to != size:
        frames_px, size = scale_tier(frames_px, size, scale_to,
                                     meta.get("downscale", "box"))

    if split_frames:
        return ([(out.with_name(f"{out.stem}_{i}{out.suffix}"), px, size, size)
                 for i, px in enumerate(frames_px)], None)
    if nframes == 1:
        return ([(out, frames_px[0], size, size)], None)
    strip_px, sw, sh = stack_vertical(frames_px, size)
    mcmeta = {"animation": {"frametime": meta.get("frametime", DEFAULT_FRAMETIME),
                            "interpolate": meta.get("interpolate", False)}}
    return ([(out, strip_px, sw, sh)], (out.with_name(out.name + ".mcmeta"), mcmeta))


def spec_ships(path):
    """The `ships:` targets a spec declares, in order (empty when it has none).

    Reads the header only: past the legend or the first grid, a line that looks
    like `ships:` is content, not a directive.
    """
    out = []
    for line in Path(path).read_text().splitlines():
        stripped = line.strip()
        if stripped.lower().startswith("ships:"):
            parts = _directive_value(stripped).split()
            if parts:
                out.append(parts[0])
        elif stripped.lower() in ("legend:", "frame:", "grid:"):
            break
    return out


def verify_spec(frames_px, size, meta, targets=None, split_frames=False):
    """Check one parsed spec against the masters it ships.

    Takes the already-parsed spec rather than a path: the caller has read it
    once already, and a spec can arrive on stdin, where there is no path to
    re-open. Returns (checked, problems). `targets` overrides the spec's own
    `ships:` lines with [(path, tier), …].

    Raises SpecError when the spec cannot describe what it ships at all — a
    tier that is not an integer factor, split frames declared on a static grid.
    That is a malformed spec, not drift, and a caller that reported it as drift
    would tell its user the shipped art was edited when it never was.
    """
    if targets is None:
        targets = [(Path(p), tier) for p, tier in meta.get("ships", [])]
    problems, checked = [], []
    for path, tier in targets:
        artifacts, mcmeta = master_artifacts(
            frames_px, size, meta, Path(path), split_frames, tier)
        problems += verify_artifacts(artifacts, mcmeta)
        checked += [str(a[0]) for a in artifacts]
    return checked, problems


def verify_tree(root, verbose=False):
    """Verify every spec under `root`, at any depth, that declares where it ships.

    The walk recurses: a repo with enough art to sort it into subdirectories is
    exactly the repo that most needs the check, and a walk that stopped at the
    top level would report a confident green over art it never opened.

    This lives in the renderer rather than in a separate tool because the
    renderer is what gets vendored into each member repo — a checker that only
    exists in concord could not be run by the repos whose art it holds.

    Returns (checked, drifted, broken, blocked, unlinked). The three failures
    are counted apart because they accuse different things: drift means a
    shipped asset was edited outside its spec, malformed means the spec itself
    doesn't parse, and blocked means a tool the spec needs isn't installed, so
    the art was never looked at either way. A caller that wraps this can only
    word its error correctly if it can tell them apart — and a walk that
    quietly passed the blocked ones would be back to reporting a confident
    green over art it never opened.
    """
    root = Path(root)
    if not root.exists():
        print(f"  {root}: no such directory — nothing to verify")
        return 0, 0, 0, 0, 0
    checked = drifted = broken = blocked = 0
    unlinked = []
    for spec_path in sorted(root.rglob("*.glyph")):
        if not spec_ships(spec_path):
            unlinked.append(spec_path)
            continue
        try:
            legend, rows, declared, meta, _used = parse_spec(spec_path.read_text())
            frames_px, size = build_frames(legend, rows, declared)
            shipped, problems = verify_spec(frames_px, size, meta)
        except SpecError as e:
            broken += 1
            print(f"  BROKEN   {spec_path}")
            print(f"           {e}")
            continue
        except ToolError as e:
            blocked += 1
            print(f"  BLOCKED  {spec_path}")
            print(f"           {e}")
            continue
        checked += 1
        if problems:
            drifted += 1
            print(f"  DRIFT    {spec_path}")
            for p in problems:
                print(f"           {p}")
        elif verbose:
            print(f"  ok       {spec_path} -> {', '.join(shipped)}")
    for spec_path in unlinked:
        print(f"  unlinked {spec_path} — no 'ships:' target, so nothing verifies it")
    return checked, drifted, broken, blocked, len(unlinked)


def verify_artifacts(artifacts, mcmeta):
    """Compare shipped files against what the spec renders. Returns a list of
    human-readable mismatches — empty means the shipped copy is reproducible.

    Pixels are compared, not bytes: a different zlib version re-encodes the same
    image differently, and that is not drift. A hand-patched pixel is.
    """
    problems = []
    for path, pixels, width, height in artifacts:
        if not path.exists():
            problems.append(f"{path}: missing — the spec renders it, nothing ships it")
            continue
        try:
            got, gw, gh = read_png(path)
        except SpecError as e:
            problems.append(f"{path}: unreadable as a rendered master — {e}")
            continue
        if (gw, gh) != (width, height):
            problems.append(
                f"{path}: is {gw}×{gh}, the spec renders {width}×{height}")
            continue
        diff = sum(1 for a, b in zip(got, pixels) if a != b)
        if diff:
            first = next(i for i, (a, b) in enumerate(zip(got, pixels)) if a != b)
            problems.append(
                f"{path}: {diff} of {len(pixels)} pixels differ from the spec "
                f"(first at {first % width},{first // width}) — the shipped copy "
                f"was edited outside the .glyph")
    if mcmeta is not None:
        path, data = mcmeta
        if not path.exists():
            problems.append(f"{path}: missing — an animated strip ships its .mcmeta")
        else:
            try:
                got = json.loads(path.read_text())
            except json.JSONDecodeError as e:
                problems.append(f"{path}: not valid JSON — {e}")
                got = None
            if got is not None and got != data:
                problems.append(
                    f"{path}: {json.dumps(got, sort_keys=True)} but the spec "
                    f"declares {json.dumps(data, sort_keys=True)}")
    return problems


def make_tiled(pixels, size, reps=2):
    """Repeat a frame reps×reps with no separator — the seam/corner check for
    tiling block textures (adjacent copies must join invisibly)."""
    out = []
    for y in range(size * reps):
        for x in range(size * reps):
            out.append(pixels[(y % size) * size + (x % size)])
    return out, size * reps


# Quality-bar thresholds, calibrated against the reference and shipped specs so
# a warning means something is actually wrong rather than merely unusual.
# Worst clean flat fill in that set is 62% (an 11px UI button); worst clean dark
# edge is 77% (mercantile-scales).
FLAT_PCT_SMALL, FLAT_MIN_SMALL = 70.0, 20   # under 32px
FLAT_PCT_LARGE, FLAT_MIN_LARGE = 30.0, 40   # 32px and up
OUTLINE_MIN_AREA = 40      # below this a motif is a pip or a spark, not a form
OUTLINE_DARK_PCT = 50.0    # share of the silhouette edge that must read as dark
OUTLINE_DARK_LUM = 0.25    # relative luminance below which a pixel reads as ink

# What a texture *is*. Inferring this from pixel geometry conflates several
# different things — a tiling block side, a single face or cap, a UI plate and
# a UV sheet all bleed to every edge — so each check specialises on the
# declared kind instead of guessing. A spec that declares none is classified
# from its edges, which is what every spec written before this directive
# relies on.
KINDS = ("sprite", "particle", "block", "cap", "ui", "atlas", "icon")
KIND_HELP = {
    "sprite": "a centred motif on transparency — items, HUD glyphs, pips",
    "particle": "a spark, mote, or pip whose motif fills the canvas it is given",
    "block": "a tiling block side face; repeats against copies of itself",
    "cap": "a full-bleed face that never repeats — a block top or bottom",
    "ui": "a UI plate, panel, or 9-slice frame; flat areas are the point",
    "atlas": "a sheet read through UV sub-windows, never drawn as one face",
    "icon": "mod, store, or hero art — read in a launcher, not on a HUD",
}
# Which checks each kind earns. A cap bleeds like a block but never repeats, so
# seam-checking it reports a break that cannot happen; a UI frame's flat centre
# is the design, not a missing tonal ramp; an atlas is only ever sampled through
# windows, so nothing about its whole-canvas geometry means anything, though the
# surfaces inside those windows owe the same shading as any other face; a particle
# is drawn at the size of the effect and spends every pixel on it, where a 1px
# ring would cost 44% of an 8px canvas; and an icon is never composited over
# an unknown background, so it owes nothing to the `ink` outline rule.
KIND_CHECKS = {
    "sprite": {"outline", "flat", "margin"},
    "particle": {"flat"},
    "block": {"seam", "flat", "bleed"},
    "cap": {"flat", "bleed"},
    "ui": set(),
    "atlas": {"flat"},
    "icon": {"flat"},
}
# A spec that declares no kind still earns the one check that holds for almost
# everything. The flat-fill message names `kind: ui` as the way out, so the
# single exception is self-correcting rather than silently unchecked.
UNDECLARED_CHECKS = {"flat"}

# How the motif meets the canvas border. A kind implies one — a sprite sits on
# a transparent margin, a block or a cap bleeds — but art that deliberately
# does otherwise says so once, in the spec, exactly as `palette: free` does: an
# 11px lock whose shackle grows out of the frame is drawn that way on purpose,
# and no amount of re-rendering will change its mind. The declaration is still
# measured against the grid, so it records intent rather than muting the check.
EDGES = ("margin", "shaped", "bleed")
EDGE_HELP = {
    "margin": "a clean 1px transparent ring on all four sides",
    "shaped": "the motif deliberately meets some edges but not all",
    "bleed": "opaque along all four edges",
}
# What the grid actually does, phrased for a warning about the mismatch.
EDGE_ACTUAL = {
    "margin": "sitting on a clean transparent margin",
    "shaped": "opaque on some of its border but not all",
    "bleed": "opaque along all four edges",
}

# How an animated spec is packaged: a vertical strip + `.mcmeta` for the vanilla
# atlas to animate, or standalone per-frame PNGs for a texture your own code
# binds and indexes. It belongs in the spec rather than in a CLI flag because it
# is a property of the deliverable — `--verify` has to expect the same files the
# render writes, and nothing on the command line is remembered between the two.
FRAME_PACKAGINGS = ("strip", "split")

# How a `ships:` tier below the native grid is resampled. `box` is the built-in
# alpha-weighted area average: exact at a whole factor, and reproducible by any
# machine with Python on it. The rest are ImageMagick's, worth reaching for when
# the master is painted or traced rather than authored cell by cell — and they
# also lift the whole-factor restriction, since ImageMagick will resample any
# ratio. The trade is that those pixels belong to ImageMagick, so a version that
# resamples differently reads as drift; a spec that wants the repeatability
# check to outlive its toolchain stays on `box`. The list is closed rather than
# free text so a typo is caught here instead of becoming a subprocess argument.
MAGICK_FILTERS = ("point", "triangle", "catrom", "mitchell", "lanczos")
DOWNSCALE_FILTERS = ("box",) + MAGICK_FILTERS

# Seam continuity for tiling textures. A seam is visible when the jump across
# the wrap is unlike the jumps already inside the texture — not merely when it
# is large. Vertical stripes jump hard at the wrap and tile perfectly, because
# the same jump occurs inside; a smooth gradient jumps no harder in absolute
# terms and screams, because nothing inside it jumps at all. So the wrap is
# compared against the texture's own gradient distribution.
SEAM_P95_FACTOR = 1.25     # how far past the interior 95th percentile counts
SEAM_MIN_JUMP = 16.0       # RGB distance below which a step isn't visible anyway
SEAM_WARN_PCT = 25.0       # share of the wrap edge that may look anomalous


def _luminance(color):
    r, g, b = color[:3]
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def _components(pixels, size):
    """4-connected opaque regions, largest first, as lists of pixel indices."""
    seen = [False] * (size * size)
    out = []
    for start in range(size * size):
        if seen[start] or pixels[start][3] == 0:
            continue
        seen[start] = True
        stack, cells = [start], []
        while stack:
            j = stack.pop()
            cells.append(j)
            x, y = j % size, j // size
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < size and 0 <= ny < size:
                    k = ny * size + nx
                    if not seen[k] and pixels[k][3] != 0:
                        seen[k] = True
                        stack.append(k)
        out.append(cells)
    out.sort(key=len, reverse=True)
    return out


def _outline_darkness(pixels, size):
    """Share of the silhouette's edge pixels that read as a dark outline.

    Edge pixels are opaque cells touching transparency or the canvas border —
    the boundary the `ink` outline is supposed to wrap. Returns (pct, edge_count).
    """
    edge = []
    for i, p in enumerate(pixels):
        if p[3] == 0:
            continue
        x, y = i % size, i // size
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if not (0 <= nx < size and 0 <= ny < size) or pixels[ny * size + nx][3] == 0:
                edge.append(i)
                break
    if not edge:
        return 100.0, 0
    dark = sum(1 for i in edge if _luminance(pixels[i]) < OUTLINE_DARK_LUM)
    return 100.0 * dark / len(edge), len(edge)


def _rgb_distance(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


def seam_discontinuity(pixels, size):
    """How anomalous each wrap edge is, as a share of its pixel pairs.

    Returns (h_pct, v_pct). For each axis the pairs straddling the wrap are
    compared against the distribution of neighbouring-pixel jumps *inside* the
    texture: a wrap pair counts as anomalous when it exceeds both the interior
    95th percentile (by SEAM_P95_FACTOR) and an absolute floor, so a texture
    full of hard edges is not punished for having one more, and a texture with
    no edges at all is not flagged over an invisible step.
    """
    def measure(wrap, interior):
        if not wrap or not interior:
            return 0.0
        ranked = sorted(interior)
        p95 = ranked[min(len(ranked) - 1, int(0.95 * len(ranked)))]
        limit = max(p95 * SEAM_P95_FACTOR, SEAM_MIN_JUMP)
        return 100.0 * sum(1 for w in wrap if w > limit) / len(wrap)

    h = measure(
        [_rgb_distance(pixels[y * size + size - 1], pixels[y * size])
         for y in range(size)],
        [_rgb_distance(pixels[y * size + x], pixels[y * size + x + 1])
         for y in range(size) for x in range(size - 1)])
    v = measure(
        [_rgb_distance(pixels[(size - 1) * size + x], pixels[x])
         for x in range(size)],
        [_rgb_distance(pixels[y * size + x], pixels[(y + 1) * size + x])
         for y in range(size - 1) for x in range(size)])
    return h, v


def roll_half(pixels, size):
    """The texture offset by half its size on both axes.

    Puts both wrap edges through the centre of the image, surrounded by the
    texture's own interior — a seam that disappears there disappears in game.
    The 2×2 tiling answers a different question (does the pattern repeat and
    corner correctly); this one isolates the join.
    """
    half = size // 2
    return [pixels[((y + half) % size) * size + (x + half) % size]
            for y in range(size) for x in range(size)]


def _largest_flat_region(pixels, size):
    """Largest 4-connected region of one opaque color. Returns (count, color)."""
    seen = [False] * (size * size)
    best, best_color = 0, None
    for start in range(size * size):
        if seen[start] or pixels[start][3] == 0:
            continue
        color = pixels[start]
        seen[start] = True
        stack, count = [start], 0
        while stack:
            j = stack.pop()
            count += 1
            x, y = j % size, j // size
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < size and 0 <= ny < size:
                    k = ny * size + nx
                    if not seen[k] and pixels[k] == color:
                        seen[k] = True
                        stack.append(k)
        if count > best:
            best, best_color = count, color
    return best, best_color


def infer_kind(frames_px, size):
    """Classify a spec that declares no `kind:`, from its edge geometry.

    This is the fallback that lets every spec written before the directive keep
    working. It cannot tell a tiling block side from a cap or a UI plate — they
    all bleed — which is exactly why declaring the kind is worth doing.
    """
    ring = [frames_px[0][y * size + x]
            for y in range(size) for x in range(size)
            if x in (0, size - 1) or y in (0, size - 1)]
    opaque = sum(1 for p in ring if p[3] != 0)
    if opaque == 0:
        return "sprite", opaque, len(ring)
    if opaque == len(ring):
        return "block", opaque, len(ring)
    return None, opaque, len(ring)


def analyze(frames_px, size, used_tokens, raw_hex=(), palette="tokens",
            kind=None, edge=None):
    """Objective read-back stats mirroring the mc-textures quality bar.

    Returns (lines, findings). `lines` are stat lines to print; `findings` are
    (severity, text) pairs, where severity is "warning" for a quality-bar
    violation and "note" for something advisory. Malformed specs never reach
    here — those raise SpecError.

    `kind` selects which checks apply (see KIND_CHECKS); when it is None the
    kind is inferred from the edges and the inference is reported as a note.
    `edge` is the spec's declared edge intent (see EDGES), which answers the
    margin question for good instead of leaving it asked on every render.
    """
    lines, findings = [], []

    def warn(text):
        findings.append(("warning", text))

    def note(text):
        findings.append(("note", text))

    declared = kind
    inferred, ring_opaque, ring_len = infer_kind(frames_px, size)
    if kind is None:
        kind = inferred
        checks = KIND_CHECKS[kind] if kind else UNDECLARED_CHECKS
    else:
        checks = KIND_CHECKS[kind]

    if raw_hex and palette != "free":
        shown = ", ".join(f"{c} {v}" for c, v in raw_hex[:4])
        if len(raw_hex) > 4:
            shown += f", +{len(raw_hex) - 4} more"
        note(
            f"{len(raw_hex)} legend "
            f"{'entry uses' if len(raw_hex) == 1 else 'entries use'} "
            f"raw hex ({shown}) — name a design-system token, or a ramp step "
            f"off one (`--snap-palette` suggests the nearest for each). If this "
            f"master is deliberately off-palette, declare 'palette: free'")

    opaque_colors = {p for px in frames_px for p in px if p[3] != 0}
    lines.append(f"colors:   {len(opaque_colors)} opaque")

    if declared is None:
        lines.append(f"kind:     {kind or 'unclear'} (inferred from the edges)")
        note(f"no 'kind:' declared — checks were picked from the edge geometry, "
             f"which reads as {kind or 'neither a sprite nor a full-bleed face'}. "
             f"Declare kind: {'|'.join(KINDS)} to get the right ones "
             f"(--list-kinds explains each)")
    else:
        lines.append(f"kind:     {kind} ({KIND_HELP[kind]})")

    # What the border actually does, then what the spec asked for. A declared
    # `edge:` settles the margin question — the skill has always allowed a
    # sprite to reach its border on purpose, and this is where the author says
    # so — but it cannot settle the bleed one: a block side that stops short of
    # its border shows the void when it tiles, whatever the spec intended.
    actual = ("margin" if ring_opaque == 0
              else "bleed" if ring_opaque == ring_len else "shaped")
    described = {"margin": "transparent 1px margin",
                 "bleed": "full bleed on all four edges",
                 "shaped": f"shaped — {ring_opaque}/{ring_len} edge px opaque"}[actual]
    lines.append(f"edge:     {described}"
                 + (f" (declared: {edge})" if edge else ""))

    if edge is not None and actual != edge:
        warn(f"declares 'edge: {edge}' — {EDGE_HELP[edge]} — but the grid is "
             f"{EDGE_ACTUAL[actual]}; make the declaration and the art agree")
    if "margin" in checks and edge is None and actual != "margin":
        warn(f"a {kind} wants a 1px transparent margin so it reads as one "
             f"motif — this one is {EDGE_ACTUAL[actual]}. If the motif is "
             f"drawn to reach the border, declare it once with "
             f"'edge: {actual}' (a spark or mote that fills its canvas is a "
             f"'kind: particle')")
    if "bleed" in checks and actual != "bleed":
        warn(f"a {kind} bleeds to all four edges — this one is "
             f"{EDGE_ACTUAL[actual]}, so it will not join its neighbours")
    if declared is None and edge is None and actual == "shaped":
        note("edge is neither a clean transparent margin nor a full bleed, so "
             "the kind could not be inferred — declare it")

    # Seam continuity, for the one kind that repeats against copies of itself.
    if "seam" in checks:
        worst_h = max(seam_discontinuity(px, size)[0] for px in frames_px)
        worst_v = max(seam_discontinuity(px, size)[1] for px in frames_px)
        lines.append(
            f"seam:     {worst_h:.0f}% of the left/right join and {worst_v:.0f}% "
            f"of the top/bottom join jump beyond the interior")
        for pct_, axis, fix in ((worst_h, "left/right", "right edge continue into the left"),
                                (worst_v, "top/bottom", "bottom edge continue into the top")):
            if pct_ > SEAM_WARN_PCT:
                warn(f"the {axis} join breaks on {pct_:.0f}% of its length — "
                     f"copies of this texture will show a seam where they meet. "
                     f"Make the {fix} (render with --tile-preview and read the "
                     f"@seam preview back). If this face never repeats, it is a "
                     f"'kind: cap', not a block")

    flat, flat_color, opaque_count = 0, None, 0
    for px in frames_px:
        cnt, col = _largest_flat_region(px, size)
        if cnt > flat:
            flat, flat_color = cnt, col
            opaque_count = sum(1 for p in px if p[3] != 0)
    if opaque_count:
        pct = 100.0 * flat / opaque_count
        hexc = "#{:02x}{:02x}{:02x}".format(*flat_color[:3]) if flat_color else "-"
        lines.append(f"flat:     largest single-tone region {flat} px, {hexc} (~{pct:.0f}% of opaque)")
        # A 16px sprite still owes its surfaces a shading step; it just holds
        # fewer of them, so it takes a larger share of one tone to read as flat.
        pct_limit, min_px = ((FLAT_PCT_LARGE, FLAT_MIN_LARGE) if size >= 32
                             else (FLAT_PCT_SMALL, FLAT_MIN_SMALL))
        if "flat" in checks and flat >= min_px and pct >= pct_limit:
            warn(f"a {flat}px single-tone region (~{pct:.0f}% of the opaque area) reads "
                 f"as a flat fill at {size}px — give that surface a tonal ramp. "
                 f"If the flat area is the design (a panel, a 9-slice frame), "
                 f"this is a 'kind: ui'")

    # Silhouette outline — a sprite thing. A block or cap bleeds to every edge so
    # it has no silhouette, and a pip or spark is too small to wrap. Gate on the
    # frame being measured, not whichever frame held the largest flat region.
    frame0_opaque = sum(1 for p in frames_px[0] if p[3] != 0)
    if "outline" in checks and frame0_opaque >= OUTLINE_MIN_AREA:
        dark_pct, edge_count = _outline_darkness(frames_px[0], size)
        lines.append(f"outline:  {dark_pct:.0f}% of the {edge_count}px silhouette edge reads dark")
        if dark_pct < OUTLINE_DARK_PCT:
            warn(f"only {dark_pct:.0f}% of the silhouette edge is dark — wrap the "
                 f"motif in an `ink` outline so it reads against any background "
                 f"(a deliberately glowing motif is the exception)")

    # Detached pieces are reported, not warned about: shipped art legitimately
    # carries them (a glint, a hanging chain link), and nothing in the grid
    # distinguishes those from a stray pixel. Worth a look, not a failure.
    pieces = _components(frames_px[0], size)
    if len(pieces) > 1:
        detached = [len(c) for c in pieces[1:]]
        lines.append(
            f"pieces:   {len(pieces)} detached regions — main {len(pieces[0])} px, "
            f"others {', '.join(str(d) for d in detached[:6])}"
            f"{' …' if len(detached) > 6 else ''} (intended?)")

    # Animation: a frame identical to the one before it spends its frametime
    # doing nothing, which is nearly always a copy-paste that was meant to move.
    if len(frames_px) > 1:
        dupes = [i for i in range(len(frames_px))
                 if frames_px[i] == frames_px[i - 1]]
        if dupes:
            where = ", ".join(
                (f"frame {i + 1} repeats frame {len(frames_px)}" if i == 0
                 else f"frame {i + 1} repeats frame {i}") for i in dupes)
            warn(f"{where} — an identical frame holds the animation still for its "
                 f"frametime; drop it or make it move")

    # Accent ownership (DESIGN-SYSTEM.md §2 rule 1). Resolved rather than read
    # off the spelling: a bare alias carries its mod's identity exactly as the
    # prefixed form does, so counting only namespaced tokens would let a legend
    # opt out of the rule by writing `crimson` for `tribulation.crimson`. Shared
    # neutrals and shared material tones own nothing and never mix.
    owned = {t: ACCENT_OWNERS.get(t, frozenset()) for t in used_tokens}
    owned = {t: mods for t, mods in owned.items() if mods}
    if owned:
        # Intersected, not counted: a colour two mods both list as an accent is
        # accounted for by either of them, and the legend is only mixed when no
        # single mod can account for all of it.
        if not frozenset.intersection(*owned.values()):
            by_mod = {}
            for token, mods in sorted(owned.items()):
                for mod in mods:
                    by_mod.setdefault(mod, []).append(token)
            # Name the tokens, not just the mods: a legend whose only Meridian
            # colour is spelled `arcane` would otherwise be told it mixes in a
            # mod that appears nowhere in it.
            where = " and ".join(f"{mod} ({', '.join(by_mod[mod])})"
                                 for mod in sorted(by_mod))
            warn(f"legend mixes accents from {where} — a mod's accents never "
                 f"appear in another mod's glyph; use your own mod's accent, or "
                 f"a {SHARED_NAMESPACES[0]}. tone where it is a material")

    return lines, findings


def render_ascii(pixels, width, height):
    """A terminal preview: filled block for opaque cells, space for transparent."""
    if width > ASCII_MAX_SIZE:
        return (f"[{width}×{height} — silhouette dump skipped past "
                f"{ASCII_MAX_SIZE}px; read the preview PNG instead]")
    lines = []
    for y in range(height):
        row = []
        for x in range(width):
            row.append("·" if pixels[y * width + x][3] == 0 else "█")
        lines.append("".join(row))
    return "\n".join(lines)


def preview_factor(size, requested):
    """The nearest-neighbor factor for a review preview of an `size`px grid.

    An explicit `requested` is honoured as given — the caller asked for it. The
    default is capped so the preview lands near PREVIEW_MAX_PX instead of
    scaling a large master into an image nobody benefits from.
    """
    if requested is not None:
        return requested
    return max(1, min(DEFAULT_PREVIEW_SCALE, PREVIEW_MAX_PX // max(1, size)))


def emit_review(out, frames_px, size, used_tokens, meta, args):
    """The read-back block after a render: tile preview, stats, warnings."""
    if args.tile_preview:
        base_factor = preview_factor(size, args.preview_scale)
        tiled, tsize = make_tiled(frames_px[0], size)
        factor = max(1, base_factor // 2)
        tpx, tw, th = scale_nearest(tiled, tsize, tsize, factor)
        tile_path = out.with_name(f"{out.stem}@2x2{out.suffix}")
        write_png(tile_path, tpx, tw, th)
        print(f"  wrote {tile_path}  ({tw}×{th} 2×2 tiling preview — check the pattern repeats and corners)")
        # The 2×2 shows the pattern repeating; this shows the join itself, with
        # both wrap edges crossing at the centre of the image and the texture's
        # own interior all around them. A seam that vanishes here vanishes in game.
        rolled = roll_half(frames_px[0], size)
        rpx, rw, rh = scale_nearest(rolled, size, size, base_factor)
        seam_path = out.with_name(f"{out.stem}@seam{out.suffix}")
        write_png(seam_path, rpx, rw, rh)
        print(f"  wrote {seam_path}  ({rw}×{rh} seam-centred — the wrap edges cross "
              f"in the middle; look for a line through the centre)")
    lines, findings = analyze(frames_px, size, used_tokens,
                              meta.get("raw_hex", ()),
                              meta.get("palette", "tokens"),
                              meta.get("kind"), meta.get("edge"))
    for ln in lines:
        print(f"  {ln}")
    # Warnings are quality-bar violations; notes are advisory. Keeping them
    # apart is what stops a hundred palette notes from burying one real seam.
    for severity, text in findings:
        print(f"glyph: {severity}: {text}", file=sys.stderr)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Render an ASCII glyph spec to a PNG sprite.")
    ap.add_argument("spec", nargs="?", help="path to the glyph spec, or '-' for stdin")
    ap.add_argument("-o", "--out", help="output PNG path (default: SPEC with .png)")
    ap.add_argument("--preview-scale", type=int, default=None,
                    help=f"nearest-neighbor preview factor. Default: ×"
                         f"{DEFAULT_PREVIEW_SCALE}, reduced so the preview lands "
                         f"near {PREVIEW_MAX_PX}px (a 16px glyph previews at ×16, "
                         f"a 128px master at ×4). Pass a value to override the cap")
    ap.add_argument("--scale-to", type=int, metavar="N",
                    help="write a real master re-tiered to N×N: an integer multiple "
                         "of the native grid upscales nearest-neighbor, a smaller N "
                         "downscales (the mod-icon ladder, where a 512 master derives "
                         "its 256 and 128 copies) by whatever the spec's 'downscale:' "
                         "line selects. Unlike --preview-scale this output IS the "
                         "master, not a '@Nx' preview")
    ap.add_argument("--split-frames", action="store_true",
                    help="for an animated spec, write each frame as a standalone "
                         "<name>_<i>.png (no strip, no .mcmeta) instead of a vertical "
                         "strip — the packaging for a texture your own code binds and "
                         "advances (custom render type, HUD icon, GUI blit), which a "
                         "strip+.mcmeta can be reinterpreted and broken by. A spec that "
                         "always ships this way says so with 'frames: split' instead, "
                         "so --verify expects the same files")
    ap.add_argument("--from-png", action="store_true",
                    help="reverse direction: transcribe the given raster PNG "
                         "master into a .glyph spec (default: alongside the "
                         "input with a .glyph suffix). Transparent pixels "
                         "become '.', each distinct color gets a legend token; "
                         "the emitted spec re-renders pixel-identical")
    ap.add_argument("--tile-preview", action="store_true",
                    help="also write a 2×2 tiled preview (<name>@2x2.png) — the "
                         "seam/corner check for tiling block textures")
    ap.add_argument("--verify", action="store_true",
                    help="don't write: re-render the spec and compare it against "
                         "the shipped master(s) already at the output path, "
                         "reporting any pixel that drifted. Exits non-zero on "
                         "drift — the CI form of the repeatability rule")
    ap.add_argument("--verify-all", nargs="?", const="art/glyphs", metavar="DIR",
                    help="verify every spec under DIR (default art/glyphs), at "
                         "any depth, against the masters it declares with "
                         "'ships:', and report the ones that declare none. "
                         "Exits non-zero on drift or on a spec that no longer "
                         "parses — run it in CI to hold the whole repo's "
                         "textures to their specs")
    ap.add_argument("--ramp", metavar="TOKEN",
                    help="print a tonal ramp off a named token as paste-ready "
                         "legend lines, and exit. A legend can name any step of "
                         "a token directly (e.g. 'mercantile.emerald-2', 'metal.gold+1'), which "
                         "is how a shaded glyph stays on named tokens instead of "
                         "falling back to raw hex")
    ap.add_argument("--ramp-steps", type=int, default=5, metavar="N",
                    help="how many steps --ramp emits (2..7, default 5)")
    ap.add_argument("--snap-palette", action="store_true",
                    help="for each raw-hex legend entry, report the nearest "
                         "design-system token or ramp step and how far off it "
                         "is — the migration path for a legend written before "
                         "ramp steps existed. Suggests; never rewrites")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="with --verify-all, also list the specs that verified clean")
    ap.add_argument("--no-preview", action="store_true", help="skip the scaled preview PNG")
    ap.add_argument("--list-colors", action="store_true", help="print the named palette and exit")
    ap.add_argument("--list-kinds", action="store_true",
                    help="print the texture kinds and the checks each earns, and exit")
    args = ap.parse_args(argv)

    if args.list_kinds:
        width = max(len(k) for k in KINDS)
        for k in KINDS:
            checks = ", ".join(sorted(KIND_CHECKS[k])) or "none beyond the shared ones"
            print(f"  {k.ljust(width)}  {KIND_HELP[k]}")
            print(f"  {' ' * width}  checks: {checks}")
        print("\n  Shared by every kind: palette, mixed-mod accents, detached "
              "pieces, duplicate animation frames.")
        ewidth = max(len(e) for e in EDGES)
        print("\n  An 'edge:' line records what the motif does at the border, "
              "for art that\n  means to do something other than its kind's "
              "default. It is measured\n  against the grid like everything "
              "else — it declares intent, it doesn't\n  silence the check:")
        for e in EDGES:
            print(f"    {e.ljust(ewidth)}  {EDGE_HELP[e]}")
        have = magick_binary()
        print(f"\n  A 'downscale:' line selects how a `ships:` tier smaller than "
              f"the grid is\n  resampled: 'box' (the default) is built in and "
              f"exact at a whole factor;\n  {', '.join(MAGICK_FILTERS)} are "
              f"ImageMagick's, and resample any ratio.\n  ImageMagick here: "
              f"{have or 'not installed — box is the only filter available'}")
        return 0

    if args.list_colors:
        width = max(len(k) for k in NAMED_COLORS)
        # Grouped by who owns what, because that is the rule a legend has to
        # hold to — a flat dump reads as one undifferentiated palette and is
        # how a mod's accent ends up in another mod's glyph.
        roles = "shared neutrals — the text/surface roles, owned by no mod"
        metals = "shared material tones — a material rather than a brand"
        accents = "per-mod accents — never appear in another mod's art"
        aliases = "bare aliases — the same colours without the prefix; the owner is unchanged"
        groups = {roles: [], metals: [], accents: [], aliases: []}
        for name in NAMED_COLORS:
            if "." in name:
                key = metals if name.split(".", 1)[0] in SHARED_NAMESPACES else accents
            else:
                key = aliases if _alias_targets(name) else roles
            groups[key].append(name)
        for heading, names in groups.items():
            if not names:
                continue
            print(f"  {heading}")
            for name in names:
                owners = ACCENT_OWNERS[name]
                owned_by = ""
                if _alias_targets(name):
                    owned_by = ("  = " + " + ".join(sorted(owners)) if owners
                                else "  = shared material tone")
                print(f"    {name.ljust(width)}  {NAMED_COLORS[name]}{owned_by}")
        print(f"\n  Any token takes a ramp step: '<token>+N' lifts toward the "
              f"highlight,\n  '<token>-N' drops toward shadow (N up to "
              f"{RAMP_MAX_STEP}). Run --ramp <token> for a ready-made ramp.")
        return 0

    # `is not None`, not truthiness: the question is whether the flag was
    # given, and an empty DIR would otherwise fall through to the render
    # path and complain about a missing spec.
    if args.verify_all is not None:
        checked, drifted, broken, blocked, unlinked = verify_tree(
            args.verify_all, args.verbose)
        print(f"  {checked} verified, {drifted} drifted, {broken} malformed, "
              f"{blocked} blocked, {unlinked} unlinked")
        return 1 if (drifted or broken or blocked) else 0

    if args.ramp:
        try:
            for line in format_ramp(args.ramp.strip().lower(), args.ramp_steps):
                print(line)
        except SpecError as e:
            print(f"glyph: {e}", file=sys.stderr)
            return 1
        return 0

    if not args.spec:
        ap.error("a spec path (or '-' for stdin) is required")

    if args.from_png:
        in_path = Path(args.spec)
        out = Path(args.out) if args.out else in_path.with_suffix(".glyph")
        try:
            size, ncolors = transcribe_png(in_path, out)
        except (SpecError, OSError) as e:
            print(f"glyph: {e}", file=sys.stderr)
            return 1
        print(f"  wrote {out}  ({size}×{size}, {ncolors} colors + transparent, "
              f"round-trip verified pixel-identical)")
        return 0

    if args.spec == "-":
        text = sys.stdin.read()
        default_out = Path("glyph.png")
    else:
        spec_path = Path(args.spec)
        text = spec_path.read_text()
        default_out = spec_path.with_suffix(".png")

    try:
        legend, frames_rows, declared_size, meta, used_tokens = parse_spec(text)
        frames_px, size = build_frames(legend, frames_rows, declared_size)
    except SpecError as e:
        print(f"glyph: {e}", file=sys.stderr)
        return 1

    if args.snap_palette:
        raw = meta.get("raw_hex", ())
        if not raw:
            print(f"  {args.spec} has no raw-hex legend entries — already on "
                  f"design-system tokens")
            return 0
        print(f"  {len(raw)} raw-hex legend "
              f"{'entry' if len(raw) == 1 else 'entries'} in {args.spec}:")
        for line in snap_palette(raw):
            print(line)
        return 0

    out = Path(args.out) if args.out else default_out
    nframes = len(frames_px)
    ft = meta.get("frametime", DEFAULT_FRAMETIME)

    if args.verify:
        # An explicit -o wins; otherwise the spec's own `ships:` lines say what
        # to check, so a size ladder verifies every tier it declares in one run.
        targets = [(out, args.scale_to)] if args.out else None
        if targets is None and not meta.get("ships"):
            print(f"glyph: {args.spec} declares no 'ships:' target and no -o was "
                  f"given — nothing to verify against", file=sys.stderr)
            return 1
        try:
            checked, problems = verify_spec(frames_px, size, meta, targets,
                                            args.split_frames)
        except (SpecError, ToolError) as e:
            print(f"glyph: {args.spec}: {e}", file=sys.stderr)
            return 1
        for p in problems:
            print(f"glyph: drift: {p}", file=sys.stderr)
        if problems:
            print(f"glyph: {len(problems)} shipped file(s) no longer match "
                  f"{args.spec} — re-render the spec to the shipped path",
                  file=sys.stderr)
            return 1
        print(f"  verified {', '.join(checked)} matches {args.spec} — pixel-identical")
        return 0

    # One description of what this spec ships, shared by the write path and
    # --verify — so what gets checked is by construction what gets written.
    try:
        artifacts, mcmeta = master_artifacts(
            frames_px, size, meta, out, args.split_frames, args.scale_to)
    except (SpecError, ToolError) as e:
        print(f"glyph: {e}", file=sys.stderr)
        return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    for path, px, w, h in artifacts:
        write_png(path, px, w, h)
    if mcmeta:
        write_mcmeta(*mcmeta)

    # A re-tiered master is a mechanical resize of a grid already reviewed at
    # native size, so it skips the ASCII dump, the preview, and the read-back.
    if args.scale_to is not None:
        filt = meta.get("downscale", "box")
        if args.scale_to >= size:
            how = f"nearest-neighbor ×{args.scale_to // size}"
        elif filt == "box" and size % args.scale_to == 0:
            how = f"area-average ÷{size // args.scale_to}"
        else:
            how = f"ImageMagick {filt}"
        for path, _px, w, h in artifacts:
            print(f"  wrote {path}  ({w}×{h} master, {how} from {size}px)")
        if mcmeta:
            print(f"  wrote {mcmeta[0]}")
        return 0

    if nframes == 1:
        print(render_ascii(frames_px[0], size, size))
        print(f"\n  wrote {out}  ({size}×{size})")
        scale = preview_factor(size, args.preview_scale)
        if not args.no_preview and scale > 1:
            spx, sw, sh = scale_nearest(frames_px[0], size, size, scale)
            preview = out.with_name(f"{out.stem}@{scale}x{out.suffix}")
            write_png(preview, spx, sw, sh)
            print(f"  wrote {preview}  ({sw}×{sh} preview)")
        emit_review(out, frames_px, size, used_tokens, meta, args)
        return 0

    # animated: a vertical strip + .mcmeta sidecar (vanilla atlas animates it),
    # or, with `frames: split` / --split-frames, one standalone PNG per frame
    # (your code binds and advances it).
    for i, px in enumerate(frames_px, 1):
        print(f"frame {i}/{nframes}")
        print(render_ascii(px, size, size))
        print()
    if mcmeta is None:
        print(f"  wrote {nframes} standalone frames {out.stem}_0..{nframes - 1}{out.suffix}  "
              f"({size}×{size} each, no strip/.mcmeta; drive frametime {ft} from your own timer)")
    else:
        _, _, sw, sh = artifacts[0]
        print(f"  wrote {out}  ({sw}×{sh} strip, {nframes} frames)")
        print(f"  wrote {mcmeta[0]}  (frametime {ft}, interpolate {meta.get('interpolate', False)})")

    scale = preview_factor(size, args.preview_scale)
    if not args.no_preview and scale > 1:
        # Filmstrip of stills — every frame side-by-side, for frame-by-frame review.
        # The filmstrip is N frames wide, so it is scaled by the per-frame factor
        # divided down — otherwise a long animation makes an unreadably wide image.
        film_scale = max(1, min(scale, PREVIEW_MAX_PX // max(1, size * nframes)))
        film_px, fw, fh = make_filmstrip(frames_px, size)
        spx, psw, psh = scale_nearest(film_px, fw, fh, film_scale)
        preview = out.with_name(f"{out.stem}@{film_scale}x{out.suffix}")
        write_png(preview, spx, psw, psh)
        print(f"  wrote {preview}  ({psw}×{psh} filmstrip preview)")

        # Animated APNG — the real motion, full alpha, for watching the loop.
        scaled = [scale_nearest(px, size, size, scale)[0] for px in frames_px]
        ssz = size * scale
        anim_preview = out.with_name(f"{out.stem}@{scale}x-anim{out.suffix}")
        write_apng(anim_preview, scaled, ssz, ssz, ft)
        print(f"  wrote {anim_preview}  ({ssz}×{ssz} animated preview, {nframes} frames @ {ft} ticks)")

    emit_review(out, frames_px, size, used_tokens, meta, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
