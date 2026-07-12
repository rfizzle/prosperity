---
description: Synthesize a Concord sound effect from a declarative .sfx spec — a UI blip, an alarm klaxon, a tech/sci-fi alert, a charge-up, or a chiptune sting — and render it to Ogg Vorbis with a waveform/spectrogram report.
argument-hint: <sound description> [mod]
allowed-tools: Read, Write, Bash(python3 .ai/skills/mc-audio/scripts/sfx.py:*)
---

You are designing a **procedurally synthesized sound effect** for a Concord
Minecraft mod, then rendering it to Ogg Vorbis with
`.ai/skills/mc-audio/scripts/sfx.py`. The pattern: *you* describe the sound as a
declarative `.sfx` spec (which you do reliably); the script deterministically
synthesizes it (which you can't). The craft is in the spec.

`.ai/skills/mc-audio/scripts/sfx.py` is the single synth — stdlib only, runs
anywhere Python 3 does. Its one external tool is **ffmpeg**, used only to encode
the rendered WAV to Ogg Vorbis (Minecraft needs `.ogg`).

## Request

$ARGUMENTS

## Step 1 — Pin the character

If no mod is named, ask which mod the sound is for — its identity decides the
character (a "Cold Steel" tech pylon wants a cold synthetic klaxon, not a warm
chime). Decide the **gesture** in one phrase: a rise, a fall, a two-tone, a
pulse, a charge-up. A good cue reads as *one* gesture — silhouette first, like a
glyph. Keep it short (most cues are well under ~2 seconds).

## Step 2 — Write the spec

A `.sfx` file is JSON: global fields (`name`, `sample_rate`, `peak_dbfs` ≈ −1.0,
`subtitle`, `seed`) plus a list of `layers`. Each layer is a `waveform`
(`sine`/`square`/`triangle`/`saw`/`noise` — run
`python3 .ai/skills/mc-audio/scripts/sfx.py --list-waveforms`), a `freq` or a
pitch glide (`from`→`to`, `glide` `exp`|`lin`), an `env`
(attack/decay/sustain/release), a `start`/`duration`, and optional
`gain`/`filter`/`repeat`. Character tools: `duty` (square pulse width — thin
duties read chiptune), `vibrato` (`{"rate": Hz, "depth": semitones}` — klaxon
wobble), and a filter cutoff sweep (`"filter": {"type": "lowpass", "from": 300,
"to": 6000}` — whoosh/riser/charge). A layer's `notes` list renders a sequence
(chiptune sting). The full format with a worked example is the `SPEC FORMAT`
header of `.ai/skills/mc-audio/scripts/sfx.py`.

Keep the patch lean (1–3 layers), pick a **`seed`** so noise renders
reproducibly, and write a **`subtitle`** key (`<mod>.subtitle.<event>`) —
accessibility is non-negotiable. Write the spec to `art/audio/<cue>.sfx` (or a
path the user gives). The `.sfx` is the committed source of truth; the rendered
files are throwaway review artifacts (step 3); the shipped master lands in the
mod's asset tree on approval (step 4).

## Step 3 — Render and review (you can't hear it)

Render the spec — the derived files land beside it in `art/audio/`, where the
suite `.gitignore` already ignores every render (`.ogg`, `.wav`, `.report.png`):

```bash
python3 .ai/skills/mc-audio/scripts/sfx.py art/audio/<cue>.sfx
```

This writes `<cue>.ogg`, `<cue>.wav`, and `<cue>.report.png` (a waveform +
spectrogram) beside the spec, and prints stats: duration, peak dBFS, RMS,
spectral centroid. These renders are throwaway review artifacts — gitignored, so
they can sit beside the source for review without ever being committable. Close
the loop on **objective signals**: **read the `.report.png`
back** and check the shape matches the gesture (envelope, sweep direction,
harmonic content); confirm peak ≈ −1 dBFS (no clipping), the duration is tight,
and the centroid suits the character (bright vs. dark). Iterate the spec until
they match — editing a `.sfx` and re-running is fast. Show the user the report
each iteration.

**The final ear-check is the human's.** You cannot judge timbre by eye — when the
report looks right, tell the user the sound needs a listen before it lands.

## Step 4 — Place the master and wire it up

Two locations, no duplication:

- **Source** — the `.sfx` is committed in `art/audio/<cue>.sfx` (step 2). This is
  the re-renderable deliverable.
- **Master** — the shipped `.ogg`. On approval, render it **straight into the
  mod's resource tree**, `src/main/resources/assets/<mod>/sounds/…` (confirm the
  exact subpath with the user). The renders beside the spec in `art/audio/` are
  gitignored review artifacts, never the shipped copy.

So the review renders (step 3) stay uncommitted beside the spec — once the cue is
approved, re-render with `-o` pointing at the final `assets/<mod>/sounds/…` path.

Then wire the Minecraft side (see the `mc-audio` and `mc-registration` skills):

- **`sounds.json`** (`assets/<mod>/sounds.json`): map the event name →
  `{ "sounds": [...], "subtitle": "<mod>.subtitle.<event>" }`, and add the
  subtitle key to the lang file.
- **Register** the `SoundEvent`
  (`SoundEvent.createVariableRangeEvent(id)`).
- **`SoundSource`** category is chosen at the `playSound` call site (so it tracks
  the right volume slider), not in `sounds.json`.

Keep going until the cue reads as one clear gesture and fits the mod's identity.
Show the user the report each iteration, and remember: a human must listen before
it ships.
