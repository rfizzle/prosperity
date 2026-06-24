<!--
Modrinth draft stub. Until this listing is publicly live, READMEs and the site
link GitHub Releases as the canonical download source. Once public: link as
modrinth.com/mod/prosperity-loot-overhaul and badge with
img.shields.io/modrinth/dt/<projectId>.
TODO before publishing: upload the 128x128 icon and a gallery (full logo +
3-5 in-game screenshots).
-->

# Prosperity — Loot Overhaul

**_Every chest, yours to discover._**

**Also on [CurseForge](https://www.curseforge.com/minecraft/mc-mods/prosperity-loot-overhaul)
and [GitHub Releases](https://github.com/rfizzle/prosperity/releases).**
Visit the [website](https://prosperity.rfizzle.com) for the full feature
list, config reference, and command guide.

---

Prosperity is an instanced loot overhaul for **Minecraft 1.21.1 (Fabric)**. It
gives every player their own loot from naturally generated containers — no more
racing to the good chests on a shared server — and rewards exploration with
distance-scaled loot quality. It works by attaching per-player loot to vanilla
containers via persistent Fabric data attachments and intercepting interactions
through events; it never registers custom blocks or replaces block entities.

**Vanilla+ by design.** A drop-in replacement for Lootr with extra reach.
Zero external dependencies beyond Fabric API.

## At a glance

- Minecraft **1.21.1**, **Fabric** loader (0.16.10+), **Fabric API** required.
- Install on the **server** and every **client**.
- Tunable through `config/prosperity.json` — hot-reload with `/prosperity reload`.
- MIT licensed.

## Features

### Instanced Loot

Every player gets their own independent loot from each naturally generated
container, rolled the first time they open it and stored per UUID. Hoppers and
comparators see an empty vanilla container — instanced loot lives outside the
vanilla inventory, preventing extraction and duplication exploits.

### Unlooted Indicators

A gold sparkle hovers above containers you haven't opened yet, and disappears
once you've looted them. No more backtracking through strongholds, mansions, and
mineshafts to find what you missed. The indicator is a world-space overlay, not
a HUD element.

### Distance Scaling

Loot quality scales with absolute distance from world origin across five tiers:

| Tier | Distance (blocks) | Quantity | Quality |
|------|------------------|----------|---------|
| **Local** | 0 – 999 | 1.0x | +0 |
| **Frontier** | 1,000 – 2,999 | 1.5x | +1 |
| **Wilderness** | 3,000 – 5,999 | 2.0x | +2 |
| **Outlands** | 6,000 – 9,999 | 2.75x | +3 |
| **Depths** | 10,000+ | 3.5x | +4 |

Stack quantities scale by the multiplier (capped at 64); quality adds luck to
loot generation. The End is always treated as Depths tier. Structure overrides
can fix, raise, or cap the tier a given structure uses.

### Loot Modifier API & Injection

A stable, Fabric-style `LootModifierCallback` event lets other mods adjust loot
generation after distance scaling. A datapack-driven injection system adds custom
items to vanilla loot tables gated by minimum distance tier — and Prosperity
ships built-in injections so distance scaling feels meaningful out of the box.

## Commands

Player commands: `/prosperity info`. Operator commands cover per-player and
per-container `reset`, viewing other players' tiers, and `reload`. Full
reference:
[prosperity.rfizzle.com/commands.html](https://prosperity.rfizzle.com/commands.html)

## Optional integrations

Prosperity detects and integrates with these mods when present. **None are
bundled** — install whichever you already use.

- [Jade](https://modrinth.com/mod/jade) / [WTHIT](https://modrinth.com/mod/wthit)
  — loot tier tooltip overlays
- [EMI](https://modrinth.com/mod/emi) / [REI](https://modrinth.com/mod/rei) /
  [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) — loot injection recipes

For mod developers: a stable Loot Modifier API
(`com.rfizzle.prosperity.api`) — see the
[developer docs](https://prosperity.rfizzle.com/api.html).

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16.10+**
- Fabric API
- Java **21+**
- Works on **dedicated servers and singleplayer**

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.1.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) into your `mods/`
   folder.
3. Download Prosperity and place it into `mods/` as well — on both server
   and client.
4. Remove Lootr first if you have it installed.

Config generates at `config/prosperity.json` on first launch.

## Links

- **Website:** <https://prosperity.rfizzle.com>
- **GitHub Releases (canonical downloads):** <https://github.com/rfizzle/prosperity/releases>
- **CurseForge:** <https://www.curseforge.com/minecraft/mc-mods/prosperity-loot-overhaul>
- **GitHub:** <https://github.com/rfizzle/prosperity>
- **Report an issue:** <https://github.com/rfizzle/prosperity/issues>
- **Changelog:** <https://prosperity.rfizzle.com/changelog.html>

## Companion mods

Prosperity is part of [Concord](https://github.com/rfizzle/concord) — a
Vanilla+ collection. Install any, combine all:

- [Meridian](https://meridian.rfizzle.com) — Chart your enchantments.
- [Mercantile](https://mercantile.rfizzle.com) — Every villager remembers.
- [Tribulation](https://tribulation.rfizzle.com) — Survive what comes next.

## License & credits

Licensed under the [MIT License](https://github.com/rfizzle/prosperity/blob/master/LICENSE).
© 2026 rfizzle. Prosperity is not affiliated with Mojang Studios or
Microsoft.
