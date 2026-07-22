---
description: Implement a GitHub issue end-to-end in a Concord member mod — sync master, pull the issue with `gh`, gate it against the mod's domain and the suite vision, verify its claims against the real code, produce a human-approved plan, implement with tests and docs, drive the build/unit-test/gametest sweep green, run parallel domain/standards/performance reviews, remediate approved findings, and open a Conventional-Commits PR. Three hard human gates: plan, remediation, ship.
argument-hint: <issue number> [--draft]
allowed-tools: Task, Read, Glob, Grep, Edit, Write, Bash
---

You are implementing a GitHub issue for a Concord member mod, from inside that
mod's own repository (not the concord orchestration repo). This is the
interactive, human-supervised implementation path — the local counterpart to
the `jules`-label handoff in `AGENTS.md`'s development lifecycle. The pipeline
runs issue → alignment → plan → implementation → verification → review →
remediation → PR.

**The gates are law.** At **GATE 1** (plan), **GATE 2** (remediation scope),
and **GATE 3** (ship), stop and wait for an explicit go-ahead in this
conversation. Never treat silence, elapsed time, or your own confidence as
approval. Between gates you work autonomously — don't ask permission for steps
the pipeline already prescribes.

Ground rules for the whole run:

- Never commit to or push `master`. All work happens on a feature branch and
  ships as a PR.
- Conventional Commits with the mod's scope vocabulary, imperative mood,
  lower-case (per `AGENTS.md`). No tooling or session metadata anywhere —
  commits, PR title/body, comments — per `AGENTS.md`.
- The Step 5 verification sweep (build + unit tests + gametests) must be
  green before the review phase and again before the PR.
- When a triggered `mc-*` skill and the issue genuinely conflict, surface it
  at the nearest gate — never silently pick a side.
- A `fix`-typed issue skips the feature-only domain gate in Step 1 but runs
  every other phase unchanged.

## Agent roster

Sub-agents do the parallelizable breadth work; you keep the context-heavy
spine (profiling, planning, implementing, synthesizing). Launch each Task with
the `subagent_type` below — the agent definition in `.ai/agents/` (symlinked
into `.claude/agents/`) pins its model, so you don't set one per call:
checklist breadth runs on `sonnet`, judgment on `opus`. If a `subagent_type`
can't be resolved, launch a default agent with the same charge on `opus`.

| `subagent_type` | Model | Phase | Charge |
|---|---|---|---|
| `recon` | `sonnet` | Step 2 | Verify the issue's claims against the real code; map the change surface |
| `domain-reviewer` | `opus` | Step 6 | Judge the diff as a player and as a steward of the mod's silo |
| `standards-reviewer` | `sonnet` | Step 6 | Judge the diff against the triggered `mc-*` skills and project conventions |
| `performance-reviewer` | `opus` | Step 6 | Hunt tick-budget, threading, and degradation problems in the diff |

Implementation itself is **not** delegated: you carry the approved plan, the
gate feedback, and the issue nuance, so you write the code.

## Request

$ARGUMENTS

Parse the **issue number** (required — if absent or not a number, stop and ask
for one) and an optional **`--draft`** flag (open the PR as a draft). State
the resolved issue number and mode in one line before starting.

## Step 0 — Preflight, sync, and mod profile

1. `git status --porcelain` — if the tree is dirty, stop and show the user
   what's there; do not stash or discard on your own.
2. `git checkout master && git pull --ff-only` so all work starts from the
   latest master.
3. Fetch the issue:
   `gh issue view <n> --json number,title,body,labels,state,url` plus
   `gh issue view <n> --comments` — clarifications and decisions often live in
   the comments. The issue must exist and be **open**. If it carries the
   `jules` label or `gh pr list --search "<n> in:body" --state open` shows a
   linked PR, stop and surface the duplicate-work risk before continuing.
4. Build a one-screen mod profile to reuse all run long: `fabric.mod.json`
   (mod id, entrypoints, depends/recommends/breaks, environment),
   `gradle.properties` (MC / loader / Fabric API / Java versions), `AGENTS.md`
   (identity, commit-scope vocabulary, conventions), `design/VISION.md` (the
   player-experience promise, when present), `design/SPEC.md` +
   `design/DESIGN.md` (intent and brand), `README.md` + `site/site.json` +
   `site/pages/*.json` (player-facing claims), and `.ai/skills/CATALOG.md`
   (the trigger table for the vendored skills).

## Step 1 — Understand, align, and gate the issue

**Classify** the work (`feat` / `fix` / `chore` / `refactor` / `perf`) from
the normalized title or, failing that, the body.

