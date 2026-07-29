---
description: Survey the whole Concord suite for conformance drift — fans parallel dimension agents (build/tooling, testing, code architecture, layout/design/docs) across every member repo, checks each against the hub standards, surfaces conventions that are consistent-but-undocumented as skill/standard candidates, and reports a ranked reconciliation plan. Report-only by default; --issues drafts the GitHub issues for approved findings.
argument-hint: [dimension: build|testing|code|layout|all] [--issues]
allowed-tools: Task, Read, Glob, Grep, Write, Bash
---

You are running a **suite-wide conformance survey** from inside the concord
orchestration repo (if the working directory is a member mod instead, stop and
say so — this command reads *across* `../<member>` checkouts and only makes
sense from the hub). Members drift apart in three ways, and the survey must
distinguish them:

1. **Violations** — a member breaks a written standard (`API-STANDARD.md`,
   `HUD-STANDARD.md`, `REPO-LAYOUT.md`, a vendored skill).
2. **Forks** — members solve the same problem differently and *no* standard
   picks a winner (five clamp-helper signatures, four config-sync payload
   names). These are the expensive kind: every new member coin-flips.
3. **Consistent-but-undocumented** — all/most members already agree and the
   convention exists nowhere in writing. Cheapest wins available: write down
   what is already true before it forks.

The deliverable is the ranked report, not edits. Never modify a member repo.

## Request

$ARGUMENTS

Parse a **dimension** (one or more of `build`, `testing`, `code`, `layout`;
default `all`) and the **`--issues`** flag (absent = report only, the default).
State the resolved dimension(s) + mode in one line before starting.

## Step 0 — Ground truth (once, before any agent)

- **Hub freshness first.** Run `git fetch origin && git status` in concord. If
  the hub is behind `origin/master`, stop and tell the user to pull — a stale
  hub makes every "member diverges from hub" finding suspect. (This exact
  failure produced a false "hub is 17 skills behind its members" finding once;
  do not repeat it.)
- Enumerate members from `members.json` and verify each `../<id>` exists on
  disk. If a directory beside the hub looks like a member (has
  `.ai/skills/.concord-rev`) but is absent from `members.json`, that is itself
  a top-priority finding — survey it anyway.
- Read the standards the agents will grade against: `API-STANDARD.md`,
  `HUD-STANDARD.md`, `REPO-LAYOUT.md`, `AGENTS-COMMON.md`,
  `design/DESIGN-SYSTEM.md`, `.ai/skills/CATALOG.md`.
- Pull the open conformance backlog so the report doesn't re-litigate it:
  `gh issue list --state open --json number,title`. Findings that match an
  open issue are *excluded* from the report body and listed once in a short
  "already tracked" appendix.
- Check sync provenance: every member's `.ai/skills/.concord-rev` must resolve
  to a real commit in the hub (`git cat-file -e <sha>`), and the vendored
  `.ai/` trees should be byte-identical to the hub's
  (`diff -rq .ai/skills ../<id>/.ai/skills`). Stale members and unresolvable
  SHAs are plumbing findings — report them ahead of everything else, because
  documentation cannot propagate until sync works.

## Step 1 — Parallel dimension agents

Launch one read-only agent per selected dimension, all in a single batch. Each
agent sweeps **every** member and reports in the same shape: (1) a per-member
divergence table of key values, (2) consistent-but-undocumented conventions
worth codifying, (3) per-member outliers — everything with `file:line`
references. Tell every agent which topics are already tracked (from Step 0) so
it skips them.

- **build** — `gradle.properties` / `versions-common.properties` wiring, MC /
  loader / Fabric API / loom / Java pins, `build.gradle` structure (version
  derivation, repositories, jacoco, `runGametest` config idiom), Makefile
  target vocabulary, workflow stubs vs the hub's reusable workflows (do the
  called workflows exist? are the stubs byte-identical?), release tooling,
  datagen wiring (entrypoint ⇔ make target ⇔ verify task ⇔ CI),
  `fabric.mod.json` shape, `.gitignore` managed block.
- **testing** — source-set layout, gametest class/package/batch/timeout
  conventions, tick-wait idioms, `.snbt` template location vs what ships in
  the jar, copy-pasted test helpers that have drifted (MockPlayers, bootstrap
  boilerplate, entrypoint-parity guards, resource-contract tests),
  source-tree-fixture reads vs declared `test` task inputs, CI test/coverage
  invocation vs what the Makefiles document as the real number.
- **code** — API-STANDARD conformance (api package, `@Stable` shape, listener
  error isolation at every `createArrayBacked` dispatch, sanctioned provider
  shapes, reflection-accessor idiom), HUD-STANDARD conformance (contract
  method names, derived vs literal heights, legacy fallbacks), config
  conventions (live-instance ownership, clamp helpers, sync payloads,
  migrations), package layout naming, localization key vocabulary, bootstrap
  class shape, client-state teardown, split-source-set discipline.
- **layout** — REPO-LAYOUT conformance per member, files that exist in
  all/most members but appear nowhere in the standard, `design/` template
  generations (do all members carry the newest DESIGN.md sections, e.g.
  `### Motif`?), README/LICENSE tiers, `site/` schema drift, `art/` reality
  vs the written rule, changelog + release-tag conventions, AGENTS.md
  unmanaged-head drift.

## Step 2 — Synthesize

Merge the agent reports into one document with these sections, in this order:

1. **Plumbing** — anything that blocks propagation itself (sync direction,
   provenance, dead shared files, workflows calling nonexistent reusables,
   `members.json` gaps). These outrank every convention finding.
2. **Ranked skill/standard candidates** — each entry states: the finding, its
   class (violation / fork / undocumented-consensus), which members diverge,
   the best-in-class implementation to promote (name the member and file), and
   the concrete fix (amend standard X §Y / new skill / member work item).
   Rank by how much active drift the fix stops, not by severity.
3. **One-off defects** noticed along the way (typos, dead config, shipped test
   fixtures) — a flat list, each one sentence + location.
4. **Already tracked** — issue numbers matched in Step 0, one line each.

Write the full report to `.plan/conform-<date>.md` and give the user the
condensed version inline (plumbing + top ~10 candidates). Report-only is the
contract: no member edits, no hub edits, no issues yet.

## Step 3 — `--issues` mode only

With `--issues`, after presenting the report, propose a set of issues (title +
two-line body + labels, grouped so one issue = one decision) and **wait for the
user to approve the list** before running any `gh issue create`. Never file
issues for section 3 one-offs without being asked — batch them into a single
"suite nits" issue instead.
