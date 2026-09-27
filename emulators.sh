#!/usr/bin/env bash
# Builds emulators from their latest source (git master / newest release) natively for this
# machine - x86-64 PCs and 64-bit ARM boards such as the Raspberry Pi 4/5 - and points WII-UU
# at the results. No flatpak, no containers.
#
#   ./emulators.sh                 build the "light" set (runs well on a Raspberry Pi)
#   ./emulators.sh --all           also build the heavy ones (Dolphin, Cemu, Ryujinx, RPCS3 ...)
#   ./emulators.sh --only ppsspp,dolphin
#   ./emulators.sh --update        pull the newest source of everything built before and rebuild
#   ./emulators.sh --list          show what can be built on this machine
#
# Options: --jobs N  --no-deps (skip apt)  --yes  --prefix DIR  --keep-going (default)
set -uo pipefail

ROOT="${WIIUU_EMU_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/wiiuu-emulators}"
SRC="$ROOT/emu-src"
EMU="$ROOT/emulators"
CONF_DIR="${WIIUU_HOME:-$HOME/.wiiuu}"
CONF="$CONF_DIR/config.properties"
LOGS="$CONF_DIR/logs/build"
ARCH="$(uname -m)"
YES=0; DEPS=1; ALL=0; UPDATE=0; LIST=0; ONLY=""; JOBS=""

c_blue=$'\033[1;36m'; c_green=$'\033[1;32m'; c_yellow=$'\033[1;33m'; c_red=$'\033[1;31m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
[ -t 1 ] || { c_blue=; c_green=; c_yellow=; c_red=; c_dim=; c_off=; }
say()  { printf '%s==>%s %s\n' "$c_blue" "$c_off" "$*"; }
ok()   { printf '%s  ✓%s %s\n' "$c_green" "$c_off" "$*"; }
warn() { printf '%s  !%s %s\n' "$c_yellow" "$c_off" "$*"; }
die()  { printf '%sError:%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }
ask()  { [ "$YES" = 1 ] && return 0; read -r -p "$1 [y/N] " a </dev/tty || return 1; [[ "$a" =~ ^[Yy] ]]; }

while [ $# -gt 0 ]; do
  case "$1" in
    --all) ALL=1 ;;
    --only) ONLY="${2//,/ }"; shift ;;
    --update) UPDATE=1 ;;
    --list) LIST=1 ;;
    --jobs|-j) JOBS="$2"; shift ;;
    --no-deps) DEPS=0 ;;
    --yes|-y) YES=1 ;;
    --prefix) ROOT="$2"; SRC="$ROOT/emu-src"; EMU="$ROOT/emulators"; shift ;;
    --keep-going) ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
  shift
done

# ------------------------------------------------------------------------------ catalogue
# name | WII-UU systems | tier | architectures | binary (relative to install dir) | arguments
# "light" builds take minutes and run well on a Pi 4/5; "heavy" ones take 30 min to hours.
CATALOGUE="fceux|nes|light|any|bin/fceux|{rom}
snes9x|snes|light|any|bin/snes9x-gtk|{rom}
mgba|gb gba|light|any|bin/mgba-qt|-f {rom}
mupen64plus|n64|light|any|bin/mupen64plus|--fullscreen --corelib @E@/lib/libmupen64plus.so.2 --plugindir @E@/lib/mupen64plus --datadir @E@/share/mupen64plus {rom}
mednafen|sms genesis saturn ps1|light|any|bin/mednafen|{rom}
melonds|nds|light|any|bin/melonDS|-f {rom}
ppsspp|psp|light|any|PPSSPPSDL|--fullscreen {rom}
flycast|dc|light|any|bin/flycast|{rom}
dolphin|gc wii|heavy|x86_64 aarch64|bin/dolphin-emu|-b -e {rom}
azahar|3ds|heavy|x86_64 aarch64|bin/azahar|{rom}
cemu|wiiu|heavy|x86_64 aarch64|Cemu_release|-f -g {rom}
ryujinx|switch|heavy|x86_64 aarch64|Ryujinx|--fullscreen {rom}
duckstation|ps1|heavy|x86_64 aarch64|bin/duckstation-qt|-fullscreen -batch -- {rom}
rpcs3|ps3|heavy|x86_64 aarch64|bin/rpcs3|--no-gui {rom}
shadps4|ps4|heavy|x86_64|shadps4|-g {rom}"

