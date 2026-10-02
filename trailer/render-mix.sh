#!/usr/bin/env bash
# Renders the WII-UU Extended Mix music video for YouTube into dist/mix/:
#   WII-UU-Extended-Mix.mp4 (1080p30, H.264/AAC), -thumbnail.jpg, -chapters.txt, -description.txt,
#   and the channel banner (WII-UU-channel-banner.png, ChannelBanner.java)
# A 3D opening, an intro card, every Future and Color House remix non-stop with a visualizer, an end
# card and a 3D ending (trailer/MixVideo.java, trailer/Logo3D.java). Needs Java 17+ and ffmpeg;
# python3 with fonttools for the Inter font (otherwise the system's sans-serif).
#   PARTS=3 (pieces rendered at once)  TRACKS=16  FPS=30  SIZE=1920x1080  CRF=20  X264_PRESET=medium
set -euo pipefail
cd "$(dirname "$0")/.."
B=trailer/build-mix
OUT=dist/mix
PARTS="${PARTS:-3}"
FPS="${FPS:-30}"
[ -f build/wiiuu.jar ] || ./build.sh
rm -rf "$B"
mkdir -p "$B/classes" "$B/fonts" "$OUT"

python3 - "$B/fonts" <<'PY' || echo "  fonttools missing (pip install fonttools brotli): using the default sans-serif"
import sys
from fontTools.ttLib import TTFont
for weight in ("600", "800", "900"):
    font = TTFont(f"trailer/fonts/inter-latin-{weight}-normal.woff2")
    font.flavor = None
    font.save(f"{sys.argv[1]}/Inter-{weight}.ttf")
PY

javac -cp build/wiiuu.jar -d "$B/classes" trailer/Logo3D.java trailer/MixVideo.java trailer/ChannelBanner.java
CP="build/wiiuu.jar:$B/classes"
ARGS=(--fps "$FPS" --size "${SIZE:-1920x1080}" --tracks "${TRACKS:-16}")
run() { java -Xmx2500m -Djava.awt.headless=true -cp "$CP" wiiuu.ui.MixVideo "$OUT" "$B/fonts" "${ARGS[@]}" "$@"; }

java -Djava.awt.headless=true -cp "$CP" wiiuu.ui.ChannelBanner "$OUT/WII-UU-channel-banner.png" "$B/fonts"

# 1. the soundtrack, chapters, description and thumbnail; prints the length in seconds
total="$(run --prepare)"
frames="$(python3 -c "import math; print(math.floor($total * $FPS))")"
echo "  $frames frames, in $PARTS parts"

# 2. the picture, in parts at once (each part replays the visualizer from the start, so they join seamlessly)
: > "$B/parts.txt"
pids=()
for ((k = 0; k < PARTS; k++)); do
  f0=$(( frames * k / PARTS )); f1=$(( frames * (k + 1) / PARTS ))
  from="$(python3 -c "print($f0 / $FPS)")"; to="$(python3 -c "print($f1 / $FPS)")"
  run --part --only "$from-$to" --out "$B/part$k.mp4" 2> "$B/part$k.log" &
  pids+=($!)
  echo "file 'part$k.mp4'" >> "$B/parts.txt"
done
for p in "${pids[@]}"; do wait "$p"; done
tail -n 1 "$B"/part*.log

# 3. joined, with the soundtrack
ffmpeg -y -loglevel error -f concat -safe 0 -i "$B/parts.txt" -i "$OUT/WII-UU-Extended-Mix.wav" \
  -map 0:v -map 1:a -c:v copy -c:a aac -b:a 256k -movflags +faststart -shortest "$OUT/WII-UU-Extended-Mix.mp4"
rm "$OUT/WII-UU-Extended-Mix.wav"
echo "Music video: $OUT/WII-UU-Extended-Mix.mp4 (+ -thumbnail.jpg, -chapters.txt, -description.txt)"
