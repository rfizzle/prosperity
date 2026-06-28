# Prosperity — Loot Overhaul

**_Every chest, yours to discover._**

![Prosperity logo](https://raw.githubusercontent.com/rfizzle/prosperity/master/art/logo.png)

**Also on [Modrinth](https://modrinth.com/mod/prosperity-loot-overhaul)
and [GitHub Releases](https://github.com/rfizzle/prosperity/releases).**
Visit the [website](https://prosperity.rfizzle.com) for the full feature
list, config reference, and command guide.

---

Prosperity gives every player their own instanced loot from naturally generated containers — no more racing to the good chests on a shared server — and rewards exploration with distance-scaled loot quality. It attaches per-player loot to vanilla containers via persistent Fabric data attachments and intercepts interactions through events; it never registers custom blocks or replaces block entities.

A drop-in replacement for Lootr with extra reach. Zero external dependencies beyond Fabric API. Just drop it in.

---

## Instanced Loot

Every player gets their own independent loot from each naturally generated container, rolled the first time they open it and stored per UUID. Hoppers and comparators see an empty vanilla container — instanced loot lives outside the vanilla inventory, which prevents extraction and duplication exploits.

---

## Unlooted Indicators

A gold sparkle hovers above containers you haven't opened yet and disappears once you've looted them. In large structures — strongholds, mansions, mineshafts — this tells you at a glance what's left to explore. It's a world-space overlay, not a HUD element.

---

## Distance Scaling

Loot quality scales with absolute distance from world origin across five tiers:

| Tier | Distance (blocks) | Quantity | Quality |
|------|------------------|----------|---------|
| **Local** | 0 – 999 | 1.0x | +0 |
| **Frontier** | 1,000 – 2,999 | 1.5x | +1 |
| **Wilderness** | 3,000 – 5,999 | 2.0x | +2 |
| **Outlands** | 6,000 – 9,999 | 2.75x | +3 |
| **Depths** | 10,000+ | 3.5x | +4 |

Stack quantities scale by the multiplier (capped at 64); quality adds luck to loot generation. The End is always treated as Depths tier. Structure overrides can fix, raise, or cap the tier a given structure uses.

---

## Loot Modifier API & Injection

A stable, Fabric-style `LootModifierCallback` event lets other mods adjust loot generation after distance scaling but before the loot table resolves. A datapack-driven injection system adds custom items to vanilla loot tables gated by minimum distance tier — and Prosperity ships built-in injections so distance scaling feels meaningful out of the box.

---

## Commands

| Command | Permission | What It Does |
|---------|-----------|-------------|
| `/prosperity info` | Anyone | Show your loot scaling tier for your current position |
| `/prosperity info <player>` | Op | Show another player's loot scaling tier |
| `/prosperity reset <pos>` | Op | Clear all instanced loot data for a container |
| `/prosperity reset <pos> <player>` | Op | Clear a specific player's instanced loot |
| `/prosperity reload` | Op | Hot-reload config and sync to clients |

---

## Configuration

Everything is tunable in `config/prosperity.json`. Key sections:

- **Distance tiers** — thresholds, quantity multipliers, and quality boosts
- **Structure overrides** — fixed / minimum / maximum tier per structure
- **Container blacklist** — exempt specific containers from all behavior
- **Indicators** — toggle the unlooted overlay and loot-tier feedback

Changes apply immediately with `/prosperity reload`. No restart required.

Full config reference: [prosperity.rfizzle.com/config.html](https://prosperity.rfizzle.com/config.html)

---

## Compatibility

- **Minecraft** 1.21.1 (Fabric)
- **Fabric Loader** 0.16.10+
- **Fabric API** required
- **Java** 21+
- Works on **dedicated servers and singleplayer**
- Remove **Lootr** before installing — Prosperity replaces its functionality
- Optional integrations: **Jade/WTHIT** (loot status, tier &amp; refresh-timer tooltips), **EMI/REI/JEI** (loot injection recipes)

---

## Installation

Drop the jar into your `mods/` folder on both server and client. Config generates automatically on first launch. That's it.

---

## Links

- [Documentation](https://prosperity.rfizzle.com)
- [Source Code](https://github.com/rfizzle/prosperity)
- [Issue Tracker](https://github.com/rfizzle/prosperity/issues)
