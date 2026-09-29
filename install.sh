#!/usr/bin/env bash
# WII-UU installer for Linux and macOS.
#
#   ./install.sh                  install for the current user
#   ./install.sh --with-emulators also install emulators (Linux, incl. Raspberry Pi): official prebuilt
#                                 releases for this CPU, built from source where none exist;
#                                 --with-all-emulators also builds heavy ones that have no release
#   ./install.sh --console-mode   Linux: also make this computer a WII-UU console (boots into WII-UU,
#                                 SteamOS style; Power → Desktop Mode switches to the desktop)
#   ./install.sh --uninstall      remove WII-UU (keeps your ROMs and settings)
#
# Options: --prefix DIR  --bin DIR  --roms DIR  --port N  --yes  --no-shortcut
set -euo pipefail

APP="WII-UU"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OS="$(uname -s)"
PREFIX="${XDG_DATA_HOME:-$HOME/.local/share}/wiiuu"
BIN="$HOME/.local/bin"
ROMS="$HOME/WiiUU/roms"
CONF_DIR="${WIIUU_HOME:-$HOME/.wiiuu}"
PORT=8080
YES=0
EMULATORS=0
SHORTCUT=1
UNINSTALL=0
CONSOLE=0
[ "$OS" = "Darwin" ] && PREFIX="$HOME/Library/Application Support/WII-UU"

SYSTEMS="nes snes gb n64 gba gc nds wii 3ds wiiu switch sms genesis saturn dc ps1 ps2 psp ps3 ps4"

c_blue=$'\033[1;36m'; c_green=$'\033[1;32m'; c_yellow=$'\033[1;33m'; c_red=$'\033[1;31m'; c_off=$'\033[0m'
[ -t 1 ] || { c_blue=; c_green=; c_yellow=; c_red=; c_off=; }
say()  { printf '%s==>%s %s\n' "$c_blue" "$c_off" "$*"; }
ok()   { printf '%s  ✓%s %s\n' "$c_green" "$c_off" "$*"; }
warn() { printf '%s  !%s %s\n' "$c_yellow" "$c_off" "$*"; }
die()  { printf '%sError:%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }
ask()  { [ "$YES" = 1 ] && return 0; read -r -p "$1 [y/N] " a </dev/tty || return 1; [[ "$a" =~ ^[Yy] ]]; }

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift ;;
    --bin) BIN="$2"; shift ;;
    --roms) ROMS="$2"; shift ;;
    --port) PORT="$2"; shift ;;
    --yes|-y) YES=1 ;;
    --with-emulators) EMULATORS=1 ;;
    --with-all-emulators) EMULATORS=all ;;
    --no-shortcut) SHORTCUT=0 ;;
    --uninstall) UNINSTALL=1 ;;
    --console-mode) CONSOLE=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------------------- uninstall
if [ "$UNINSTALL" = 1 ]; then
  say "Removing $APP"
  rm -rf "$PREFIX" "$BIN/wiiuu" "$BIN/wiiuu-emulators" "$BIN/wiiuu-console" \
         "$HOME/.local/share/applications/wiiuu.desktop" \
         "$HOME/.local/share/icons/hicolor/256x256/apps/wiiuu.png" \
         "$HOME/Applications/WII-UU.app"
  ok "Removed. Your ROMs ($ROMS) and settings ($CONF_DIR) were kept."
  EMU_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/wiiuu-emulators"
  [ -d "$EMU_ROOT" ] && ok "Emulators built from source were kept in $EMU_ROOT (delete it to free the space)."
  exit 0
fi

printf '\n%s  WII-UU installer%s\n\n' "$c_blue" "$c_off"

