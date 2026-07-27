---
name: mc-audio
description: How to produce a good Concord sound effect through the .sfx procedural-synthesis pipeline — the craft reference and quality bar for custom UI/alarm/tech audio cues. TRIGGER when adding or editing any custom sound (anything under assets/<mod>/sounds/, a sounds.json entry, a registered SoundEvent), or when authoring or editing a .sfx spec.
---

You are making (or judging) a **custom** sound for a Concord mod — this skill is the craft
reference for making a good one. Custom sound comes from **procedural synthesis**: no
recorded audio, no samples, no DAW, no licensing — just a declarative spec and a
deterministic synth. Synthesis shines on synthetic cues — alarm klaxons, UI blips,
sci-fi/tech alerts, charge-ups, chiptune stings.

## What "good" means

A Concord sound is conformant when it:

- **fits the format** — **Ogg Vorbis, mono** (mono is required for 3D distance attenuation;
  stereo plays at constant volume and is only for music/ambient/UI), 44.1 or 48 kHz.
- **is reproducible from its spec** — noise is seeded per layer, so editing or reordering
  one layer never re-rolls another. The same `.sfx` always renders the same cue.
- **is short and trimmed** — an SFX cue, not a track. No leading/trailing silence; tight
  envelope. Most cues are well under ~2 seconds.
- **sits centred** — a pulse wave carries a DC offset that grows as its duty leaves 50%.
  A mild one is inherent to the timbre, but a large one costs headroom once the game mixes
  several sounds and can click when a cue is cut. The render reports it; a highpass on the
  offending layer (`"filter": {"type": "highpass", "cutoff": 20}`) recentres the cue
  without touching how it sounds — which is what real chip hardware does at its output.
- **sits at vanilla loudness** — matched on **perceived loudness** (K-weighted, target
  −14 LUFS) with the peak kept as a ceiling (≈ −1 dBFS, no clipping), so it neither
  buries nor hides under vanilla sounds at the same volume. Peak alone does not carry
  this: a square-wave klaxon and a sine blip peaked to the same −1 dBFS differ by about
  10 dB to the ear. A cue whose transient hits the ceiling before it reaches the target
  is flagged, and the fix is to soften the spike rather than accept a quiet cue.
- **reads as one gesture** — a single recognizable cue (rise, fall, two-tone, pulse), not a
  busy patch. Silhouette first, like a glyph.
- **belongs to its mod** — matches the feature's character (e.g. a "Cold Steel" tech pylon
  wants a cold synthetic klaxon, not a warm chime).
- **ships its companions** — a registered `SoundEvent`, a `sounds.json` entry, and a
  **subtitle** (accessibility — shown when subtitles are on; non-negotiable).

## The pipeline

Author sounds as declarative **`.sfx` specs** (JSON) and let
`.ai/skills/mc-audio/scripts/sfx.py` synthesize them deterministically — you describe the
layers (oscillators, envelopes, pitch sweeps), the script renders exactly that (seeded
noise, no drift). Synthesis is stdlib-only and ships beside this skill, so it runs anywhere
the skill is vendored; its one external tool is **ffmpeg** (the WAV→Ogg Vorbis encode). The
`/sfx` slash command drives it end to end.

Spec shape — global fields (`sample_rate`, `loudness_lufs`, `peak_dbfs`, `subtitle`,
`seed`, `ships`, output `name`)
plus a list of `layers`, each a `waveform` (`sine`/`square`/`triangle`/`saw`/`noise`), a
`freq` or pitch glide (`from`→`to`), an amplitude `env` (attack/decay/sustain/release), a
`start`/`duration`, and optional `filter`/`gain`/`repeat`, plus the character tools:
`duty` (square pulse width — thin duties read chiptune), `vibrato` (`rate`/`depth` pitch
LFO — klaxon wobble), and a filter cutoff sweep (`filter` with `from`→`to` — whoosh,
riser, charge). A layer's `notes` list makes a
chiptune sting. Full format + worked example: the `SPEC FORMAT` header of
`.ai/skills/mc-audio/scripts/sfx.py`, and the `/sfx` command. Reference specs to copy from
live under `.ai/skills/mc-audio/examples/`, one per archetype: `ui-blip` (two-tone
confirm), `charge-up` (riser + pop), `alarm-klaxon` (alternating two-tone with vibrato),
and `chiptune-sting` (a `notes` arpeggio with thin `duty`).

