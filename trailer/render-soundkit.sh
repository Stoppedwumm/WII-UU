#!/usr/bin/env bash
# Makes the WII-UU trailer sound kit: stingers, sound effects and the menu's UI sounds, synthesized
# by trailer/SoundKit.java, as 48 kHz 24-bit WAVs zipped into dist/WII-UU-Sound-Kit.zip.
set -euo pipefail
cd "$(dirname "$0")/.."
B=trailer/build-soundkit
K="$B/WII-UU Sound Kit"
[ -f build/wiiuu.jar ] || ./build.sh
rm -rf "$B"
mkdir -p "$B/classes" "$B/raw" dist
javac -cp build/wiiuu.jar -d "$B/classes" trailer/SoundKit.java
java -Djava.awt.headless=true -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.SoundKit "$B/raw"

# 48 kHz 24-bit, what video editors expect
(cd "$B/raw" && find . -name '*.wav') | while read -r f; do
  mkdir -p "$K/$(dirname "$f")"
  ffmpeg -loglevel error -y -i "$B/raw/$f" -ar 48000 -c:a pcm_s24le "$K/$f"
done
cp trailer/soundkit-readme.txt "$K/README.txt"
rm -f dist/WII-UU-Sound-Kit.zip
(cd "$B" && zip -qr "../../dist/WII-UU-Sound-Kit.zip" "WII-UU Sound Kit")
echo "Sound kit: dist/WII-UU-Sound-Kit.zip"
