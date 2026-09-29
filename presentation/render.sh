#!/usr/bin/env bash
# Renders the 8-minute WII-UU presentation (talk.json / talk.html) to dist/WII-UU-presentation.mp4,
# with captions (.srt) and chapter timestamps.
#   VOICE_ENGINE=elevenlabs ELEVENLABS=<key> ./presentation/render.sh     (default: the offline Kokoro voice)
# Uses the screenshots and live demo of the trailers (trailer/build, trailer/build2); makes them first if missing.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f trailer/build/menu-light.png ] || ./trailer/render.sh
[ -d trailer/build2/tv ] || ./trailer/render2.sh
B=presentation/build
mkdir -p "$B/voice" dist
ENGINE="${VOICE_ENGINE:-kokoro}"
TTS="${WIIUU_TTS_DIR:-$HOME/.cache/wiiuu-tts}"
rm -f "$B"/voice/*
if [ "$ENGINE" = elevenlabs ]; then
  voice_len="$(python3 trailer/tts.py presentation/talk.json "$B/voice" --engine elevenlabs ${VOICE_ID:+--voice "$VOICE_ID"})"
else
  voice_len="$("$TTS/venv/bin/python" trailer/tts.py presentation/talk.json "$B/voice" --engine kokoro \
    --model "$TTS/package/kokoro-int8-en-v0_19" ${VOICE_ID:+--voice "$VOICE_ID"})"
fi
echo "$voice_len" > "$B/voice.json"
phone_offset="$(sed -E 's/.*"phoneOffset": *([0-9.]+).*/\1/' trailer/build2/config.json)"
printf '{"phoneOffset": %s, "tvFirst": 4.0, "voiceLen": %s}\n' "$phone_offset" "$voice_len" > "$B/config.json"
mkdir -p "$B/classes"
javac -cp build/wiiuu.jar -d "$B/classes" trailer/Soundtrack.java
java -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.Soundtrack "$B/music.wav" 600 >/dev/null
PAGE=../presentation/talk.html MUSIC_LEVEL="${MUSIC_LEVEL:-0.35}" node trailer/render2.mjs "$B/config.json" "$B/music.wav" "$B/voice" dist/WII-UU-presentation.mp4 "${FPS:-30}"
echo "Presentation: dist/WII-UU-presentation.mp4 (+ .srt, -chapters.txt)"
