#!/usr/bin/env bash
# Builds build/wiiuu.jar with only the JDK (javac + jar), Java 17 or newer.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
cp -r resources/. build/classes/
# the release notes WII-UU shows after an update (Settings > General > What's new)
[ -f CHANGELOG.md ] && cp CHANGELOG.md build/classes/CHANGELOG.md
printf 'Main-Class: wiiuu.Main\n' > build/manifest.txt
jar --create --file build/wiiuu.jar --manifest build/manifest.txt -C build/classes .
echo "Built build/wiiuu.jar"
# the reactions editor (reaction mode's videos): the same classes, its own start
printf 'Main-Class: wiiuu.react.Editor\n' > build/manifest-reactions.txt
jar --create --file build/WII-UU-Reactions.jar --manifest build/manifest-reactions.txt -C build/classes .
echo "Built build/WII-UU-Reactions.jar"
