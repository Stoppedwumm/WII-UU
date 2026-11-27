#!/usr/bin/env bash
# Unified test harness for GitHub Actions and local development.
# Usage: bash tools/ci-tests.sh build|installer|native-startup
set -euo pipefail
cd "$(dirname "$0")/.."

suite="${1:-build}"
failed=0
passed=0
results=()

run_check() {
  local name="$1"
  shift
  printf '::group::%s\n' "$name"
  if "$@"; then
    printf 'PASS: %s\n' "$name"
    results+=("| $name | PASS |")
    passed=$((passed + 1))
  else
    local exit_code=$?
    printf 'FAIL: %s (exit %s)\n' "$name" "$exit_code" >&2
    results+=("| $name | FAIL (exit $exit_code) |")
    failed=$((failed + 1))
  fi
  printf '::endgroup::\n'
}

case "$suite" in
  native-startup)
    run_check "Test actual native DS frame path on Java 27" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_1'
mkdir -p build/native-startup
find engine/src -name '*.java' > build/native-engine.txt
javac --release 17 -d build/native-startup @build/native-engine.txt
find engine/src-choccy -name '*.java' > build/native-helper.txt
javac --release 22 -cp build/native-startup -d build/native-startup @build/native-helper.txt
javac --release 17 -cp build/native-startup -d build/native-startup test/wiiuu/core/NativeStartupTest.java
if [[ "$RUNNER_OS" == macOS ]]; then
  core=build/native-startup/melondsds_libretro.dylib
  clang -dynamiclib -o "$core" test/native/ds_startup_fixture.c
else
  core=build/native-startup/melondsds_libretro.so
  gcc -shared -fPIC -o "$core" test/native/ds_startup_fixture.c
fi
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -cp build/native-startup wiiuu.core.NativeStartupTest "$core"
WIIUU_CI_COMMAND_1
    ;;
  installer)
    run_check "Test build ZIP import" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_2'
mkdir -p build/installer-tests
javac --release 17 -d build/installer-tests engine/src/wiiuu/core/Config.java engine/src/wiiuu/core/GameSystem.java src/wiiuu/core/Changelog.java src/wiiuu/core/Updater.java engine/src/wiiuu/core/BuildInfo.java engine/src/wiiuu/core/Version.java test/wiiuu/core/ActionsBuildTest.java test/wiiuu/core/BuildInfoTest.java test/wiiuu/core/VersionTest.java test/wiiuu/core/UpdateChannelsTest.java engine/src/wiiuu/core/MiniJson.java src/wiiuu/core/ReleaseArchive.java test/wiiuu/core/ReleaseArchiveTest.java src/wiiuu/core/PluginStore.java src/wiiuu/core/XboxCloudRuntime.java test/wiiuu/core/PluginStoreTest.java test/wiiuu/core/XboxCloudRuntimeTest.java src/wiiuu/core/NativePluginSession.java test/wiiuu/core/NativePluginSessionTest.java test/wiiuu/core/UserProfilesTest.java
cp -r resources/plugins build/installer-tests/plugins
java -cp build/installer-tests wiiuu.core.NativePluginSessionTest
java -cp build/installer-tests wiiuu.core.PluginStoreTest
java -cp build/installer-tests wiiuu.core.XboxCloudRuntimeTest
java -cp build/installer-tests wiiuu.core.ActionsBuildTest
java -cp build/installer-tests wiiuu.core.BuildInfoTest
java -cp build/installer-tests wiiuu.core.VersionTest
java -Djava.awt.headless=true -cp build/installer-tests wiiuu.core.UserProfilesTest
java --add-modules jdk.httpserver -cp build/installer-tests wiiuu.core.UpdateChannelsTest
java --add-modules jdk.httpserver -cp build/installer-tests wiiuu.core.ReleaseArchiveTest
python3 -m unittest discover -s tools/tests -p 'test_release*.py'
python3 -m unittest discover -s test/xcloud -p 'test_native_rtc.py'
WIIUU_CI_COMMAND_2
    if [[ "${RUNNER_OS:-}" == "Windows" ]]; then
      run_check "Check Windows installer syntax" pwsh -NoProfile -Command - <<'WIIUU_CI_COMMAND_3'