field() { printf '%s\n' "$CATALOGUE" | awk -F'|' -v n="$1" -v f="$2" '$1 == n { print $f }'; }
supported() { local a; a="$(field "$1" 4)"; [ "$a" = any ] || [[ " $a " == *" $ARCH "* ]]; }

# ------------------------------------------------------------------------------ list / selection
if [ "$LIST" = 1 ]; then
  printf '%-12s %-24s %-6s %s\n' EMULATOR SYSTEMS TIER "ON THIS MACHINE ($ARCH)"
  while IFS='|' read -r n sys tier arch _ _; do
    if supported "$n"; then st="yes"; [ -f "$EMU/$n/.built" ] && st="built ($(cat "$EMU/$n/.built"))"; else st="no (needs $arch)"; fi
    printf '%-12s %-24s %-6s %s\n' "$n" "$sys" "$tier" "$st"
  done <<< "$CATALOGUE"
  echo; echo "PCSX2 (PS2) is not built here: it only runs on x86-64 and needs its own dependency toolchain;"
  echo "on a PC use the official AppImage from pcsx2.net and set its path in WII-UU Settings."
  exit 0
fi

SELECTED=""
if [ -n "$ONLY" ]; then
  for n in $ONLY; do [ -n "$(field "$n" 1)" ] || die "unknown emulator '$n' (see --list)"; SELECTED="$SELECTED $n"; done
elif [ "$UPDATE" = 1 ]; then
  for n in $(printf '%s\n' "$CATALOGUE" | cut -d'|' -f1); do [ -f "$EMU/$n/.built" ] && SELECTED="$SELECTED $n"; done
  [ -n "$SELECTED" ] || die "nothing built yet - run without --update first"
else
  while IFS='|' read -r n _ tier _ _ _; do
    { [ "$tier" = light ] || [ "$ALL" = 1 ]; } && SELECTED="$SELECTED $n"
  done <<< "$CATALOGUE"
fi
TODO=""
for n in $SELECTED; do
  if supported "$n"; then TODO="$TODO $n"; else warn "$n does not support $ARCH - skipped"; fi
done
[ -n "$TODO" ] || die "nothing to build"

case "$ARCH" in
  x86_64|aarch64) ;;
  armv7l|armv6l) warn "32-bit ARM OS detected. Most emulators need a 64-bit OS; on a Raspberry Pi install the 64-bit Raspberry Pi OS." ;;
  *) warn "untested architecture $ARCH" ;;
esac

# parallel jobs: one per ~1.5 GB of RAM so big C++ builds don't run the Pi out of memory
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 4096)
CPUS=$(nproc 2>/dev/null || echo 2)
if [ -z "$JOBS" ]; then
  JOBS=$(( MEM_MB / 1500 )); [ "$JOBS" -lt 1 ] && JOBS=1; [ "$JOBS" -gt "$CPUS" ] && JOBS=$CPUS
fi

printf '\n%s  WII-UU emulator builder%s  %s%s, %s CPUs, %s MB RAM, %s parallel jobs%s\n\n' \
  "$c_blue" "$c_off" "$c_dim" "$ARCH" "$CPUS" "$MEM_MB" "$JOBS" "$c_off"
say "Will build:$TODO"
for n in $TODO; do
  if [ "$(field "$n" 3)" = heavy ] && [ "$MEM_MB" -lt 6000 ]; then
    warn "$n is a large build; with ${MEM_MB} MB RAM make sure you have >= 4 GB swap (Raspberry Pi OS: CONF_SWAPSIZE in /etc/dphys-swapfile)."
    break
  fi
