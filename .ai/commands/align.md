---
description: Reconcile a Concord member mod's documentation with what the code actually does — detects drift across VISION, SPEC, DESIGN, ASSETS, README, site/, and AGENTS, holds each design/ doc to its concord authoring guide (shape, domain ownership, existence), reports it all, and (with --fix) corrects the docs code is the source of truth for, relocates misplaced content, and scaffolds any missing design/ doc from its guide — while flagging spec/intent divergences for your judgment instead of blindly rewriting them.
argument-hint: [target: vision|spec|design|assets|readme|site|agents|all] [--fix]
allowed-tools: Task, Read, Glob, Grep, Edit, Write, Bash
---

You are running a **documentation-alignment sweep** for a Concord member mod,
from inside that mod's own repository (not the concord orchestration repo). Docs
drift from code over a mod's life; this brings them back into line — or, by
default, tells you exactly where the line broke. The four fixed `design/` docs
are additionally held to their concord authoring guides
(`../concord/design/<DOC>-GUIDE.md`): shape, domain ownership, and existence —
a doc that doesn't exist yet is a finding, and in `--fix` mode it gets
scaffolded from its guide.

The cardinal rule is **direction of truth**, and it is not the same for every
file. Some docs *describe what is* — the code is authoritative and the doc gets
corrected toward it. Others record *intent* — rewriting them to match code would
launder a bug into a blessing. Honor the split below; never collapse it.

| Doc | Truth direction | `--fix` behavior |
|---|---|---|
| `README.md` | code → doc (describes what is) | safe to correct toward code |
| `site/` (`site.json`, `pages/*.json`) | code → doc (player-facing) | safe to correct toward code |
| `AGENTS.md` (repo-owned region only) | code/repo → doc | safe to correct toward reality |
| `design/ASSETS.md` | filesystem → doc (a manifest) | safe; largely mechanical |
| `design/SPEC.md` | **bidirectional (intent)** | **report + adjudicate; no blind rewrite** |
| `design/DESIGN.md` | **bidirectional (brand intent)** | **report; correct only objective drift** |
| `design/VISION.md` | **bidirectional (experience intent)** | factual claims corrected toward code; **promises adjudicated** |

## Request

$ARGUMENTS

Parse a **target** (one or more of `vision`, `spec`, `design`, `assets`,
`readme`, `site`, `agents`, or `all`; default `all`) and the **`--fix`** flag
(absent = report-only dry run, the default). State the resolved target + mode
in one line before starting.

## Step 0 — Establish ground truth (do this once, share with every agent)

Alignment is only as good as your model of what the code *actually does*. Compute
it once so the per-doc agents don't each re-derive it. Gather:

- **Identity & metadata** — `fabric.mod.json` (mod id, name, description,
  entrypoints, `depends`/`recommends`/`breaks`, `icon`, environment),
  `gradle.properties` (MC / loader / Fabric API / Java versions).
- **The real feature surface** — registered content (items, blocks, entities,
  sounds), commands, the config class (every public field + its default and
  validation), networking payloads, the public `com.rfizzle.<mod>.api` package,
  and any HUD element. This is the spine every doc is measured against.
- **Player-facing strings** — `assets/<modid>/lang/en_us.json` keys, tooltips,
  death messages.
- **Assets on disk** — `art/` masters and their `.glyph`/`.sfx` sources, and
  `src/main/resources/assets/<modid>/**` (textures, sounds, models).
- **History** — `git log --oneline` since the last tag and `git tag --list`, so
  you can tell *recent intentional change* from *long-standing drift* when
  adjudicating SPEC.

