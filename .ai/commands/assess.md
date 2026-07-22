---
description: Assess a Concord member mod with parallel grading agents — a quality read by default (architecture, tests, code, completeness → A–F scorecard), or a full release gate with `release` (adds a live build, packaging/metadata, a go/no-go verdict, and a mod-specific beta testing guide).
argument-hint: [release] [version e.g. 1.0.0] [target: modrinth|curseforge|both]
allowed-tools: Task, Read, Glob, Grep, Write, Bash
---

You are assessing a Concord member mod, from inside that mod's own repository (not
the concord orchestration repo). The same engine — parallel agents each grading one
dimension against the real code — runs at two depths:

- **Quality read (default)** — a fast, build-free grade of how good the mod is right
  now. Run it anytime.
- **Release gate (`release` in the args)** — a superset: everything in the quality
  read **plus** a live build, a packaging/store-metadata pass, a go/no-go verdict,
  and a ready-to-share beta testing guide. Run it before cutting a tag.

This is an assessment, not a change: don't edit source, tag, push, or publish. The
only writes are the release-mode report files (Step 4) and whatever `./gradlew`
puts under `build/`.

## Request

$ARGUMENTS

Resolve the mode: **release** if the args contain `release` (or a version like
`1.0.0`), else **quality**. In release mode, parse an optional target version
(default: the next version after the latest `v*` git tag — confirm it) and a publish
target (`modrinth` | `curseforge` | `both`, default `both`).
State the resolved mode (and version/target in release mode) in one line before
starting.

## Step 0 — Orient (do this yourself, then share with every agent)

Build a one-screen profile so the agents don't each re-derive it. Read in this repo:
`fabric.mod.json` (mod id, name, entrypoints, `depends`/`recommends`/`breaks`,
`icon`, environment), `AGENTS.md` (identity, entrypoints, compat, commit-scope
vocab, the declared suite-standard conformance), `design/SPEC.md` + `design/DESIGN.md`
(intended behavior and brand), `README.md` + `site/site.json` + `site/pages/*.json`
(player-facing claims), and `gradle.properties` (MC / loader / Fabric API / Java
versions). Note which optional integrations exist (a `compat.*` package, `recommends`
entries — Mod Menu, Cloth Config, Jade/WTHIT, EMI/REI/JEI), and whether the mod has
a HUD slot, config, commands, and custom assets.

The API and HUD standards are operationalized in the vendored, self-contained
`.ai/skills/mc-public-api/SKILL.md` and `.ai/skills/mc-hud/SKILL.md` — read those
for `api`-package and HUD-slot conformance; they ship in this repo, so they are
always available. The remaining suite standards live at `../concord/` in the
workspace — `REPO-LAYOUT.md`, `design/DESIGN-SYSTEM.md`. Read the relevant one when
present; if `../concord/` is absent (a bare clone), fall back to `AGENTS.md`'s
conformance claims and note the gap.

**Release mode also:** run `git tag --list` + `git log --oneline -15` (what's landed
since the last tag) to ground the target version — the released version is the tag you
push, not `gradle.properties` (which holds the `0.0.0` dev base) — and identify the
store-listing files (`site/listing-modrinth.md`, `site/listing-curseforge.md`).

## Step 1 — Launch the grading agents in parallel

Spawn the agents for the resolved mode concurrently (one message, multiple Task
calls). Give each the Step-0 profile and a tight charge. Each returns a **dimension
grade (A+–F)**, an evidence-backed summary, and findings tagged by severity
(Critical / High / Medium / Low / Trivial). Tell each to cite `file:line` and to
read the named vendored `.ai/skills/mc-*` skill before judging its area.

**These four run in both modes:**

**Architecture & conventions.** Source-set separation (main/client/gametest/test —
clean client/server split), package organization (no god packages/classes), build
files (dependency scoping, externalized versions), and REPO-LAYOUT conformance (root
hygiene — no stray logs, compiled `net/` dirs, `replay_pid*`, committed
`build/`/`run/`/`_site/`; correct `.gitignore`). Mojang mappings and the `<Mod>.id()`
helper (no hand-built ResourceLocations with an inlined mod id). Read
`.ai/skills/mc-public-api/SKILL.md` and judge the public `api` package against it;
if the mod owns a HUD slot, read `.ai/skills/mc-hud/SKILL.md` and judge the slot
against it. DESIGN-SYSTEM palette adherence, and that `AGENTS.md`'s declared
conformance versions are truthful.

**Test coverage & quality.** Read `.ai/skills/mc-mod-testing/SKILL.md`. Map test
files to source: which features/classes are exercised vs untested, and how critical
the gaps are. Judge quality (behavioral assertions vs trivial), correct tier (pure
JUnit for math, fabric-loader-junit for registry-dependent logic, Fabric gametest
for world-state), gametest realism (entity spawning, tick-based assertions), and
whether the riskiest player-facing paths have a test.

**Code quality & stability.** Read `.ai/skills/mc-mixin-craft/SKILL.md`,
`.ai/skills/mc-shared-state/SKILL.md`, `.ai/skills/mc-persistence/SKILL.md`,
`.ai/skills/mc-config/SKILL.md`, and — for any in-world or particle rendering —
`.ai/skills/mc-world-render/SKILL.md`. Fabric idiom (registries, events, codecs,
ComponentType, PayloadTypeRegistry, StreamCodec); class design (single
responsibility, sizes — flag >500 lines); mixin injection safety and hot-path
exposure; client/server boundary and shared-mutable-state guarding; performance
against the 20Hz tick budget (per-tick allocations, O(n²) entity loops, unbounded
caches, sync I/O on the server thread); error handling and graceful degradation on
malformed config/data; config validation and **migration** (old config / old save
loads cleanly); and code smells (deep nesting, magic numbers, duplication, dead code,
over-engineering).

