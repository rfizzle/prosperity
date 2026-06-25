#!/usr/bin/env python3
"""Generate the Prosperity mod icon as a single self-contained .glyph.

Matches the Concord house style of the Meridian/Mercantile icons — a motif in a
circular medallion over a dark stone-brick field. The medallion FRAME (gold glow
halo, gold bezel ring, dark brick field, cyan gem studs at the four cardinals) is
computed mathematically; the treasure-chest motif is the exact hand-pixeled art
from `art/chest-256.png` (itself glyph-sourced), whose opaque pixels are stamped
into the center of the same grid. The result is emitted as ONE ascii-grid
`icon.glyph` (45 colors) — glyph.py rasterizes it standalone, with no run-time
compositing. The chest is read once at generate time only because hand art has no
mathematical generator; the emitted glyph needs nothing but itself.

Requires ImageMagick (`convert`) and glyph.py.

Run:  python3 art/glyphs/icon.gen.py
      python3 .ai/skills/mc-textures/scripts/glyph.py art/glyphs/icon.glyph -o art/icon-512.png
"""
import math
import re
import subprocess

N = 512
CX = CY = (N - 1) / 2.0
CHEST = "art/chest-256.png"
OUT_GLYPH = "art/glyphs/icon.glyph"
OUT_MASTER = "art/icon-512.png"
OUT_128 = "art/icon-128.png"

# Medallion radii; the chest's opaque content reaches ~112px from center at native
# 256, so the ring's inner edge clears it with margin.
R_IN = 178.0
R_OUT = 226.0
CHEST_OFF = (N - 256) // 2      # native-256 chest centered on the 512 grid
CHEST_DY = 10                   # nudge the chest down so the open lid sits high

COL = {
    'ink':    '#0a0a0a',
    'g_spec': '#fff3c0', 'g_hi': '#ffe066', 'g': '#ffd700',
    'g_mid':  '#daa520', 'g_dk': '#b8860b', 'g_sh': '#8b6914',
    'glow1':  '#ffd700b0', 'glow2': '#daa52078', 'glow3': '#daa52040',
    'st':     '#241e24', 'st_lit': '#312733', 'st_dk': '#180f18',
    'mortar': '#120a12', 'vig': '#0d070d',
    'gem_co': '#eafdfd', 'gem': '#4eeaed', 'gem_dk': '#2a9fb8',
}

# G cells hold either a COL key (frame) or a raw "#rrggbb" (stamped chest pixel).
G = [[None] * N for _ in range(N)]
S = {}


def put(x, y, k):
    xi, yi = int(round(x)), int(round(y))
    if 0 <= xi < N and 0 <= yi < N:
        G[yi][xi] = k


def sput(x, y, k):
    xi, yi = int(round(x)), int(round(y))
    if 0 <= xi < N and 0 <= yi < N:
        S[(xi, yi)] = k


def dist(x, y):
    return math.hypot(x - CX, y - CY)


def ang(x, y):
    return math.atan2(y - CY, x - CX)


# ---- 1. gold glow halo -----------------------------------------------------
for y in range(N):
    for x in range(N):
        d = dist(x, y)
        if R_OUT < d <= R_OUT + 8:
            G[y][x] = 'glow1'
        elif R_OUT + 8 < d <= R_OUT + 16:
            G[y][x] = 'glow2'
        elif R_OUT + 16 < d <= R_OUT + 24:
            G[y][x] = 'glow3'

# ---- 2. gold bezel ring (smooth torus + faint rope bead) -------------------
RMID = (R_IN + R_OUT) / 2.0
for y in range(N):
    for x in range(N):
        d = dist(x, y)
        if R_IN <= d <= R_OUT:
            a = ang(x, y)
            shade = math.cos(a - math.radians(225))
            edge = abs(d - RMID) / ((R_OUT - R_IN) / 2.0)
            bead = 0.16 * math.sin(a * 30)
            base = shade * 0.7 + (0.55 - edge) + bead
            if d >= R_OUT - 3 or d <= R_IN + 3:
                G[y][x] = 'ink'
            elif base > 1.0:
                G[y][x] = 'g_spec'
            elif base > 0.6:
                G[y][x] = 'g_hi'
            elif base > 0.2:
                G[y][x] = 'g'
            elif base > -0.2:
                G[y][x] = 'g_mid'
            elif base > -0.7:
                G[y][x] = 'g_dk'
            else:
                G[y][x] = 'g_sh'

