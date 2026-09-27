#!/usr/bin/env bash
# Builds the jar and the release zip: dist/WII-UU-<version>.zip
set -euo pipefail
cd "$(dirname "$0")"
VERSION="$(grep -o 'VERSION = "[^"]*"' src/wiiuu/Main.java | cut -d'"' -f2)"
NAME="WII-UU-$VERSION"
./build.sh
rm -rf "dist/$NAME" "dist/$NAME.zip"
mkdir -p "dist/$NAME/source"
cp build/wiiuu.jar install.sh emulators.sh install.ps1 install.bat README.md "dist/$NAME/"
cp -r src resources build.sh package.sh "dist/$NAME/source/"
chmod +x "dist/$NAME/install.sh" "dist/$NAME/emulators.sh" "dist/$NAME/source/build.sh" "dist/$NAME/source/package.sh"
(cd dist && zip -qr -X "$NAME.zip" "$NAME")
rm -rf "dist/$NAME"
# the website (docs/, served at wiiuu.stoppedwumm.net) hosts the downloads too
mkdir -p docs/download
cp "dist/$NAME.zip" "docs/download/$NAME.zip"
cp "dist/$NAME.zip" docs/download/WII-UU-latest.zip
echo "Packaged dist/$NAME.zip (also docs/download/)"
