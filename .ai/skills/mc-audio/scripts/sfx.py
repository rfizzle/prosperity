#!/usr/bin/env python3
"""Synthesize a declarative sound spec into a Minecraft-ready Ogg Vorbis cue.

The idea mirrors the glyph pipeline: a language model describes a sound far more
reliably than it emits audio samples. So a sound effect is authored as a *spec* —
a small JSON document of synthesis `layers` (oscillators, envelopes, pitch
sweeps) — and this script deterministically renders it to a mono WAV, encodes it
to Ogg Vorbis, and writes a **render report** you can actually inspect: a
waveform + spectrogram PNG plus loudness/spectral stats.

The model can't hear the result, so the report is the feedback loop: read the
shape back, check the numbers, iterate the spec. The final ear-check is a human's.

Synthesis is stdlib-only (`math`, `array`, `wave`, `zlib`) so it runs anywhere
Python 3 does. The single external tool is **ffmpeg**, used to encode the
rendered WAV to Ogg Vorbis (`-c:a libvorbis`) — Minecraft needs `.ogg` — and to
decode a shipped cue back for `--verify`. Without ffmpeg the WAV and report are
still written, but the run exits non-zero: the `.ogg` is the deliverable, and a
render that produced none has not succeeded.

SPEC FORMAT
-----------
A `.sfx` file is JSON. Top-level fields (all optional except `layers`):

    {
      "name": "pylon-alarm",      # output basename (default: spec filename stem)
      "sample_rate": 44100,       # 44100 or 48000
      "loudness_lufs": -14.0,     # target perceived loudness (null = peak-only)
      "peak_dbfs": -1.0,          # ceiling the loudness match may not exceed
      "subtitle": "mercantile.subtitle.pylon_alarm",  # accessibility key (reminder only)
      "seed": 1234,               # seeds noise so renders are reproducible
      "ships": "src/main/resources/assets/mercantile/sounds/pylon_alarm.ogg",
      "layers": [ ... ]           # one or more synthesis layers, mixed together
    }

`loudness_lufs` is what makes cues sit together in game. Peak normalization
alone does not: a square-wave klaxon and a sine blip both peaked at -1 dBFS
differ by ~10 dB to the ear, so one buries vanilla and the other hides under it.
Cues are matched on K-weighted loudness (ITU-R BS.1770) with `peak_dbfs` kept
only as a ceiling — a spiky cue that hits the ceiling before reaching the target
says so rather than being silently quiet.

`ships` records where the encoded master belongs in the mod's resource tree. It
is what `--verify` checks against, and what makes the repeatability rule
enforceable: the `.sfx` is the source of truth, the shipped `.ogg` is derived,
and drift between them is now a build failure rather than a silent edit.

Each layer:

    {
      "waveform": "square",       # sine | square | triangle | saw | noise
      "freq": 440,                # constant pitch (Hz) ...
      "from": 880, "to": 220,     # ... OR a pitch glide (overrides freq)
      "glide": "exp",             # glide curve: exp (default) | lin
      "duty": 0.25,               # square only: pulse width 0..1 (default 0.5)
      "vibrato": {"rate": 6, "depth": 0.5},  # pitch LFO: rate Hz, depth semitones
      "start": 0.0,               # layer onset, seconds
      "duration": 0.25,           # tone length before release, seconds
      "gain": 0.8,                # linear mix level (0..1)
      "env": {"attack": 0.005, "decay": 0.05, "sustain": 0.6, "release": 0.05},
      "filter": {"type": "lowpass", "cutoff": 3000},   # one-pole lowpass|highpass
                                  # ... or a cutoff sweep over the note:
                                  # {"type": "lowpass", "from": 200, "to": 4000,
                                  #  "sweep": "exp"}   # sweep: exp (default) | lin
      "repeat": {"count": 3, "interval": 0.3},          # repeat the whole layer
      "notes": [                  # OR a sequence (chiptune sting); shares the
        {"freq": 523, "start": 0.0, "duration": 0.1},   # waveform/env/filter above
        {"freq": 659, "start": 0.1, "duration": 0.1}    # note.start is relative
      ]                                                  # to the layer start
    }

A note may carry its own `freq` / `from`+`to` / `gain` / `duty` overrides.
`env.sustain` is a level (0..1); `attack`/`decay`/`release` are seconds. Release
extends a note past its `duration`. Total cue length is inferred from the layers.

USAGE
-----
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx               # -> SPEC.ogg + SPEC.report.png + stats
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx -o art/audio/alarm.ogg
    python3 .ai/skills/mc-audio/scripts/sfx.py - < SPEC.sfx           # spec on stdin
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx --no-report   # skip the PNG
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx --verify      # shipped cue still matches the spec? (uses ships)
    python3 .ai/skills/mc-audio/scripts/sfx.py --verify-all           # ... same check over every cue under art/audio/, at any depth
    python3 .ai/skills/mc-audio/scripts/sfx.py --list-waveforms       # available oscillators

Exits non-zero when it could not write the `.ogg` — that file is the
deliverable, so a run that produced only the WAV fallback has not succeeded.
"""

import argparse
import array
import cmath
import json
import math
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import wave
import zlib
from pathlib import Path

WAVEFORMS = ("sine", "square", "triangle", "saw", "noise")

DEFAULTS = {
    "sample_rate": 44100,
    "peak_dbfs": -1.0,
    "loudness_lufs": -14.0,
    "seed": 0,
}
# How far a cue may sit from the loudness target before it is worth saying so.
LOUDNESS_TOLERANCE_DB = 1.0
# DC offset, as a share of peak, past which a cue is worth flagging. A pulse
# wave carries an offset by construction — it grows as the duty leaves 50% —
# so the bar sits above what the reference cues measure (klaxon 28%, chiptune
# 14%) and catches only the levels a bare thin pulse reaches (duty 0.25 is 48%).
DC_OFFSET_WARN_PCT = 40.0
DEFAULT_ENV = {"attack": 0.005, "decay": 0.04, "sustain": 1.0, "release": 0.04}


class SpecError(ValueError):
    """A malformed .sfx spec — message is shown to the user, no traceback."""


# --------------------------------------------------------------------------- #
# Spec parsing
# --------------------------------------------------------------------------- #

