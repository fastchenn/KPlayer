#!/usr/bin/env python3
"""Generate reproducible media from testsrc2 and our existing synthetic VBR fixture."""
import argparse
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise SystemExit("Long-video regression fixtures require FFmpeg and ffprobe on PATH")
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    def ffmpeg(*args):
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *map(str, args)], check=True)
    ffmpeg("-i", root / "playback/mpv/src/test/resources/timeline-vbr.mkv", "-c:v", "copy", "-c:a", "aac", "-b:a", "32k", output / "timeline-vbr.mp4")
    with (output / "timeline-vbr-mp4-packets.json").open("w") as report:
        subprocess.run(["ffprobe", "-v", "error", "-show_entries", "packet=stream_index,pts_time,duration_time,pos,size,flags", "-of", "json", str(output / "timeline-vbr.mp4")], stdout=report, check=True)
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=1/5:duration=10800", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-g", "12", "-bf", "0", output / "timeline-3h.mp4")
    ffmpeg("-i", output / "timeline-3h.mp4", "-c", "copy", output / "timeline-3h.mkv")


if __name__ == "__main__":
    main()