# ---------------------------------------------------------------------------- java
java_major() {
  command -v java >/dev/null 2>&1 || { echo 0; return; }
  java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

install_java() {
  if [ "$OS" = "Darwin" ]; then
    command -v brew >/dev/null 2>&1 || die "Install Java 17+ from https://adoptium.net (or install Homebrew) and re-run."
    brew install openjdk@21 && sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
  elif command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y openjdk-21-jre || sudo apt-get install -y openjdk-17-jre
  elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y java-21-openjdk || sudo dnf install -y java-17-openjdk
  elif command -v pacman >/dev/null 2>&1; then sudo pacman -S --needed --noconfirm jre-openjdk
  elif command -v zypper >/dev/null 2>&1; then sudo zypper install -y java-21-openjdk || sudo zypper install -y java-17-openjdk
  else die "Please install Java 17 or newer (e.g. from https://adoptium.net) and re-run."
  fi
}

say "Checking Java"
JV="$(java_major)"
if [ "${JV:-0}" -lt 17 ] 2>/dev/null; then
  warn "Java 17 or newer is required (found: ${JV:-none})."
  if ask "Install Java now?"; then install_java; else die "Java 17+ is required."; fi
  JV="$(java_major)"
  [ "${JV:-0}" -ge 17 ] || die "Java install did not work; install Java 17+ manually."
fi
ok "Java $JV"

# ---------------------------------------------------------------------------- the jar
say "Locating $APP"
JAR=""
for c in "$HERE/wiiuu.jar" "$HERE/build/wiiuu.jar"; do [ -f "$c" ] && { JAR="$c"; break; }; done
if [ -z "$JAR" ]; then
  [ -x "$HERE/build.sh" ] || die "wiiuu.jar not found next to install.sh"
  command -v javac >/dev/null 2>&1 || die "Building from source needs a JDK (javac). Install one, or use the release zip."
  "$HERE/build.sh"
  JAR="$HERE/build/wiiuu.jar"
fi
ok "$JAR"

# ---------------------------------------------------------------------------- files
say "Installing to $PREFIX"
mkdir -p "$PREFIX" "$BIN"
cp "$JAR" "$PREFIX/wiiuu.jar"
java -jar "$PREFIX/wiiuu.jar" --write-icon "$PREFIX/wiiuu.png" 2>/dev/null || true
cat > "$BIN/wiiuu" <<EOF
#!/usr/bin/env bash
exec java -jar "$PREFIX/wiiuu.jar" "\$@"
EOF
chmod +x "$BIN/wiiuu"
ok "Launcher: $BIN/wiiuu"
case ":$PATH:" in *":$BIN:"*) ;; *) warn "$BIN is not on your PATH; add it or start WII-UU from the app menu." ;; esac

say "Creating ROM folders in $ROMS"
for s in $SYSTEMS; do mkdir -p "$ROMS/$s"; done
ok "Drop games into $ROMS/<system> (nes, snes, n64, gc, wii, wiiu, switch, ps1 ... ps4)"

# settings: set only keys the user has not customised yet
CONF="$CONF_DIR/config.properties"
mkdir -p "$CONF_DIR"; touch "$CONF"
set_conf() {  # key value [force]
  local key="$1" val="$2"
  if grep -q "^${key//./\\.}=" "$CONF"; then
    [ "${3:-}" = force ] || return 0
    grep -v "^${key//./\\.}=" "$CONF" > "$CONF.tmp" && mv "$CONF.tmp" "$CONF"
  fi
  # java.util.Properties treats backslash as escape, so double them
  printf '%s=%s\n' "$key" "${val//\\/\\\\}" >> "$CONF"
}
set_conf roms.base "$ROMS"
set_conf server.port "$PORT"
ok "Settings: $CONF"

