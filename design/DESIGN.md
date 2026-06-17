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

**Icon (`art/icon.png`):** The open wooden treasure chest isolated — warm brown planks with ornate gold trim and corner bands, overflowing with gold coins and a large diamond-cyan gem. Warm golden/amber glow radiating outward against a dark/transparent background. Reads cleanly down to 128×128.

> **Note:** Final assets shipped 2026-06-13. The motif is a **treasure chest** (not the
> earlier chalice concept), and the palette is warm gold with diamond-cyan gem accents —
> deliberately avoiding the emerald green of the original draft so Prosperity reads as
> distinct from Mercantile across the mod suite.

**In-Game HUD Icon (`art/hud-icon-16.png`):** A small pixel-art treasure chest — warm brown wood with gold trim, slightly open to suggest contents within.

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
| Text Primary | Bone | `#e8e0d4` | Body text |
| Text Secondary | Ash | `#a89f93` | Muted text, descriptions |
| Text Tertiary | Smoke | `#6b6359` | Disabled, placeholder |
| Surface Base | Obsidian | `#0a0a0a` | Page backgrounds |
| Surface Card | Dark Stone | `#1a1a1a` | Cards, panels |
| Surface Elevated | Stone | `#222222` | Elevated surfaces, hover cards |

### Typography

- **Headings:** Pixel/blocky display font in gradient (`#DAA520` → `#FFD700`)
- **Body:** Monospace stack: SF Mono, Cascadia Code, Fira Code, Consolas
- **Website gradient animation:** `gold-shimmer` keyframes (4s ease-in-out, brightness 1→1.15)

---

## 2. Asset Inventory

### Existing Assets

| Asset | Location | Size | Status |
|-------|----------|------|--------|
| Full Logo (master) | `art/logo.png` | 3168×1344 | Final — treasure-chest medallion + winged "PROSPERITY" wordmark |
| Icon (full-res master) | `art/icon.png` | 2048×2048 | Final — open treasure chest, gold trim, diamond-cyan gem |
| Icon (128 master) | `art/icon-128.png` | 128×128 | Final — standard-named master (Concord REPO-LAYOUT) |
| HUD Icon | `art/hud-icon-16.png` | small | Final — treasure chest glyph for the shared HUD strip |
| In-jar mod icon | `src/main/resources/assets/prosperity/icon.png` | 128×128 | Final — derived from `art/icon.png` |
| Website assets | `site/assets/{logo,icon,og-image,apple-touch-icon}.png` | derived | Final — web copies |

> Earlier style explorations (HUD frame/icon variant sheets) were generated externally
> and are not committed; working files belong in `art/exploration/`.

### Needed Assets

| Asset | Generator | Priority | Spec |
|-------|-----------|----------|------|
| ~~Repo `logo.png`~~ | Derived | ✅ Done | `art/logo.png` |
| ~~In-game mod icon~~ | `/glyph` | ✅ Done | 128×128 treasure chest — `assets/prosperity/icon.png` |
| ~~Website assets~~ | Derived | ✅ Done | `site/assets/` (logo, icon, og-image, apple-touch); site content under `site/` |
| CNAME | Manual | High | `prosperity.rfizzle.com` — add when Pages is enabled (handled by `site.yml`) |
| Recipe browser icon (EMI/REI/JEI tab) | `/glyph` | High | 16×16 or 32×32, treasure chest or key motif |
| Unlooted container indicator sprite | `/glyph` | Critical | 16×16 star/sparkle icon, gold color, for world overlay |
| Distance tier icons (set of 5) | `/glyph` | High | 16×16 icons for Local → Depths tiers |
| HUD loot tier indicator | `/glyph` | High | 16×16 icon + compact text — single persistent HUD element showing current area's loot tier |
| Unlooted indicator sprite | `/glyph` | Critical | 16×16 gold sparkle/star — world-space overlay above unopened containers (NOT a HUD element) |
| Website hero background | Gemini | Medium | 1920×600 — stone brickwork with treasure/coin accents |
| ~~Open Graph image~~ | Derived | ✅ Done | `site/assets/og-image.png` — logo on deep-bronze 1200×630 |
| CurseForge gallery screenshots | Screenshot | Medium | (Deferred until implementation exists) |
| Favicon (`.ico` / `.svg`) | Derived | Low | 32×32 / 16×16 from icon |
| Apple Touch Icon | Derived | Low | 180×180 from icon |
| Discord embed banner | Gemini | Low | 1280×640, logo on dark background |

---

## 3. Generation Prompts

### Gemini Prompts (Logos / High-Res Art)

**Open Graph / Social Card:**
```
Pixel art style, 1200x630 banner image for a Minecraft mod called "Prosperity".
Center the logo: an open wooden treasure chest (brown #7A4E2D, gold trim
#DAA520/#FFD700) overflowing with gold coins and a diamond-cyan (#4EEAED) gem,
inside a golden circular medallion frame wrapped with bronze-gold vines. An
ornate gold key with a diamond-cyan gem crowns the top. The word "PROSPERITY"
in blocky pixel font below. Dark bronze (#1a1408) background. Warm amber/gold
glow behind the chest. Falling gold-coin and diamond-cyan gem particles.
DO NOT use green/emerald/teal/lime — palette is warm gold + brown chest with
diamond-cyan accents on dark bronze.
```

