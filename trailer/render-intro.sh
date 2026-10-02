#!/usr/bin/env bash
# Renders the WII-UU 3D intro (trailer/intro3d.html, three.js) to dist/WII-UU-3D-Intro.mp4, 1080p30,
# with its soundtrack (IntroAudio.java, from the sound kit). Needs Java 17+, Node with Playwright,
# npm (for three.js), ffmpeg, and python3 with fonttools.
#   FPS=30   FROM=0 TO=15 (render part of it)
set -euo pipefail
cd "$(dirname "$0")/.."
B=trailer/build-intro
[ -f build/wiiuu.jar ] || ./build.sh
[ -d trailer/build-soundkit/raw ] || ./trailer/render-soundkit.sh
mkdir -p "$B/classes" "$B/fonts" dist
[ -f "$B/node_modules/three/build/three.module.js" ] || npm install --silent --no-audit --no-fund --prefix "$B" three@0.170.0

python3 - "$B/fonts" <<'PY'
import sys
from fontTools.ttLib import TTFont
font = TTFont("trailer/fonts/inter-latin-900-normal.woff2")
font.flavor = None
font.save(f"{sys.argv[1]}/Inter-900.ttf")
PY

javac -cp build/wiiuu.jar -d "$B/classes" trailer/LogoPath.java trailer/IntroAudio.java
java -Djava.awt.headless=true -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.LogoPath "$B/fonts/Inter-900.ttf" "$B/logo.json"
java -Djava.awt.headless=true -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.IntroAudio trailer/build-soundkit/raw "$B/intro.wav"

out=dist/WII-UU-3D-Intro.mp4
[ -n "${FROM:-}${TO:-}" ] && out="dist/WII-UU-3D-Intro-${FROM:-0}-${TO:-15}.mp4"
node trailer/render-intro.mjs "$B/intro.wav" "$out" "${FPS:-30}" "${FROM:-0}" "${TO:-15}"
echo "3D intro: $out"