done
ask "Continue?" || exit 1

# ------------------------------------------------------------------------------ dependencies (apt)
APT_COMMON="build-essential git cmake ninja-build pkg-config curl ca-certificates xz-utils unzip zip tar file"
apt_deps() {
  case "$1" in
    fceux) echo "qtbase5-dev libqt5opengl5-dev libsdl2-dev zlib1g-dev libminizip-dev libarchive-dev liblua5.1-0-dev" ;;
    snes9x) echo "libgtkmm-3.0-dev libsdl2-dev libepoxy-dev libminizip-dev libx11-dev libxrandr-dev libxext-dev libpulse-dev libasound2-dev portaudio19-dev libwayland-dev gettext python3" ;;
    mgba) echo "qtbase5-dev qtmultimedia5-dev qttools5-dev qttools5-dev-tools libsdl2-dev libzip-dev libedit-dev libelf-dev libpng-dev libsqlite3-dev libepoxy-dev" ;;
    mupen64plus) echo "libsdl2-dev libpng-dev zlib1g-dev libfreetype-dev libgl-dev libglu1-mesa-dev libspeexdsp-dev libsamplerate0-dev nasm" ;;
    mednafen) echo "libsdl2-dev zlib1g-dev libasound2-dev libsndfile1-dev libflac-dev libvorbis-dev libzstd-dev" ;;
    melonds) echo "qt6-base-dev qt6-base-private-dev qt6-multimedia-dev libqt6svg6-dev libqt6opengl6-dev libsdl2-dev libarchive-dev libenet-dev libzstd-dev libfaad-dev extra-cmake-modules" ;;
    ppsspp) echo "libsdl2-dev libsdl2-ttf-dev libgl1-mesa-dev libglu1-mesa-dev libvulkan-dev libfontconfig1-dev libcurl4-openssl-dev python3" ;;
    flycast) echo "libsdl2-dev libcurl4-openssl-dev libudev-dev libzip-dev libgl1-mesa-dev libvulkan-dev libasound2-dev libpulse-dev libao-dev libminiupnpc-dev libflac-dev" ;;
    dolphin) echo "qt6-base-dev qt6-base-private-dev libqt6svg6-dev libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libxi-dev libxrandr-dev libudev-dev libevdev-dev libsfml-dev libminiupnpc-dev libmbedtls-dev libcurl4-openssl-dev libhidapi-dev libsystemd-dev libbluetooth-dev libasound2-dev libpulse-dev libpugixml-dev libbz2-dev libzstd-dev liblzo2-dev libpng-dev libusb-1.0-0-dev gettext" ;;
    azahar) echo "clang lld qt6-base-dev qt6-base-private-dev qt6-multimedia-dev qt6-tools-dev qt6-tools-dev-tools libqt6opengl6-dev libsdl2-dev libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libswresample-dev libssl-dev libusb-1.0-0-dev libudev-dev glslang-tools" ;;
    cemu) echo "clang libbluetooth-dev libgcrypt20-dev libgl1-mesa-dev libgtk-3-dev libpulse-dev libsecret-1-dev libsystemd-dev libudev-dev libusb-1.0-0-dev freeglut3-dev autoconf automake libtool nasm" ;;
    ryujinx) echo "libicu-dev libssl-dev libsdl2-dev" ;;
    duckstation) echo "clang lld llvm nasm patchelf extra-cmake-modules libasound2-dev libcurl4-openssl-dev libdbus-1-dev libdecor-0-dev libegl-dev libevdev-dev libfontconfig-dev libfreetype-dev libgtk-3-dev libgudev-1.0-dev libharfbuzz-dev libinput-dev libopengl-dev libpipewire-0.3-dev libpulse-dev libssl-dev libudev-dev libwayland-dev libx11-dev libx11-xcb-dev libxcb1-dev libxcb-composite0-dev libxcb-cursor-dev libxcb-damage0-dev libxcb-glx0-dev libxcb-icccm4-dev libxcb-image0-dev libxcb-keysyms1-dev libxcb-present-dev libxcb-randr0-dev libxcb-render0-dev libxcb-render-util0-dev libxcb-shape0-dev libxcb-shm0-dev libxcb-sync-dev libxcb-util-dev libxcb-xfixes0-dev libxcb-xinput-dev libxcb-xkb-dev libxext-dev libxkbcommon-x11-dev libxrandr-dev zlib1g-dev" ;;
    rpcs3) echo "clang lld libasound2-dev libpulse-dev libopenal-dev libglew-dev zlib1g-dev libedit-dev libvulkan-dev libudev-dev libevdev-dev libsdl2-dev libjack-jackd2-dev libsndio-dev qt6-base-dev qt6-base-private-dev qt6-multimedia-dev libqt6svg6-dev libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libswresample-dev python3" ;;
    shadps4) echo "clang lld libasound2-dev libpulse-dev libopenal-dev libssl-dev zlib1g-dev libedit-dev libudev-dev libevdev-dev libsdl2-dev libjack-jackd2-dev libsndio-dev libvulkan-dev libpng-dev libxtst-dev libxrandr-dev" ;;
  esac
}

