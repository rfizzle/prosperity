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

`--scale-to N` mints a true high-res master by nearest-neighbor upscale (N an
integer multiple of the native grid), the honest way to fill the large tiers of
a 16/32/64/128/256 size ladder from a native master — for static glyphs and
animated strips alike.

`--from-png IN.png` runs the pipeline in reverse: it transcribes a finished
raster master into a .glyph spec (transparent pixels -> '.', each distinct
color -> a legend token with a raw-hex entry) so a texture that predates its
spec joins the repeatability rule. The emitted spec re-renders pixel-identical
— verified before it is written.

Zero dependencies: every PNG/APNG is encoded with the stdlib (`zlib` + manual
chunks), so this runs anywhere Python 3 does, no `pip install` required.

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

    legend:
      . transparent         # '.' is transparent by convention
      g gold                # #ffd700  (hex, or a named Concord token)
      d diamond             # #4eeaed
      K ink                 # #0a0a0a outline

    frame:                  # frame 1 — <size> rows of <size> legend chars
      ................
      ... (16 rows) ...
    frame:                  # frame 2
      ................
      ... (16 rows) ...

Colors may be:  `transparent` / `none`,  `#RGB`,  `#RRGGBB`,  `#RRGGBBAA`,
or a named token from NAMED_COLORS (the Concord design-system palette).

USAGE
-----
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph                 # -> SPEC.png (+ .mcmeta if animated) + preview
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --split-frames  # -> SPEC_0.png, SPEC_1.png … (code-driven anim; no strip/.mcmeta)
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph -o art/marker.png
    python3 .ai/skills/mc-textures/scripts/glyph.py - < SPEC.glyph             # spec on stdin
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --preview-scale 24 --no-preview
    python3 .ai/skills/mc-textures/scripts/glyph.py SPEC.glyph --tile-preview  # + 2×2 tiled seam check (block textures)
    python3 .ai/skills/mc-textures/scripts/glyph.py --from-png MASTER.png      # raster -> .glyph spec (transcription)
    python3 .ai/skills/mc-textures/scripts/glyph.py --list-colors              # dump the named palette

