#!/usr/bin/env bash
# Renders the 8-minute WII-UU presentation (talk.json / talk.html) to dist/WII-UU-presentation.mp4,
# with captions (.srt) and chapter timestamps. The music and chimes are its own (Theme.java), made to
# fit the page's timeline.
#   VOICE_ENGINE=elevenlabs ELEVENLABS=<key> ./presentation/render.sh     (default: the offline Kokoro voice)
#   KEEP_VOICE=1 ./presentation/render.sh      reuse the narration of the last render (script unchanged)
# Uses the screenshots and live demo of the trailers (trailer/build, trailer/build2); makes them first if missing.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f trailer/build/menu-light.png ] || ./trailer/render.sh
[ -d trailer/build2/tv ] || ./trailer/render2.sh
B=presentation/build
mkdir -p "$B/voice" dist
ENGINE="${VOICE_ENGINE:-kokoro}"
TTS="${WIIUU_TTS_DIR:-$HOME/.cache/wiiuu-tts}"
if [ "${KEEP_VOICE:-0}" = 1 ] && [ -s "$B/voice.json" ]; then
  voice_len="$(cat "$B/voice.json")"
else
  rm -f "$B"/voice/*
  if [ "$ENGINE" = elevenlabs ]; then
    voice_len="$(python3 trailer/tts.py presentation/talk.json "$B/voice" --engine elevenlabs ${VOICE_ID:+--voice "$VOICE_ID"})"
  else
    voice_len="$("$TTS/venv/bin/python" trailer/tts.py presentation/talk.json "$B/voice" --engine kokoro \
      --model "$TTS/package/kokoro-int8-en-v0_19" ${VOICE_ID:+--voice "$VOICE_ID"})"
  fi
fi
echo "$voice_len" > "$B/voice.json"
phone_offset="$(sed -E 's/.*"phoneOffset": *([0-9.]+).*/\1/' trailer/build2/config.json)"
printf '{"phoneOffset": %s, "tvFirst": 4.0, "voiceLen": %s}\n' "$phone_offset" "$voice_len" > "$B/config.json"
# the timeline (slide turns, points, part cards, themes), then music and chimes made to fit it
PAGE=../presentation/talk.html CUES_OUT="$B/cues.txt" node trailer/render2.mjs "$B/config.json" /dev/null "$B/voice" "$B/unused.mp4"
java presentation/Theme.java "$B/cues.txt" "$B/music.wav" "$B/sfx.wav"
PAGE=../presentation/talk.html MUSIC_LEVEL="${MUSIC_LEVEL:-0.4}" SFX="$B/sfx.wav" SFX_LEVEL="${SFX_LEVEL:-0.5}" \
  node trailer/render2.mjs "$B/config.json" "$B/music.wav" "$B/voice" dist/WII-UU-presentation.mp4 "${FPS:-30}"
echo "Presentation: dist/WII-UU-presentation.mp4 (+ .srt, -chapters.txt)"