SUDO=sudo; [ "$(id -u)" = 0 ] && SUDO=""
if [ "$DEPS" = 1 ]; then
  if command -v apt-get >/dev/null 2>&1; then
    say "Installing build dependencies (apt)"
    want="$APT_COMMON"; for n in $TODO; do want="$want $(apt_deps "$n")"; done
    $SUDO apt-get update -qq || warn "apt-get update had errors"
    # only ask apt for packages this distro actually has (names differ between releases)
    pkgs=""; missing=""
    for p in $(printf '%s\n' $want | sort -u); do
      [ "$p" = nasm ] && [ "$ARCH" != x86_64 ] && continue
      if apt-cache show "$p" >/dev/null 2>&1; then pkgs="$pkgs $p"; else missing="$missing $p"; fi
    done
    [ -n "$missing" ] && warn "not in your apt sources (skipped):$missing"
    # shellcheck disable=SC2086
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgs || warn "some packages failed to install; builds may fail"
  else
    warn "No apt found. Install the build dependencies listed in each emulator's BUILD/README yourself"
    warn "(compiler, cmake, ninja, git, SDL2, Qt 5 and Qt 6 dev packages); trying to build anyway."
  fi
fi
for t in git cmake make cc c++; do command -v "$t" >/dev/null 2>&1 || die "'$t' is missing - install build tools first"; done

# ------------------------------------------------------------------------------ helpers
mkdir -p "$SRC" "$EMU" "$LOGS" "$CONF_DIR"; touch "$CONF"

# clone or fast-forward to the newest commit (shallow, to save space and time on a Pi)
fetch_git() {  # dir url
  local dir="$SRC/$1" url="$2"
  if [ -d "$dir/.git" ]; then
    git -C "$dir" fetch --depth 1 origin HEAD || return 1
    git -C "$dir" reset --hard FETCH_HEAD || return 1
  else
    rm -rf "$dir"
    git clone --depth 1 "$url" "$dir" || return 1
  fi
  echo "source: $url @ $(git -C "$dir" rev-parse --short HEAD)"
  git -C "$dir" submodule sync --recursive
  git -C "$dir" submodule update --init --recursive --depth 1 --jobs 4 \
    || git -C "$dir" submodule update --init --recursive --jobs 4 || return 1
}

cmake_build() {  # srcdir builddir [cmake args...]  -> configures, builds (Release)
  local s="$1" b="$2"; shift 2
  cmake -S "$s" -B "$b" -G Ninja -DCMAKE_BUILD_TYPE=Release "$@"
  cmake --build "$b" --parallel "$JOBS"
}

# ------------------------------------------------------------------------------ builders
# Each builder installs into $E (= $EMU/<name>) so that "$E/<binary from catalogue>" exists.

