#!/usr/bin/env bash
# Builds build/wiiuu.jar with only the JDK (javac + jar), Java 17 or newer.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
# Choccy Milk (WII-UU's own libretro frontend) uses Java 22's foreign function API; it runs in its own
# process on a Java 22+ that WII-UU looks for (or downloads), so WII-UU itself stays Java 17. Built with
# this JDK if it can, else with a newer one on this computer, else taken from the release zip in docs/.
JAVAC22=""
for c in javac "${JAVA_HOME:-/nonexistent}/bin/javac" /usr/lib/jvm/*/bin/javac /opt/java/*/bin/javac \
    /opt/homebrew/opt/openjdk*/bin/javac /usr/local/opt/openjdk*/bin/javac \
    /opt/homebrew/Cellar/openjdk*/*/bin/javac /usr/local/Cellar/openjdk*/*/bin/javac \
    /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/javac "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/javac \
    "$HOME"/.sdkman/candidates/java/*/bin/javac "$HOME"/.jdks/*/bin/javac; do
  if { [ "$c" = javac ] || [ -x "$c" ]; } && "$c" --release 22 -version >/dev/null 2>&1; then JAVAC22="$c"; break; fi
done
if [ -n "$JAVAC22" ]; then
  find src-choccy -name '*.java' > build/sources-choccy.txt
  "$JAVAC22" --release 22 -encoding UTF-8 -d build/classes @build/sources-choccy.txt
  [ "$JAVAC22" = javac ] || echo "(Choccy Milk built with $JAVAC22)"
elif [ -f docs/download/WII-UU-latest.zip ]; then
  rm -rf build/prebuilt && mkdir -p build/prebuilt
  (cd build/prebuilt && jar xf ../../docs/download/WII-UU-latest.zip)
  pre=$(ls build/prebuilt/*/wiiuu.jar 2>/dev/null | head -1)
  if [ -n "$pre" ]; then
    (cd build/classes && jar xf "../../$pre" wiiuu/choccy)
    echo "(this JDK is older than 22: Choccy Milk taken from the release in docs/download)"
  fi
  rm -rf build/prebuilt
else
  echo "(no JDK 22 or newer here: built without Choccy Milk)"
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