# ---------------------------------------------------------------------------- shortcuts
if [ "$SHORTCUT" = 1 ]; then
  if [ "$OS" = "Darwin" ]; then
    # A real app (native launcher + bundled Java runtime, made with the JDK's jpackage) lets macOS attach
    # Screen Recording (GamePad screen) and Accessibility (GamePad keys) to "WII-UU"; with a script app
    # those permissions have nothing to attach to and capture just comes back black.
    APP="$HOME/Applications/WII-UU.app"
    JPACKAGE="$(command -v jpackage 2>/dev/null || true)"
    if [ -z "$JPACKAGE" ] && [ -x "$(/usr/libexec/java_home 2>/dev/null)/bin/jpackage" ]; then JPACKAGE="$(/usr/libexec/java_home)/bin/jpackage"; fi
    for jp in /opt/homebrew/opt/openjdk*/bin/jpackage /usr/local/opt/openjdk*/bin/jpackage; do
      if [ -z "$JPACKAGE" ] && [ -x "$jp" ]; then JPACKAGE="$jp"; fi
    done
    mkdir -p "$HOME/Applications"
    built=0
    if [ -n "$JPACKAGE" ]; then
      say "Building WII-UU.app (native macOS app, about a minute)"
      stage="$(mktemp -d)"; cp "$PREFIX/wiiuu.jar" "$stage/"
      icon=()
      if [ -f "$PREFIX/wiiuu.png" ] && command -v iconutil >/dev/null 2>&1; then
        set_dir="$stage/WII-UU.iconset"; mkdir -p "$set_dir"
        for sz in 16 32 128 256; do
          sips -z $sz $sz "$PREFIX/wiiuu.png" --out "$set_dir/icon_${sz}x${sz}.png" >/dev/null 2>&1
          sips -z $((sz*2)) $((sz*2)) "$PREFIX/wiiuu.png" --out "$set_dir/icon_${sz}x${sz}@2x.png" >/dev/null 2>&1
        done
        iconutil -c icns "$set_dir" -o "$stage/WII-UU.icns" 2>/dev/null && icon=(--icon "$stage/WII-UU.icns")
      fi
      version="$(java -jar "$PREFIX/wiiuu.jar" --version 2>/dev/null | awk '{print $2}')"
      rm -rf "$APP"
      if "$JPACKAGE" --type app-image --name WII-UU --app-version "${version:-1.0.0}" --input "$stage" \
           --main-jar wiiuu.jar --main-class wiiuu.Main --dest "$HOME/Applications" \
           --mac-package-identifier io.github.wiiuu ${icon[@]+"${icon[@]}"} >/dev/null 2>&1; then
        built=1
        ok "App: ~/Applications/WII-UU.app"
      else
        warn "jpackage failed; using a simple launcher app instead"
      fi
      rm -rf "$stage"
    fi
    if [ "$built" = 0 ]; then
      APPDIR="$APP/Contents"
      mkdir -p "$APPDIR/MacOS" "$APPDIR/Resources"
      printf '#!/bin/bash\nexport PATH="/opt/homebrew/opt/openjdk@21/bin:/usr/local/opt/openjdk@21/bin:$PATH"\nexec java -Xdock:name=WII-UU -jar "%s/wiiuu.jar"\n' "$PREFIX" > "$APPDIR/MacOS/wiiuu"
      chmod +x "$APPDIR/MacOS/wiiuu"
      printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' \
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
        '<plist version="1.0"><dict>' \
        '  <key>CFBundleName</key><string>WII-UU</string>' \
        '  <key>CFBundleIdentifier</key><string>io.github.wiiuu</string>' \
        '  <key>CFBundleExecutable</key><string>wiiuu</string>' \
        '  <key>CFBundlePackageType</key><string>APPL</string>' \
        '  <key>NSHighResolutionCapable</key><true/>' \
        '</dict></plist>' > "$APPDIR/Info.plist"
      ok "App: ~/Applications/WII-UU.app (simple launcher; install a JDK with jpackage for the native app)"
    fi
    say "macOS permissions (needed for the GamePad screen and GamePad buttons)"
    warn "In System Settings > Privacy & Security, turn on WII-UU under BOTH 'Screen Recording' and 'Accessibility'."
    warn "Start WII-UU from ~/Applications/WII-UU.app (not the terminal) so the permissions belong to WII-UU."
    open "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture" 2>/dev/null || true
  else
    mkdir -p "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/256x256/apps"
    [ -f "$PREFIX/wiiuu.png" ] && cp "$PREFIX/wiiuu.png" "$HOME/.local/share/icons/hicolor/256x256/apps/wiiuu.png"
    cat > "$HOME/.local/share/applications/wiiuu.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=WII-UU
Comment=Wii U style emulator launcher with phone GamePad
Exec=$BIN/wiiuu
Icon=wiiuu
Terminal=false
Categories=Game;Emulator;
EOF
    command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
    ok "Menu entry: WII-UU"
  fi
fi

# ---------------------------------------------------------------------------- console mode tool (Linux)
if [ "$OS" = "Linux" ] && [ -f "$HERE/console/wiiuu-console" ]; then
  cp "$HERE/console/wiiuu-console" "$PREFIX/wiiuu-console"; chmod +x "$PREFIX/wiiuu-console"
  printf '#!/usr/bin/env bash\nexec "%s/wiiuu-console" "$@"\n' "$PREFIX" > "$BIN/wiiuu-console"
  chmod +x "$BIN/wiiuu-console"
fi

# ---------------------------------------------------------------------------- emulators (optional)
# Always install the builder so `wiiuu-emulators --update` works later.
if [ -f "$HERE/emulators.sh" ]; then
  cp "$HERE/emulators.sh" "$PREFIX/emulators.sh"; chmod +x "$PREFIX/emulators.sh"
  printf '#!/usr/bin/env bash\nexec "%s/emulators.sh" "$@"\n' "$PREFIX" > "$BIN/wiiuu-emulators"
  chmod +x "$BIN/wiiuu-emulators"
fi
if [ "$EMULATORS" != 0 ]; then
  if [ "$OS" = "Darwin" ]; then
    warn "The emulator installer is Linux-only. On macOS install emulators from their websites, then set paths in WII-UU Settings (F1)."
  else
    say "Installing emulators for $(uname -m) (official releases, source builds where none exist)"
    extra=""; [ "$EMULATORS" = all ] && extra="--all"; [ "$YES" = 1 ] && extra="$extra --yes"
    # shellcheck disable=SC2086
    WIIUU_HOME="$CONF_DIR" "$PREFIX/emulators.sh" $extra || warn "some emulators failed to build; see the summary above"
  fi