**Prior spec.** If the body carries an `## Implementation Spec` section (from
the `needs-spec` lifecycle), treat it as a prior contract to *verify*, not
gospel: Step 2 checks its claims. Collect anything under its Open questions,
and note an `open-questions` label if present.

**Domain-boundary gate** (feat-typed work). The mod's own `design/VISION.md`
(when present, its "What <Mod> will never do" section is binding),
`design/DESIGN.md`, and `design/SPEC.md` define its silo; the suite line is
`../concord/VISION.md` —
especially §1 (one domain per mod, nothing another member must load) and §8
(Explicitly Out of Scope, including the rejected integration-matrix rows). If
`../concord/` is absent (a bare clone), fall back to `AGENTS.md`'s conformance
claims plus the mod's design docs, and note the gap. Test the proposal four
ways:

- **Domain fit** — is this the mod's own system, deepened?
- **Independence** — no hard sibling dependency, no shared jar; sibling
  integration only via `modCompileOnly` + `isModLoaded` guards and the public
  `api` package.
- **MP fairness** — no login-timing races, no server-wide state that punishes
  the absent player.
- **Silo cleanliness** — it doesn't annex an axis another member owns (e.g.
  village standing buying wilderness loot).

If any test fails, **stop**: present the specific conflict with the vision
text it violates, and offer the realistic options — reshape it inside the
silo, note it belongs to a sibling mod, or recommend closing the issue. Wait
for the user's decision.

**Skill alignment.** From the CATALOG trigger table, name every `mc-*` skill
this work will touch, and read each triggered `SKILL.md` now — the plan must
conform to them.

**Clarifications.** Collect the genuine ambiguities — including inherited open
questions — into **one batched round** of questions for the user, and wait for
answers. If nothing is genuinely ambiguous, say so and move on; do not
manufacture questions to look thorough.

## Step 2 — Codebase reconnaissance

Spawn the **`recon`** agent (two in parallel only when the work has genuinely
separable surfaces, e.g. a server system plus a client renderer). Give it the
mod profile, the issue + spec, and this charge — return:

1. **Claim verification** — every factual statement the issue/spec makes
   about the code (paths, class names, current behavior), each confirmed or
   corrected with `file:line` evidence.
2. **Change surface** — the files that must change and the files that must be
   read to change them safely; the nearest analogous feature already in the
   repo whose pattern the new code should follow.
3. **Paired surfaces owed** — for player-facing work: config class + config
   GUI, `assets/<modid>/lang/en_us.json` keys, tooltips, `site/pages/*.json`,
   README, and the right test tier per `mc-mod-testing`.
4. **Risk notes** — mixins near hot paths, persistence/migration impact,
   thread boundaries, compat classes that import what you'll touch.

Reconcile the report yourself: if the issue is materially wrong about the
code, fold the correction into the plan — or, if it invalidates the request,
go back to the user with what you found.

## Step 3 — Plan → GATE 1

Write the implementation plan, mirroring the spec contract the suite already
uses:

- **Approach** — strategy in 3–6 sentences, naming the key classes, mixins,
  packets, or systems; if alternatives were considered, the chosen one and why.
- **Skills & alignment** — each triggered `mc-*` skill and the *specific
  constraint* it places on this change, not "consult it".
- **Files to touch** — path → what changes, correct source set for each.
- **Test plan** — unit / fabric-loader-junit / gametest split, naming the
  behaviors each tier covers.