def parse_spec(text):
    try:
        spec = json.loads(text)
    except json.JSONDecodeError as e:
        raise SpecError(f"not valid JSON: {e}") from None
    if not isinstance(spec, dict):
        raise SpecError("top level must be a JSON object")
    layers = spec.get("layers")
    if not isinstance(layers, list) or not layers:
        raise SpecError("'layers' must be a non-empty list")
    spec["sample_rate"] = int(spec.get("sample_rate", DEFAULTS["sample_rate"]))
    if spec["sample_rate"] not in (44100, 48000):
        raise SpecError("sample_rate must be 44100 or 48000")
    spec["peak_dbfs"] = float(spec.get("peak_dbfs", DEFAULTS["peak_dbfs"]))
    if spec["peak_dbfs"] > 0:
        raise SpecError("peak_dbfs must be <= 0 (it is dB below full scale)")
    target = spec.get("loudness_lufs", DEFAULTS["loudness_lufs"])
    if target is not None:
        target = float(target)
        if target > 0:
            raise SpecError("loudness_lufs must be <= 0 (it is dB below full scale)")
    spec["loudness_lufs"] = target
    spec["seed"] = int(spec.get("seed", DEFAULTS["seed"]))
    return spec


def _env(layer):
    e = dict(DEFAULT_ENV)
    e.update(layer.get("env") or {})
    return e


def _glide_freq(note, t, dur):
    """Instantaneous frequency at time t within a note of length dur."""
    if "from" in note and "to" in note:
        f0, f1 = float(note["from"]), float(note["to"])
        frac = 0.0 if dur <= 0 else min(1.0, max(0.0, t / dur))
        if note.get("glide", "exp") == "lin" or f0 <= 0 or f1 <= 0:
            return f0 + (f1 - f0) * frac
        return f0 * (f1 / f0) ** frac
    return float(note.get("freq", 440.0))


# --------------------------------------------------------------------------- #
# Synthesis
# --------------------------------------------------------------------------- #

def _poly_blep(t, dt):
    """Polynomial band-limited step correction around a discontinuity.

    A square or saw built by direct waveshaping has instantaneous jumps, whose
    harmonics run past Nyquist and fold back down as inharmonic partials — a
    5 kHz saw at 44.1 kHz lands audible junk at 19 kHz. PolyBLEP smears each
    jump across the two samples either side of it, which cancels most of that
    fold-back while leaving the waveform's shape (and its duty) intact.
    """
    if t < dt:
        t /= dt
        return t + t - t * t - 1.0
    if t > 1.0 - dt:
        t = (t - 1.0) / dt
        return t * t + t + t + 1.0
    return 0.0


def _osc(waveform, phase, rng, duty=0.5, dt=0.0):
    """One oscillator sample. `dt` is the phase increment per sample (freq/sr),
    which the band-limited waveforms need to size their step correction."""
    if waveform == "sine":
        return math.sin(phase)
    frac = (phase / (2 * math.pi)) % 1.0
    # dt >= 0.5 means the fundamental is at or above Nyquist; there is nothing
    # left to band-limit, so fall through to the naive shape.
    blep = 0.0 < dt < 0.5
    if waveform == "square":
        v = 1.0 if frac < duty else -1.0
        if blep:
            v += _poly_blep(frac, dt)                       # rising edge at 0
            v -= _poly_blep((frac + 1.0 - duty) % 1.0, dt)  # falling edge at duty
        return v
    if waveform == "saw":
        v = 2.0 * frac - 1.0
        if blep:
            v -= _poly_blep(frac, dt)
        return v
    if waveform == "triangle":
        # Triangle's harmonics roll off at 12 dB/octave (vs 6 for square/saw),
        # so its aliasing sits far enough down to leave the naive shape alone.
        return 4.0 * abs(frac - 0.5) - 1.0
    if waveform == "noise":
        return rng.uniform(-1.0, 1.0)
    raise SpecError(f"unknown waveform '{waveform}' (use one of {', '.join(WAVEFORMS)})")


def _envelope(n_samples, sr, env, tail):
    """ADSR gain per sample over (note duration + release tail)."""
    a = max(0, int(env["attack"] * sr))
    d = max(0, int(env["decay"] * sr))
    s = max(0.0, min(1.0, float(env["sustain"])))
    body = n_samples - tail
    out = [0.0] * n_samples
    for i in range(n_samples):
        if i < body:
            if a and i < a:
                g = i / a
            elif d and i < a + d:
                g = 1.0 - (1.0 - s) * ((i - a) / d)
            else:
                g = s
        else:  # release tail
            g = s * (1.0 - (i - body) / tail) if tail else 0.0
        out[i] = g
    return out


def _one_pole(samples, sr, ftype, cutoff, cutoff_to=None, sweep="exp"):
    """One-pole filter; `cutoff_to` sweeps the cutoff across the buffer."""
    if cutoff <= 0 and (cutoff_to is None or cutoff_to <= 0):
        return samples
    n = len(samples)
    dt = 1.0 / sr

    def cut_at(i):
        if cutoff_to is None or n <= 1:
            return cutoff
        frac = i / (n - 1)
        if sweep == "lin" or cutoff <= 0 or cutoff_to <= 0:
            return cutoff + (cutoff_to - cutoff) * frac
        return cutoff * (cutoff_to / cutoff) ** frac

    out = [0.0] * n
    if ftype == "lowpass":
        prev = 0.0
        for i, x in enumerate(samples):
            rc = 1.0 / (2 * math.pi * max(1e-6, cut_at(i)))
            alpha = dt / (rc + dt)
            prev = prev + alpha * (x - prev)
            out[i] = prev
    elif ftype == "highpass":
        prev_x = prev_y = 0.0
        for i, x in enumerate(samples):
            rc = 1.0 / (2 * math.pi * max(1e-6, cut_at(i)))
            alpha = rc / (rc + dt)
            prev_y = alpha * (prev_y + x - prev_x)
            prev_x = x
            out[i] = prev_y
    else:
        raise SpecError(f"filter type must be lowpass or highpass, got '{ftype}'")
    return out


