#!/usr/bin/env bash
# Builds the jar and the release zip: dist/WII-UU-<version>.zip
set -euo pipefail
cd "$(dirname "$0")"
python3 tools/build-version.py > /dev/null
BASE_VERSION="$(python3 -c 'import json; print(json.load(open("build/version.json"))["baseVersion"])')"
# every release says what changed: WII-UU shows it after updating
if ! grep -qE "^## v?${BASE_VERSION//./\\.}( |$)" CHANGELOG.md; then
  echo "CHANGELOG.md has no '## $BASE_VERSION' entry: add what changed first." >&2
  exit 1
fi
python3 tools/package-cloudflared.py
WIIUU_SLIM_RELAY=1 WIIUU_SKIP_JAVA_RTC=0 ./build.sh
VERSION="$(python3 -c 'import json; print(json.load(open("build/version.json"))["releaseVersion"])')"
CHANNEL="$(python3 -c 'import json; print(json.load(open("build/version.json"))["channel"])')"
NAME="WII-UU-$VERSION"
rm -rf "dist/$NAME" "dist/$NAME.zip"
mkdir -p "dist/$NAME"
cp build/wiiuu.jar build/ChoccyMilk.jar build/WII-UU-Cleanup.jar build/WII-UU-OEM-Creator.jar install.sh emulators.sh install.ps1 install.bat README.md CHANGELOG.md "dist/$NAME/"
cp -r console "dist/$NAME/"
chmod +x "dist/$NAME/install.sh" "dist/$NAME/emulators.sh" "dist/$NAME/console/wiiuu-console"
(cd dist && zip -qr -X "$NAME.zip" "$NAME")
rm -rf "dist/$NAME"
# the website (docs/, served at wiiuu.stoppedwumm.net) hosts the downloads too
mkdir -p docs/download
relay_version="$(python3 -c 'import json; print(json.load(open("tools/cloudflared.json"))["version"])')"
for helper in build/cloudflared/*; do
  target="docs/download/cloudflared/$relay_version/$(basename "$helper")"
  mkdir -p "$target"
  cp "$helper"/cloudflared* "$target/"
done
if [ "$CHANNEL" = stable ]; then
  DOWNLOAD=docs/download
else
  DOWNLOAD=docs/beta/download
fi
mkdir -p "$DOWNLOAD"
cp install.sh install.ps1 build/WII-UU-Cleanup.jar build/WII-UU-OEM-Creator.jar "$DOWNLOAD/"
cp "dist/$NAME.zip" "docs/download/$NAME.zip"
cp "dist/$NAME.zip" "$DOWNLOAD/WII-UU-latest.zip"
# the reactions editor on its own (it runs anywhere with Java 17+ and ffmpeg)
cp build/WII-UU-Reactions.jar "$DOWNLOAD/WII-UU-Reactions.jar"
# Choccy Milk on its own (Java 17+; it gets the newer Java its player needs by itself)
cp build/ChoccyMilk.jar "$DOWNLOAD/ChoccyMilk.jar"
# the notes, for the website and for installed copies about to update (next to version.json)
if [ "$CHANNEL" = stable ]; then cp CHANGELOG.md docs/changelog.md; else cp CHANGELOG.md docs/beta/changelog.md; fi
python3 tools/write-update-manifest.py
