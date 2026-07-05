<p align="center">
  <img src="art/logo.png" alt="Prosperity" width="800">
</p>

<p align="center"><strong>Every chest, yours to discover.</strong></p>

<p align="center">
  <a href="https://www.minecraft.net/"><img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A?logo=minecraft&logoColor=white"></a>
  <a href="https://fabricmc.net/"><img alt="Fabric" src="https://img.shields.io/badge/Mod_Loader-Fabric-DBB69B"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/github/license/rfizzle/prosperity"></a>
  <a href="https://github.com/rfizzle/prosperity/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/rfizzle/prosperity?include_prereleases"></a>
  <a href="https://github.com/rfizzle/prosperity/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/rfizzle/prosperity/actions/workflows/ci.yml/badge.svg"></a>
</p>

A loot overhaul for Minecraft 1.21.1 (Fabric). Prosperity gives every player their own instanced loot from naturally-generated containers — no more empty dungeon chests because someone got there first — and rewards exploration with distance-scaled loot quality. It works as a zero-trust proxy over vanilla containers: it never registers custom blocks, retextures world-gen, or replaces block entities. Per-player state rides on vanilla container block entities via persistent Fabric data attachments.

## Download

| [GitHub Releases](https://github.com/rfizzle/prosperity/releases) | [Website](https://prosperity.rfizzle.com) | [Report an issue](https://github.com/rfizzle/prosperity/issues) |
| --- | --- | --- |

---

## Features

- **Instanced loot** — Every naturally-generated container (chests, trapped chests, barrels, end-city shulkers, jungle-temple dispensers, and chest/hopper minecarts in mineshafts) rolls a fresh, private inventory for each player. The first visitor no longer empties the chest for everyone; each player opens their own instance, served through a virtual container UI with full vanilla lid animation and sound. Replaces the role of Lootr.
- **Visual indicators** — Unopened containers carry a gold sparkle overlay so unlooted treasure is visible at a glance. Rendered client-side via `WorldRenderEvents.LAST` for full compatibility with Sodium, Enhanced Block Entities, and shader packs — no block models are touched.
- **Prospector's Compass** — A gold-cased compass found in Frontier+ chest loot whose needle points at the nearest container *you* haven't looted — per player, blacklist- and refresh-aware. Loot the target and it swings to the next candidate; with nothing unlooted in range it spins like a vanilla compass in the Nether.
- **Distance-based loot scaling** — The further you are from world spawn, the better the haul. Five tiers (Local → Frontier → Wilderness → Outlands → Depths) scale stack sizes and loot quality, with separate handling for the Nether and the End.
- **Mob loot scaling** — The same distance tiers apply to hostile-mob drops on your kills, so a zombie far out drops more than one at spawn and combat keeps pace with exploration. Hostile mobs only, player kills only; passive and environmental kills are untouched. Independently togglable.
- **Trial chamber scaling** — Vault loot (normal and ominous) and trial spawner rewards scale by the chamber's distance tier, with a shipped structure override raising chambers to at least the Wilderness tier. Scaling only — vanilla's per-player vault gating and key consumption stay untouched. Independently togglable.
- **Loot Modifier API** — A Fabric-style event (`LootModifierCallback.EVENT`) lets other mods inject luck, stack multipliers, and custom data into the loot context after distance scaling but before the table resolves. Vanilla `generic.luck` is wired in by default. Replaces the role of LootIntegrations.
- **Loot table injection** — Datapack-driven additive injections (`data/prosperity/loot_injections/<name>.json`) layer extra drops onto existing tables, including wildcard targets, without overwriting vanilla loot.
- **Structure-specific scaling** — Per-structure overrides tune loot independently of raw distance.
- **Structure completion bonus** — Looting *every* instanced container in a structure drops one extra injection-pool reward into the final container, with a `✦ Stronghold cleared!` fanfare. Per player, once per structure instance (loot refresh never re-arms it); single-container structures don't count. Independently togglable.
- **Jade / WTHIT tooltips & loot index** — Container loot status (unlooted, looted, refresh timer) surfaces through Jade/WTHIT, and an EMI/REI/JEI loot index lets players browse what a container can drop.
- **Loot notifications** — Opening a container for the first time flashes a brief action-bar message naming the loot tier and active modifiers (e.g. `✦ Wilderness — 2.0x stacks, +2 quality`), with the structure appended when an override changed the tier. Independently togglable.
- **HUD tier badge** — A compact, shared-suite HUD element (priority 3 in the rfizzle HUD strip) shows your current distance loot tier (e.g. `[chest] Frontier`). Independently togglable.
- **Loot refresh** — Optional per-player restocking: after a configurable cooldown (`lootRefreshDays`, default 7 in-game days) a player's instance of a container regenerates on their next open, the unlooted sparkle reappears, and fresh loot rolls at the current tier — so explored structures stay worth revisiting. Each player's cooldown is independent, the check is lazy (no per-tick cost), and enabling it later applies retroactively. Off by default.
- **Absent-player data eviction** — Optional housekeeping for long-running public servers: once a player has been offline past a threshold (`absentPlayerEvictionDays`, default 60 in-game days), their per-player entries on a container — stored inventory, visited marker, and refresh count — are dropped the next time that container is touched. No world scans; a self-owned last-seen ledger tracks logins. The trade-off: an evicted player who returns forfeits uncollected loot and regenerates fresh, the same trade loot refresh makes. Off by default.
- **Container protection** — Optional anti-grief: a loot container that some online player still has pending breaks several times slower (configurable) with a quiet cue when breaking begins, so one player cannot quickly erase everyone else's instanced loot. Creative bypasses it, and protection lifts once every online player has opened the container. Off by default.
- **Commands** — `/prosperity info` reports your current tier and multipliers; op-level `reset`, `refresh`, and `reload` manage instanced data and config.

Full feature detail, container handling, and config knobs live in [`design/SPEC.md`](design/SPEC.md) and at [prosperity.rfizzle.com](https://prosperity.rfizzle.com).

---

## Installation

**Requirements:** Minecraft 1.21.1, Fabric Loader 0.16.10+, Fabric API, Java 21

Drop the jar (and Fabric API) into `mods/` on both server and client. Config generates at `config/prosperity.json` on first launch — tune everything with `/prosperity reload`.

---

## Development

Fabric API is the only non-trivial required dependency — Prosperity attaches per-player loot state to vanilla container block entities through persistent Fabric data attachments.

```sh
./gradlew build          # produces build/libs/prosperity-<version>.jar
./gradlew test           # runs unit tests
./gradlew runGametest    # runs Fabric gametest suite
./gradlew runClient      # launch dev client
./gradlew runServer      # launch dev server
```

See [`AGENTS.md`](AGENTS.md) for source layout, conventions, and the suite-wide standards this repo conforms to.

---

## For Mod Developers

Prosperity exposes a stable loot-modifier API under `com.rfizzle.prosperity.api`. Register a `LootModifierCallback.EVENT` listener to influence loot generation — adjust luck, multiply stack sizes, or pass custom data through the context — after distance scaling and before the loot table resolves. Use it as a soft dependency by compiling against Prosperity with `modCompileOnly` and guarding calls with `FabricLoader.isModLoaded("prosperity")`.

See [`design/SPEC.md`](design/SPEC.md) §4 for the full API surface and integration examples.

---

## Part of Concord

Part of [Concord](https://github.com/rfizzle/concord) — a modular collection of system overhauls.
Install any, combine all.

- [Meridian](https://meridian.rfizzle.com) — Chart your enchantments.
- [Mercantile](https://mercantile.rfizzle.com) — Every villager remembers.
- [Tribulation](https://tribulation.rfizzle.com) — Survive what comes next.

---

## License

Licensed under the [MIT License](LICENSE). © 2026 rfizzle. Prosperity is not
affiliated with Mojang Studios or Microsoft.
