#!/usr/bin/env bash
# Builds build/wiiuu.jar with only the JDK (javac + jar), Java 17 or newer.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
# Choccy Milk (WII-UU's own libretro frontend) uses Java 22's foreign function API: built when this
# JDK can; it runs in its own process on a Java 22+ that WII-UU looks for, so WII-UU stays Java 17
if javac --release 22 -version >/dev/null 2>&1; then
  find src-choccy -name '*.java' > build/sources-choccy.txt
  javac --release 22 -encoding UTF-8 -d build/classes @build/sources-choccy.txt
else
  echo "(this JDK is older than 22: built without Choccy Milk)"
fi
cp -r resources/. build/classes/
# the release notes WII-UU shows after an update (Settings > General > What's new)
[ -f CHANGELOG.md ] && cp CHANGELOG.md build/classes/CHANGELOG.md
printf 'Main-Class: wiiuu.Main\n' > build/manifest.txt
jar --create --file build/wiiuu.jar --manifest build/manifest.txt -C build/classes .
echo "Built build/wiiuu.jar"
# the reactions editor (reaction mode's videos): the same classes, its own start
printf 'Main-Class: wiiuu.react.Editor\n' > build/manifest-reactions.txt
# (without WII-UU's example reaction packs: the editor doesn't need them)
jar --create --file build/WII-UU-Reactions.jar --manifest build/manifest-reactions.txt -C build/classes .
zip -q -d build/WII-UU-Reactions.jar 'reactions/*' >/dev/null || true
echo "Built build/WII-UU-Reactions.jar"