$tokens = $null
$errors = $null
foreach ($file in @('install.ps1', 'docs/get.ps1', 'tools/tests/windows-web-installer.ps1')) {
    [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $file).Path, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors) { throw ($errors | Out-String) }
}
& ./tools/tests/windows-web-installer.ps1
WIIUU_CI_COMMAND_3
    fi
    run_check "Test shared core manager" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_4'
find engine/src -name '*.java' > build/core-manager-sources.txt
javac --release 17 -d build/installer-tests @build/core-manager-sources.txt test/wiiuu/core/CoreManagerTest.java
java -Djava.awt.headless=true -cp build/installer-tests wiiuu.core.CoreManagerTest
WIIUU_CI_COMMAND_4
    run_check "Test native cloudflared bundle and relay boundary" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_5'
case "$RUNNER_OS/$RUNNER_ARCH" in
  Windows/*) platform=windows-amd64; binary=cloudflared.exe ;;
  macOS/ARM64) platform=darwin-arm64; binary=cloudflared ;;
  macOS/*) platform=darwin-amd64; binary=cloudflared ;;
  Linux/*) platform=linux-amd64; binary=cloudflared ;;
esac
python3 tools/package-cloudflared.py "$platform"
"build/cloudflared/$platform/$binary" --version
javac --release 17 -d build/installer-tests src/wiiuu/net/CloudflaredRelay.java src/wiiuu/net/RelayGateway.java src/wiiuu/net/QrCode.java test/wiiuu/net/RelayTest.java
java -Djava.awt.headless=true -cp build/installer-tests wiiuu.net.RelayTest
WIIUU_CI_COMMAND_5
    ;;
  build)
    run_check "Test Node archive server and JavaScript plugins" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_6'
node --test archive/test/*.test.mjs test/plugins/*.test.cjs test/xcloud/*.test.cjs
WIIUU_CI_COMMAND_6
    run_check "Test real plugin browser against local fixtures" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_7'
mkdir -p build/plugin-host
cp resources/plugins/host/package*.json build/plugin-host/
npm ci --prefix build/plugin-host --no-audit --no-fund --engine-strict
# Chromium's sandbox cannot initialize on restricted CI hosts. Only this local fixture disables it.
build/plugin-host/node_modules/.bin/electron test/plugins/browser-smoke.cjs --no-sandbox --ozone-platform=headless --disable-gpu
ELECTRON_RUN_AS_NODE=1 build/plugin-host/node_modules/.bin/electron --test test/plugins/native.test.cjs
WIIUU_CI_COMMAND_7
    run_check "Validate portable Xbox WebRTC player dependencies and bundle" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_8'
mkdir -p build/xcloud-deps
cp resources/xcloud/player-entry.js build/xcloud-deps/player-entry.js
npm install --prefix build/xcloud-deps --no-save --no-fund --no-audit --ignore-scripts -- xal-node@1.1.6-beta2 xbox-xcloud-player@1.0.0-beta7 esbuild@0.27.7
node -e "const xal=require('./build/xcloud-deps/node_modules/xal-node'); if(!xal.Msal || !xal.TokenStore) throw Error('xal-node missing API')"
node build/xcloud-deps/node_modules/esbuild/bin/esbuild build/xcloud-deps/player-entry.js --bundle --platform=browser --format=iife --target=chrome110 --outfile=build/xcloud-deps/player.bundle.js
test -s build/xcloud-deps/player.bundle.js
WIIUU_CI_COMMAND_8
    run_check "Build and test the standalone UI library" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_9'
./ui-library/build.sh
ui_version="$(cat ui-library/VERSION)"
echo "UI_VERSION=$ui_version" >> "$GITHUB_ENV"
ui_jar="build/ui-library/WII-UU-UI-$ui_version.jar"
mkdir -p build/ui-example
javac --release 17 -cp "$ui_jar" -d build/ui-example docs/support/ui-library/UiDemo.java docs/support/ui-library/ThemeDemo.java docs/support/ui-library/MenuDemo.java docs/support/ui-library/AnimatedDemo.java test/wiiuu/ui/UiLibraryTest.java test/wiiuu/ui/AnimatedUiTest.java test/wiiuu/ui/UiControlsTest.java docs/support/ui-library/ControlsDemo.java
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" UiDemo --snapshot build/ui-example/demo.png
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" ThemeDemo --snapshot build/ui-example/themes.png
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" wiiuu.ui.UiLibraryTest
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" MenuDemo --snapshot build/ui-example/menu-light.png
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" MenuDemo --snapshot build/ui-example/menu-dark.png --dark
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" wiiuu.ui.AnimatedUiTest
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" AnimatedDemo --snapshot build/ui-example/mascot.png
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" AnimatedDemo --snapshot build/ui-example/visualizer.png --visualizer
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" wiiuu.ui.UiControlsTest
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" ControlsDemo --snapshot build/ui-example/controls-light.png
java -Djava.awt.headless=true -cp "$ui_jar:build/ui-example" ControlsDemo --snapshot build/ui-example/controls-dark.png --dark
test -s build/ui-example/controls-light.png
test -s build/ui-example/controls-dark.png
test -f build/ui-library/api/wiiuu/ui/Ui.html
test -f build/ui-library/api/wiiuu/ui/UiButton.html
test -s build/ui-example/mascot.png
test -s build/ui-example/visualizer.png
test -f build/ui-library/api/wiiuu/ui/AnimatedMascot.html
test -f build/ui-library/api/wiiuu/ui/AudioVisualizer.html
test -s build/ui-example/menu-light.png
test -s build/ui-example/menu-dark.png
test -f build/ui-library/api/wiiuu/ui/MenuPainter.html
test -s build/ui-example/themes.png
test -f build/ui-library/api/wiiuu/ui/ThemeScript.html
test -s build/ui-example/demo.png
test -f build/ui-library/api/wiiuu/engine/GameScreen.html
cp -R build/ui-library/api docs/support/ui-library/
WIIUU_CI_COMMAND_9
    run_check "Smoke test (render the menu headless)" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_10'
java -Djava.awt.headless=true -jar build/wiiuu.jar --home "$RUNNER_TEMP/wiiuu" \
  --snapshot "$RUNNER_TEMP/menu.png" --snapshot-view home
test -s "$RUNNER_TEMP/menu.png"
java -jar build/ChoccyMilk.jar --help
WIIUU_CI_COMMAND_10
    run_check "Test activated plugin apps in the dock" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_11'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/ui/PluginDockTest.java test/wiiuu/ui/NativePluginScreenTest.java test/wiiuu/ui/XboxCloudNativeScreenTest.java test/wiiuu/ui/XboxCloudFavoritesTest.java test/wiiuu/core/NativePluginBridgeTest.java test/wiiuu/core/XboxCloudResourceSyncTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.PluginDockTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/ui/GameAchievementActionTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.GameAchievementActionTest
java --add-modules jdk.httpserver -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.NativePluginScreenTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.XboxCloudNativeScreenTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.XboxCloudFavoritesTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.XboxCloudResourceSyncTest
test -s build/xcloud-ui-catalog-light.png
test -s build/xcloud-ui-search-keyboard-small.png
java -cp build/classes:build/tests wiiuu.core.NativePluginBridgeTest build/plugin-host/node_modules/electron/dist
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/XboxCloudInputTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.XboxCloudInputTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/net/XboxCloudPadBridgeTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.net.XboxCloudPadBridgeTest
WIIUU_CI_COMMAND_11
    run_check "Test USB backup, factory reset and restore" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_12'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/DemoDeviceTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.DemoDeviceTest
WIIUU_CI_COMMAND_12
    run_check "Test opt-in diagnostic consent and UI" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_13'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/DiagnosticsTest.java test/wiiuu/ui/DiagnosticsOverlayTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.DiagnosticsTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.DiagnosticsOverlayTest
WIIUU_CI_COMMAND_13
    run_check "Test multi-user profiles and sign-out UI" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_14'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/UserProfilesTest.java test/wiiuu/ui/SignOutPowerTest.java test/wiiuu/ui/UserSelectScreenTest.java test/wiiuu/input/LocalPadsHomeTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.UserProfilesTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/AchievementViewerTest.java
java --add-modules jdk.httpserver -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.AchievementViewerTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.SignOutPowerTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.UserSelectScreenTest
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.input.LocalPadsHomeTest
WIIUU_CONSOLE=1 java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.SignOutPowerTest
WIIUU_CI_COMMAND_14
    run_check "Test boot changelog controller input" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_15'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/ui/ChangelogControllerTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.ChangelogControllerTest
WIIUU_CI_COMMAND_15
    run_check "Test native Linux Wi-Fi list parsing" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_16'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/ui/LinuxNetworkPickerTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.ui.LinuxNetworkPickerTest
WIIUU_CI_COMMAND_16
    run_check "Test Choccy Milk dual screens and pointer input" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_17'
javac --release 22 -cp build/classes -d build/tests test/wiiuu/screen/ChoccyDualScreenTest.java
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.screen.ChoccyDualScreenTest
WIIUU_CI_COMMAND_17
    run_check "Test storage relocation and LAN migration" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_18'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/net/MigrationTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.net.MigrationTest
WIIUU_CI_COMMAND_18
    run_check "Test relay boundary and downloaded Linux helper" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_19'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/net/RelayTest.java test/wiiuu/net/RelayBundleTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.net.RelayTest
java -Djava.awt.headless=true -cp build/wiiuu.jar:build/tests wiiuu.net.RelayBundleTest
WIIUU_CI_COMMAND_19
    run_check "Test standalone bindings and shared DS online options" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_20'
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/ChoccySettingsTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.ChoccySettingsTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/BluetoothGamesTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.BluetoothGamesTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/BluetoothBridgeTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.BluetoothBridgeTest
javac --release 17 -cp build/classes -d build/tests test/wiiuu/core/BluetoothInteroperabilityTest.java
java -Djava.awt.headless=true -cp build/classes:build/tests wiiuu.core.BluetoothInteroperabilityTest build/wiiuu.jar build/ChoccyMilk.jar
WIIUU_CI_COMMAND_20
    run_check "Test GamePad screen and controller layouts" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_21'
node test/web/screen-choices.cjs
node test/web/controller-layouts.cjs
node test/web/xcloud-screen.cjs
WIIUU_CI_COMMAND_21
    run_check "Check shell scripts" bash -euo pipefail -s <<'WIIUU_CI_COMMAND_22'
bash -n install.sh emulators.sh build.sh package.sh docs/get.sh
WIIUU_CI_COMMAND_22
    ;;
  *)
    echo "Unknown suite: $suite (expected build, installer, native-startup)" >&2
    exit 2
    ;;
esac

printf '\nCI suite %s: %s passed, %s failed\n' "$suite" "$passed" "$failed"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### WII-UU CI: %s\n\n' "$suite"
    printf '%s passed, %s failed.\n\n' "$passed" "$failed"
    printf '| Check | Result |\n| --- | --- |\n'
    printf '%s\n' "${results[@]}"
  } >> "$GITHUB_STEP_SUMMARY"
fi
if (( failed > 0 )); then
  exit 1
fi