Every render also prints read-back stats (opaque color count, edge margin vs
full bleed, largest single-tone region) and warns on quality-bar violations:
a big flat fill, a half-bled edge, or a legend mixing two mods' accents.
"""

import argparse
import json
import struct
import sys
import zlib
from pathlib import Path

# Concord design-system palette (DESIGN-SYSTEM.md §1–§2). Legend entries may
# reference these names instead of raw hex. Per-mod accents are namespaced so a
# spec can't accidentally borrow another mod's identity color.
NAMED_COLORS = {
    # shared neutrals
    "ink": "#0a0a0a",
    "card": "#1a1a1a",
    "elevated": "#222222",
    "bone": "#e8e0d4",
    "ash": "#a89f93",
    "smoke": "#6b6359",
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
    # bare convenience aliases (unambiguous accents)
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

TRANSPARENT = (0, 0, 0, 0)
DEFAULT_FRAMETIME = 6  # ticks (0.3s) — a calm, readable default pulse
_DIRECTIVES = ("grid:", "frame:")

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


def parse_color(token):
    """Resolve a legend color token to an (r, g, b, a) tuple."""
    t = token.strip().lower()
    if t in ("transparent", "none", "_"):
        return TRANSPARENT
    if t in NAMED_COLORS:
        t = NAMED_COLORS[t]
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
    """Parse a glyph spec into (legend, frames, declared_size, anim, used_tokens).

    `frames` is a list of grids; each grid is a list of row strings.
    `anim` carries frametime / interpolate directives (may be empty).
    `used_tokens` is the set of NAMED_COLORS tokens the legend referenced.
    """
    legend = {}
    frames = []          # list of grids (each a list of row strings)
    current = None       # the grid currently being filled
    declared_size = None
    anim = {}
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
        if low.startswith("frametime:"):
            try:
                anim["frametime"] = int(line.split(":", 1)[1].split()[0])
            except (ValueError, IndexError):
                raise SpecError(f"line {lineno}: frametime: must be an integer")
            continue
        if low.startswith("interpolate:"):
            val = line.split(":", 1)[1].strip().lower()
            anim["interpolate"] = val in ("true", "1", "yes")
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
            legend[char] = parse_color(color)
            if color.strip().lower() in NAMED_COLORS:
                used_tokens.add(color.strip().lower())
        else:
            raise SpecError(
                f"line {lineno}: unexpected content before a 'legend:' or 'frame:' "
                f"directive: {line!r}"
            )

    flush()
    if not frames or all(not f for f in frames):
        raise SpecError("spec has no frame grids (need a 'frame:' or 'grid:' section)")
    legend.setdefault(".", TRANSPARENT)
    return legend, frames, declared_size, anim, used_tokens


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


def write_mcmeta(path, anim):
    """Write the vanilla animated-texture sidecar next to the sprite strip."""
    data = {
        "animation": {
            "frametime": anim.get("frametime", DEFAULT_FRAMETIME),
            "interpolate": anim.get("interpolate", False),
        }
    }
    Path(path).write_text(json.dumps(data, indent=2) + "\n")


def scale_nearest(pixels, width, height, factor):
    """Nearest-neighbor upscale (keeps pixels crisp, no blur)."""
    out = []
    for y in range(height * factor):
        sy = y // factor
        for x in range(width * factor):
            out.append(pixels[sy * width + (x // factor)])
    return out, width * factor, height * factor


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
             f"size: {w}", "", "legend:", "  . transparent"]
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


def make_tiled(pixels, size, reps=2):
    """Repeat a frame reps×reps with no separator — the seam/corner check for
    tiling block textures (adjacent copies must join invisibly)."""
    out = []
    for y in range(size * reps):
        for x in range(size * reps):
            out.append(pixels[(y % size) * size + (x % size)])
    return out, size * reps


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


def analyze(frames_px, size, used_tokens):
    """Objective read-back stats mirroring the mc-textures quality bar.

    Returns (lines, warnings): human-readable stat lines plus the subset of
    findings that deserve a stderr warning.
    """
    lines, warnings = [], []

    opaque_colors = {p for px in frames_px for p in px if p[3] != 0}
    lines.append(f"colors:   {len(opaque_colors)} opaque")

    ring = [frames_px[0][y * size + x]
            for y in range(size) for x in range(size)
            if x in (0, size - 1) or y in (0, size - 1)]
    ring_opaque = sum(1 for p in ring if p[3] != 0)
    if ring_opaque == 0:
        lines.append("edge:     transparent 1px margin (sprite)")
    elif ring_opaque == len(ring):
        lines.append("edge:     full bleed on all four edges (block/tile)")
    else:
        lines.append(f"edge:     mixed — {ring_opaque}/{len(ring)} edge px opaque")
        warnings.append(
            "edge is neither a clean transparent margin (sprite) nor a full "
            "bleed (block) — pick one deliberately")

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
        if size >= 32 and flat >= 40 and pct >= 30:
            warnings.append(
                f"a {flat}px single-tone region (~{pct:.0f}% of the opaque area) reads "
                f"as a flat fill at {size}px — give that surface a tonal ramp")

    prefixes = {t.split(".", 1)[0] for t in used_tokens if "." in t}
    if len(prefixes) > 1:
        warnings.append(
            f"legend mixes accents from {' and '.join(sorted(prefixes))} — "
            f"a mod's accents never appear in another mod's glyph")

    return lines, warnings


def render_ascii(pixels, width, height):
    """A terminal preview: filled block for opaque cells, space for transparent."""
    lines = []
    for y in range(height):
        row = []
        for x in range(width):
            row.append("·" if pixels[y * width + x][3] == 0 else "█")
        lines.append("".join(row))
    return "\n".join(lines)


def emit_review(out, frames_px, size, used_tokens, args):
    """The read-back block after a render: tile preview, stats, warnings."""
    if args.tile_preview:
        tiled, tsize = make_tiled(frames_px[0], size)
        factor = max(1, args.preview_scale // 2)
        tpx, tw, th = scale_nearest(tiled, tsize, tsize, factor)
        tile_path = out.with_name(f"{out.stem}@2x2{out.suffix}")
        write_png(tile_path, tpx, tw, th)
        print(f"  wrote {tile_path}  ({tw}×{th} 2×2 tiling preview — check the seams and the shared corner)")
    lines, warnings = analyze(frames_px, size, used_tokens)
    for ln in lines:
        print(f"  {ln}")
    for w in warnings:
        print(f"glyph: warning: {w}", file=sys.stderr)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Render an ASCII glyph spec to a PNG sprite.")
    ap.add_argument("spec", nargs="?", help="path to the glyph spec, or '-' for stdin")
    ap.add_argument("-o", "--out", help="output PNG path (default: SPEC with .png)")
    ap.add_argument("--preview-scale", type=int, default=16,
                    help="nearest-neighbor preview factor (default 16 → 256px for a 16px glyph)")
    ap.add_argument("--scale-to", type=int, metavar="N",
                    help="write a real master upscaled to N×N by nearest-neighbor "
                         "(N must be an integer multiple of the native grid size). "
                         "Use this to mint the high-res tiers of a size ladder from a "
                         "native master — unlike --preview-scale this output IS the master, "
                         "not a '@Nx' preview")
    ap.add_argument("--split-frames", action="store_true",
                    help="for an animated spec, write each frame as a standalone "
                         "<name>_<i>.png (no strip, no .mcmeta) instead of a vertical "
                         "strip — the packaging for a texture your own code binds and "
                         "advances (custom render type, HUD icon, GUI blit), which a "
                         "strip+.mcmeta can be reinterpreted and broken by")
    ap.add_argument("--from-png", action="store_true",
                    help="reverse direction: transcribe the given raster PNG "
                         "master into a .glyph spec (default: alongside the "
                         "input with a .glyph suffix). Transparent pixels "
                         "become '.', each distinct color gets a legend token; "
                         "the emitted spec re-renders pixel-identical")
    ap.add_argument("--tile-preview", action="store_true",
                    help="also write a 2×2 tiled preview (<name>@2x2.png) — the "
                         "seam/corner check for tiling block textures")
    ap.add_argument("--no-preview", action="store_true", help="skip the scaled preview PNG")
    ap.add_argument("--list-colors", action="store_true", help="print the named palette and exit")
    args = ap.parse_args(argv)

    if args.list_colors:
        width = max(len(k) for k in NAMED_COLORS)
        for name, hex_ in NAMED_COLORS.items():
            print(f"  {name.ljust(width)}  {hex_}")
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
        legend, frames_rows, declared_size, anim, used_tokens = parse_spec(text)
        frames_px, size = build_frames(legend, frames_rows, declared_size)
    except SpecError as e:
        print(f"glyph: {e}", file=sys.stderr)
        return 1

    out = Path(args.out) if args.out else default_out
    out.parent.mkdir(parents=True, exist_ok=True)
    nframes = len(frames_px)

    if args.split_frames and nframes == 1:
        print("glyph: --split-frames needs an animated spec (2+ frames)", file=sys.stderr)
        return 1

    # --scale-to mints a true high-res master by nearest-neighbor upscale, the
    # honest way to fill the large tiers of a size ladder from a native master.
    # Works for both static glyphs and animated sprite strips.
    if args.scale_to is not None:
        target = args.scale_to
        if target < size or target % size != 0:
            print(
                f"glyph: --scale-to {target} must be a positive integer multiple "
                f"of the native size {size} (e.g. {size*2}, {size*4}, {size*8})",
                file=sys.stderr,
            )
            return 1
        factor = target // size
        scaled = [scale_nearest(px, size, size, factor)[0] for px in frames_px]
        if nframes == 1:
            write_png(out, scaled[0], target, target)
            print(f"  wrote {out}  ({target}×{target} master, nearest-neighbor ×{factor} from {size}px)")
        elif args.split_frames:
            for i, px in enumerate(scaled):
                fp = out.with_name(f"{out.stem}_{i}{out.suffix}")
                write_png(fp, px, target, target)
                print(f"  wrote {fp}  ({target}×{target} frame {i}, ×{factor} from {size}px)")
        else:
            strip_px, sw, sh = stack_vertical(scaled, target)
            write_png(out, strip_px, sw, sh)
            mcmeta = out.with_name(out.name + ".mcmeta")
            write_mcmeta(mcmeta, anim)
            print(f"  wrote {out}  ({sw}×{sh} strip master, ×{factor} from {size}px, {nframes} frames)")
            print(f"  wrote {mcmeta}")
        return 0

    if nframes == 1:
        write_png(out, frames_px[0], size, size)
        print(render_ascii(frames_px[0], size, size))
        print(f"\n  wrote {out}  ({size}×{size})")
        if not args.no_preview and args.preview_scale > 1:
            spx, sw, sh = scale_nearest(frames_px[0], size, size, args.preview_scale)
            preview = out.with_name(f"{out.stem}@{args.preview_scale}x{out.suffix}")
            write_png(preview, spx, sw, sh)
            print(f"  wrote {preview}  ({sw}×{sh} preview)")
        emit_review(out, frames_px, size, used_tokens, args)
        return 0

    # animated: a vertical strip + .mcmeta sidecar (vanilla atlas animates it), or,
    # with --split-frames, one standalone PNG per frame (your code binds and advances it).
    ft = anim.get("frametime", DEFAULT_FRAMETIME)
    if args.split_frames:
        for i, px in enumerate(frames_px):
            write_png(out.with_name(f"{out.stem}_{i}{out.suffix}"), px, size, size)
    else:
        strip_px, sw, sh = stack_vertical(frames_px, size)
        write_png(out, strip_px, sw, sh)
        mcmeta = out.with_name(out.name + ".mcmeta")
        write_mcmeta(mcmeta, anim)

    for i, px in enumerate(frames_px, 1):
        print(f"frame {i}/{nframes}")
        print(render_ascii(px, size, size))
        print()
    if args.split_frames:
        print(f"  wrote {nframes} standalone frames {out.stem}_0..{nframes - 1}{out.suffix}  "
              f"({size}×{size} each, no strip/.mcmeta; drive frametime {ft} from your own timer)")
    else:
        print(f"  wrote {out}  ({sw}×{sh} strip, {nframes} frames)")
        print(f"  wrote {mcmeta}  (frametime {ft}, interpolate {anim.get('interpolate', False)})")

    if not args.no_preview and args.preview_scale > 1:
        # Filmstrip of stills — every frame side-by-side, for frame-by-frame review.
        film_px, fw, fh = make_filmstrip(frames_px, size)
        spx, psw, psh = scale_nearest(film_px, fw, fh, args.preview_scale)
        preview = out.with_name(f"{out.stem}@{args.preview_scale}x{out.suffix}")
        write_png(preview, spx, psw, psh)
        print(f"  wrote {preview}  ({psw}×{psh} filmstrip preview)")

        # Animated APNG — the real motion, full alpha, for watching the loop.
        scale = args.preview_scale
        scaled = [scale_nearest(px, size, size, scale)[0] for px in frames_px]
        ssz = size * scale
        anim_preview = out.with_name(f"{out.stem}@{scale}x-anim{out.suffix}")
        write_apng(anim_preview, scaled, ssz, ssz, ft)
        print(f"  wrote {anim_preview}  ({ssz}×{ssz} animated preview, {nframes} frames @ {ft} ticks)")

    emit_review(out, frames_px, size, used_tokens, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
