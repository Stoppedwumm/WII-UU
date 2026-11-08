#!/usr/bin/env bash
# Build the standalone Java 17 UI library and its API documentation.
set -euo pipefail
cd "$(dirname "$0")/.."
version="$(cat ui-library/VERSION)"
out="build/ui-library"
rm -rf "$out/classes" "$out/api"
mkdir -p "$out/classes" "$out/api"
sources=(
  engine/src/wiiuu/engine/GameScreen.java
  engine/src/wiiuu/engine/SystemTheme.java
  engine/src/wiiuu/input/PadButton.java
)
javac --release 17 -encoding UTF-8 -d "$out/classes" "${sources[@]}"
printf 'Implementation-Title: WII-UU UI Library\nImplementation-Version: %s\nAutomatic-Module-Name: wiiuu.ui.library\n' "$version" > "$out/manifest.txt"
jar --create --file "$out/WII-UU-UI-$version.jar" --manifest "$out/manifest.txt" -C "$out/classes" .
javadoc -quiet -Xdoclint:none --release 17 -encoding UTF-8 -d "$out/api" -windowtitle "WII-UU UI $version API" "${sources[@]}"
jar --create --file "$out/WII-UU-UI-$version-javadoc.jar" -C "$out/api" .
echo "Built $out/WII-UU-UI-$version.jar and Javadoc"
