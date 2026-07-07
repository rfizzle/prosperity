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
| OG image | — | `site/assets/og-image.png` |
| Apple touch icon | — | `site/assets/apple-touch-icon.png` |

## In-game pixel art

| Asset | `.glyph` source | Final asset |
|---|---|---|
| Unlooted container overlay (4-frame sparkle) | `art/glyphs/unlooted-sparkle.glyph` | `assets/prosperity/textures/overlay/unlooted_0.png` … `_3.png` (standalone frames, cycled in-code) |
| HUD loot-tier icon (treasure chest) | `art/glyphs/hud_icon.glyph` (self-contained; renders the 32px chest) | `assets/prosperity/textures/gui/hud_icon.png` |
| Loot detail panel frame | `art/glyphs/panel-frame-64.glyph` | `assets/prosperity/textures/gui/loot_detail_panel.png` |
| Prospector's compass (32-frame animation) | `MISSING` | `assets/prosperity/textures/item/prospectors_compass_00.png` … `_31.png` |
| 256px treasure chest | `art/glyphs/chest-256.glyph` (from `chest-256.gen.py`) | `art/chest-256.png` master |
| Mod icon (chest medallion) | `art/glyphs/icon.glyph` (from `icon.gen.py`: computed medallion + baked-in chest) | `art/icon-512.png`/`art/icon-128.png` masters → `assets/prosperity/icon.png` (256, in-jar), `site/assets/icon.png` |

## Not yet created

The EMI/REI/JEI loot-index tab reuses the mod brand icon (`assets/prosperity/icon.png`); no
bespoke recipe-browser glyph is authored.

| Asset | Source | Final asset |
|---|---|---|
| Distance tier icons (set of 5) | `/glyph` | — (planned, Local → Depths) |
| Website hero background | Gemini | — (planned, `site/`) |
| Discord embed banner | Gemini | — (planned) |