def _render_note(note, waveform, env, filt, gain, sr, rng, duty=0.5, vibrato=None):
    """A single tone: oscillator -> envelope -> filter -> gain. Returns floats."""
    dur = float(note.get("duration", 0.2))
    tail = max(0, int(env["release"] * sr))
    n = max(1, int(dur * sr) + tail)
    body_t = dur
    duty = float(note.get("duty", duty))
    if vibrato:
        vib_rate = float(vibrato.get("rate", 6.0))
        vib_depth = float(vibrato.get("depth", 0.5))  # semitones
    buf = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = i / sr
        f = _glide_freq(note, t, body_t)
        if vibrato:
            f *= 2.0 ** (vib_depth * math.sin(2 * math.pi * vib_rate * t) / 12.0)
        buf[i] = _osc(waveform, phase, rng, duty, f / sr)
        phase += 2 * math.pi * f / sr
    eg = _envelope(n, sr, env, tail)
    for i in range(n):
        buf[i] *= eg[i]
    if filt:
        ftype = filt.get("type", "lowpass")
        if "from" in filt and "to" in filt:
            buf = _one_pole(buf, sr, ftype, float(filt["from"]), float(filt["to"]),
                            filt.get("sweep", "exp"))
        else:
            buf = _one_pole(buf, sr, ftype, float(filt.get("cutoff", 0)))
    g = gain * float(note.get("gain", 1.0))
    return [x * g for x in buf], n


def _note_rng(seed, layer, rep_i, note_i):
    """A stable RNG for one rendered note, keyed by the layer's own definition.

    Every noise-bearing note draws from its own stream. A single shared stream
    consumed in render order would mean adding or reordering a noise layer
    silently re-rolled every later one — the spec would still be reproducible,
    but editing it would not be *safe*, which is the property that matters while
    iterating. Keying on the layer's content rather than its index extends that
    to insertion: only editing a layer changes that layer's noise. `rep_i` and
    `note_i` keep a repeated layer from firing the same burst twice, and crc32
    keeps the key stable across runs, unlike `hash()` on a string.
    """
    import random

    ident = json.dumps(layer, sort_keys=True, separators=(",", ":"))
    key = f"{seed}:{ident}:{rep_i}:{note_i}".encode()
    return random.Random(zlib.crc32(key))


def synthesize(spec):
    """Mix all layers into a single float buffer. Returns (samples, sample_rate)."""
    sr = spec["sample_rate"]
    rendered = []  # (offset_samples, float_buffer)
    end = 0
    for layer_i, layer in enumerate(spec["layers"]):
        waveform = layer.get("waveform", "sine")
        env = _env(layer)
        filt = layer.get("filter")
        gain = float(layer.get("gain", 1.0))
        duty = float(layer.get("duty", 0.5))
        vibrato = layer.get("vibrato")
        notes = layer.get("notes")
        if notes is None:
            notes = [{k: layer[k] for k in ("freq", "from", "to", "glide", "duration")
                      if k in layer}]
            if "duration" not in notes[0]:
                notes[0]["duration"] = 0.2
        rep = layer.get("repeat") or {"count": 1, "interval": 0.0}
        count = max(1, int(rep.get("count", 1)))
        interval = float(rep.get("interval", 0.0))
        layer_start = float(layer.get("start", 0.0))
        for r in range(count):
            base = layer_start + r * interval
            for note_i, note in enumerate(notes):
                rng = _note_rng(spec["seed"], layer, r, note_i)
                buf, n = _render_note(note, waveform, env, filt, gain, sr, rng,
                                      duty, note.get("vibrato", vibrato))
                off = int((base + float(note.get("start", 0.0))) * sr)
                rendered.append((off, buf))
                end = max(end, off + n)
    mix = [0.0] * max(1, end)
    for off, buf in rendered:
        for i, x in enumerate(buf):
            mix[off + i] += x
    return mix, sr


def normalize(samples, peak_dbfs):
    peak = max((abs(x) for x in samples), default=0.0)
    if peak <= 0:
        return samples, 0.0
    target = 10 ** (peak_dbfs / 20.0)
    scale = target / peak
    return [x * scale for x in samples], scale


def _biquad(samples, b, a):
    """Direct-form-I biquad. `b`/`a` are 3-tuples; a[0] normalizes the rest."""
    b0, b1, b2 = (c / a[0] for c in b)
    a1, a2 = a[1] / a[0], a[2] / a[0]
    x1 = x2 = y1 = y2 = 0.0
    out = [0.0] * len(samples)
    for i, x in enumerate(samples):
        y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        out[i] = y
        x2, x1 = x1, x
        y2, y1 = y1, y
    return out


def k_weighting_coeffs(sr):
    """ITU-R BS.1770 K-weighting biquads for `sr`: (shelf_b, shelf_a, hp_b, hp_a).

    Derived from the standard's filter parameters via the bilinear transform, so
    any sample rate works — the coefficient tables published in BS.1770 itself
    are 48 kHz only, and reproducing them at 48 kHz is how this is tested.
    """
    # Stage 1 — the "head effect" high shelf, ~+4 dB above 1.7 kHz.
    fc, q, gain_db = 1681.974450955533, 0.7071752369554196, 3.999843853973347
    k = math.tan(math.pi * fc / sr)
    vh = 10 ** (gain_db / 20.0)
    vb = vh ** 0.4996667741545416
    denom = 1.0 + k / q + k * k
    shelf_b = ((vh + vb * k / q + k * k) / denom,
               2.0 * (k * k - vh) / denom,
               (vh - vb * k / q + k * k) / denom)
    shelf_a = (1.0,
               2.0 * (k * k - 1.0) / denom,
               (1.0 - k / q + k * k) / denom)
    # Stage 2 — the RLB high-pass at ~38 Hz.
    fc, q = 38.13547087602444, 0.5003270373238773
    k = math.tan(math.pi * fc / sr)
    denom = 1.0 + k / q + k * k
    hp_b = (1.0, -2.0, 1.0)
    hp_a = (1.0, 2.0 * (k * k - 1.0) / denom, (1.0 - k / q + k * k) / denom)
    return shelf_b, shelf_a, hp_b, hp_a


def _k_weight(samples, sr):
    shelf_b, shelf_a, hp_b, hp_a = k_weighting_coeffs(sr)
    return _biquad(_biquad(samples, shelf_b, shelf_a), hp_b, hp_a)