**Website Hero Background:**
```
Pixel art tileable background texture, 1920x600. Dark stone brickwork
(#1a1408 to #2e2510 gradient) with subtle hieroglyph/treasure-map carvings
in the stone. Faint gold coins embedded in mortar. Occasional gem glint.
Very subtle — this is a background behind text. Minecraft pixel art style,
16-pixel grid aligned.
```

**Discord Banner:**
```
Pixel art banner, 1280x640. The Prosperity treasure-chest icon centered on a
dark bronze (#1a1408) background. Warm golden/amber glow radiating from center.
Falling gold coin particles with occasional diamond-cyan (#4EEAED) gem sparkle.
"Prosperity" in gold pixel font below the icon. "Loot Overhaul" subtitle in
lighter text. Clean, minimal. No emerald green.
```

### Glyph Specs (In-Game Pixel Art)

In-game pixel art — HUD/UI glyphs, recipe-browser icons, item textures, and
tier-icon sets — is authored through Concord's glyph pipeline: write the
ASCII-grid `.glyph` spec, then render it deterministically with `/glyph` (the
`mc-textures` skill is the craft reference). Every PNG master commits its
`.glyph` source beside it in `art/glyphs/`, so each texture re-renders from its
spec rather than being hand-patched. Design at the target size with hard pixels,
a limited palette, and an `ink` (#0a0a0a) 1px outline so the glyph reads against
any background. The normative spec is concord's `design/DESIGN-SYSTEM.md` §8.
The specs below seed that work.

**Unlooted Container Indicator Sprite (CRITICAL):**
```
Theme: Treasure / undiscovered loot
Subject: Star or sparkle indicator icon
Style: Minecraft HUD overlay icon, pixel art
Size: 16x16
Colors: Gold (#FFD700) primary, warm amber (#F0C040) secondary,
        slight white (#FFFFFF) center highlight
Notes: Must be immediately recognizable as "something valuable here."
       Clean silhouette. Will render as a world overlay above containers.
       Needs to be visible against varied block backgrounds. Subtle
       animation-ready (will bob in-game). No background — transparent.
```

**Recipe Browser Icon (EMI/REI/JEI Tab):**
```
Theme: Treasure / loot discovery
Subject: Golden key or small treasure chest
Style: Minecraft item icon, pixel art
Size: 32x32
Colors: Gold (#DAA520) key/chest body, darker gold (#8B6914) shadows,
        diamond cyan (#4EEAED) gem accent
Notes: Must read clearly at 16x16 downscale. No text. Single centered motif.
       Should suggest "loot" or "discovery" at a glance.
```

**Distance Tier Icons (set of 5):**
```
Theme: Exploration distance / loot quality progression
Subject: Five 16x16 icons representing distance-based loot tiers:
  1. Local (0–999 blocks) — simple wooden chest, plain
  2. Frontier (1k–3k) — iron-banded chest, slight glow
  3. Wilderness (3k–6k) — gold-trimmed chest, cyan gem accent
  4. Outlands (6k–10k) — ornate golden chest, multiple diamond-cyan gems
  5. Depths (10k+) — radiant diamond/netherite chest, maximum cyan glow
Style: Minecraft item icons, pixel art, consistent set
Size: 16x16 each
Colors: Progression from wood brown through gold (#DAA520) to bright
        gold (#FFD700) with increasing diamond cyan (#4EEAED) gem accents
```

**In-Game Mod Icon:** (shipped — `art/icon.png` → `assets/prosperity/icon.png`)
```
Theme: Treasure abundance
Subject: Open wooden treasure chest overflowing with gold coins
Style: Minecraft mod icon, pixel art, clean readable at small sizes
Size: 128x128
Colors: Wood brown (#7A4E2D / #4A2E18 / #A06A3C), gold trim (#DAA520 / #FFD700 /
        #8B6914), gold coins (#FFD700), one diamond-cyan gem (#4EEAED),
        warm amber inner glow (#F0C040)
Notes: Match the pixel density of Meridian (open book) and Tribulation (skull
       with flame). Lid open, coins spilling over the front. Works as a
       fabric.mod.json icon at all display sizes. Dark/transparent background.
       DO NOT use green/emerald — metal is gold, wood is brown, only non-gold
       accent is the diamond-cyan gem.
```

**HUD Loot Tier Icon:**
```
Theme: Exploration / treasure discovery
Subject: Small treasure chest icon for the shared HUD element strip
Style: Minecraft HUD icon, pixel art, minimal and flat
Size: 16x16
Colors: Gold (#DAA520) chest body, diamond cyan (#4EEAED) gem accent,
        tier-dependent intensity (plain wood Local → radiant gold Depths)
Notes: Sits inside the shared semi-transparent HUD box alongside a text
       label like "Frontier". Must be legible at native 16x16 against the
       dark box background. Transparent PNG. No frame or border — the
       shared HUD box provides the container. Could use a single icon
       tinted by code, or match the 5-variant Distance Tier Icons set.
```

### HUD

Prosperity holds **slot 3** in the Concord HUD stack: a 16×16 treasure-chest glyph with the current loot-distance tier label, tinted by tier. The full visual spec, slot registry, and stacking/coordination contract live in concord [`HUD-STANDARD.md`](../../concord/HUD-STANDARD.md). The five tier labels are Local, Frontier, Wilderness, Outlands, and Depths.

---

## 4. Image References

| Image | Reference Source | Notes |
|-------|----------------|-------|
| Chest motif | `art/icon.png` | Open wooden treasure chest, gold trim, overflowing with coins + diamond-cyan gem |
| Golden frame | `art/logo.png` medallion | Vine-wrapped gold ring with bronze-gold berries |
| Key symbol | `art/logo.png` top | Ornate golden key with diamond-cyan gem — basis for the recipe-browser icon |
| Treasure variety | `art/logo.png` contents | Multi-colored gems, coins, small chests, trinkets |
| Background texture | `art/logo.png` background | Dark stone brickwork with hieroglyph carvings |
| HUD pixel density | `art/hud-icon-16.png` | Small treasure-chest glyph for the HUD strip |
| Companion icon density | Meridian `assets/meridian/icon.png`, Tribulation `assets/tribulation/icon.png` | Match pixel density and style |

---

## 5. Website Specification

### Domain & Hosting

- **Domain:** `prosperity.rfizzle.com`
- **Hosting:** GitHub Pages via Actions — `site.yml` renders the `site/` content with the shared Concord Eleventy template and deploys it (the legacy `docs/` directory is retired per Concord REPO-LAYOUT)
- **CNAME:** `prosperity.rfizzle.com` (set in `site.json` / handled by the deploy workflow)
- **Status:** Structured content scaffolded under `site/` (stub pages); copy to be filled in

### Pages to Create

| Page | File | Content |
|------|------|---------|
| Home | `index.html` | Hero with logo, feature overview (instanced loot, indicators, distance scaling), download links |
| Features | `features.html` | Detailed breakdown — instanced loot, visual indicators, distance tiers, loot modifier API, loot injection |
| Config | `config.html` | Configuration reference |
| Commands | `commands.html` | Command reference |
| Getting Started | `guide.html` | Installation, how instanced loot works, understanding distance tiers |
| API | `api.html` | Loot Modifier API documentation for mod developers |
| FAQ | `faq.html` | Compatibility (Lootr migration), performance, hopper behavior |
| Changelog | `changelog.html` | Version history |

### Website Design Tokens (Tailwind)

```javascript
colors: {
    base: '#0a0a0a',
    card: '#1a1a1a',
    elevated: '#222222',
    gold: { DEFAULT: '#DAA520', dark: '#8B6914' },
    amber: { DEFAULT: '#F0C040', bright: '#FFD700' },
    diamond: { DEFAULT: '#4EEAED', dark: '#00BCD4' },
    bone: '#e8e0d4',
    ash: '#a89f93',
    smoke: '#6b6359',
}
```

### SEO & Social

- **Title pattern:** `{Page} — Prosperity | Loot Overhaul for Minecraft`
- **og:image:** Absolute URL (`https://prosperity.rfizzle.com/logo.png`)
- **twitter:card:** `summary_large_image`
- **Favicon:** `<link rel="icon" type="image/png" href="icon.png">`
- **Apple Touch:** `<link rel="apple-touch-icon" href="apple-touch-icon.png">`

### Cross-Mod Navigation

Footer section linking to all companion mods:
```
Part of the rfizzle mod suite:
[Meridian] [Mercantile] [Tribulation] [Prosperity]
```

---

## 6. Distribution Listings

### CurseForge / Modrinth

**Description Template:**
1. Logo image (centered)
2. One-paragraph summary
3. Feature list with headers (Instanced Loot, Visual Indicators, Distance Scaling, Loot Modifier API, Loot Injection)
4. Screenshot gallery (3–5 images)
5. Requirements section (Fabric Loader, Fabric API)
6. Optional dependencies (EMI/REI/JEI, Jade/WTHIT)
7. Links to companion mods

**Screenshot Standards:**
- Resolution: 1920×1080
- Shader: Complementary Shaders (or vanilla for clarity)
- Subjects: (1) Unlooted indicator sprites above containers in a structure, (2) Two players opening the same chest showing different loot, (3) Distance tier comparison — local vs. frontier loot, (4) Loot quality at extreme distance, (5) Nether/End container loot

**Changelog Format:**
```markdown
## [0.1.0] — 2025-XX-XX
### Added
- Feature description
### Changed
- Change description
### Fixed
- Fix description
```

### README Badges

```markdown
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![Fabric](https://img.shields.io/badge/Loader-Fabric-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)
![GitHub](https://img.shields.io/github/v/release/rfizzle/prosperity)
```

---

## 7. Companion Mod Context

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