build_fceux() {
  fetch_git fceux https://github.com/TASEmulators/fceux.git
  cmake_build "$SRC/fceux" "$SRC/fceux/build" -DCMAKE_INSTALL_PREFIX="$E"
  cmake --install "$SRC/fceux/build"
}

build_snes9x() {
  fetch_git snes9x https://github.com/snes9xgit/snes9x.git
  cmake_build "$SRC/snes9x/gtk" "$SRC/snes9x/build" -DCMAKE_INSTALL_PREFIX="$E"
  mkdir -p "$E/bin"; cp "$SRC/snes9x/build/snes9x-gtk" "$E/bin/"
  cmake --install "$SRC/snes9x/build" >/dev/null 2>&1 || true   # locale/data files, optional
}

build_mgba() {
  fetch_git mgba https://github.com/mgba-emu/mgba.git
  cmake_build "$SRC/mgba" "$SRC/mgba/build" -DCMAKE_INSTALL_PREFIX="$E" -DBUILD_QT=ON -DBUILD_SDL=OFF \
    -DUSE_DISCORD_RPC=OFF -DCMAKE_INSTALL_RPATH="$E/lib"
  cmake --install "$SRC/mgba/build"
}

build_mupen64plus() {
  local m nd=""
  [ "$ARCH" = aarch64 ] && nd="NEW_DYNAREC=1"   # mupen64plus' ARM64 recompiler
  for m in core rsp-hle audio-sdl input-sdl video-rice ui-console; do
    fetch_git "mupen64plus-$m" "https://github.com/mupen64plus/mupen64plus-$m.git"
    # shellcheck disable=SC2086
    make -C "$SRC/mupen64plus-$m/projects/unix" -j"$JOBS" all $nd \
      APIDIR="$SRC/mupen64plus-core/src/api" PREFIX="$E"
    # shellcheck disable=SC2086
    make -C "$SRC/mupen64plus-$m/projects/unix" install $nd \
      APIDIR="$SRC/mupen64plus-core/src/api" PREFIX="$E" LDCONFIG=true
  done
}

build_mednafen() {
  # Mednafen is not developed in public git; take the newest source release (incl. WIP ones).
  local page url
  page="$(curl -fsSL https://mednafen.github.io/releases/)"
  url="$(printf '%s' "$page" | grep -oE 'files/mednafen-[0-9][0-9A-Za-z.-]*\.tar\.xz' | sort -V | tail -1)"
  [ -n "$url" ] || { echo "could not find a mednafen release"; return 1; }
  echo "using $url"
  rm -rf "$SRC/mednafen"; mkdir -p "$SRC/mednafen"
  curl -fsSL "https://mednafen.github.io/releases/$url" | tar -xJ -C "$SRC/mednafen" --strip-components=1
  (cd "$SRC/mednafen" && ./configure --prefix="$E" && make -j"$JOBS" && make install)
}

build_melonds() {
  fetch_git melonds https://github.com/melonDS-emu/melonDS.git
  cmake_build "$SRC/melonds" "$SRC/melonds/build" -DCMAKE_INSTALL_PREFIX="$E" -DUSE_QT6=ON
  cmake --install "$SRC/melonds/build"
}

build_ppsspp() {
  fetch_git ppsspp https://github.com/hrydgard/ppsspp.git
  cmake_build "$SRC/ppsspp" "$SRC/ppsspp/build" -DUSING_QT_UI=OFF -DHEADLESS=OFF -DUNITTEST=OFF
  # PPSSPP runs from its build folder (binary + assets/ side by side)
  rm -rf "$E"; mkdir -p "$E"
  cp "$SRC/ppsspp/build/PPSSPPSDL" "$E/"
  cp -r "$SRC/ppsspp/build/assets" "$E/assets"
}

build_flycast() {
  fetch_git flycast https://github.com/flyinghead/flycast.git
  cmake_build "$SRC/flycast" "$SRC/flycast/build" -DCMAKE_INSTALL_PREFIX="$E"
  mkdir -p "$E/bin"; cp "$SRC/flycast/build/flycast" "$E/bin/"
}

