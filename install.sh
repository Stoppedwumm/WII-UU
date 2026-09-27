#!/usr/bin/env bash
# WII-UU installer for Linux and macOS.
#
#   ./install.sh                  install for the current user
#   ./install.sh --with-emulators also install emulators from Flathub (Linux) and point WII-UU at them
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
    --no-shortcut) SHORTCUT=0 ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------------------- uninstall
if [ "$UNINSTALL" = 1 ]; then
  say "Removing $APP"
  rm -rf "$PREFIX" "$BIN/wiiuu" \
         "$HOME/.local/share/applications/wiiuu.desktop" \
         "$HOME/.local/share/icons/hicolor/256x256/apps/wiiuu.png" \
         "$HOME/Applications/WII-UU.app"
  ok "Removed. Your ROMs ($ROMS) and settings ($CONF_DIR) were kept."
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
exec java -Dsun.java2d.opengl=true -jar "$PREFIX/wiiuu.jar" "\$@"
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
    APPDIR="$HOME/Applications/WII-UU.app/Contents"
    mkdir -p "$APPDIR/MacOS" "$APPDIR/Resources"
    cat > "$APPDIR/MacOS/wiiuu" <<EOF
#!/bin/bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:/usr/local/opt/openjdk@21/bin:\$PATH"
exec java -Xdock:name=WII-UU -jar "$PREFIX/wiiuu.jar"
EOF
    chmod +x "$APPDIR/MacOS/wiiuu"
    cat > "$APPDIR/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>WII-UU</string>
  <key>CFBundleIdentifier</key><string>io.github.wiiuu</string>
  <key>CFBundleExecutable</key><string>wiiuu</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
EOF
    ok "App: ~/Applications/WII-UU.app"
    warn "macOS: allow 'Accessibility' for Java in System Settings > Privacy & Security so the GamePad can type into emulators."
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

# ---------------------------------------------------------------------------- emulators (optional)
if [ "$EMULATORS" = 1 ]; then
  if [ "$OS" = "Darwin" ]; then
    warn "--with-emulators is Linux-only. On macOS install emulators from their websites, then set paths in WII-UU Settings (F1)."
  elif ! command -v flatpak >/dev/null 2>&1; then
    warn "flatpak not found; install it (https://flathub.org/setup) or install emulators with your package manager."
  else
    say "Installing emulators from Flathub (this can take a while)"
    flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo || true
    # system(s) | flatpak id | arguments
    EMUS="snes|com.snes9x.Snes9x|{rom}
gb gba|io.mgba.mGBA|-f {rom}
n64|com.github.Rosalie241.RMG|{rom}
gc wii|org.DolphinEmu.dolphin-emu|-b -e {rom}
nds|net.kuribo64.melonDS|-f {rom}
3ds|org.azahar_emu.Azahar|{rom}
wiiu|info.cemu.Cemu|-f -g {rom}
switch|io.github.ryubing.Ryujinx|--fullscreen {rom}
dc|org.flycast.Flycast|{rom}
ps1|org.duckstation.DuckStation|-fullscreen -batch -- {rom}
ps2|net.pcsx2.PCSX2|-fullscreen -batch -- {rom}
psp|org.ppsspp.PPSSPP|--fullscreen {rom}
ps3|net.rpcs3.RPCS3|--no-gui {rom}
ps4|net.shadps4.shadPS4|-g {rom}"
    while IFS='|' read -r systems id args; do
      if flatpak install --user -y --noninteractive flathub "$id" >/dev/null 2>&1 || flatpak info "$id" >/dev/null 2>&1; then
        for s in $systems; do set_conf "system.$s.command" "flatpak run $id $args" force; done
        ok "$id  ->  $systems"
      else
        warn "could not install $id (skipped; set the $systems emulator in Settings)"
      fi
    done <<< "$EMUS"
    warn "NES/Sega: install Mesen or Mednafen with your package manager (e.g. 'sudo apt install mednafen')."
  fi
fi

# ---------------------------------------------------------------------------- network hint
if command -v ufw >/dev/null 2>&1 && sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
  warn "Firewall active: run 'sudo ufw allow $PORT/tcp' so phones can reach the GamePad server."
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  warn "Firewall active: run 'sudo firewall-cmd --add-port=$PORT/tcp --permanent && sudo firewall-cmd --reload'."
fi
if [ "$OS" = "Linux" ] && [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
  warn "Wayland session: GamePad key input reaches X11/XWayland emulator windows. If an emulator ignores it, run it with QT_QPA_PLATFORM=xcb or log into an X11 session."
fi

printf '\n%sDone!%s Start WII-UU with %swiiuu%s (or from your app menu).\n' "$c_green" "$c_off" "$c_blue" "$c_off"
printf 'On the TV press %s+%s / F2 and scan the QR code with your phone to use it as a GamePad.\n\n' "$c_blue" "$c_off"
