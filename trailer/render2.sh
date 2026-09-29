#!/usr/bin/env bash
# Renders the second trailer, a narrated demo, to dist/WII-UU-demo-trailer.mp4 (and
# docs/trailer-demo.mp4): records a live demo of WII-UU with a phone GamePad, speaks the
# narration, and composites it with scenes from the first trailer.
# Needs: a JDK, Xvfb, ffmpeg, Node with Playwright, Python 3 (the voice is set up on first run).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f trailer/build/menu-light.png ] || ./trailer/render.sh   # the screenshots trailer.html shows
./build.sh
B=trailer/build2
rm -rf "$B"; mkdir -p "$B/tv" "$B/phone" "$B/voice" dist
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# ---- the voice: Kokoro (Apache-2.0), run by sherpa-onnx; the model comes from an npm package
TTS="${WIIUU_TTS_DIR:-$HOME/.cache/wiiuu-tts}"
MODEL="$TTS/package/kokoro-int8-en-v0_19"
if [ ! -f "$MODEL/model.int8.onnx" ]; then
  echo "  setting up the voice (about 100 MB, once)"
  mkdir -p "$TTS"
  (cd "$TTS" && npm pack --silent n8n-nodes-ttsbro@0.1.6 >/dev/null && tar xzf n8n-nodes-ttsbro-*.tgz && rm -f n8n-nodes-ttsbro-*.tgz)
fi
[ -x "$TTS/venv/bin/python" ] || { python3 -m venv "$TTS/venv" && "$TTS/venv/bin/pip" install -q sherpa-onnx soundfile numpy; }
voice_len="$("$TTS/venv/bin/python" trailer/voice.py trailer/trailer2.html "$MODEL" "$B/voice")"
echo "  narration: $voice_len"

# ---- the live demo (a made-up library and a stand-in emulator)
for se in nes:nes snes:sfc gb:gb n64:z64 gba:gba gc:iso nds:nds wii:wbfs 3ds:3ds wiiu:wua switch:nsp \
          sms:sms genesis:md saturn:chd dc:chd ps1:chd ps2:iso psp:iso; do
  mkdir -p "$work/roms/${se%%:*}"
  for g in "Pixel Quest" "Star Runner" "Neon Drift"; do touch "$work/roms/${se%%:*}/$g.${se##*:}"; done
done
printf '#!/bin/sh\nsleep 9\n' > "$work/emulator"; chmod +x "$work/emulator"
mkdir -p "$work/home"
{
  printf 'roms.base=%s\nserver.code=4821\nui.minimizeOnLaunch=false\nui.fullscreen=true\nupdate.check=false\n' "$work/roms"
  for s in nes snes gb n64 gba gc nds wii 3ds wiiu switch sms genesis saturn dc ps1 ps2 psp; do echo "system.$s.command=$work/emulator {rom}"; done
} > "$work/home/config.properties"
node trailer/demo.mjs build/wiiuu.jar "$work/home" "$work/demo"

TV_FIRST=4.0
# without a window manager WII-UU's window may not fill the virtual screen: crop to it
crop="$(ffmpeg -ss 12 -i "$work/demo/tv.mp4" -t 3 -vf cropdetect=24:2:0 -f null - 2>&1 | grep -o 'crop=[0-9:]*' | tail -1)"
echo "  TV window: ${crop:-full screen}"
ffmpeg -loglevel error -ss "$TV_FIRST" -i "$work/demo/tv.mp4" -t 29 -vf "${crop:+$crop,}scale=1920:1080:flags=lanczos,fps=30" -q:v 2 "$B/tv/%05d.jpg"
ffmpeg -loglevel error -i "$work/demo/phone.mp4" -vf fps=30 -q:v 2 "$B/phone/%05d.jpg"
phone_offset="$(sed -E 's/.*"phoneOffset":([0-9.]+).*/\1/' "$work/demo/sync.json")"
printf '{"phoneOffset": %s, "tvFirst": %s, "voiceLen": %s}\n' "$phone_offset" "$TV_FIRST" "$voice_len" > "$B/config.json"

# ---- music bed with the boot chime, then the frames
mkdir -p "$B/classes"
javac -cp build/wiiuu.jar -d "$B/classes" trailer/Soundtrack.java
java -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.Soundtrack "$B/music.wav" 59.6
node trailer/render2.mjs "$B/config.json" "$B/music.wav" "$B/voice" dist/WII-UU-demo-trailer.mp4 "${FPS:-30}"
cp dist/WII-UU-demo-trailer.mp4 docs/trailer-demo.mp4
echo "Trailer: dist/WII-UU-demo-trailer.mp4 (also docs/trailer-demo.mp4)"
