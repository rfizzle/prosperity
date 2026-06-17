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
Python 3 does. The single external tool is **ffmpeg**, used only to encode the
rendered WAV to Ogg Vorbis (`-c:a libvorbis`) — Minecraft needs `.ogg`. If ffmpeg
is absent the WAV and report are still written and the script says so.

SPEC FORMAT
-----------
A `.sfx` file is JSON. Top-level fields (all optional except `layers`):

    {
      "name": "pylon-alarm",      # output basename (default: spec filename stem)
      "sample_rate": 44100,       # 44100 or 48000
      "peak_dbfs": -1.0,          # normalize so the loudest peak hits this (headroom)
      "subtitle": "mercantile.subtitle.pylon_alarm",  # accessibility key (reminder only)
      "seed": 1234,               # seeds noise so renders are reproducible
      "layers": [ ... ]           # one or more synthesis layers, mixed together
    }

Each layer:

    {
      "waveform": "square",       # sine | square | triangle | saw | noise
      "freq": 440,                # constant pitch (Hz) ...
      "from": 880, "to": 220,     # ... OR a pitch glide (overrides freq)
      "glide": "exp",             # glide curve: exp (default) | lin
      "start": 0.0,               # layer onset, seconds
      "duration": 0.25,           # tone length before release, seconds
      "gain": 0.8,                # linear mix level (0..1)
      "env": {"attack": 0.005, "decay": 0.05, "sustain": 0.6, "release": 0.05},
      "filter": {"type": "lowpass", "cutoff": 3000},   # one-pole lowpass|highpass
      "repeat": {"count": 3, "interval": 0.3},          # repeat the whole layer
      "notes": [                  # OR a sequence (chiptune sting); shares the
        {"freq": 523, "start": 0.0, "duration": 0.1},   # waveform/env/filter above
        {"freq": 659, "start": 0.1, "duration": 0.1}    # note.start is relative
      ]                                                  # to the layer start
    }

A note may carry its own `freq` / `from`+`to` / `gain` overrides. `env.sustain`
is a level (0..1); `attack`/`decay`/`release` are seconds. Release extends a note
past its `duration`. Total cue length is inferred from the layers.

USAGE
-----
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx               # -> SPEC.ogg + SPEC.wav + SPEC.report.png + stats
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx -o art/audio/alarm.ogg
    python3 .ai/skills/mc-audio/scripts/sfx.py - < SPEC.sfx           # spec on stdin
    python3 .ai/skills/mc-audio/scripts/sfx.py SPEC.sfx --no-report   # skip the PNG
    python3 .ai/skills/mc-audio/scripts/sfx.py --list-waveforms       # available oscillators
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
    "seed": 0,
}
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

def _osc(waveform, phase, rng):
    if waveform == "sine":
        return math.sin(phase)
    if waveform == "square":
        return 1.0 if math.sin(phase) >= 0 else -1.0
    if waveform == "saw":
        frac = (phase / (2 * math.pi)) % 1.0
        return 2.0 * frac - 1.0
    if waveform == "triangle":
        frac = (phase / (2 * math.pi)) % 1.0
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


def _one_pole(samples, sr, ftype, cutoff):
    if cutoff <= 0:
        return samples
    dt = 1.0 / sr
    rc = 1.0 / (2 * math.pi * cutoff)
    out = [0.0] * len(samples)
    if ftype == "lowpass":
        alpha = dt / (rc + dt)
        prev = 0.0
        for i, x in enumerate(samples):
            prev = prev + alpha * (x - prev)
            out[i] = prev
    elif ftype == "highpass":
        alpha = rc / (rc + dt)
        prev_x = prev_y = 0.0
        for i, x in enumerate(samples):
            prev_y = alpha * (prev_y + x - prev_x)
            prev_x = x
            out[i] = prev_y
    else:
        raise SpecError(f"filter type must be lowpass or highpass, got '{ftype}'")
    return out


def _render_note(note, waveform, env, filt, gain, sr, rng):
    """A single tone: oscillator -> envelope -> filter -> gain. Returns floats."""
    dur = float(note.get("duration", 0.2))
    tail = max(0, int(env["release"] * sr))
    n = max(1, int(dur * sr) + tail)
    body_t = dur
    buf = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = i / sr
        f = _glide_freq(note, t, body_t)
        buf[i] = _osc(waveform, phase, rng)
        phase += 2 * math.pi * f / sr
    eg = _envelope(n, sr, env, tail)
    for i in range(n):
        buf[i] *= eg[i]
    if filt:
        buf = _one_pole(buf, sr, filt.get("type", "lowpass"), float(filt.get("cutoff", 0)))
    g = gain * float(note.get("gain", 1.0))
    return [x * g for x in buf], n


