# Prosperity — Asset Manifest

> Where every committed asset lives: its source under `art/` (a re-renderable
> `.glyph` for pixel art, or a `.png` master for logos) and the final file it
> ships as. **`MISSING`** in the glyph column flags a pixel asset that has no
> `.glyph` source yet — a candidate for the glyph pipeline (concord
> [`design/DESIGN-SYSTEM.md`](../../concord/design/DESIGN-SYSTEM.md) §8).
> [`DESIGN.md`](DESIGN.md) covers *why* each asset exists; this file covers *where* it lives.
>
> Final paths are under `src/main/resources/` unless noted. A separate report sweeps
> the resource tree for any final asset lacking a `.glyph` source.

## Branding masters (`.png` — not glyph-based)

| Asset | `art/` master | Final / derived copies |
|---|---|---|
| Full logo | `art/logo.png` | `site/assets/logo.png` |
| Mod icon (full-res) | `art/icon.png` | (master) |
| Mod icon (128) | `art/icon-128.png` | `assets/prosperity/icon.png` (in-jar), `site/assets/icon.png` |
| OG image | — | `site/assets/og-image.png` |
| Apple touch icon | — | `site/assets/apple-touch-icon.png` |

## In-game pixel art

| Asset | `.glyph` source | Final asset |
|---|---|---|
| Unlooted container overlay | `art/glyphs/unlooted-sparkle.glyph` | `assets/prosperity/textures/overlay/unlooted.png` |
| HUD loot-tier icon (treasure chest) | `art/glyphs/hud_icon.glyph` (self-contained; renders the 32px chest) | `assets/prosperity/textures/gui/hud_icon.png` |

## Not yet created

| Asset | Source | Final asset |
|---|---|---|
| Recipe browser icon (EMI/REI/JEI tab) | `/glyph` | — (planned) |
| Distance tier icons (set of 5) | `/glyph` | — (planned, Local → Depths) |
| Website hero background | Gemini | — (planned, `site/`) |
| Discord embed banner | Gemini | — (planned) |
