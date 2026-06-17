# AGENTS.md

Guidance for AI coding agents (Claude Code, Google Jules, and any future tool)
working on this repository. `CLAUDE.md` is a symlink to this file — both names
point at the same content so each agent finds what it expects to read.

## Project overview

Prosperity is a Minecraft 1.21.1 Fabric mod — a loot overhaul that gives every
player their own instanced loot from naturally-generated containers and rewards
exploration with distance-based loot scaling. Java 21, Fabric Loader 0.16.10,
Loom 1.9. **Required dependency:** Cardinal Components API (CCA) 6.x — per-player
loot state is attached to vanilla container block entities via CCA block entity
components (the mod is a zero-trust proxy and never registers custom blocks or
replaces vanilla block entities). The feature surface is documented in
[`README.md`](README.md), [`design/SPEC.md`](design/SPEC.md), and
[prosperity.rfizzle.com](https://prosperity.rfizzle.com). Work is tracked in
GitHub Issues — see the [Development lifecycle](#development-lifecycle) section below.

## Suite standards (Concord)

This mod is a member of Concord, the Vanilla+ collection. Suite-wide standards live in
the [concord repo](https://github.com/rfizzle/concord) — checked out at `../concord/`
in the local workspace. Normative for this repo:

- [API-STANDARD.md](https://github.com/rfizzle/concord/blob/master/API-STANDARD.md) — the `api` package conventions (conforms to v1)
- [HUD-STANDARD.md](https://github.com/rfizzle/concord/blob/master/HUD-STANDARD.md) — HUD slot, stacking, accessors (conforms to v1)
- [DESIGN-SYSTEM.md](https://github.com/rfizzle/concord/blob/master/design/DESIGN-SYSTEM.md) — palette, typography, logo rules
- [REPO-LAYOUT.md](https://github.com/rfizzle/concord/blob/master/REPO-LAYOUT.md) — where non-code files live (conforms)

## Build commands

```bash
./gradlew build          # compile + test + jar
./gradlew test           # JUnit tests only
./gradlew runGametest    # Fabric gametests (headless server)
./gradlew runClient      # launch dev client
./gradlew runServer      # launch dev server
./gradlew genSources     # decompile MC sources for IDE nav
```

Run a single JUnit test:
`./gradlew test --tests "com.rfizzle.prosperity.SomeTest"`

Read [`.ai/skills/mc-gradle-builds/SKILL.md`](.ai/skills/mc-gradle-builds/SKILL.md)
**before running any Gradle command** — it covers how to avoid wasted reruns
from partial output capture.

## Source layout

Loom's `splitEnvironmentSourceSets()` is enabled — three source sets:

| Source set | Root | Purpose |
|---|---|---|
| `main` | `src/main/java` | Server + common logic. Entrypoint: `Prosperity.java` |
| `client` | `src/client/java` | Client-only code. Entrypoint: `ProsperityClient.java` |
| `gametest` | `src/gametest/java` | Fabric gametests (run with `runGametest`). Has `main` on its classpath but is NOT included in the jar. No gametests exist yet — add entrypoints under `com.rfizzle.prosperity.gametest` as features land. |

JUnit tests go in the standard `src/test/java` directory. The test classpath
includes `fabric-loader-junit` but excludes `fabric-api` — tests that need
Fabric APIs must use gametests instead.

## Key conventions

- **Mod ID:** `prosperity` — use `Prosperity.id("path")` to create
  `ResourceLocation`s. Never construct `ResourceLocation` directly with the
  mod ID inlined.
- **Mappings:** Official Mojang mappings (not Yarn). Use Mojang class/method
  names everywhere (`CompoundTag`, not `NbtCompound`; `Level`, not `World`).
- **CCA components:** Per-player container state lives under
  `com.rfizzle.prosperity.component` (e.g. `InstancedLootComponent`,
  registered via `ProsperityComponents`). Components attach to vanilla
  `RandomizableContainerBlockEntity` instances — never replace the vanilla
  block entity, model, or renderer.
- **Assets:** The mod ships almost no custom block/textures by design —
  container blocks stay vanilla. Client-side sprite overlays and a HUD icon
  live under `assets/prosperity/`. Visual indicators render via
  `WorldRenderEvents.LAST` for Sodium/EBE/shader compatibility.
- **Mixin config:** `prosperity.mixins.json` in `src/main/resources`. Mixin
  package: `com.rfizzle.prosperity.mixin`.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/)
  with a topical scope naming the feature area: `feat(instancing): …`,
  `feat(scaling): …`, `fix(indicators): …`, `refactor(loot-api): …`,
  `ci(review): …`, `build(test): …`, `chore(ai): …`, `docs(readme): …`.
  Allowed types: `feat`, `fix`, `refactor`, `chore`, `docs`, `test`, `build`,
  `ci`, `perf`, `style`. Subject line in imperative mood, no trailing period,
  ≤72 chars. Reference the issue in the body footer: `Closes #42` (or
  `Refs #42` for partial work).

## Compat integrations

The mod has optional integrations (all `modCompileOnly` — not bundled —
deferred to a later phase):

- **Mod Menu** — config screen entry
- **Cloth Config** — settings GUI builder
- **Jade / WTHIT** — container loot-status tooltips
- **EMI / REI / JEI** — loot index / recipe viewer support

Compat classes live under `com.rfizzle.prosperity.compat.<modid>`.

## Where things live

| Path | Purpose |
|---|---|
| `README.md` | Project overview and feature summary. |
| `design/SPEC.md` | Full feature spec — behavior, container handling, API surface, config knobs. |
| `design/DESIGN.md` | Brand identity, asset inventory, HUD standard, website spec. |
| GitHub Issues | Active work — feature requests, bugs, in-flight specs. |
| `.ai/skills/` | Domain skills — read these before working in their subject area. |
| `.github/workflows/` | Thin trigger stubs — workflow logic, default CI prompts, and [review criteria](https://github.com/rfizzle/concord/blob/master/.ai/review-criteria.yml) live in [rfizzle/concord](https://github.com/rfizzle/concord). |

<!-- concord:managed:start -->
## Working with domain skills

The suite's `mc-*` domain skills live under `.ai/skills/`, vendored from concord
and refreshed with `make sync-skills`. The full list — each skill's one-line
summary and the situation that should make you pull it in — is the generated
catalog at [`.ai/skills/CATALOG.md`](.ai/skills/CATALOG.md). It is always in step
with the skills actually vendored here, so consult it rather than a hand-kept
table.

Claude Code auto-loads these via the `.claude/skills` symlink; Google Jules,
OpenCode, and any other agent should read the relevant `SKILL.md` directly
**before** working in its subject area.

## Custom art & audio

Custom, high-quality assets are encouraged across the suite — there are clean,
consistent pipelines for both (the `mc-textures` skill → `/glyph`, the `mc-audio`
skill → `/sfx`), so the bar is *fitness and coherence*, not vanilla purity. The
one hard cosmetic rule is the vanilla **font** (never a custom font in any
GUI/HUD/tooltip).

Decide *whether* to make a custom asset here, before reaching for a skill:

- **Default to custom where it serves a valid purpose** — identity, clarity, or a
  slot vanilla can't fill. This is not license for a blanket retexture or a
  wholesale soundscape overhaul; add assets where they earn their place, not for
  their own sake.
- **Use a vanilla asset when it is genuinely already right** — a trade UI literally
  showing an emerald, a literal bell on a bell block.
- **Audio also stays vanilla when the sound is organic** — a real horn, a physical
  bell, footsteps, foley — which pure synthesis renders obviously fake. Synthesis
  is for synthetic cues (alarms, UI blips, tech alerts, charge-ups, chiptune).

Once the decision is made, the `mc-textures` / `mc-audio` skills are the craft
reference for producing a good one. The normative spec is concord's
`design/DESIGN-SYSTEM.md` §8 (textures) and §9 (audio).

## Development lifecycle

1. **Issue opened** using the feature or bug template under `.github/ISSUE_TEMPLATE/`.
2. **Triage** — human discussion in the issue.
3. **`needs-spec` label** added → `.github/workflows/claude-spec.yml` fires.
   Claude normalizes the issue title to a Conventional Commits form and writes
   a plain-language summary plus a structured implementation spec into the
   issue body, preserving the reporter's original text in between (prompt:
   concord's default `spec-writer.md`, unless a repo-local
   `.ai/prompts/spec-writer.md` override exists). Once the spec lands the
   `needs-spec` label is removed and a status label is added: **`ready`** when
   the spec has no open questions, **`open-questions`** when it does. A
   player-facing change (new feature, config option, command, or gameplay rule)
   carries a **Docs impact** section naming the `site/` page(s) to update;
   internal-only work omits it.
4. **Human review** — spec edited or approved. For `open-questions`, answer the
   questions inline in the issue (no spec re-run needed for the simple cases).
5. **`jules` label** added → Jules picks up the issue and opens a draft PR.
   Apply it from either `ready` or `open-questions` once you're satisfied.
6. **PR opened** → `claude-code-review.yml` posts a structured ✓/⚠/✗ review
   (categories from concord's default `review-criteria.yml`, unless a
   repo-local `.ai/review-criteria.yml` override exists). For player-facing
   work it scores a **Site docs** category — a feature, config, command, or
   gameplay change that ships without the matching `site/` page update is
   flagged. `ci.yml` runs the full build, unit tests + gametests, and uploads
   coverage + results to Codecov.
7. **Human review + merge.**

`@claude <message>` in any issue or PR comment also invokes Claude for ad-hoc
help via `.github/workflows/claude.yml`.

## Pull requests & commits

When you open a pull request for an issue:

- **Title** — Conventional Commits with a topical scope, matching the issue's
  normalized title (e.g. `feat(render): add glyph atlas cache`). Imperative
  mood, lower-case, no trailing period.
- **Body** — open with a short plain-language summary of what changed and why,
  then link the source issue with `Closes #<n>` so it auto-closes on merge and
  the code review can pull the issue's spec for context. Use `Refs #<n>` only
  when the PR deliberately leaves part of the issue for later.
- **Commits** — Conventional Commits using the same scope vocabulary. Group the
  edits for one logical change together rather than scattering fixup commits.
- Run the project's build and tests before opening the PR, and open it only
  once the build is green.

## Version scheme

Version is computed from git tags at build time (`build.gradle`,
`computeModVersion()`). Base version is in `gradle.properties` as
`mod_version`. Tagged commits produce clean versions; post-tag commits append
`+<commits>.g<sha>`.
<!-- concord:managed:end -->