**Feature completeness & polish.** Cross-check `design/SPEC.md`, README, and
`site/pages/*.json` against the implementation: stubs, `TODO`/`FIXME`, placeholder
text, half-wired features. Verify every `Component.translatable(...)` key has an
`assets/<modid>/lang/en_us.json` entry (a missing key renders the raw id in-game);
data completeness (recipes, tags, loot, advancements, models/blockstates);
client↔server payload parity; config defaults matching documented defaults; tooltips,
item/block names, death messages; assets present per `design/ASSETS.md` (no
`MISSING`); and each optional integration actually wired. Confirm `site/` reflects
the shipped feature set.

**Release mode adds these two:**

**Build, tests & CI stability.** Read `.ai/skills/mc-gradle-builds/SKILL.md` first so
output is captured fully and reruns aren't wasted. Run the build the way CI does —
`./gradlew build`, `./gradlew runGametest`, `./gradlew jacocoMergedReport`,
`./gradlew printVersion` (use `Makefile` targets if present). Report: clean build vs
failures; compiler warnings and deprecations; unit + gametest pass counts and any
`@Disabled`/ignored or flaky tests; the JaCoCo coverage % from
`build/reports/jacoco/jacocoMergedReport/jacocoMergedReport.xml`; and that
`printVersion` resolves to the expected `0.0.0+g<sha>` dev string — local builds
are tag-less by design, so the
released version comes from the pushed tag (injected by the release workflow), not from
`gradle.properties`; do not treat the local dev version as a blocker. A red build or
failing gametest is a release blocker, full stop.

**Release packaging & store metadata.** Validate the artifact a user installs.
`fabric.mod.json`: name, description, authors, contact, `license`, `icon` (and the
file exists at that path), environment, entrypoints resolve, mixins config
referenced, `depends`/`recommends`/`breaks` versions sane and pinned to the right
MC/loader/API range. `LICENSE` at root. The built jar
(`build/libs/<mod>-0.0.0+g<sha>.jar`) carries the expected resources and no debug/secret
leakage — the `0.0.0+g<sha>` jar name is the expected local dev version, and the released
jar takes the pushed tag's version via CI, so don't flag the local name as unclean.
Release-notes readiness — the notes are generated in CI from the merged PRs since the
last tag, so there's no manual changelog to check; instead confirm that raw material is
clean: PR subjects since the last tag are player-meaningful conventional-commit titles,
not `wip`/`fixup` noise that would muddy the generated notes. For
the publish target(s), the store listing copy exists and is current
(`site/listing-modrinth.md` / `site/listing-curseforge.md`) — correct MC version,
loader, and dependency callouts.

Scale within a mode to the ask: the four/six agents are the default; for a flagship
1.0 add depth (e.g. split code-quality-vs-stability, or mixins-vs-performance) rather
than dropping coverage.

## Step 2 — Synthesize

Wait for all agents and reconcile overlaps (the same finding may surface in two
dimensions — merge it).

**Quality mode** produces:
1. **Overall grade** (A+–F) — weighted across the four dimensions.
2. **Per-dimension scorecard** — letter + one-line summary each.
3. **Strengths** — what's genuinely solid, citing patterns.
4. **Issues** — table: Severity · Issue · `file:line` · Dimension.
5. **Bottom line** — 2–3 sentences on overall quality and the most valuable next fix.

**Release mode** produces all of the above (now across six dimensions), plus:
1. **Verdict** — **SHIP** / **SHIP WITH CAVEATS** / **HOLD**, one-sentence rationale.
   HOLD if any release blocker exists; SHIP WITH CAVEATS if only recommendeds remain;
   SHIP if clean.
2. **Smooth-experience confidence** — High / Medium / Low: your honest read of whether
   a first-time player on a fresh world has a clean ride.
3. **Build & test evidence** — build green?, N unit + M gametests passing, coverage %,
   version string computed, jar built (the hard facts from the build agent).
4. **Blockers** vs **Recommended before release** — split the issues table by what
   must be fixed before the tag and what can follow.

## Step 3 — Beta testing guide (release mode only)

Produce a guide your beta testers can follow without reading the code, built from
*this mod's actual features* (Step 0 + the completeness agent), not a generic list:
- **Setup** — exact MC / Fabric Loader / Fabric API / Java versions; where to drop the
  jar; which optional sibling/integration mods to install to exercise compat; and
  whether to test on a fresh world, an upgraded pre-release world, or both.
- **What to focus on** — headline features and anything new since the last beta.
- **Feature-by-feature checklist** — one section per real feature, each with concrete
  steps and the expected result (pass/fail-able). Cover every command, config option
  (including non-default values and reload behavior), HUD/UI element, and custom asset.
- **Integration pass** — one check per optional integration and per sibling Concord
  mod (and confirm the mod still works with none installed).
- **Edge & stability pass** — `/reload`, dimension change, relog, dedicated-server vs
  single-player, and upgrading a world from the previous version (no data loss).
- **Performance sanity** — F3 check, no TPS/FPS regression in a populated world.
- **How to report** — link the repo's bug issue template; ask for the full log, exact
  mod + MC + loader versions, and minimal repro steps.
- **Results table** — a fill-in grid (feature · pass/fail · notes) testers return.

## Step 4 — Output

In **quality mode**, print the Step-2 report inline; write nothing.

In **release mode**, write both artifacts to the gitignored local scratchpad
(`.plan/` is never committed — these are working files you share with testers by
hand, not repo docs), creating `.plan/` if needed:
- `.plan/assess-<version>.md` — the Step-2 release report.
- `.plan/beta-testing-<version>.md` — the Step-3 guide.

Then print the **verdict + scorecard + blockers** inline, name the two file paths,
and close with the single most important next action.
