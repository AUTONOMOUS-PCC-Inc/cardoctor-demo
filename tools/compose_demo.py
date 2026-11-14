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


parts = []
for clip in sorted(clips.glob("scene*.webm")):
    scene = int(clip.stem.replace("scene", ""))
    part = work / f"part{scene:02d}.mp4"
    vf = (f"scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,fps=30,"
          f"subtitles='{ff_path(ass_file(scene))}':fontsdir='{ff_path(FONT_DIR)}'")
    cmd = [ffmpeg, "-y", "-v", "error", "-i", str(clip)]
    if not has_audio(clip):
        cmd += ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-shortest"]
    cmd += ["-vf", vf, "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "160k", str(part)]
    print("render", clip.name, flush=True)
    subprocess.run(cmd, check=True)
    parts.append(part)

listing = work / "parts.txt"
listing.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts), encoding="utf-8")
subprocess.run([ffmpeg, "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy",
                "-movflags", "+faststart", str(output)], check=True)
dur = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(output)],
                     capture_output=True, text=True).stdout.strip()
print(f"wrote {output} ({output.stat().st_size // 1024} KB, {float(dur):.1f} s)")
