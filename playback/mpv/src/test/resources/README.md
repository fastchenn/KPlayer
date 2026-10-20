# Timeline fixture

`timeline-vbr.mkv` is generated locally from FFmpeg color/testsrc2 and sine sources; it contains no third-party footage. H.264 keyframe PTS verified with ffprobe: 0, 2, 4, 6, 8, 10, 12, 14 seconds. FLAC audio, duration 16 seconds. The changing scene makes byte offsets non-proportional to timestamps.

Generate with FFmpeg 6.1.1:

```sh
ffmpeg -f lavfi -i 'color=red:size=64x48:rate=4:duration=8' -f lavfi -i 'testsrc2=size=64x48:rate=4:duration=8' -f lavfi -i 'sine=frequency=440:sample_rate=8000:duration=16' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -map 2:a -c:v libx264 -preset veryfast -crf 25 -g 8 -keyint_min 8 -sc_threshold 0 -c:a flac -cluster_time_limit 2000 timeline-vbr.mkv
ffprobe -v error -select_streams v:0 -show_entries packet=pts_time,pos,flags -of csv=p=0 timeline-vbr.mkv
```

The fixture is retained so CI tests do not depend on FFmpeg version or encoding output.
