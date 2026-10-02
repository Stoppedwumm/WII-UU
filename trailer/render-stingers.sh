#!/usr/bin/env bash
# Makes the WII-UU visual stingers (trailer/VisualStingers.java): animated logo stings and
# transitions with their sound from the sound kit, each as a ProRes 4444 .mov with a transparent
# background, an .mp4 preview on WII-UU's dark background and an .mp4 on green screen, zipped into
# dist/WII-UU-Visual-Stingers.zip (the green screen ones also on their own, -Greenscreen.zip).
# Needs Java 17+, ffmpeg and zip; python3 with fonttools for Inter.
set -euo pipefail
cd "$(dirname "$0")/.."
B=trailer/build-stingers
K="$B/WII-UU Visual Stingers"
[ -f build/wiiuu.jar ] || ./build.sh
[ -d trailer/build-soundkit/raw ] || ./trailer/render-soundkit.sh
rm -rf "$B"
mkdir -p "$B/classes" "$B/fonts" "$K" dist

python3 - "$B/fonts" <<'PY' || echo "  fonttools missing (pip install fonttools brotli): using the default sans-serif"
import sys
from fontTools.ttLib import TTFont
for weight in ("600", "800", "900"):
    font = TTFont(f"trailer/fonts/inter-latin-{weight}-normal.woff2")
    font.flavor = None
    font.save(f"{sys.argv[1]}/Inter-{weight}.ttf")
PY

javac -cp build/wiiuu.jar -d "$B/classes" trailer/Logo3D.java trailer/VisualStingers.java
java -Xmx3g -Djava.awt.headless=true -cp "build/wiiuu.jar:$B/classes" wiiuu.ui.VisualStingers "$K" "$B/fonts" trailer/build-soundkit/raw
cp trailer/stingers-readme.txt "$K/README.txt"
rm -f dist/WII-UU-Visual-Stingers.zip
(cd "$B" && zip -qr "../../dist/WII-UU-Visual-Stingers.zip" "WII-UU Visual Stingers")
rm -f dist/WII-UU-Visual-Stingers-Greenscreen.zip
(cd "$K" && zip -qr "../../../dist/WII-UU-Visual-Stingers-Greenscreen.zip" Greenscreen README.txt)
echo "Visual stingers: dist/WII-UU-Visual-Stingers.zip (green screen only: dist/WII-UU-Visual-Stingers-Greenscreen.zip)"
