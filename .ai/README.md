# `.ai/` — agent-shared assets

Single, vendor-neutral home for everything AI agents (Claude Code, Jules, future tools) read when working on this repo.

| Path | What lives there |
|---|---|
| `skills/` | Domain skills (`mc-*/SKILL.md`), **vendored from [rfizzle/concord](https://github.com/rfizzle/concord)** — refresh with `make sync-skills`, never edit here. Claude Code auto-loads via `.claude/skills` symlink; Jules reads them when `AGENTS.md` points to them. |
| `skills/.concord-rev` | Provenance — the concord commit the skills were last synced from. |

The CI prompts (`code-reviewer.md`, `spec-writer.md`) and the review criteria
(`review-criteria.yml`) are **not** kept here — the generic suite defaults in
[rfizzle/concord](https://github.com/rfizzle/concord) `.ai/` are used.
Domain-specific review emphasis (e.g. the per-interaction container hot path
and CCA component serialization) lives in `AGENTS.md`, which the workflows
always inject into the prompt.

Resolution order in the reusable workflows: explicit `prompt-file` /
`criteria-file` workflow input → repo-local `.ai/` file (whole-file override)
→ concord default. To specialize a prompt or the scored categories for this
repo, copy the concord default into `.ai/` and edit it.

## Changing or adding a skill

`skills/` is wholly owned by the sync — `make sync-skills` mirrors concord's
`.ai/skills/` (including deletions), so local edits here are overwritten.

1. Edit or create the skill in `concord/.ai/skills/<name>/SKILL.md` (standard
   frontmatter: `name`, `description`), commit it there.
2. Run `make sync-skills` here (requires a sibling concord checkout, or set
   `CONCORD_DIR=`) and commit the result.
3. Add a row to the "Working with domain skills" table in `AGENTS.md` so Jules
   knows when to read it.

A skill that is genuinely specific to this repo goes *outside* the synced
directory, with `.claude/skills` rewired as a directory of per-skill symlinks
— not needed yet; see concord's README.