fi

# ---------------------------------------------------------------------------- GamePad extras (Linux)
# ffmpeg: fast GamePad screen streaming · x11-utils: finds emulator windows · python3 + uinput:
# phones become real virtual controllers that every emulator detects on its own · pulseaudio-utils
# (parec): the PC's sound on the phone (PulseAudio and PipeWire)
if [ "$OS" = "Linux" ]; then
  missing=""
  command -v ffmpeg >/dev/null 2>&1 || missing="$missing ffmpeg"
  command -v xwininfo >/dev/null 2>&1 || command -v xdotool >/dev/null 2>&1 || missing="$missing x11-utils"
  command -v python3 >/dev/null 2>&1 || missing="$missing python3"
  command -v parec >/dev/null 2>&1 || missing="$missing pulseaudio-utils"
  if [ -n "$missing" ]; then
    if command -v apt-get >/dev/null 2>&1 && ask "Install GamePad extras ($missing ) for a smooth GamePad screen, sound and real controllers?"; then
      sudo apt-get install -y $missing || warn "could not install:$missing"
    else
      warn "For the best GamePad experience install:$missing"
    fi
  fi
  if [ ! -f /etc/udev/rules.d/60-wiiuu-uinput.rules ]; then
    if ask "Let WII-UU create virtual controllers (loads the uinput module, adds a udev rule; needs sudo)?"; then
      if printf 'KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"\n' \
           | sudo tee /etc/udev/rules.d/60-wiiuu-uinput.rules >/dev/null \
         && echo uinput | sudo tee /etc/modules-load.d/wiiuu-uinput.conf >/dev/null; then
        sudo modprobe uinput 2>/dev/null || true
        sudo udevadm control --reload-rules 2>/dev/null || true
        sudo udevadm trigger --sysname-match=uinput 2>/dev/null || true
        ok "Virtual controllers enabled (if WII-UU still says otherwise, log out and back in once)"
      else
        warn "could not set up uinput; phones will type keyboard keys instead"
      fi
    fi
  fi
fi

# ---------------------------------------------------------------------------- GamePad screen + sound (macOS)
# WII-UU compiles its ScreenCaptureKit helper (smooth GamePad screen, sound) with the Command Line Tools
if [ "$OS" = "Darwin" ] && ! xcode-select -p >/dev/null 2>&1; then
  if ask "Install Apple's Command Line Tools for a smooth GamePad screen and sound on the phone?"; then
    xcode-select --install 2>/dev/null || true
    ok "Finish the Command Line Tools installer that just opened, then start WII-UU"
  else
    warn "For a smooth GamePad screen and sound install the Command Line Tools: xcode-select --install"
  fi
fi

# ---------------------------------------------------------------------------- network hint
if command -v ufw >/dev/null 2>&1 && sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
  warn "Firewall active: run 'sudo ufw allow $PORT/tcp && sudo ufw allow 8443/tcp' so phones can reach the GamePad (8443 = secure address for gyro)."
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  warn "Firewall active: run 'sudo firewall-cmd --add-port=$PORT/tcp --add-port=8443/tcp --permanent && sudo firewall-cmd --reload'."
fi
if [ "$OS" = "Linux" ] && [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
  warn "Wayland session: GamePad keys, screen streaming and touch work with X11/XWayland windows. If an emulator ignores it, run it with QT_QPA_PLATFORM=xcb or log into an X11 session."
fi

# ---------------------------------------------------------------------------- console mode (optional)
if [ "$CONSOLE" = 1 ]; then
  if [ "$OS" != "Linux" ]; then
    warn "Console mode is for Linux (PCs and Raspberry Pi)."
  else
    say "Setting up console mode (needs sudo)"
    cflags="--user $(id -un)"; [ "$YES" = 1 ] && cflags="$cflags --yes"
    # shellcheck disable=SC2086
    sudo env XDG_SESSION_DESKTOP="${XDG_SESSION_DESKTOP:-}" "$PREFIX/wiiuu-console" install --session-hint "${XDG_SESSION_DESKTOP:-${DESKTOP_SESSION:-}}" $cflags \
      || warn "console mode was not set up; try again later with: sudo wiiuu-console install"
  fi
fi

printf '\n%sDone!%s Start WII-UU with %swiiuu%s (or from your app menu).\n' "$c_green" "$c_off" "$c_blue" "$c_off"
printf 'On the TV press %s+%s / F2 and scan the QR code with your phone to use it as a GamePad.\n\n' "$c_blue" "$c_off"