Suite standards, when present, are at `../concord/` — `REPO-LAYOUT.md`,
`API-STANDARD.md`, `HUD-STANDARD.md`, `design/DESIGN-SYSTEM.md`, and the
`design/*-GUIDE.md` authoring guides (VISION / DESIGN / SPEC / ASSETS — each
fixed `design/` doc's canonical shape, requirements, and truth direction).
Read the relevant one per target; if `../concord/` is absent, fall back to
`AGENTS.md`'s declared conformance and note the gap.

## Step 1 — Fan out one agent per requested target (in parallel)

Spawn the agents for the resolved target set concurrently (one message, multiple
Task calls), each with the Step-0 ground truth and the mode. Every agent returns:
the **drift items** it found (each as: doc location `file:line` · what the doc
says · what the code/reality is · classification), and, **only in `--fix` mode**,
the edits it is authorized to make per the rules below. Tell each to cite
`file:line` on both sides of every divergence.

**Every `design/` doc agent (`vision`, `spec`, `design`, `assets`) also holds
its doc to its concord authoring guide** (`../concord/design/<DOC>-GUIDE.md`,
when concord is present): required sections present, the guide's checklist
satisfied, and **domain ownership** — content the guide assigns to a sibling
doc (an asset table or listing copy in DESIGN.md, behavior rules in VISION.md,
brand prose in SPEC.md, rationale in ASSETS.md) is flagged as **Misplaced**
with its destination named. `--fix` may perform only mechanical,
information-preserving relocations (move the table, leave a pointer); anything
needing rewording is report-only. **A missing doc is a finding, not a skip**:
report the absent file, and in `--fix` mode generate it from its guide's
template, seeded from the Step-0 ground truth and the sibling docs — `assets`
scaffolds are mechanical and land finished; `vision`/`spec`/`design` scaffolds
are intent documents, so land them as complete drafts and list every judgment
call they embed for the human to review.

**`readme` + `site` — player-facing description (code is truth).** Run these as a
pair and **reconcile them against each other as well as against code** — README
and `site/pages/*.json` make overlapping feature/config/command claims and must
agree. Flag: features documented but not implemented (and vice-versa), wrong
config defaults or option names, removed/renamed commands, stale version or
compatibility claims, screenshots/feature lists that no longer match. `--fix`:
correct the prose/JSON toward the code. (`site/pages/*.json` block schema is in
`../concord/template/README.md`.)

**`agents` — conventions & repo facts (reality is truth).** Verify the
**repo-owned** parts of `AGENTS.md`: entrypoints match `fabric.mod.json`, the
stated source layout matches `src/`, commit-scope vocabulary still fits the
package structure, and **declared conformance versions are truthful** (the
API/HUD-STANDARD versions it claims). **Hard constraint: never touch anything
between `<!-- concord:managed:start -->` and `<!-- concord:managed:end -->`** —
that region is synced from concord by `propagate.yml`; drift there is fixed in
concord, not here. If you find managed-block drift, *report* it as "fix upstream
in concord," don't edit it. `--fix`: correct only the repo-owned prose.

**`assets` — manifest vs filesystem (mechanical).** Diff `design/ASSETS.md`
against what's actually on disk: every listed `.glyph`/`.sfx` source exists, every
declared final resource path exists, every shipped asset under
`art/`+`src/main/resources/assets/` is accounted for, and `MISSING` markers are
accurate (still missing → keep; now present → update with the real path). `--fix`:
update the manifest to match disk.

**`vision` — the player-experience promise (bidirectional; claims are
checkable, promises are not).** Compare `design/VISION.md` to reality on two
axes. **Factual claims** — every promised feature exists and behaves as
described, the how-you-use-it steps match the real interactions, and every
quoted number matches the code (the vision quotes values; SPEC and the code
own them). Stale claims are code→doc corrections. **Promises** — the arc, the
tone, "What <Mod> will never do": a shipped feature the vision never promised,
or one colliding with the never-do list, is adjudicated like spec intent,
never rewritten silently. Also enforce the guide's hard rule: implementation
vocabulary anywhere in the file is a finding. `--fix`: correct factual drift
and vocabulary violations; report promise-level divergence.

**`spec` — intent (bidirectional; adjudicate, do not blindly rewrite).** Compare
`design/SPEC.md`'s behavioral spec to the implementation. For **each** divergence,
classify it:
  - **Code drifted from spec (likely a bug/regression)** — the spec is right, the
    code is wrong. *Never* edit the spec to match. Report it prominently as a
    suspected bug with the `file:line` of both, and (if siblings agree) note that
    README/site/tests still expect the spec'd behavior.
  - **Intentional change, spec is stale** — code + tests + player-facing docs all
    reflect a deliberate new behavior the spec never caught up to. The spec should
    be updated.
  - **Ambiguous** — can't tell from code/history alone. Report, recommend a human
    call.
`--fix` here is conservative: update the spec **only** for items confidently in
the "intentional change, spec stale" bucket (corroborated by tests *and*
player-facing docs); leave suspected-bug and ambiguous items as report-only
findings for you to resolve. Write the spec update as present-tense intent (it
records *what the mod does and why*), never as a changelog of what changed.

**`design` — brand intent (bidirectional; objective drift only).** Check the
checkable parts of `design/DESIGN.md`: palette tokens against
`../concord/design/DESIGN-SYSTEM.md`, and whether the declared HUD-slot decision
matches the actual HUD implementation (or its documented absence). Report
subjective brand/narrative mismatches; do not rewrite voice or motif. `--fix`:
correct only objective facts (a wrong palette hex/token, a HUD-slot claim the code
contradicts).

Only spawn agents for the requested targets. `all` runs all seven (readme+site
still pair up).

## Step 2 — Report (and, in `--fix` mode, apply)

Reconcile overlaps (a single feature gap can surface in SPEC, README, and site at
once — present it once, noting every doc it touches) and produce:

1. **Summary** — target, mode, and a one-line drift verdict (Aligned / Minor drift
   / Significant drift).
2. **Drift table** — Doc · Location `file:line` · Doc says · Reality · Classification
   (Doc-stale · **Suspected code bug** · Ambiguous · Mechanical · Misplaced ·
   Missing-doc) · Action (Fixed / Relocated / Generated / Proposed /
   Needs-your-call / Upstream-in-concord).
3. **⚠ Suspected code bugs** — called out separately and first; these are the
   items where *code*, not a doc, is probably wrong. Never silently "fixed" by
   editing a doc.
4. **Aligned** — a brief note of what was checked and found consistent, so a clean
   result reads as verified, not skipped.

In **`--fix` mode**, apply only the edits authorized above — code→doc corrections,
mechanical manifest updates, confidently-stale spec items, information-preserving
relocations, and missing-doc scaffolds (intent-doc scaffolds land as drafts
with their judgment calls listed) — then list exactly which files you changed
and which findings you deliberately left for the human
(every suspected-bug and ambiguous item, and any managed-block drift). Never edit
source code, never edit the AGENTS managed block, and never rewrite a spec to
match code you suspect is buggy. In report-only mode, change nothing; end by noting
the user can re-run with `--fix` to apply the safe corrections.