def measure_loudness(samples, sr):
    """Ungated K-weighted loudness, in LUFS.

    BS.1770's block gating exists to stop silence dragging down a long
    programme's average; an SFX cue is shorter than one 400 ms gating block and
    is trimmed to its transient anyway, so the ungated mean square over the whole
    cue is the honest measure here.
    """
    if not samples:
        return -float("inf")
    y = _k_weight(samples, sr)
    ms = sum(v * v for v in y) / len(y)
    return -0.691 + 10 * math.log10(ms) if ms > 0 else -float("inf")


def normalize_loudness(samples, sr, target_lufs, peak_ceiling_dbfs):
    """Scale to `target_lufs`, then pull back if that would break the peak ceiling.

    Peak normalization alone does not deliver a consistent perceived level: a
    square-wave klaxon and a sine blip both peak-normalized to -1 dBFS differ by
    ~10 dB to the ear, so one buries vanilla and the other hides under it.
    Matching loudness and keeping the peak only as a ceiling is what makes cues
    sit together. Returns (samples, measured_lufs, scale, peak_limited_by_db).
    """
    measured = measure_loudness(samples, sr)
    if measured == -float("inf"):
        return samples, measured, 1.0, 0.0
    scale = 10 ** ((target_lufs - measured) / 20.0)
    peak = max((abs(x) for x in samples), default=0.0) * scale
    ceiling = 10 ** (peak_ceiling_dbfs / 20.0)
    limited = 0.0
    if peak > ceiling:
        limited = 20 * math.log10(peak / ceiling)
        scale *= ceiling / peak
    return [x * scale for x in samples], measured, scale, limited


# --------------------------------------------------------------------------- #
# Output: WAV + ffmpeg OGG
# --------------------------------------------------------------------------- #

def write_wav(path, samples, sr):
    pcm = array.array("h")
    for x in samples:
        v = int(max(-1.0, min(1.0, x)) * 32767)
        pcm.append(v)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        if sys.byteorder == "big":
            pcm.byteswap()
        w.writeframes(pcm.tobytes())


def encode_ogg(wav_path, ogg_path, quality=5):
    """Encode WAV -> Ogg Vorbis via ffmpeg.

    Returns (ok, reason) — reason distinguishes a missing ffmpeg from one that
    ran and failed, because the fixes are different.
    """
    if not shutil.which("ffmpeg"):
        return False, "ffmpeg is not installed"
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path),
           "-c:a", "libvorbis", "-q:a", str(quality), "-ac", "1", str(ogg_path)]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode == 0:
        return True, ""
    detail = (proc.stderr or "").strip().splitlines()
    return False, f"ffmpeg failed: {detail[-1] if detail else 'no output'}"