- **Docs impact** — the `site/pages/<slug>.json`, README, and lang updates
  this change owes (schema in concord's `template/README.md`); state "none —
  internal-only" when players can't observe the change.
- **Branch & commits** — branch `<type>/<n>-<slug>` (e.g.
  `feat/42-glyph-atlas-cache`) and the planned commit sequence.
- **Open questions** — should be empty by now; anything here blocks the gate.

Present the full plan. **GATE 1: wait for explicit approval.** Fold feedback
in and re-present until approved.

## Step 4 — Implement

Create the branch off master, then execute the approved plan yourself:

- Follow the triggered skills as you write — they are the house patterns, not
  suggestions. Deviations from the plan that grow beyond small judgment calls
  go back to the user, not into the diff quietly.
- Write the tests and the Docs-impact updates as part of the same work, not as
  an afterthought.
- Read `.ai/skills/mc-gradle-builds/SKILL.md` before the first Gradle run,
  then iterate with the fast loop while you work — compile plus the unit
  tests nearest what you're changing. The full sweep is Step 5's job.
- Commit in the planned logical units as they complete, using the mod's scope
  vocabulary.

## Step 5 — Verify: build, tests, gametests

The automatic quality gate between writing the code and reviewing it — no
human input, but nothing proceeds until it is green. Run the suite the way CI
will (Makefile targets when present):

1. `./gradlew build` — compile, unit tests, jar.
2. `./gradlew runGametest` — the in-world suite.
3. `./gradlew jacocoMergedReport` — merged unit + gametest coverage, where the
   project wires it. Never read the number off the unit-only `jacocoTestReport`.

On a failure: fix the code and re-run. If a failure traces back to a defect
in the approved plan rather than the code, take it to the user — don't
re-plan silently. Never disable, weaken, or delete a test to get to green
beyond what the plan itself called for; if a pre-existing test is genuinely
wrong, that's a finding to surface, not a silent edit.

Once green, check the tests, not just their color:

- Every behavior named in the plan's Test plan has its test, at the tier the
  plan assigned it.
- The new tests exercise the new code — spot-check that the key ones would
  fail without the change.

Record the evidence — unit + gametest counts, coverage % when produced, and
anything fixed along the way — for the reviewers (Step 6) and the ship
summary (Step 8).

## Step 6 — Parallel triple review

Launch all three reviewers **concurrently** (one message, three Task calls).
Each gets: the issue + spec, the approved plan, the mod profile, the Step 5
verification evidence, and the branch diff (`git diff master...HEAD` — name
the changed files so agents can read full context around them). Each returns findings as **must_fix** (a
verified bug, regression, spec violation, or safety break — the same bar as
the CI review's `review-criteria.yml`) or **optional** (polish), every finding
with `file:line` and a concrete proposed fix, plus a one-line verdict for its
dimension.

**Domain & player experience** (`domain-reviewer`). Judge the diff as a player and as
the suite's steward: the as-built change still fits the silo, the suite
vision, and the promises in the mod's `design/VISION.md` when present (no
scope creep past the approved plan); in-game text is vanilla-toned (short,
dry, no exclamation points); every `Component.translatable(...)` key added by
the diff has an `en_us.json` entry; tooltips ordered per `mc-tooltips`; HUD
changes conform to `mc-hud`; new config options appear in the config GUI with
matching defaults; `site/` and README now tell the truth about shipped
behavior; and the absence check — every paired surface a change of this kind
owes actually exists.

**Standards & skills conformance** (`standards-reviewer`). Re-read each triggered
`SKILL.md` and judge the diff against it. Then the conventions: Mojang
mappings, ResourceLocations via the mod's `id()` helper, correct source-set
placement, registry/event/codec idiom per `mc-registration`; correctness
(logic, null/Optional, bounded loops, error paths, config/save **migration**
per `mc-persistence`/`mc-config`); mixin safety per `mc-mixin-craft` when the
diff touches mixins; `api`-package conformance per `mc-public-api` when it
touches `api/` or compat; test coverage at the right tier per
`mc-mod-testing`; commit-message hygiene.

**Performance & stability** (`performance-reviewer`). The 20Hz tick budget: per-tick
allocations in hot paths, O(n²) loops over entity lists, unbounded caches,
synchronous I/O on the server thread; new mixins on hot vanilla methods;
in-world/particle rendering without culling, LOD, or a spawn budget per
`mc-world-render`; client/server boundary crossings and unguarded shared
mutable state per `mc-shared-state`; graceful degradation on malformed config,
data, or network payloads.

## Step 7 — Synthesize → GATE 2 → remediate

Merge the three reports, dedupe overlapping findings, and present one table:
Severity · Finding · `file:line` · Review · Proposed fix. Recommend fixing
every must_fix; give a per-item recommendation on the optionals. **GATE 2:
the user picks the remediation scope.**

Apply the approved fixes: each remediated must_fix gets a regression test
where one is practical; re-run the Step 5 verification sweep to green; then
re-verify each fixed must_fix against its original finding (by test where
possible, by inspection otherwise). A must_fix the user declines is not
silently dropped — it appears in the Step 8 summary as a known issue.

## Step 8 — Ship summary → GATE 3 → PR

Present the final summary:

- **What shipped** vs the approved plan, with any deviations called out.
- **Review outcome** — findings fixed vs deferred (deferred must_fix flagged
  as known issues).
- **Evidence** — build green, unit + gametest counts, coverage if produced.
- **Docs** — the site/README/lang surfaces updated.
- **Commits** — the branch's commit list.

**GATE 3: wait for explicit approval to ship.** Then push the branch and open
the PR with `gh pr create` (`--draft` if requested): title is the normalized
Conventional-Commits title matching the issue; body opens with a short
plain-language summary, notes how it was tested, and links `Closes #<n>` (use
`Refs #<n>` only when part of the issue is deliberately deferred — say what).
No tooling or session metadata.

Print the PR URL, note that the CI code review will post its own scorecard on
the PR, and offer to file follow-up issues for any deferred findings.
