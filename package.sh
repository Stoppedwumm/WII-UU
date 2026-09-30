#!/usr/bin/env bash
# Builds the jar and the release zip: dist/WII-UU-<version>.zip
set -euo pipefail
cd "$(dirname "$0")"
VERSION="$(grep -o 'VERSION = "[^"]*"' src/wiiuu/Main.java | cut -d'"' -f2)"
NAME="WII-UU-$VERSION"
# every release says what changed: WII-UU shows it after updating
if ! grep -qE "^## v?${VERSION//./\\.}( |$)" CHANGELOG.md; then
  echo "CHANGELOG.md has no '## $VERSION' entry: add what changed first." >&2
  exit 1
fi
./build.sh
rm -rf "dist/$NAME" "dist/$NAME.zip"
mkdir -p "dist/$NAME/source"
cp build/wiiuu.jar install.sh emulators.sh install.ps1 install.bat README.md CHANGELOG.md "dist/$NAME/"
cp -r console "dist/$NAME/"
cp -r src resources build.sh package.sh CHANGELOG.md "dist/$NAME/source/"
chmod +x "dist/$NAME/install.sh" "dist/$NAME/emulators.sh" "dist/$NAME/console/wiiuu-console" "dist/$NAME/source/build.sh" "dist/$NAME/source/package.sh"
(cd dist && zip -qr -X "$NAME.zip" "$NAME")
rm -rf "dist/$NAME"
# the website (docs/, served at wiiuu.stoppedwumm.net) hosts the downloads too
mkdir -p docs/download
cp "dist/$NAME.zip" "docs/download/$NAME.zip"
cp "dist/$NAME.zip" docs/download/WII-UU-latest.zip
# the notes, for the website and for installed copies about to update (next to version.json)
cp CHANGELOG.md docs/changelog.md
# version.json tells installed copies (Settings > Check for updates, wiiuu --upgrade) what's newest
BASE_URL="${WIIUU_SITE:-https://wiiuu.stoppedwumm.net}"
SHA="$(sha256sum "dist/$NAME.zip" | cut -d' ' -f1)"
printf '{\n  "version": "%s",\n  "zip": "%s/download/%s.zip",\n  "sha256": "%s"\n}\n' \
  "$VERSION" "$BASE_URL" "$NAME" "$SHA" > docs/version.json
echo "Packaged dist/$NAME.zip (also docs/download/, docs/version.json)"
