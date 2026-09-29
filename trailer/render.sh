#!/usr/bin/env bash
# Renders the WII-UU trailer to dist/WII-UU-trailer.mp4 (and docs/trailer.mp4 for the website):
# fresh app screenshots, the soundtrack from WII-UU's own chime and menu music, then the frames.
# Needs a JDK, Node with Playwright, and ffmpeg.
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh
B=trailer/build
mkdir -p "$B" dist
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# a small made-up library, so the menu shows game counts
for se in nes:nes snes:sfc gb:gb n64:z64 gba:gba gc:iso nds:nds wii:wbfs 3ds:3ds wiiu:wua switch:nsp \
          sms:sms genesis:md saturn:chd dc:chd ps1:chd ps2:iso psp:iso; do
  mkdir -p "$work/roms/${se%%:*}"
  for g in "Pixel Quest" "Star Runner" "Neon Drift"; do touch "$work/roms/${se%%:*}/$g.${se##*:}"; done
done
snap() {   # snap <name> <theme> <view> [env...]
  mkdir -p "$work/$1"
  printf 'roms.base=%s\nui.theme=%s\n' "$work/roms" "$2" > "$work/$1/config.properties"
  env "${@:4}" java -Djava.awt.headless=true -jar build/wiiuu.jar --home "$work/$1" --snapshot "$B/$1.png" --snapshot-view "$3" >/dev/null
}
snap menu-light light home
snap menu-dark dark home
snap power-console light power WIIUU_CONSOLE=1
echo "  screenshots in $B"

mkdir -p "$B/classes"
javac -cp build/wiiuu.jar -d "$B/classes" trailer/Soundtrack.java
java -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.Soundtrack "$B/soundtrack.wav" 67.2

node trailer/render.mjs "$B/soundtrack.wav" dist/WII-UU-trailer.mp4 "${FPS:-30}"
cp dist/WII-UU-trailer.mp4 docs/trailer.mp4
echo "Trailer: dist/WII-UU-trailer.mp4 (also docs/trailer.mp4)"
