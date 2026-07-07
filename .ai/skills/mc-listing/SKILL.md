---
name: mc-listing
description: Write a Concord mod's store listing — the `site/listing-curseforge.md` and `site/listing-modrinth.md` pages published to CurseForge and Modrinth. Defines the canonical Concord listing structure so a mod's two files stay aligned with each other and every member reads the same across the suite. TRIGGER when creating or editing any `site/listing-*.md` file, preparing a store description for a release, or when the user mentions a CurseForge / Modrinth listing or store page.
---

Every member ships two store pages — `site/listing-curseforge.md` and
`site/listing-modrinth.md` — pasted into the mod's CurseForge and Modrinth
description fields. They are the first thing a player reads before installing,
and they render as Markdown on both platforms. Because four mods share one
suite identity, the pages carry a **fixed skeleton**: a player who reads the
Meridian page and then the Prosperity page should feel the same hand.

The two files for one mod are **95% identical**. They diverge only where the
store forces it (cross-links, a handful of dependency links, the install
wording). Everything else — structure, prose, feature copy — is the same text
in both. Write one, then fork it for the other store by applying only the
deltas below.

## The skeleton

Sections appear in this order. Omit `## Commands` only if the mod has no
commands; every other section is present in every listing.

```markdown
# <Name> — <Category>

**_<Tagline>._**

![<Name> logo](https://raw.githubusercontent.com/rfizzle/<mod>/master/art/logo.png)

**Also on [<other store>](<url>)
and [GitHub Releases](https://github.com/rfizzle/<mod>/releases).**
Visit the [website](https://<mod>.rfizzle.com) for the full feature
list, config reference, and command guide.

---

<Intro paragraph: what the mod is, for **Minecraft 1.21.1 (Fabric)**, and the
one-line hook for why it exists.>

**<Bold positioning sentence.>** <One optional sentence of support — what the
mod deliberately is or isn't.>

## At a glance

- Minecraft **1.21.1**, **Fabric** loader (0.16.10+), **Fabric API** required.
- <Real client/server split — see below.>
- <Config surface> — hot-reload with `/<mod> reload`.
- MIT licensed.

## Features

### <Feature name>

<Prose or bullets. One `###` per feature. Tables are fine for tiered/graded
data — stat axes, reputation tiers, distance tiers.>

## Commands