build_dolphin() {
  fetch_git dolphin https://github.com/dolphin-emu/dolphin.git
  cmake_build "$SRC/dolphin" "$SRC/dolphin/build" -DCMAKE_INSTALL_PREFIX="$E" -DENABLE_AUTOUPDATE=OFF \
    -DENABLE_ANALYTICS=OFF -DENABLE_TESTS=OFF -DCMAKE_INSTALL_RPATH="$E/lib"
  cmake --install "$SRC/dolphin/build"
}

build_azahar() {
  fetch_git azahar https://github.com/azahar-emu/azahar.git
  cmake_build "$SRC/azahar" "$SRC/azahar/build" -DCMAKE_INSTALL_PREFIX="$E" -DENABLE_QT=ON \
    -DENABLE_TESTS=OFF -DENABLE_QT_UPDATE_CHECKER=OFF -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
  cmake --install "$SRC/azahar/build"
}

build_cemu() {
  fetch_git cemu https://github.com/cemu-project/Cemu.git
  # Cemu pulls most libraries through its bundled vcpkg; ARM needs the system cmake/ninja
  [ "$ARCH" = aarch64 ] && export VCPKG_FORCE_SYSTEM_BINARIES=1
  cmake_build "$SRC/cemu" "$SRC/cemu/build" -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DENABLE_DISCORD_RPC=OFF
  rm -rf "$E"; mkdir -p "$E"
  cp -r "$SRC/cemu/bin/." "$E/"
}

install_dotnet() {  # channel -> sets DOTNET
  DOTNET_ROOT="$ROOT/dotnet"; export DOTNET_ROOT DOTNET_CLI_TELEMETRY_OPTOUT=1
  curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$ROOT/dotnet-install.sh"
  bash "$ROOT/dotnet-install.sh" --install-dir "$DOTNET_ROOT" "$@"
  DOTNET="$DOTNET_ROOT/dotnet"
}

build_ryujinx() {
  fetch_git ryujinx https://git.ryujinx.app/ryubing/ryujinx.git
  local rid
  case "$ARCH" in aarch64) rid=linux-arm64 ;; *) rid=linux-x64 ;; esac
  install_dotnet --jsonfile "$SRC/ryujinx/global.json" || install_dotnet --channel LTS
  rm -rf "$E"
  "$DOTNET" publish "$SRC/ryujinx/src/Ryujinx/Ryujinx.csproj" -c Release -r "$rid" --self-contained true \
    -p:DebugType=embedded -p:ExtraDefineConstants=DISABLE_UPDATER -o "$E"
}

build_duckstation() {
  fetch_git duckstation https://github.com/stenzek/duckstation.git
  # DuckStation builds its own copies of SDL3, Qt 6 and friends first (slow, but only once)
  if [ ! -f "$SRC/duckstation/deps/.done" ] || [ "$UPDATE" = 1 ]; then
    "$SRC/duckstation/scripts/deps/build-dependencies-linux.sh" "$SRC/duckstation/deps"
    touch "$SRC/duckstation/deps/.done"
  fi
  cmake_build "$SRC/duckstation" "$SRC/duckstation/build" -DCMAKE_PREFIX_PATH="$SRC/duckstation/deps" \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_EXE_LINKER_FLAGS_INIT="-fuse-ld=lld" \
    -DCMAKE_MODULE_LINKER_FLAGS_INIT="-fuse-ld=lld" -DCMAKE_SHARED_LINKER_FLAGS_INIT="-fuse-ld=lld"
  rm -rf "$E"; mkdir -p "$E/lib"
  cp -r "$SRC/duckstation/build/bin" "$E/bin"
  cp -a "$SRC/duckstation/deps/lib/." "$E/lib/"
}