def decode_audio(path, sr):
    """Decode an encoded cue back to mono float samples at `sr`, via ffmpeg.

    This is how the shipped `.ogg` gets measured rather than assumed: every
    stat before this point describes the pre-encode float buffer, and Vorbis is
    lossy. Returns None when ffmpeg can't produce samples.
    """
    if not shutil.which("ffmpeg"):
        return None
    cmd = ["ffmpeg", "-v", "error", "-i", str(path),
           "-f", "s16le", "-ac", "1", "-ar", str(sr), "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0 or not proc.stdout:
        return None
    pcm = array.array("h")
    pcm.frombytes(proc.stdout[: len(proc.stdout) // 2 * 2])
    if sys.byteorder == "big":
        pcm.byteswap()
    return [v / 32768.0 for v in pcm]


# Tolerances for --verify. Vorbis is lossy, so a shipped cue never matches the
# synth sample-for-sample; these bound how far the encode may move each measure
# before the file stops being the spec's cue. Generous enough that a re-encode
# passes, tight enough that a different cue fails.
VERIFY_TOLERANCE = {
    "duration_s": 0.02,
    "peak_dbfs": 1.5,       # Vorbis overshoots; a clean re-encode moves ~0.5 dB
    "loudness_lufs": 1.0,
    # Fractional. Vorbis discards high-frequency detail, which pulls the
    # centroid down several percent on every encode — and further at a low
    # -q:a — so this is the loosest of the four by necessity. Duration, peak,
    # and loudness are what actually discriminate one cue from another.
    "centroid_hz": 0.18,
}


def spec_ships(path):
    """The `ships` target a .sfx declares, or None."""
    try:
        return json.loads(Path(path).read_text()).get("ships")
    except (json.JSONDecodeError, OSError):
        return None


def verify_tree(root, verbose=False):
    """Verify every cue under `root`, at any depth, that declares where it ships.

    The walk recurses: a repo with enough audio to sort it into subdirectories
    is exactly the repo that most needs the check, and a walk that stopped at
    the top level would report a confident green over cues it never opened.

    This lives in the synth rather than in a separate tool because the synth is
    what gets vendored into each member repo — a checker that only exists in
    concord could not be run by the repos whose audio it holds.

    Returns (checked, drifted, broken, blocked, unlinked). The three failures
    are counted apart because they accuse different things: drift means a
    shipped cue was re-encoded outside its spec, malformed means the spec itself
    doesn't parse, and blocked means ffmpeg is missing, so the cue was never
    decoded and nothing about it was actually checked. A caller that wraps this
    can only word its error correctly if it can tell them apart — and one that
    quietly passed the blocked ones would report a green it never earned.
    """
    root = Path(root)
    if not root.exists():
        print(f"  {root}: no such directory — nothing to verify")
        return 0, 0, 0, 0, 0
    checked = drifted = broken = blocked = 0
    unlinked = []
    # Verification decodes the shipped .ogg, so without ffmpeg there is no
    # comparison to make — every linked cue is blocked, not drifted.
    have_ffmpeg = shutil.which("ffmpeg") is not None
    for spec_path in sorted(root.rglob("*.sfx")):
        shipped = spec_ships(spec_path)
        if not shipped:
            unlinked.append(spec_path)
            continue
        if not have_ffmpeg:
            blocked += 1
            print(f"  BLOCKED  {spec_path}")
            print(f"           ffmpeg is not installed, so the shipped cue "
                  f"cannot be decoded and compared")
            continue
        try:
            spec = parse_spec(spec_path.read_text())
            samples, sr = synthesize(spec)
            target = spec["loudness_lufs"]
            if target is None:
                samples, _ = normalize(samples, spec["peak_dbfs"])
            else:
                samples, _, _, _ = normalize_loudness(
                    samples, sr, target, spec["peak_dbfs"])
            problems = verify_render(shipped, samples, sr,
                                     compute_stats(samples, sr))
        except SpecError as e:
            broken += 1
            print(f"  BROKEN   {spec_path}")
            print(f"           {e}")
            continue
        checked += 1
        if problems:
            drifted += 1
            print(f"  DRIFT    {spec_path}")
            for p in problems:
                print(f"           {p}")
        elif verbose:
            print(f"  ok       {spec_path} -> {shipped}")
    for spec_path in unlinked:
        print(f"  unlinked {spec_path} — no 'ships' target, so nothing verifies it")
    return checked, drifted, broken, blocked, len(unlinked)


def verify_render(shipped, samples, sr, stats):
    """Compare a shipped cue against what the spec synthesizes right now.

    Returns a list of human-readable mismatches — empty means the shipped `.ogg`
    still is what the `.sfx` describes.
    """
    if not Path(shipped).exists():
        return [f"{shipped}: missing — the spec renders it, nothing ships it"]
    got = decode_audio(shipped, sr)
    if got is None:
        return [f"{shipped}: could not be decoded for comparison "
                f"(ffmpeg missing or the file is not audio)"]
    got_stats = compute_stats(got, sr)
    problems = []
    for key, tol in VERIFY_TOLERANCE.items():
        a, b = got_stats[key], stats[key]
        if key == "duration_s":
            # Compare content duration, not stream duration: libvorbis finalizes
            # some cue lengths with ~1000 samples of sub--80 dBFS padding past
            # the final granule, and every ffmpeg's Vorbis decode emits it — so
            # a byte-faithful, freshly rendered cue would drift on raw length.
            # Trailing silence below -60 dBFS is excluded on BOTH sides; real
            # truncation or a different cue still moves the audible length.
            a -= got_stats["tail_silence_s"]
            b -= stats["tail_silence_s"]
        limit = tol * max(abs(b), 1e-9) if key == "centroid_hz" else tol
        if abs(a - b) > limit:
            unit = {"duration_s": "s", "peak_dbfs": "dBFS",
                    "loudness_lufs": "LUFS", "centroid_hz": "Hz"}[key]
            problems.append(
                f"{shipped}: {key.rsplit('_', 1)[0]} is {a:.3f} {unit}, the spec "
                f"renders {b:.3f} {unit} (tolerance {limit:.3f})")
    return problems


# --------------------------------------------------------------------------- #
# Analysis: FFT, stats, spectrogram
# --------------------------------------------------------------------------- #

def _fft(a):
    """Recursive radix-2 Cooley-Tukey FFT (len(a) must be a power of two)."""
    n = len(a)
    if n == 1:
        return list(a)
    even = _fft(a[0::2])
    odd = _fft(a[1::2])
    out = [0j] * n
    for k in range(n // 2):
        t = cmath.exp(-2j * math.pi * k / n) * odd[k]
        out[k] = even[k] + t
        out[k + n // 2] = even[k] - t
    return out


def _stft(samples, win=1024, hop=512):
    """Magnitude spectra per frame. Returns (frames, bins) where bins = win/2.

    Each frame is mean-removed before windowing. A waveform with a DC offset —
    a thin-duty square is the usual source — otherwise dumps its offset into
    bin 0, and the window leaks that into bin 1, painting a bright band along
    the bottom of the spectrogram and dragging the spectral centroid toward
    zero. Neither is audible content, so neither belongs in the analysis.
    """
    window = [0.5 - 0.5 * math.cos(2 * math.pi * i / (win - 1)) for i in range(win)]
    frames = []
    n = len(samples)
    pos = 0
    while pos < n:
        chunk = samples[pos:pos + win]
        # Measure the offset on the real samples, then pad with it rather than
        # with zero: padding a cue that sits above zero with silence would fake
        # a step discontinuity in the last frame and smear broadband energy
        # across it.
        dc = sum(chunk) / len(chunk) if chunk else 0.0
        if len(chunk) < win:
            chunk = chunk + [dc] * (win - len(chunk))
        windowed = [(chunk[i] - dc) * window[i] for i in range(win)]
        spec = _fft(windowed)
        frames.append([abs(spec[k]) for k in range(win // 2)])
        pos += hop
    return frames


def compute_stats(samples, sr):
    n = len(samples)
    peak = max((abs(x) for x in samples), default=0.0)
    rms = math.sqrt(sum(x * x for x in samples) / n) if n else 0.0
    frames = _stft(samples) if n else []
    num = den = 0.0
    bins = len(frames[0]) if frames else 0
    for fr in frames:
        for k in range(bins):
            mag = fr[k]
            freq = k * sr / 1024.0
            num += freq * mag
            den += mag
    centroid = (num / den) if den else 0.0

    def dbfs(v):
        return -float("inf") if v <= 0 else 20 * math.log10(v)

    # Leading/trailing silence (below -60 dBFS) — the quality bar wants the cue
    # trimmed tight, so measure what a trim would remove.
    thresh = 10 ** (-60 / 20.0)
    lead = next((i for i, x in enumerate(samples) if abs(x) >= thresh), n)
    tail = next((i for i, x in enumerate(reversed(samples)) if abs(x) >= thresh), n)

    # DC offset — the mix's mean sample. Inaudible alone, but it displaces the
    # whole waveform: several offset cues playing at once sum their offsets and
    # eat master headroom, and a cue cut before its release can click. Real chip
    # hardware AC-couples its output, so removing it is also the more faithful
    # rendering of a pulse wave, not a departure from one.
    dc = sum(samples) / n if n else 0.0

    return {
        "duration_s": n / sr if sr else 0.0,
        "peak_dbfs": dbfs(peak),
        "rms_dbfs": dbfs(rms),
        "loudness_lufs": measure_loudness(samples, sr),
        "dc_offset": dc,
        "dc_pct": 100.0 * abs(dc) / peak if peak > 0 else 0.0,
        "centroid_hz": centroid,
        "lead_silence_s": lead / sr if sr else 0.0,
        "tail_silence_s": tail / sr if sr else 0.0,
        "frames": frames,
    }


# --------------------------------------------------------------------------- #
# PNG report (stdlib encoder, flat RGBA bytearray)
# --------------------------------------------------------------------------- #

def _png_chunk(tag, data):
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def _write_png_rgba(path, buf, width, height):
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += buf[y * stride:(y + 1) * stride]
    body = (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + _png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _png_chunk(b"IEND", b"")
    )
    Path(path).write_bytes(body)


# A 3×5 pixel font, just wide enough to label the report's axes. Each glyph is
# five rows of three bits, high bit leftmost. Without labels the report is a
# picture with no scale on it — you can see that something rises, but not from
# what to what, which is most of what there is to judge.
_FONT = {
    "0": (0b111, 0b101, 0b101, 0b101, 0b111),
    "1": (0b010, 0b110, 0b010, 0b010, 0b111),
    "2": (0b111, 0b001, 0b111, 0b100, 0b111),
    "3": (0b111, 0b001, 0b111, 0b001, 0b111),
    "4": (0b101, 0b101, 0b111, 0b001, 0b001),
    "5": (0b111, 0b100, 0b111, 0b001, 0b111),
    "6": (0b111, 0b100, 0b111, 0b101, 0b111),
    "7": (0b111, 0b001, 0b010, 0b010, 0b010),
    "8": (0b111, 0b101, 0b111, 0b101, 0b111),
    "9": (0b111, 0b101, 0b111, 0b001, 0b111),
    ".": (0b000, 0b000, 0b000, 0b000, 0b010),
    "-": (0b000, 0b000, 0b111, 0b000, 0b000),
    "+": (0b000, 0b010, 0b111, 0b010, 0b000),
    ":": (0b000, 0b010, 0b000, 0b010, 0b000),
    " ": (0b000, 0b000, 0b000, 0b000, 0b000),
    "k": (0b100, 0b101, 0b110, 0b101, 0b101),
    "H": (0b101, 0b101, 0b111, 0b101, 0b101),
    "z": (0b000, 0b111, 0b001, 0b010, 0b111),
    "s": (0b000, 0b011, 0b110, 0b011, 0b110),
    "m": (0b000, 0b111, 0b111, 0b101, 0b101),
    "d": (0b001, 0b001, 0b111, 0b101, 0b111),
    "B": (0b110, 0b101, 0b110, 0b101, 0b110),
    "F": (0b111, 0b100, 0b110, 0b100, 0b100),
    "S": (0b011, 0b100, 0b010, 0b001, 0b110),
    "L": (0b100, 0b100, 0b100, 0b100, 0b111),
    "U": (0b101, 0b101, 0b101, 0b101, 0b111),
}
_FONT_W, _FONT_H = 3, 5


def _text_width(text, scale=1, spacing=1):
    return len(text) * (_FONT_W + spacing) * scale - spacing * scale


def _heat(v):
    """Map 0..1 to a black->purple->red->yellow->white heat ramp (R,G,B)."""
    v = max(0.0, min(1.0, v))
    stops = [(0, 0, 0), (60, 0, 90), (170, 20, 60), (240, 120, 20), (255, 240, 140)]
    seg = v * (len(stops) - 1)
    i = min(len(stops) - 2, int(seg))
    f = seg - i
    a, b = stops[i], stops[i + 1]
    return (int(a[0] + (b[0] - a[0]) * f),
            int(a[1] + (b[1] - a[1]) * f),
            int(a[2] + (b[2] - a[2]) * f))


def _layer_onsets(spec):
    """Every moment a layer (or one of its repeats) starts, in seconds.

    Marking these on the waveform is what turns "the shape looks about right"
    into a check: the spec says a layer lands at 0.3 s, and the picture shows
    whether anything actually happens there.
    """
    onsets = []
    for layer in spec.get("layers", []):
        start = float(layer.get("start", 0.0))
        rep = layer.get("repeat") or {}
        count = max(1, int(rep.get("count", 1)))
        interval = float(rep.get("interval", 0.0))
        for r in range(count):
            onsets.append(start + r * interval)
    return sorted(set(onsets))


# Frequency gridlines, chosen to read on a log axis across the audible band.
_FREQ_TICKS = (100, 200, 500, 1000, 2000, 5000, 10000, 20000)
_SPEC_FLOOR_DB = -60.0  # spectrogram black point


def write_report(path, samples, sr, stats, spec=None):
    W, H = 900, 576
    left, right_pad = 44, 8          # gutter for the axis labels
    head_h, wave_h, gap, axis_h = 16, 190, 34, 16
    plot_w = W - left - right_pad
    spec_top = head_h + wave_h + gap
    spec_h = H - spec_top - axis_h
    bg = (18, 18, 22, 255)
    grid = (58, 58, 68, 255)
    label = (150, 150, 165, 255)
    buf = bytearray(bg * (W * H))

    def px(x, y, rgba):
        if 0 <= x < W and 0 <= y < H:
            o = (y * W + x) * 4
            buf[o:o + 4] = bytes(rgba)

    def vline(x, y0, y1, rgba):
        for y in range(max(0, y0), min(H, y1)):
            px(x, y, rgba)

    def hline(y, x0, x1, rgba):
        for x in range(max(0, x0), min(W, x1)):
            px(x, y, rgba)

    def text(s, x, y, rgba, scale=1):
        """Draw `s` with its top-left at (x, y). Unknown chars render blank."""
        cx = x
        for ch in s:
            rows = _FONT.get(ch, _FONT[" "])
            for ry, bits in enumerate(rows):
                for cxi in range(_FONT_W):
                    if bits & (1 << (_FONT_W - 1 - cxi)):
                        for sy in range(scale):
                            for sx in range(scale):
                                px(cx + cxi * scale + sx, y + ry * scale + sy, rgba)
            cx += (_FONT_W + 1) * scale
        return cx

    duration = stats["duration_s"] or (len(samples) / sr if sr else 0.0)

    # --- waveform (top): amplitude against time, in dBFS-labelled thirds ---
    top = head_h + 4
    mid = top + wave_h // 2
    half = wave_h // 2 - 2
    for frac, tag in ((1.0, "0dBFS"), (0.5, "-6"), (0.0, "")):
        y = mid - int(frac * half)
        hline(y, left, W - right_pad, grid if frac else (78, 78, 90, 255))
        if frac:
            hline(mid + int(frac * half), left, W - right_pad, grid)
        if tag:
            text(tag, 2, y - _FONT_H, label, 2)
    n = len(samples)
    for i in range(plot_w):
        x = left + i
        lo = int(i * n / plot_w)
        hi = max(lo + 1, int((i + 1) * n / plot_w))
        seg = samples[lo:hi]
        smin = min(seg) if seg else 0.0
        smax = max(seg) if seg else 0.0
        y0 = mid - int(smax * half)
        y1 = mid - int(smin * half)
        for y in range(min(y0, y1), max(y0, y1) + 1):
            px(x, y, (90, 200, 160, 255))

    # Layer onsets — dashed verticals over the waveform, so the timing the spec
    # declares can be read against the sound that came out.
    if spec and duration > 0:
        for onset in _layer_onsets(spec):
            if 0 < onset < duration:
                x = left + int(onset / duration * plot_w)
                for y in range(top, top + wave_h, 4):
                    px(x, y, (120, 110, 200, 255))
                    px(x, y + 1, (120, 110, 200, 255))

    # --- time axis, shared by both panels ---
    if duration > 0:
        step = next(s for s in (0.05, 0.1, 0.25, 0.5, 1.0, 2.0)
                    if duration / s <= 10) if duration > 0.05 else 0.01
        t = 0.0
        while t <= duration + 1e-9:
            x = left + int(t / duration * plot_w)
            vline(x, spec_top, spec_top + spec_h, (255, 255, 255, 40))
            tag = f"{t:.2f}".rstrip("0").rstrip(".") or "0"
            text(f"{tag}s", x - _text_width(f"{tag}s", 2) // 2,
                 spec_top + spec_h + 4, label, 2)
            t += step

    # --- spectrogram (bottom): log frequency, dB magnitude ---
    frames = stats["frames"]
    if frames:
        bins = len(frames[0])
        bin_hz = sr / 2.0 / bins
        # Normalize against audible content only. Bin 0 is DC, and a waveform
        # with an offset (a thin-duty square, say) puts more energy there than
        # anywhere else — letting it set the scale flattens the whole picture.
        peak = max((max(fr[1:]) for fr in frames if len(fr) > 1), default=1.0) or 1.0
        # Start the axis at the first real bin. Below it every row would resolve
        # to bin 0 — DC plus window leakage — and paint a solid bright band that
        # reads as sub-bass the cue does not contain.
        f_min, f_max = max(40.0, bin_hz), sr / 2.0
        log_min, log_max = math.log10(f_min), math.log10(f_max)

        def y_of(freq):
            """Row for a frequency — low at the bottom, log-spaced."""
            frac = (math.log10(max(f_min, freq)) - log_min) / (log_max - log_min)
            return spec_top + int((1.0 - frac) * (spec_h - 1))

        for x in range(plot_w):
            fi = min(len(frames) - 1, int(x * len(frames) / plot_w))
            fr = frames[fi]
            for y in range(spec_h):
                # Invert y_of: which frequency this row shows, then which bin.
                frac = 1.0 - y / max(1, spec_h - 1)
                freq = 10 ** (log_min + frac * (log_max - log_min))
                b = min(bins - 1, max(1, int(freq / bin_hz)))
                mag = fr[b] / peak
                db = 20 * math.log10(mag) if mag > 0 else _SPEC_FLOOR_DB
                val = max(0.0, min(1.0, (db - _SPEC_FLOOR_DB) / -_SPEC_FLOOR_DB))
                r, g, bl = _heat(val)
                px(left + x, spec_top + y, (r, g, bl, 255))

        for freq in _FREQ_TICKS:
            if freq >= f_max:
                continue
            y = y_of(freq)
            hline(y, left, W - right_pad, (255, 255, 255, 45))
            tag = f"{freq // 1000}k" if freq >= 1000 else str(freq)
            ty = min(max(y - _FONT_H, spec_top), spec_top + spec_h - _FONT_H * 2)
            text(tag, 2, ty, label, 2)

    # --- header: the numbers, so the picture carries its own scale ---
    head = (f"{duration:.3f}s  peak {stats['peak_dbfs']:.1f}dBFS  "
            f"{stats['loudness_lufs']:.1f}LUFS  centroid "
            f"{stats['centroid_hz'] / 1000:.2f}kHz")
    text(head.replace("centroid ", ""), left, 2, (125, 125, 140, 255), 2)
    _write_png_rgba(path, buf, W, H)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def main(argv=None):
    ap = argparse.ArgumentParser(description="Synthesize a .sfx spec into an Ogg Vorbis cue.")
    ap.add_argument("spec", nargs="?", help="path to a .sfx spec, or - for stdin")
    ap.add_argument("-o", "--output", help="output .ogg path (default: spec name)")
    ap.add_argument("--no-report", action="store_true", help="skip the waveform/spectrogram PNG")
    ap.add_argument("--ogg-quality", type=int, default=5, help="libvorbis -q:a (0..10, default 5)")
    ap.add_argument("--verify", action="store_true",
                    help="don't write: re-synthesize the spec and compare it "
                         "against the shipped .ogg already at the output path, "
                         "decoding that file so what is measured is what ships. "
                         "Exits non-zero on drift — the CI form of the "
                         "repeatability rule")
    ap.add_argument("--verify-all", nargs="?", const="art/audio", metavar="DIR",
                    help="verify every cue under DIR (default art/audio), at any "
                         "depth, against the .ogg it declares with 'ships', and "
                         "report the ones that declare none. Exits non-zero on "
                         "drift or on a spec that no longer parses — run it in "
                         "CI to hold the whole repo's audio to its specs")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="with --verify-all, also list the cues that verified clean")
    ap.add_argument("--list-waveforms", action="store_true", help="print the oscillators and exit")
    args = ap.parse_args(argv)

    if args.list_waveforms:
        for w in WAVEFORMS:
            print(w)
        return 0

    # `is not None`, not truthiness: the question is whether the flag was
    # given, and an empty DIR would otherwise fall through to the render
    # path and complain about a missing spec.
    if args.verify_all is not None:
        checked, drifted, broken, blocked, unlinked = verify_tree(
            args.verify_all, args.verbose)
        print(f"  {checked} verified, {drifted} drifted, {broken} malformed, "
              f"{blocked} blocked, {unlinked} unlinked")
        return 1 if (drifted or broken or blocked) else 0
    if not args.spec:
        ap.error("a spec path (or -) is required")

    if args.spec == "-":
        text = sys.stdin.read()
        stem = "sound"
    else:
        text = Path(args.spec).read_text(encoding="utf-8")
        stem = Path(args.spec).stem

    try:
        spec = parse_spec(text)
    except SpecError as e:
        print(f"sfx: {e}", file=sys.stderr)
        return 2

    name = spec.get("name") or stem
    if args.output:
        ogg_path = Path(args.output)
    elif args.verify:
        # `ships` records where the master lands in the mod's resource tree, so
        # the spec knows its own deliverable and --verify can find it unaided.
        # Without one there is no shipped cue to check — falling back to the
        # gitignored render beside the spec would pass on a stale local file.
        if not spec.get("ships"):
            print(f"sfx: {args.spec} declares no 'ships' target and no -o was "
                  f"given — nothing to verify against", file=sys.stderr)
            return 1
        ogg_path = Path(spec["ships"])
    elif args.spec != "-":
        ogg_path = Path(args.spec).with_suffix(".ogg")
    else:
        ogg_path = Path(f"{name}.ogg")
    base = ogg_path.with_suffix("")
    wav_path = base.with_suffix(".wav")
    report_path = base.with_name(base.name + ".report.png")

    samples, sr = synthesize(spec)
    target = spec["loudness_lufs"]
    if target is None:
        # Opted out: peak normalization only. Two cues normalized this way can
        # still differ by ~10 dB to the ear — see normalize_loudness.
        samples, _ = normalize(samples, spec["peak_dbfs"])
        measured_before, peak_limited = None, 0.0
    else:
        samples, measured_before, _scale, peak_limited = normalize_loudness(
            samples, sr, target, spec["peak_dbfs"])

    stats = compute_stats(samples, sr)

    if args.verify:
        problems = verify_render(ogg_path, samples, sr, stats)
        for p in problems:
            print(f"sfx: drift: {p}", file=sys.stderr)
        if problems:
            print(f"sfx: the shipped cue no longer matches {args.spec} — "
                  f"re-render the spec to the shipped path", file=sys.stderr)
            return 1
        print(f"  verified {ogg_path} matches {args.spec} "
              f"(decoded and re-measured within tolerance)")
        return 0

    ogg_path.parent.mkdir(parents=True, exist_ok=True)

    # Render to a temp WAV for encoding; the .ogg is the master. Keep the WAV
    # beside the output only as a fallback when ffmpeg can't produce the .ogg.
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    write_wav(tmp.name, samples, sr)
    ogg_ok, ogg_why = encode_ogg(tmp.name, ogg_path, args.ogg_quality)
    if ogg_ok:
        os.unlink(tmp.name)
    else:
        shutil.move(tmp.name, wav_path)

    if not args.no_report:
        write_report(report_path, samples, sr, stats, spec)

    print(f"name:       {name}")
    if ogg_ok:
        print(f"ogg:        {ogg_path}")
    else:
        print(f"ogg:        NOT WRITTEN — {ogg_why}; the shipped master must be "
              f".ogg (WAV fallback kept at {wav_path})", file=sys.stderr)
    if not args.no_report:
        print(f"report:     {report_path}  (read this back)")
    print(f"duration:   {stats['duration_s']:.3f} s")
    print(f"peak:       {stats['peak_dbfs']:.2f} dBFS")
    print(f"loudness:   {stats['loudness_lufs']:.2f} LUFS "
          f"(K-weighted, ungated{'' if target is None else f'; target {target:.1f}'})")
    print(f"rms:        {stats['rms_dbfs']:.2f} dBFS")
    print(f"centroid:   {stats['centroid_hz']:.0f} Hz")
    print(f"dc:         {stats['dc_offset']:+.4f}  ({stats['dc_pct']:.0f}% of peak)")
    print(f"silence:    lead {stats['lead_silence_s'] * 1000:.0f} ms, "
          f"tail {stats['tail_silence_s'] * 1000:.0f} ms  (below -60 dBFS)")
    if target is not None and peak_limited > LOUDNESS_TOLERANCE_DB:
        print(f"sfx: warning: {peak_limited:.1f} dB below the {target:.1f} LUFS "
              f"target — the peak ceiling ({spec['peak_dbfs']:.1f} dBFS) hit "
              f"first, so this cue is spikier than it is loud. Soften the "
              f"transient (longer attack, or trim the layer that spikes) to let "
              f"it reach the target", file=sys.stderr)
    if stats["lead_silence_s"] > 0.025:
        print("sfx: warning: leading silence over 25 ms — the cue should start "
              "at the transient; pull the first onset to t=0", file=sys.stderr)
    if stats["tail_silence_s"] > 0.12:
        print("sfx: warning: trailing silence over 120 ms — tighten the last "
              "note's duration/release so the cue ends when the sound does", file=sys.stderr)
    if stats["dc_pct"] > DC_OFFSET_WARN_PCT:
        print(f"sfx: warning: the mix sits {stats['dc_pct']:.0f}% of peak off "
              f"centre (DC {stats['dc_offset']:+.3f}) — a very thin pulse duty "
              f"does this. It costs headroom in the game's mix and can click "
              f"when the cue is cut; add a highpass to that layer "
              f"(\"filter\": {{\"type\": \"highpass\", \"cutoff\": 20}}) to "
              f"centre it without touching the timbre", file=sys.stderr)
    if stats["duration_s"] > 2.5:
        print(f"sfx: warning: {stats['duration_s']:.2f} s is long for an SFX cue — "
              f"most read best under ~2 s", file=sys.stderr)
    sub = spec.get("subtitle")
    if sub:
        print(f"subtitle:   {sub}")
    else:
        print("subtitle:   MISSING — add a subtitle key (accessibility)", file=sys.stderr)
    print("ear-check:  a human must listen before this lands.")
    # The deliverable is the .ogg. Without it the run produced no shippable
    # master, so it must not report success — CI has no other way to notice.
    return 0 if ogg_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