```bash
S=.ai/skills/mc-audio/scripts/sfx.py
python3 $S .ai/skills/mc-audio/examples/ui-blip.sfx          # synth + render report
python3 $S --list-waveforms                                  # available oscillators
python3 $S CUE.sfx -o art/audio/<cue>.ogg                    # render preview + report (gitignored)
python3 $S CUE.sfx --verify                                  # shipped cue still matches the spec?
```

The oscillators are band-limited, so square and saw hold their character up the
register instead of shedding fold-back junk into the top octaves — a thin-duty
chiptune lead or a 5 kHz zap is a legitimate thing to write.

**You cannot hear the output — so close the loop with objective signals, then a human.**
Every render emits, beside the `.ogg`: a **waveform + spectrogram PNG** (`<name>.report.png`
— read it back and judge the shape against the gesture you intended) and **stats**
(duration, peak dBFS, loudness LUFS, RMS, spectral centroid, DC offset). The report carries its own
scale: labelled dBFS and time axes, a **log** frequency axis ticked from 100 Hz to 20 kHz,
and a dashed marker at every layer onset — so "the second layer lands at 0.3 s" is
something you can read off the picture rather than assume. Iterate the spec until those match intent —
fixing a synth is fast (edit the `.sfx`, re-run). The final **ear-check is a human's**; flag
that the sound needs a listen before it lands.

## Minecraft packaging

- **`sounds.json`** at `assets/<mod>/sounds.json` maps the event name → `{ "sounds": [...],
  "subtitle": "<mod>.subtitle.<event>" }`. Add the subtitle's translation key to the lang file.
- **Register** the `SoundEvent` (`Registry.register(BuiltInRegistries.SOUND_EVENT, id,
  SoundEvent.createVariableRangeEvent(id))`) — follow `mc-registration`.
- **`SoundSource` category** (`BLOCKS`, `PLAYERS`, …) is chosen at the `playSound` call site,
  not in `sounds.json` — pick the category that respects the right volume slider.

## Companion `.sfx` files (the repeatability rule)

`art/audio/` holds the committed `.sfx` source of truth (same basename as the sound, e.g.
`art/audio/pylon-alarm.sfx`). The rendered `.ogg` is **not** kept there: render a preview
into `art/audio/` to inspect it — the `.ogg` and `.report.png` there are throwaway and
gitignored — then ship the final `.ogg` to `assets/<mod>/sounds/<event>.ogg`, the only
committed copy. The `.sfx` re-renders reproducibly, so re-touching a sound means editing
the `.sfx` and re-rendering.

Every spec carries a **`ships`** field naming that shipped path, which is what holds the
rule up: `sfx.py CUE.sfx --verify` re-synthesizes the spec, decodes the `.ogg` that
shipped, and compares duration, peak, loudness, and spectral centroid — so what gets
measured is what the game actually plays, not the pre-encode buffer. `sfx.py --verify-all`
runs that across every cue under `art/audio/`, at any depth (exit non-zero, so it belongs
in CI) — a re-encoded `.ogg` fails as **drift**, a spec that no longer parses fails as
**malformed**, and a machine with no ffmpeg to decode the shipped cue fails as
**blocked**, counted apart so a CI wrapper can say which of the three happened. A spec
with no `ships` field is reported as unlinked — it has no declared deliverable, so nothing
holds it to anything.

## Quick checklist

- [ ] Ogg Vorbis, mono, trimmed, short; loudness on target with peak ≈ −1 dBFS
      (no clipping) and no peak-ceiling warning
- [ ] DC offset small, or highpassed back to centre if a thin duty pushed it out
- [ ] Single clear gesture that fits the mod's character
- [ ] Rendered via `.ai/skills/mc-audio/scripts/sfx.py`; waveform/spectrogram + stats read
      back and judged
- [ ] Human ear-check requested before it lands
- [ ] `SoundEvent` registered, `sounds.json` entry + subtitle key + lang translation
- [ ] `.sfx` source committed in `art/audio/`; the shipping `.ogg` in `assets/<mod>/sounds/`
      (the `.ogg`/report rendered into `art/audio/` are gitignored throwaways)
- [ ] `ships` names that shipping path, and `--verify` passes on it
