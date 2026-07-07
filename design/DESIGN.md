# Prosperity — Design Specification

> Loot Overhaul for Minecraft 1.21.1 Fabric

---

## 1. Brand Identity

### Narrative

Prosperity solves multiplayer loot fairness by giving every player their own instanced loot from naturally-generated containers, while rewarding exploration with distance-scaled quality. The name evokes abundance, reward, and the promise of riches beyond the horizon. The visual language draws from **treasure hoards**, **overflowing chests**, **precious gems**, and **ancient keys** — the allure of undiscovered wealth.

### Tagline

*"Every chest, yours to discover."*

### Logo Description

**Full Logo (`art/logo.png`):** An open wooden treasure chest overflowing with gold coins and a diamond-cyan gem sits within a golden circular medallion frame wrapped with bronze-gold vines bearing golden berries. An ornate golden key crowns the top of the frame. The emblem is flanked by smaller treasure chests, a golden compass, and trinkets, with a pile of gold coins below. The background is dark stone brickwork with hieroglyph-like carvings, showered in falling coins and gems. Warm amber glow emanates from the central chest. Below, "PROSPERITY" in a blocky pixel font on a winged stone tablet, with "MINECRAFT LOOT OVERHAUL" subtitle.

**Icon (`art/icon-128.png`):** The open wooden treasure chest isolated — warm brown planks with ornate gold trim and corner bands, overflowing with gold coins and a large diamond-cyan gem. Warm golden/amber glow radiating outward against a dark/transparent background. Reads cleanly down to 128×128.

> **Motif rationale:** The motif is a **treasure chest** and the palette is warm gold
> with diamond-cyan gem accents — kept clear of emerald green so Prosperity reads as
> distinct from Mercantile across the mod suite.

**In-Game HUD Icon (`art/glyphs/hud_icon.glyph`):** A small pixel-art treasure chest — warm brown wood with gold trim, slightly open to suggest contents within.

### Color Palette

| Role | Color | Hex | Usage |
|------|-------|-----|-------|
| Primary | Deep Bronze | `#1a1408` | Backgrounds, dark surfaces |
| Secondary | Dark Gold | `#2e2510` | Mid-tones, card backgrounds |
| Accent 1 | Treasure Gold | `#DAA520` | Glows, highlights, interactive elements |
| Accent 2 | Diamond Cyan | `#4EEAED` | Gem accents, discovery highlights, tier indicators |
| Bright | Rich Gold | `#FFD700` | Hover states, emphasis, coins |
| Glow | Warm Amber | `#F0C040` | Particle effects, loot indicators, background radiance |
| Gem Red | Ruby | `#DC143C` | Gem accent, rare loot tier |
| Gem Cyan | Diamond | `#00BCD4` | Gem accent, endgame loot tier |

Shared neutrals (text and surfaces) follow the standard tokens as-is —
`--color-bone`, `--color-ash`, `--color-smoke`, `--color-ink`,
`--color-card`, `--color-elevated` — see concord
[`design/DESIGN-SYSTEM.md`](../../concord/design/DESIGN-SYSTEM.md) §2.

### Typography

- **Headings:** Pixel/blocky display font in gradient (`#DAA520` → `#FFD700`)
- **Body:** Monospace stack: SF Mono, Cascadia Code, Fira Code, Consolas
- **Website gradient animation:** `gold-shimmer` keyframes (4s ease-in-out, brightness 1→1.15)

---

## 2. Assets

The full asset manifest — every `.glyph` source under `art/`, the final
resource/site path it ships as, and what is still `MISSING` a glyph source —
lives in [`ASSETS.md`](ASSETS.md).

---

## 3. HUD

Prosperity holds **slot 3** in the Concord HUD stack: a treasure-chest glyph with the current loot-distance tier label, tinted by tier. The glyph is authored at 32×32 (HUD-STANDARD density) and blitted down to 16×16 at render time. The full visual spec, slot registry, and stacking/coordination contract live in concord [`HUD-STANDARD.md`](../../concord/HUD-STANDARD.md). The five tier labels are Local, Frontier, Wilderness, Outlands, and Depths.

---

## 4. Website & Listing Brand Notes

How the brand lands on Prosperity's public surfaces. The content itself lives
elsewhere — page copy under `site/`, store copy in `site/listing-*.md` — so this
section carries only the brand direction, not the copy.

### Where the content lives

- **Website** — `site/site.json` (identity, nav order, theme accents) plus one
  `site/pages/<slug>.json` per page (home, features, config, commands, guide,
  api, faq, changelog), rendered and deployed by the shared Concord Eleventy
  template at `prosperity.rfizzle.com`. The template owns surfaces, neutrals,
  the SEO/OG scaffolding, and the cross-mod footer; the mod supplies only its
  content and accent colors.
- **Store listings** — `site/listing-curseforge.md` and
  `site/listing-modrinth.md`, authored per the `mc-listing` skill.
- **README badges** — maintained in `README.md`.
- **Release notes** — `changelogs/<version>.md` when curated, otherwise
  generated from the merged PRs (the `mc-changelog` skill).

### Accent usage

The two signature accents — gold (`#DAA520` → bright `#FFD700`) and diamond-cyan
(`#4EEAED`) — carry every branded moment: hero glow, headings, links, and card
borders. Base surfaces and body text stay on the shared Concord neutrals
(bone/ash/smoke over ink/card/elevated). The accents are declared once in
`site.json`'s `theme` block; the full token set lives in
`design/DESIGN-SYSTEM.md`.

### Hero & gallery art direction

The hero leads with the full logo over the dark stone-brick field. Suggested
gallery shots (1920×1080, vanilla or a light shader for clarity): unlooted
sparkle indicators above containers in a structure; two players opening the same
chest to different loot; a local-vs-frontier loot comparison; loot quality at
extreme distance; Nether/End container loot.

### OG image

The full logo on the dark field, served from an absolute URL; social cards use
the large-summary format.

---

## 5. Companion Mod Context

Prosperity is part of a four-mod suite. Each mod overhauls a different Minecraft system:

| Mod | Domain | Color Signature | Icon Motif |
|-----|--------|----------------|------------|
| **Meridian** | Enchanting | Violet / Gold | Compass rose |
| **Mercantile** | Villagers & Trade | Green / Emerald | Market stall / scales |
| **Tribulation** | Difficulty & Scaling | Crimson / Red | Hourglass with hearts |
| **Prosperity** | Loot & Containers | Gold / Diamond Cyan | Treasure chest |

All four share:
- Minecraft 1.21.1, Java 21, Fabric
- Dark base website theme (`#0a0a0a` / `#1a1a1a` / `#222222`)
- Bone/Ash/Smoke text palette
- Monospace font stack
- Pixel art logo style (Gemini-generated)
- Same website structural pattern (hero → features → config → commands)
- MIT license
- Optional Jade/WTHIT, EMI/REI/JEI, ModMenu, Cloth Config integrations