def synthesize(spec):
    """Mix all layers into a single float buffer. Returns (samples, sample_rate)."""
    import random

    sr = spec["sample_rate"]
    rng = random.Random(spec["seed"])
    rendered = []  # (offset_samples, float_buffer)
    end = 0
    for layer in spec["layers"]:
        waveform = layer.get("waveform", "sine")
        env = _env(layer)
        filt = layer.get("filter")
        gain = float(layer.get("gain", 1.0))
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
            for note in notes:
                buf, n = _render_note(note, waveform, env, filt, gain, sr, rng)
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
    """Encode WAV -> Ogg Vorbis via ffmpeg. Returns True on success."""
    if not shutil.which("ffmpeg"):
        return False
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_path),
           "-c:a", "libvorbis", "-q:a", str(quality), "-ac", "1", str(ogg_path)]
    return subprocess.run(cmd).returncode == 0


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
    """Magnitude spectra per frame. Returns (frames, bins) where bins = win/2."""
    window = [0.5 - 0.5 * math.cos(2 * math.pi * i / (win - 1)) for i in range(win)]
    frames = []
    n = len(samples)
    pos = 0
    while pos < n:
        chunk = samples[pos:pos + win]
        if len(chunk) < win:
            chunk = chunk + [0.0] * (win - len(chunk))
        windowed = [chunk[i] * window[i] for i in range(win)]
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

    return {
        "duration_s": n / sr if sr else 0.0,
        "peak_dbfs": dbfs(peak),
        "rms_dbfs": dbfs(rms),
        "centroid_hz": centroid,
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


def write_report(path, samples, sr, stats):
    W, H = 900, 520
    wave_h, gap = 200, 20
    spec_top = wave_h + gap
    spec_h = H - spec_top
    bg = (18, 18, 22, 255)
    buf = bytearray(bg * (W * H))

    def px(x, y, rgba):
        if 0 <= x < W and 0 <= y < H:
            o = (y * W + x) * 4
            buf[o:o + 4] = bytes(rgba)

    # --- waveform (top) ---
    mid = wave_h // 2
    n = len(samples)
    for x in range(W):
        lo = int(x * n / W)
        hi = max(lo + 1, int((x + 1) * n / W))
        seg = samples[lo:hi]
        smin = min(seg) if seg else 0.0
        smax = max(seg) if seg else 0.0
        y0 = mid - int(smax * (mid - 2))
        y1 = mid - int(smin * (mid - 2))
        for y in range(min(y0, y1), max(y0, y1) + 1):
            px(x, y, (90, 200, 160, 255))
    for x in range(W):  # center line
        px(x, mid, (70, 70, 80, 255))

    # --- spectrogram (bottom) ---
    frames = stats["frames"]
    if frames:
        bins = len(frames[0])
        peak = max((max(fr) for fr in frames), default=1.0) or 1.0
        for x in range(W):
            fi = int(x * len(frames) / W)
            fr = frames[fi]
            for y in range(spec_h):
                b = int((1.0 - y / spec_h) * (bins - 1))  # low freq at bottom
                mag = fr[b] / peak
                val = math.log10(1 + 9 * mag)  # log compress
                r, g, bl = _heat(val)
                px(x, spec_top + y, (r, g, bl, 255))
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
    ap.add_argument("--list-waveforms", action="store_true", help="print the oscillators and exit")
    args = ap.parse_args(argv)

    if args.list_waveforms:
        for w in WAVEFORMS:
            print(w)
        return 0
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
    elif args.spec != "-":
        ogg_path = Path(args.spec).with_suffix(".ogg")
    else:
        ogg_path = Path(f"{name}.ogg")
    base = ogg_path.with_suffix("")
    wav_path = base.with_suffix(".wav")
    report_path = base.with_name(base.name + ".report.png")
    ogg_path.parent.mkdir(parents=True, exist_ok=True)

    samples, sr = synthesize(spec)
    samples, _ = normalize(samples, spec["peak_dbfs"])

    # Render to a temp WAV for encoding; the .ogg is the master. Keep the WAV
    # beside the output only as a fallback when ffmpeg can't produce the .ogg.
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    write_wav(tmp.name, samples, sr)
    ogg_ok = encode_ogg(tmp.name, ogg_path, args.ogg_quality)
    if ogg_ok:
        os.unlink(tmp.name)
    else:
        shutil.move(tmp.name, wav_path)

    stats = compute_stats(samples, sr)
    if not args.no_report:
        write_report(report_path, samples, sr, stats)

    print(f"name:       {name}")
    if ogg_ok:
        print(f"ogg:        {ogg_path}")
    else:
        print(f"ogg:        SKIPPED — ffmpeg not found; install it, then re-run "
              f"(WAV fallback kept at {wav_path})", file=sys.stderr)
    if not args.no_report:
        print(f"report:     {report_path}  (read this back)")
    print(f"duration:   {stats['duration_s']:.3f} s")
    print(f"peak:       {stats['peak_dbfs']:.2f} dBFS")
    print(f"rms:        {stats['rms_dbfs']:.2f} dBFS")
    print(f"centroid:   {stats['centroid_hz']:.0f} Hz")
    sub = spec.get("subtitle")
    if sub:
        print(f"subtitle:   {sub}")
    else:
        print("subtitle:   MISSING — add a subtitle key (accessibility)", file=sys.stderr)
    print("ear-check:  a human must listen before this lands.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