Player commands: `/<mod> ...`. Operator commands cover <...>. Full reference:
[<mod>.rfizzle.com/commands.html](https://<mod>.rfizzle.com/commands.html)

## Optional integrations

<Name> detects and integrates with these mods when present. **None are
bundled** — install whichever you already use.

- [Mod Menu](https://modrinth.com/mod/modmenu) — config screen entry
- [Cloth Config](https://modrinth.com/mod/cloth-config) — settings GUI
- [Jade](https://modrinth.com/mod/jade) / [WTHIT](https://modrinth.com/mod/wthit)
  — <what the probe shows>
- [EMI](https://modrinth.com/mod/emi) / [REI](https://modrinth.com/mod/rei) /
  [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) — <recipe integration>

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16.10+**
- Fabric API
- Java **21+**
- <Deployment line — "Works on dedicated servers and singleplayer", or the
  mod's real server/client requirement.>

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.1.
2. Drop [Fabric API](<store-appropriate link>) into your `mods/` folder.
3. Download <Name> from this <store> page and place it into `mods/` as well.
4. *(Optional / mod-specific step.)*

Config generates at `config/<mod>.json` on first launch.

## Links

- **Website:** <https://<mod>.rfizzle.com>
- **GitHub Releases (canonical downloads):** <https://github.com/rfizzle/<mod>/releases>
- **<other store>:** <url>
- **GitHub:** <https://github.com/rfizzle/<mod>>
- **Report an issue:** <https://github.com/rfizzle/<mod>/issues>
- **Changelog:** <https://<mod>.rfizzle.com/changelog.html>

## Companion mods

<Name> is part of [Concord](https://github.com/rfizzle/concord) — a
modular collection of system overhauls. Install any, combine all:

- [<sibling>](https://<sibling>.rfizzle.com) — <sibling tagline>.
- ...

## License & credits

Licensed under the [MIT License](https://github.com/rfizzle/<mod>/blob/master/LICENSE).
© <year(s)> rfizzle. <Name> is not affiliated with Mojang Studios or
Microsoft.

<Optional: one paragraph of credits for third-party assets, only if the mod
uses any.>
```

## CurseForge ↔ Modrinth deltas — the only differences between the two files

| Spot | `listing-curseforge.md` | `listing-modrinth.md` |
|---|---|---|
| Header cross-link | "Also on **[Modrinth]**…" | "Also on **[CurseForge]**…" |
| Fabric API link (At-a-glance intro is textless; Installation + any inline mention) | CurseForge fabric-api page | Modrinth `/mod/fabric-api` |
| Installation download step | "from this **CurseForge** page (or the CurseForge app / your launcher)" | "from this **Modrinth** page (Modrinth App, Prism's Modrinth tab, or a manual jar drop)" |
| Links list | lists **Modrinth:** | lists **CurseForge:** |

Everything else is byte-identical. Note two fixed exceptions inside **Optional
integrations** that do *not* flip between files: **JEI always links to
CurseForge** (it isn't on Modrinth), and Mod Menu / Cloth Config / Jade / WTHIT
/ EMI / REI always link to **Modrinth** in both files. These are canonical
homes, not store variants — don't "correct" them per-store.

## Per-section rules

- **Category** in the H1 is the mod's role, not its name: "Enchanting
  Overhaul", "Villager & Trade Overhaul", "Difficulty Overhaul", "Loot
  Overhaul".
- **Bold positioning sentence** states what the mod deliberately is or isn't —
  the suite's restraint promise or scope boundary ("An overhaul, not a content
  pack.", "Restrained by design.", "A focused overhaul."). One sentence, bold
  lead. Not a feature; the honest framing that sets expectations.
- **At a glance** is exactly four bullets: version line, client/server split,
  config/hot-reload line, license. The client/server bullet must state the
  mod's *real* deployment — "Required on the **server**; install client-side
  too for the HUD" vs "Install on the **server** and every **client**." Don't
  copy another mod's split.
- **Features** carries the weight. One `###` per feature, ordered
  headline-first. Reserve tables for genuinely tabular data (tiers, axes,
  costs); prose for everything else.
- **Requirements** always lists all five lines (MC, loader, Fabric API, Java,
  deployment). Loader is **0.16.10+** and Java is **21+** across the whole
  suite — these track `fabric.mod.json`; if a mod's manifest disagrees, the
  manifest is truth and the listing is wrong.
- **Companion mods** always names **Concord** (never "the rfizzle mod suite" or
  similar), links the concord repo, uses the "Install any, combine all:" lead,
  and lists the *other three* members **with their taglines**.
- **License & credits** carries the © line with the mod's own year(s) (these
  differ per mod — don't homogenize them) and the "not affiliated with Mojang
  Studios or Microsoft" disclaimer. Add a credits paragraph only for a mod that
  actually ships third-party assets.
- **No Screenshots section, no TODO/checklist comments.** A listing is a
  published artifact; author-notes and `<!-- ... -->` placeholders never ship.
  Add images inline in the relevant feature section when they exist, or leave
  none.

## Source of truth

The listing *describes* the mod — code wins every factual claim. Pull versions
from `fabric.mod.json`, command names from the registered commands, config
paths from the config class, and the client/server split from the mod's actual
environment and features. When the deep detail (full config reference, every
command, the complete enchant/feature list) would bloat the page, summarize and
send the reader to `https://<mod>.rfizzle.com` — the header already promises the
website carries the full reference. See [`mc-changelog`](../mc-changelog/SKILL.md)
for the sibling player-facing artifact (release notes) and the same
write-it-as-finished discipline.
