#!/usr/bin/env python3
"""Joins the recorded scene clips into one 1080p MP4 with burned-in Korean captions.

    python tools/compose_demo.py <clips_dir> <output.mp4>

Needs ffmpeg/ffprobe on PATH. Captions come from demo_captions.py; the font is the app's Pretendard.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from demo_captions import CAPTIONS  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
clips, output = Path(sys.argv[1]), Path(sys.argv[2])
work = clips / "work"
work.mkdir(exist_ok=True)
ffmpeg = shutil.which("ffmpeg")
ffprobe = shutil.which("ffprobe")


def ts(seconds: float) -> str:
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


def ass_file(scene: int) -> Path:
    path = work / f"scene{scene:02d}.ass"
    lines = [
        "[Script Info]", "ScriptType: v4.00+", "PlayResX: 1920", "PlayResY: 1080", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
        "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
        "MarginR, MarginV, Encoding",
        "Style: Cap,Pretendard SemiBold,44,&H00FFFFFF,&H00FFFFFF,&H00000000,&H90000000,0,0,0,0,100,100,0,0,3,14,0,2,"
        "120,120,70,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for start, end, text in CAPTIONS.get(scene, []):
        lines.append(f"Dialogue: 0,{ts(start)},{ts(end)},Cap,,0,0,0,,{text.replace(chr(10), chr(92) + 'N')}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def has_audio(path: Path) -> bool:
    out = subprocess.run([ffprobe, "-v", "error", "-show_streams", "-of", "json", str(path)],
                         capture_output=True, text=True).stdout
    return any(s.get("codec_type") == "audio" for s in json.loads(out or "{}").get("streams", []))


def ff_path(p: Path) -> str:
    # The subtitles filter needs ':' and '\' escaped in Windows paths.
    return str(p).replace("\\", "/").replace(":", "\\:")


def soften_onsets(path: Path, ramp_ms: float = 8.0, quiet: int = 60, min_gap_ms: float = 50.0) -> int:
    """Fades in/out where sound starts or stops after silence.

    The emulator records nothing while silent, so a spoken sentence starts on a hard edge and pops.
    Returns how many edges were softened.
    """
    import array
    import wave
    with wave.open(str(path)) as w:
        params = w.getparams()
        data = array.array("h", w.readframes(w.getnframes()))
    ch, rate = params.nchannels, params.framerate
    frames = len(data) // ch
    ramp = max(1, int(rate * ramp_ms / 1000))
    gap = int(rate * min_gap_ms / 1000)
    loud = [max(abs(data[i * ch + c]) for c in range(ch)) >= quiet for i in range(frames)]
    edges = 0
    i = 0
    while i < frames:
        if loud[i]:
            i += 1
            continue
        j = i
        while j < frames and not loud[j]:
            j += 1
        if j - i >= gap:
            if j < frames:  # sound starts at j: ramp it in
                for k in range(min(ramp, frames - j)):
                    g = k / ramp
                    for c in range(ch):
                        data[(j + k) * ch + c] = int(data[(j + k) * ch + c] * g)
                edges += 1
            if i > 0:  # sound stopped at i: ramp it out
                for k in range(min(ramp, i)):
                    g = k / ramp
                    for c in range(ch):
                        data[(i - 1 - k) * ch + c] = int(data[(i - 1 - k) * ch + c] * g)
                edges += 1
        i = j
    with wave.open(str(path), "wb") as w:
        w.setparams(params)
        w.writeframes(data.tobytes())
    return edges


def duration(path: Path) -> float:
    out = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True).stdout.strip()
    return float(out)


# The emulator's capture is low-bitrate Vorbis of an on-device TTS voice: remove clicks, lower the noise
# floor, tame the harsh top end, then raise it by the SAME gain in every scene (it records quietly, peaks
# near -18 dB). Per-clip loudness matching would pump up the hiss in silent scenes. adeclick at t=1 (more
# sensitive than the default 2) is what removes the ticks inside the spoken warnings.
AUDIO_FILTER = "adeclick=t=1,afftdn=nf=-30,highpass=f=90,lowpass=f=9500,volume=14dB,alimiter=limit=0.89:level=disabled"

videos, audios = [], []
for clip in sorted(clips.glob("scene*.webm")):
    scene = int(clip.stem.replace("scene", ""))
    video = work / f"part{scene:02d}.mp4"
    audio = work / f"part{scene:02d}.wav"
    vf = (f"scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=30,"
          f"subtitles='{ff_path(ass_file(scene))}':fontsdir='{ff_path(FONT_DIR)}'")
    print("render", clip.name, flush=True)
    subprocess.run([ffmpeg, "-y", "-v", "error", "-i", str(clip), "-an", "-vf", vf, "-c:v", "libx264",
                    "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", str(video)], check=True)
    # Audio as PCM, exactly as long as its video: each clip's sound track runs 0.1-0.3 s short, and those
    # holes at the scene cuts are where the joined video clicked. Pad with silence, cut to length.
    length = f"{duration(video):.3f}"
    if has_audio(clip):
        src = ["-i", str(clip)]
        # Timestamps first: the emulator writes almost no audio while it is silent, so samples must be
        # placed by their timestamps (filling silence) before any filter renumbers them.
        af = f"aresample=async=1:first_pts=0,{AUDIO_FILTER},aresample=48000,apad"
    else:
        src = ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]
        af = "anull"
    subprocess.run([ffmpeg, "-y", "-v", "error", *src, "-vn", "-af", af, "-t", length, "-ar", "48000", "-ac", "2",
                    "-c:a", "pcm_s16le", str(audio)], check=True)
    if has_audio(clip):
        print(f"  softened {soften_onsets(audio)} sound edges", flush=True)
    videos.append(video)
    audios.append(audio)


def concat(files: list[Path], name: str) -> Path:
    listing = work / f"{name}.txt"
    listing.write_text("".join(f"file '{p.as_posix()}'\n" for p in files), encoding="utf-8")
    joined = work / (name + files[0].suffix)
    subprocess.run([ffmpeg, "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy",
                    str(joined)], check=True)
    return joined


# One continuous sound track, encoded once (no AAC joins at the cuts).
subprocess.run([ffmpeg, "-y", "-v", "error", "-i", str(concat(videos, "video")), "-i", str(concat(audios, "audio")),
                "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k", "-shortest",
                "-movflags", "+faststart", str(output)], check=True)
dur = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(output)],
                     capture_output=True, text=True).stdout.strip()
print(f"wrote {output} ({output.stat().st_size // 1024} KB, {float(dur):.1f} s)")