# ---- 3. dark stone brickwork field -----------------------------------------
BRH, BRW = 34, 68
for y in range(N):
    for x in range(N):
        d = dist(x, y)
        if d >= R_IN - 2:
            continue
        row = int((y - (CY - R_IN)) // BRH)
        off = (BRW // 2) if (row % 2) else 0
        my = ((y - (CY - R_IN)) % BRH) < 3
        mx = ((x - off) % BRW) < 3
        if my or mx:
            G[y][x] = 'mortar'
        else:
            tone = (row * 3 + int((x - off) // BRW)) % 5
            G[y][x] = 'st_lit' if tone == 0 else ('st_dk' if tone == 3 else 'st')
        if d > R_IN - 18:
            G[y][x] = 'vig' if not (my or mx) else 'mortar'
for y in range(N):
    for x in range(N):
        if R_IN - 5 <= dist(x, y) < R_IN:
            G[y][x] = 'ink'

# ---- 4. cyan gem studs at the four cardinals on the ring -------------------
for adeg in (0, 90, 180, 270):
    gx = CX + RMID * math.cos(math.radians(adeg))
    gy = CY + RMID * math.sin(math.radians(adeg))
    for yy in range(-9, 10):
        wd = 9 - abs(yy)
        for xx in range(-wd, wd + 1):
            facet = 'gem_co' if (xx - yy) < -4 else ('gem_dk' if (xx - yy) > 4 else 'gem')
            sput(gx + xx, gy + yy, facet)
    sput(gx - 2, gy - 3, 'gem_co')
for (x, y) in list(S.keys()):
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if (nx, ny) not in S and 0 <= nx < N and 0 <= ny < N:
            G[ny][nx] = 'ink'
for (x, y), k in S.items():
    G[y][x] = k

# ---- 5. stamp the exact chest art into the center --------------------------
# Read the chest master's pixels once and lay its opaque pixels over the frame
# (the chest is hard-edged — alpha is only 0 or 255 — so no blending is needed).
dump = subprocess.run(["convert", CHEST, "-depth", "8", "txt:-"],
                      check=True, capture_output=True, text=True).stdout
px = re.compile(r"^(\d+),(\d+):\s*\((\d+),(\d+),(\d+),(\d+)\)")
for line in dump.splitlines():
    m = px.match(line)
    if not m:
        continue
    cx, cy, r, g, b, a = (int(v) for v in m.groups())
    if a == 255:
        G[CHEST_OFF + cy + CHEST_DY][CHEST_OFF + cx] = "#%02X%02X%02X" % (r, g, b)

# ---- emit one self-contained .glyph + render -------------------------------
pool = "@$%&*+=oOxX0123456789abcdefghijklmnpqrstuvwzABCDEFGHIJKLMNPQRSTUVWZ?!~^"


def hexof(c):
    return COL[c] if c in COL else c


used = []
for row in G:
    for c in row:
        if c is not None:
            h = hexof(c)
            if h not in used:
                used.append(h)
assert len(used) <= len(pool), f"too many colors: {len(used)}"
ch = {h: pool[i] for i, h in enumerate(used)}

lines = ["# Prosperity mod icon — generated by icon.gen.py.",
         "# Treasure chest in a golden medallion over a dark stone-brick field, with",
         "# cyan gem studs at the four cardinals. Self-contained: the frame is computed",
         "# and the exact chest art is baked in, so glyph.py renders it with no compositing.",
         f"size: {N}", "", "legend:", "  . transparent"]
for h in used:
    lines.append(f"  {ch[h]} {h}")
lines.append("")
lines.append("frame:")
for row in G:
    lines.append("  " + "".join(ch[hexof(c)] if c is not None else "." for c in row))
with open(OUT_GLYPH, "w") as f:
    f.write("\n".join(lines) + "\n")

subprocess.run(["python3", ".ai/skills/mc-textures/scripts/glyph.py", OUT_GLYPH,
                "-o", OUT_MASTER, "--no-preview"], check=True, stdout=subprocess.DEVNULL)
subprocess.run(["convert", OUT_MASTER, "-resize", "128x128", OUT_128], check=True)
print(f"wrote {OUT_GLYPH} ({len(used)} colors), {OUT_MASTER}, {OUT_128}")