build_rpcs3() {
  fetch_git rpcs3 https://github.com/RPCS3/rpcs3.git
  cmake_build "$SRC/rpcs3" "$SRC/rpcs3/build" -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DBUILD_LLVM=ON -DUSE_NATIVE_INSTRUCTIONS=OFF -DUSE_DISCORD_RPC=OFF -DCMAKE_INSTALL_PREFIX="$E"
  cmake --install "$SRC/rpcs3/build"
}

build_shadps4() {
  fetch_git shadps4 https://github.com/shadps4-emu/shadPS4.git
  cmake_build "$SRC/shadps4" "$SRC/shadps4/build" -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
  rm -rf "$E"; mkdir -p "$E"
  cp "$SRC/shadps4/build/shadps4" "$E/"
}

# ------------------------------------------------------------------------------ WII-UU config
set_conf() {  # key value  (always overrides: the user asked for this emulator)
  local key="$1" val="$2"
  grep -v "^${key//./\\.}=" "$CONF" > "$CONF.tmp" 2>/dev/null; mv "$CONF.tmp" "$CONF"
  printf '%s=%s\n' "$key" "${val//\\/\\\\}" >> "$CONF"
}

# Wrapper so emulators always find their own bundled libraries, whatever the build system did.
write_launcher() {  # name binary
  local e="$EMU/$1"
  cat > "$e/run" <<EOF
#!/usr/bin/env bash
export LD_LIBRARY_PATH="$e/lib:$e/lib64\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
cd "$e"
exec "$e/$2" "\$@"
EOF
  chmod +x "$e/run"
}

# ------------------------------------------------------------------------------ build loop
declare -A RESULT
START_ALL=$(date +%s)
for n in $TODO; do
  E="$EMU/$n"; export E
  log="$LOGS/$n.log"
  say "Building $n  ${c_dim}(log: $log)${c_off}"
  t0=$(date +%s)
  mkdir -p "$E"
  # run outside an `if` so `set -e` really aborts the build at the first failing step
  ( set -eo pipefail; "build_$n" ) >"$log" 2>&1
  rc=$?
  if [ "$rc" -eq 0 ]; then
    bin="$(field "$n" 5)"
    if [ ! -x "$E/$bin" ]; then
      RESULT[$n]="FAILED (binary $bin missing)"; warn "$n: build finished but $E/$bin is missing"
      continue
    fi
    write_launcher "$n" "$bin"
    rev="$(git -C "$SRC/$n" rev-parse --short HEAD 2>/dev/null || git -C "$SRC/$n-core" rev-parse --short HEAD 2>/dev/null || echo release)"
    echo "$rev $(date +%Y-%m-%d)" > "$E/.built"
    args="$(field "$n" 6)"; args="${args//@E@/$E}"
    for s in $(field "$n" 2); do set_conf "system.$s.command" "$E/run $args"; done
    RESULT[$n]="ok $rev ($(( ($(date +%s) - t0) / 60 )) min)"
    ok "$n $rev -> $(field "$n" 2)"
  else
    RESULT[$n]="FAILED"
    warn "$n failed. Last lines of the log:"
    tail -n 15 "$log" | sed 's/^/      /'
  fi
done

# ------------------------------------------------------------------------------ summary
printf '\n%sSummary%s  (%s min total)\n' "$c_blue" "$c_off" "$(( ($(date +%s) - START_ALL) / 60 ))"
fails=0
for n in $TODO; do
  r="${RESULT[$n]}"
  case "$r" in ok*) printf '  %s✓%s %-12s %s\n' "$c_green" "$c_off" "$n" "${r#ok }" ;;
               *) printf '  %s✗%s %-12s %s\n' "$c_red" "$c_off" "$n" "$r  - see $LOGS/$n.log"; fails=$((fails+1)) ;; esac
done
echo
echo "Emulators live in $EMU and WII-UU's settings were updated ($CONF)."
echo "Update everything later with:  $(basename "$0") --update"
echo "Games still need your own dumps; Switch/PS3 also need your own keys/firmware (see each emulator's docs)."
[ "$fails" -eq 0 ]
