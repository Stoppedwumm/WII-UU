#!/usr/bin/env bash
# Renders the WII-UU YouTube video (script: trailer/youtube.json) to dist/WII-UU-youtube.mp4, with
# captions (.srt), chapter timestamps, a thumbnail and a description ready to paste.
#   VOICE_ENGINE=elevenlabs ELEVENLABS=<key> ./trailer/render-youtube.sh      (or VOICE_ENGINE=kokoro, the default)
# Reuses the live demo recorded by render2.sh (trailer/build2); runs it first if it's missing.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d trailer/build2/tv ] || ./trailer/render2.sh
B=trailer/build-yt
rm -rf "$B"; mkdir -p "$B/voice" dist

ENGINE="${VOICE_ENGINE:-kokoro}"
TTS="${WIIUU_TTS_DIR:-$HOME/.cache/wiiuu-tts}"
if [ "$ENGINE" = elevenlabs ]; then
  voice_len="$(python3 trailer/tts.py trailer/youtube.json "$B/voice" --engine elevenlabs ${VOICE_ID:+--voice "$VOICE_ID"})"
else
  voice_len="$("$TTS/venv/bin/python" trailer/tts.py trailer/youtube.json "$B/voice" --engine kokoro \
    --model "$TTS/package/kokoro-int8-en-v0_19" ${VOICE_ID:+--voice "$VOICE_ID"})"
fi
echo "  narration ($ENGINE): $voice_len"
phone_offset="$(sed -E 's/.*"phoneOffset": *([0-9.]+).*/\1/' trailer/build2/config.json)"
printf '{"phoneOffset": %s, "tvFirst": 4.0, "voiceLen": %s}\n' "$phone_offset" "$voice_len" > "$B/config.json"

mkdir -p "$B/classes"
javac -cp build/wiiuu.jar -d "$B/classes" trailer/Soundtrack.java
java -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.Soundtrack "$B/music.wav" 300 >/dev/null
PAGE=youtube.html node trailer/render2.mjs "$B/config.json" "$B/music.wav" "$B/voice" dist/WII-UU-youtube.mp4 "${FPS:-30}"

# thumbnail
node -e '
const pw = require(process.env.PLAYWRIGHT || "/opt/node22/lib/node_modules/playwright");
(async () => { const b = await pw.chromium.launch(); const p = await b.newPage({ viewport: { width: 1280, height: 720 } });
  await p.goto("file://" + process.cwd() + "/trailer/thumbnail.html"); await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(300);
  await p.screenshot({ path: "dist/WII-UU-youtube-thumbnail.jpg", type: "jpeg", quality: 92 }); await b.close(); })();'

# description, with the chapters
{
  echo "WII-UU turns any PC or Raspberry Pi into a Wii U–style console, and your phone into the GamePad."
  echo
  echo "▶ Download (free): https://wiiuu.stoppedwumm.net"
  echo "▶ Source: https://github.com/Stoppedwumm/WII-UU"
  echo
  echo "Chapters"
  cat dist/WII-UU-youtube-chapters.txt
  echo
  echo "• 20 consoles in one Wii U–style menu: NES to Wii U and Switch, PS1 to PS4"
  echo "• Your phone is the GamePad: no app, just scan the code. Gyro, touch, sound and a live TV mirror"
  echo "• Wii U GamePad view and DS/3DS touch screens on your phone"
  echo "• Controllers that just work, including temporary mappings for Dolphin, PCSX2 and DuckStation"
  echo "• RetroArch mode, and Console Mode: boot straight into WII-UU, Desktop Mode anytime"
  echo
  echo "WII-UU is a launcher: bring your own legally dumped games and, where needed, your own firmware."
  echo "Not affiliated with Nintendo, Sony or Sega."
} > dist/WII-UU-youtube-description.txt
echo "YouTube video: dist/WII-UU-youtube.mp4 (+ .srt, -thumbnail.jpg, -description.txt)"
