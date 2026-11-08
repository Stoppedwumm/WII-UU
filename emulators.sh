#!/usr/bin/env bash
# Installs emulators for WII-UU on Linux - x86-64 PCs and 64-bit ARM boards such as the
# Raspberry Pi 4/5 - and points WII-UU at them. No flatpak, no containers.
#
# For each emulator it downloads the newest official prebuilt Linux release for this CPU
# (AppImage / tarball from the project's GitHub or GitLab releases). Where a project publishes
# no build for this CPU, it compiles the latest source instead.
#
#   ./emulators.sh                 install everything that has a release for this machine,
#                                  and build the "light" rest from source
#   ./emulators.sh --all           also build heavy emulators that have no release (Dolphin ...)
#   ./emulators.sh --only ppsspp,dolphin
#   ./emulators.sh --update        update everything installed before (skips if already newest)
#   ./emulators.sh --list          show what is available / installed on this machine
#
# Options: --source (always build)  --releases-only (never build)  --nightly (allow pre-releases)
#          --jobs N  --no-deps (skip apt)  --yes  --prefix DIR
# Set GITHUB_TOKEN to avoid GitHub's 60 requests/hour limit for anonymous users.
set -uo pipefail

ROOT="${WIIUU_EMU_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/wiiuu-emulators}"
SRC="$ROOT/emu-src"
EMU="$ROOT/emulators"
CONF_DIR="${WIIUU_HOME:-$HOME/.wiiuu}"
CONF="$CONF_DIR/config.properties"
LOGS="$CONF_DIR/logs/build"
ARCH="${WIIUU_ARCH:-$(uname -m)}"
YES=0; DEPS=1; ALL=0; UPDATE=0; LIST=0; ONLY=""; JOBS=""; MODE=auto; NIGHTLY=0

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
    --source) MODE=source ;;
    --releases-only) MODE=release ;;
    --nightly) NIGHTLY=1 ;;
    --keep-going) ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
mupen64plus|n64|light|any|bin/mupen64plus|--fullscreen --corelib @E@/lib/libmupen64plus.so.2 --plugindir @E@/lib/mupen64plus --datadir @E@/share/mupen64plus @GFX@{rom}
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
shadps4|ps4|heavy|x86_64|shadps4|-g {rom}
pcsx2|ps2|heavy|x86_64|-|-fullscreen -batch -- {rom}"

# Official prebuilt releases:
# name | host (github, or gitlab:<domain>) | repo | repo for aarch64 (- = same) | preferred-asset regex | executables
# Assets are chosen at run time by name (linux + this CPU; AppImage > tarball > zip), so new
# versions are picked up without editing this table.
RELEASES="fceux|github|TASEmulators/fceux|-||fceux
snes9x|github|snes9xgit/snes9x|-|gtk|snes9x-gtk snes9x
mgba|github|mgba-emu/mgba|-|qt|mgba-qt mgba
melonds|github|melonDS-emu/melonDS|-||melonDS
ppsspp|github|hrydgard/ppsspp|-|sdl|PPSSPPSDL PPSSPPQt ppsspp
flycast|github|flyinghead/flycast|-||flycast
azahar|github|azahar-emu/azahar|-|qt|azahar azahar-qt
cemu|github|cemu-project/Cemu|-||Cemu
ryujinx|gitlab:git.ryujinx.app|ryubing/ryujinx|-||Ryujinx.sh Ryujinx
duckstation|github|stenzek/duckstation|-||duckstation-qt DuckStation
rpcs3|github|RPCS3/rpcs3-binaries-linux|RPCS3/rpcs3-binaries-linux-arm64||rpcs3
shadps4|github|shadps4-emu/shadPS4|-|sdl|shadps4 Shadps4-sdl
pcsx2|github|PCSX2/pcsx2|-|qt|pcsx2-qt pcsx2"

rfield() { printf '%s\n' "$RELEASES" | awk -F'|' -v n="$1" -v f="$2" '$1 == n { print $f }'; }
has_release() { [ -n "$(rfield "$1" 1)" ]; }
has_source() { declare -F "build_$1" >/dev/null 2>&1; }

field() { printf '%s\n' "$CATALOGUE" | awk -F'|' -v n="$1" -v f="$2" '$1 == n { print $f }'; }
supported() { local a; a="$(field "$1" 4)"; [ "$a" = any ] || [[ " $a " == *" $ARCH "* ]]; }

# ------------------------------------------------------------------------------ list / selection
if [ "$LIST" = 1 ]; then
  printf '%-12s %-24s %-6s %s\n' EMULATOR SYSTEMS TIER "ON THIS MACHINE ($ARCH)"
  while IFS='|' read -r n sys tier arch _ _; do
    how="source"; has_release "$n" && how="release"; [ "$n" = pcsx2 ] && how="release only"
    if supported "$n"; then st="available ($how)"; [ -f "$EMU/$n/.built" ] && st="installed: $(cat "$EMU/$n/.built")"
    else st="no (needs $arch)"; fi
    printf '%-12s %-24s %-6s %s\n' "$n" "$sys" "$tier" "$st"
  done <<< "$CATALOGUE"
  echo; echo "\"release\" = official prebuilt download when the project publishes one for $ARCH, else built from source."
  exit 0
fi

SELECTED=""
if [ -n "$ONLY" ]; then
  for n in $ONLY; do [ -n "$(field "$n" 1)" ] || die "unknown emulator '$n' (see --list)"; SELECTED="$SELECTED $n"; done
elif [ "$UPDATE" = 1 ]; then
  for n in $(printf '%s\n' "$CATALOGUE" | cut -d'|' -f1); do [ -f "$EMU/$n/.built" ] && SELECTED="$SELECTED $n"; done
  [ -n "$SELECTED" ] || die "nothing built yet - run without --update first"
else
  # everything; heavy emulators are only *built* with --all (a release download is always fine)
  while IFS='|' read -r n _ _ _ _ _; do SELECTED="$SELECTED $n"; done <<< "$CATALOGUE"
fi
TODO=""
for n in $SELECTED; do
  if supported "$n"; then TODO="$TODO $n"; elif [ -n "$ONLY" ]; then warn "$n does not run on $ARCH - skipped"; fi
done
[ -n "$TODO" ] || die "nothing to install"

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

printf '\n%s  WII-UU emulator installer%s  %s%s, %s CPUs, %s MB RAM, mode: %s%s\n\n' \
  "$c_blue" "$c_off" "$c_dim" "$ARCH" "$CPUS" "$MEM_MB" "$MODE" "$c_off"
say "Emulators:$TODO"
case "$MODE" in
  auto) echo "  Official prebuilt releases where available for $ARCH; the rest is built from source." ;;
  source) echo "  Building everything from the latest source."
    for n in $TODO; do
      if [ "$(field "$n" 3)" = heavy ] && [ "$MEM_MB" -lt 6000 ]; then
        warn "heavy builds with ${MEM_MB} MB RAM need >= 4 GB swap (Raspberry Pi OS: CONF_SWAPSIZE in /etc/dphys-swapfile)."; break
      fi
    done ;;
  release) echo "  Official prebuilt releases only." ;;
esac
ask "Continue?" || exit 1

# ------------------------------------------------------------------------------ dependencies (apt)
APT_COMMON="build-essential git cmake ninja-build pkg-config curl ca-certificates xz-utils unzip zip tar file"
apt_deps() {
  case "$1" in
    fceux) echo "qtbase5-dev libqt5opengl5-dev libsdl2-dev zlib1g-dev libminizip-dev libarchive-dev liblua5.1-0-dev" ;;
    snes9x) echo "libgtkmm-3.0-dev libsdl2-dev libepoxy-dev libminizip-dev libx11-dev libxrandr-dev libxext-dev libpulse-dev libasound2-dev portaudio19-dev libwayland-dev gettext python3" ;;
    mgba) echo "qtbase5-dev qtmultimedia5-dev qttools5-dev qttools5-dev-tools libsdl2-dev libzip-dev zipcmp zipmerge ziptool libedit-dev libelf-dev libpng-dev libsqlite3-dev libepoxy-dev" ;;
    mupen64plus) echo "libsdl2-dev libpng-dev zlib1g-dev libfreetype-dev libgl-dev libglu1-mesa-dev libgles-dev libegl-dev libspeexdsp-dev libsamplerate0-dev nasm" ;;
    mednafen) echo "libsdl2-dev zlib1g-dev libasound2-dev libsndfile1-dev libflac-dev libvorbis-dev libzstd-dev" ;;
    melonds) echo "qt6-base-dev qt6-base-private-dev qt6-multimedia-dev libqt6svg6-dev libqt6opengl6-dev libsdl2-dev libarchive-dev libenet-dev libzstd-dev libfaad-dev extra-cmake-modules" ;;
    ppsspp) echo "libsdl3-dev libsdl3-ttf-dev libfreetype-dev libwayland-dev libxkbcommon-dev libdecor-0-dev libsdl2-dev libsdl2-ttf-dev libgl1-mesa-dev libglu1-mesa-dev libvulkan-dev libfontconfig1-dev libcurl4-openssl-dev python3" ;;
    flycast) echo "libsdl2-dev libcurl4-openssl-dev libudev-dev libzip-dev zipcmp zipmerge ziptool libgl1-mesa-dev libvulkan-dev libasound2-dev libpulse-dev libao-dev libminiupnpc-dev libflac-dev" ;;
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
APT_UPDATED=0
apt_install() {  # packages... (only those this distro actually has; names differ between releases)
  command -v apt-get >/dev/null 2>&1 || return 1
  [ "$APT_UPDATED" = 1 ] || { $SUDO apt-get update -qq || warn "apt-get update had errors"; APT_UPDATED=1; }
  local pkgs="" missing="" p
  for p in $(printf '%s\n' "$@" | sort -u); do
    [ "$p" = nasm ] && [ "$ARCH" != x86_64 ] && continue
    if apt-cache show "$p" >/dev/null 2>&1; then pkgs="$pkgs $p"; else missing="$missing $p"; fi
  done
  [ -n "$missing" ] && warn "not in your apt sources (skipped):$missing"
  # shellcheck disable=SC2086
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgs || warn "some packages failed to install"
}

# build dependencies are only installed for emulators that actually get compiled
build_deps() {  # name
  if [ "$DEPS" = 1 ]; then
    if command -v apt-get >/dev/null 2>&1; then
      say "Installing build dependencies for $1 (apt)"
      # shellcheck disable=SC2046
      apt_install $APT_COMMON $(apt_deps "$1")
    else
      warn "No apt found; install $1's build dependencies (compiler, cmake, ninja, git, SDL2, Qt dev packages) yourself."
    fi
  fi
  local t
  for t in git cmake make cc c++; do command -v "$t" >/dev/null 2>&1 || { warn "'$t' is missing - install build tools first"; return 1; }; done
}

# tools needed to download and unpack releases
need=""
for t in curl python3 tar unzip xz; do command -v "$t" >/dev/null 2>&1 || need="$need $t"; done
if [ -n "$need" ]; then
  if [ "$DEPS" = 1 ] && command -v apt-get >/dev/null 2>&1; then
    say "Installing download tools:$need"
    apt_install curl ca-certificates python3 tar unzip xz-utils file
  else
    die "please install:$need"
  fi
fi

# ------------------------------------------------------------------------------ helpers
mkdir -p "$SRC" "$EMU" "$LOGS" "$CONF_DIR"; touch "$CONF"

# clone or fast-forward to the newest commit (shallow, to save space and time on a Pi)
fetch_git() {  # dir url [submodule paths...]  (default: all submodules)
  local dir="$SRC/$1" url="$2"; shift 2
  if [ -d "$dir/.git" ]; then
    git -C "$dir" fetch --depth 1 origin HEAD || return 1
    git -C "$dir" reset --hard FETCH_HEAD || return 1
  else
    rm -rf "$dir"
    git clone --depth 1 "$url" "$dir" || return 1
  fi
  echo "source: $url @ $(git -C "$dir" rev-parse --short HEAD)"
  git -C "$dir" submodule sync --recursive
  # --force re-checks-out every submodule, repairing empty folders left by an interrupted earlier fetch
  git -C "$dir" submodule update --init --recursive --force --depth 1 --jobs 4 -- "$@" && return 0
  # retry with full history, and via official GitHub mirrors for hosts that are often unreachable
  git -C "$dir" -c url."https://github.com/freetype/freetype.git".insteadOf="https://gitlab.freedesktop.org/freetype/freetype.git" \
      -c url."https://github.com/pnggroup/libpng.git".insteadOf="https://git.code.sf.net/p/libpng/code" \
      submodule update --init --recursive --force --jobs 4 -- "$@" || return 1
}

# SDL3 is newer than what Raspberry Pi OS Bookworm / Ubuntu 24.04 ship; build the latest release
# into $ROOT/deps when the system doesn't have it. Launchers add $ROOT/deps/lib to the library path.
ensure_sdl3() {
  if pkg-config --exists sdl3 && pkg-config --exists sdl3-ttf; then return 0; fi
  if [ -f "$ROOT/deps/lib/pkgconfig/sdl3.pc" ] && [ -f "$ROOT/deps/lib/pkgconfig/sdl3-ttf.pc" ] && [ "$UPDATE" != 1 ]; then return 0; fi
  local tag
  tag="$(git ls-remote --tags --refs https://github.com/libsdl-org/SDL.git 'release-3.*' | sed 's|.*/||' | sort -V | tail -1)"
  echo "building SDL3 $tag from source"
  rm -rf "$SRC/sdl3"; git clone --depth 1 --branch "$tag" https://github.com/libsdl-org/SDL.git "$SRC/sdl3"
  cmake_build "$SRC/sdl3" "$SRC/sdl3/build" -DCMAKE_INSTALL_PREFIX="$ROOT/deps" -DCMAKE_INSTALL_LIBDIR=lib -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF
  cmake --install "$SRC/sdl3/build"
  tag="$(git ls-remote --tags --refs https://github.com/libsdl-org/SDL_ttf.git 'release-3.*' | sed 's|.*/||' | sort -V | tail -1)"
  echo "building SDL3_ttf $tag from source"
  rm -rf "$SRC/sdl3_ttf"; git clone --depth 1 --branch "$tag" https://github.com/libsdl-org/SDL_ttf.git "$SRC/sdl3_ttf"
  cmake_build "$SRC/sdl3_ttf" "$SRC/sdl3_ttf/build" -DCMAKE_INSTALL_PREFIX="$ROOT/deps" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$ROOT/deps" -DSDLTTF_VENDORED=OFF -DSDLTTF_HARFBUZZ=OFF -DSDLTTF_PLUTOSVG=OFF -DSDLTTF_SAMPLES=OFF
  cmake --install "$SRC/sdl3_ttf/build"
}

cmake_build() {  # srcdir builddir [cmake args...]  -> configures, builds (Release)
  local s="$1" b="$2"; shift 2
  # a build folder configured for another location (e.g. after moving --prefix) is unusable
  if [ -f "$b/CMakeCache.txt" ] && ! grep -qxF "CMAKE_HOME_DIRECTORY:INTERNAL=$(cd "$s" && pwd -P)" "$b/CMakeCache.txt"; then
    rm -rf "$b"
  fi
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
  # only the submodules the Linux GTK port uses (skips the Windows-only win32/* ones)
  fetch_git snes9x https://github.com/snes9xgit/snes9x.git \
    external/SPIRV-Cross external/glslang external/vulkan-headers external/cubeb
  cmake_build "$SRC/snes9x/gtk" "$SRC/snes9x/build" -DCMAKE_INSTALL_PREFIX="$E"
  mkdir -p "$E/bin"; cp "$SRC/snes9x/build/snes9x-gtk" "$E/bin/"
  cmake --install "$SRC/snes9x/build" >/dev/null 2>&1 || true   # locale/data files, optional
}

build_mgba() {
  fetch_git mgba https://github.com/mgba-emu/mgba.git
  # SKIP_GIT: mGBA's CMake reads release tags, which a shallow clone doesn't have
  cmake_build "$SRC/mgba" "$SRC/mgba/build" -DCMAKE_INSTALL_PREFIX="$E" -DBUILD_QT=ON -DBUILD_SDL=OFF -DSKIP_GIT=ON \
    -DUSE_DISCORD_RPC=OFF -DCMAKE_INSTALL_RPATH="$E/lib"
  cmake --install "$SRC/mgba/build"
}

# OpenGL ES for the N64 emulator on ARM boards (Raspberry Pi 4/5 and the like): their drivers offer
# desktop OpenGL only as an old compatibility profile (2.1 on a Pi 5), where Mupen64Plus' picture
# stays black while the game runs. N64_GLES=1 / N64_GLES=0 forces it either way.
n64_gles() {
  case "${N64_GLES:-auto}" in 1|yes|on) return 0 ;; 0|no|off) return 1 ;; esac
  [[ "$ARCH" == aarch64 || "$ARCH" == arm* ]]
}

build_mupen64plus() {
  local m flags=()
  [ "$ARCH" = aarch64 ] && flags+=(NEW_DYNAREC=1)   # mupen64plus' ARM64 recompiler
  n64_gles && flags+=(USE_GLES=1)                     # every part, the core included (it creates the GL context)
  for m in core rsp-hle audio-sdl input-sdl video-rice ui-console; do
    fetch_git "mupen64plus-$m" "https://github.com/mupen64plus/mupen64plus-$m.git"
    # a build of the other GL flavour must not be reused
    make -C "$SRC/mupen64plus-$m/projects/unix" clean >/dev/null 2>&1 || true
    make -C "$SRC/mupen64plus-$m/projects/unix" -j"$JOBS" all "${flags[@]}" \
      APIDIR="$SRC/mupen64plus-core/src/api" PREFIX="$E"
    make -C "$SRC/mupen64plus-$m/projects/unix" install "${flags[@]}" \
      APIDIR="$SRC/mupen64plus-core/src/api" PREFIX="$E" LDCONFIG=true
  done
  # GLideN64, the video plugin most N64 setups use (RetroPie too): far better and faster than Rice.
  # Without the high-res texture pack loader (NOHQ), which needs nothing more and isn't used here.
  local g=(-DMUPENPLUSAPI=On -DNOHQ=On) so
  n64_gles && g+=(-DMESA=On -DEGL=On)
  case "$ARCH" in aarch64) g+=(-DNEON_OPT=On -DCRC_ARMV8=On) ;; x86_64) g+=(-DCRC_OPT=On) ;; esac
  rm -f "$E/lib/mupen64plus/mupen64plus-video-GLideN64.so"
  if fetch_git GLideN64 https://github.com/gonetz/GLideN64.git \
     && rm -rf "$SRC/GLideN64/build" && cmake_build "$SRC/GLideN64/src" "$SRC/GLideN64/build" "${g[@]}" \
     && so="$(find "$SRC/GLideN64/build" -name mupen64plus-video-GLideN64.so | head -1)" && [ -n "$so" ]; then
    install -m 644 "$so" "$E/lib/mupen64plus/"
  else
    echo "GLideN64 did not build; N64 games use the Rice video plugin"
  fi
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
  ensure_sdl3
  export PKG_CONFIG_PATH="$ROOT/deps/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  cmake_build "$SRC/ppsspp" "$SRC/ppsspp/build" -DUSING_QT_UI=OFF -DHEADLESS=OFF -DUNITTEST=OFF \
    -DCMAKE_PREFIX_PATH="$ROOT/deps"
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
export LD_LIBRARY_PATH="$e/lib:$e/lib64:$ROOT/deps/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export APPIMAGE_EXTRACT_AND_RUN=1   # only matters if an AppImage could not be unpacked
cd "$e"
exec "$e/$2" "\$@"
EOF
  chmod +x "$e/run"
}

# ------------------------------------------------------------------------------ releases
# Prints "version<TAB>url<TAB>filename" of the best Linux asset for this CPU, or fails.
rel_pick() {  # host repo prefer
  python3 - "$1" "$2" "$ARCH" "$NIGHTLY" "$3" <<'PY'
import json, os, re, sys, urllib.parse, urllib.request
host, repo, arch, nightly, prefer = sys.argv[1:6]
mock = os.environ.get("WIIUU_RELEASES_MOCK")          # test hook: directory of saved API responses
def get(url):
    if mock:
        with open(os.path.join(mock, repo.replace("/", "_") + ".json")) as f:
            return json.load(f)
    req = urllib.request.Request(url, headers={"User-Agent": "wiiuu-emulators", "Accept": "application/json"})
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if tok and "api.github.com" in url:
        req.add_header("Authorization", "Bearer " + tok)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)
try:
    if host == "github":
        rels = [{"tag": r["tag_name"], "pre": r["prerelease"],
                 "assets": [(a["name"], a["browser_download_url"], a.get("updated_at", "")) for a in r["assets"]]}
                for r in get(f"https://api.github.com/repos/{repo}/releases?per_page=20") if not r["draft"]]
    else:  # gitlab:<domain>
        dom = host.split(":", 1)[1]
        rels = [{"tag": r["tag_name"], "pre": bool(r.get("upcoming_release")),
                 "assets": [(l["name"], l.get("direct_asset_url") or l["url"], "") for l in r.get("assets", {}).get("links", [])]}
                for r in get(f"https://{dom}/api/v4/projects/{urllib.parse.quote(repo, safe='')}/releases?per_page=20")]
except Exception as e:
    print(f"release lookup failed: {e}", file=sys.stderr)
    sys.exit(2)

ARM = r"(aarch64|arm64|armv8)"
X86 = r"(x86[_-]?64|amd64|x64|linux64)"
want, other = (ARM, X86) if arch == "aarch64" else (X86, ARM)
BAD = (r"(windows|win32|win64|msvc|mingw|macos|mac-|-mac|osx|darwin|apple|universal|android|\.apk$|\.dmg$|\.exe$|"
       r"\.msi$|\.pkg$|debug|symbols|\.pdb|\.sha\d*$|\.sig$|\.asc$|\.zsync$|\.sym$|source|\.deb$|\.rpm$|flatpak|"
       r"armhf|armv7|i386|i686|riscv|ppc|freebsd|\bios\b|\.ipa$|libretro)")

def score(name):
    n = name.lower()
    if re.search(BAD, n):
        return None
    if n.endswith(".appimage"):
        ext = 3
    elif re.search(r"(\.tar\.(gz|xz|zst|bz2)|\.tgz)$", n):
        ext = 2
    elif n.endswith(".zip"):
        ext = 1
    else:
        return None
    linuxish = any(k in n for k in ("linux", "appimage", "ubuntu", "debian"))
    if not linuxish:
        return None
    if re.search(want, n):
        s = 10
    elif re.search(other, n) or arch != "x86_64":
        return None           # other CPU, or untagged (untagged builds are x86-64 by convention)
    else:
        s = 0
    s += ext * 3
    if prefer and re.search(prefer, n, re.I):
        s += 5
    return s

def best(rel):
    cands = [(score(n), n, u, t) for n, u, t in rel["assets"]]
    cands = [c for c in cands if c[0] is not None]
    return max(cands) if cands else None

order = rels if nightly == "1" else [r for r in rels if not r["pre"]] + [r for r in rels if r["pre"]]
for rel in order:                       # API lists newest first
    b = best(rel)
    if b:
        # stamp: rolling tags (e.g. DuckStation "latest") keep their name, so include the upload time
        stamp = rel["tag"] + ("@" + b[3][:16] if b[3] else "")
        print(f"{stamp}\t{b[2]}\t{b[1]}")
        sys.exit(0)
sys.exit(1)
PY
}

# Unpacks an AppImage into $2/app so it runs without FUSE (not always present on a Pi).
# Sets RELBIN to the program to launch, relative to $2.
unpack_appimage() {  # file dest
  chmod +x "$1"
  if (cd "$2" && "$1" --appimage-extract >/dev/null 2>&1) && [ -e "$2/squashfs-root/AppRun" ]; then
    mv "$2/squashfs-root" "$2/app"; rm -f "$1"; RELBIN="app/AppRun"
  else
    rm -rf "$2/squashfs-root"
    mv "$1" "$2/emulator.AppImage"; RELBIN="emulator.AppImage"   # launched with APPIMAGE_EXTRACT_AND_RUN
  fi
}

# Finds the emulator executable inside an unpacked archive (ordered candidate names).
find_exe() {  # dir names...
  local dir="$1" nm f; shift
  for nm in "$@"; do
    f="$(find "$dir" -type f -iname "$nm" -perm -u+x 2>/dev/null | awk '{ print length, $0 }' | sort -n | head -1 | cut -d' ' -f2-)"
    [ -z "$f" ] && f="$(find "$dir" -type f -iname "$nm" 2>/dev/null | awk '{ print length, $0 }' | sort -n | head -1 | cut -d' ' -f2-)"
    [ -n "$f" ] && { chmod +x "$f"; echo "${f#$dir/}"; return 0; }
  done
  return 1
}

# Downloads and unpacks the newest release into $E. Exit codes: 0 ok, 3 already newest,
# 4 no release for this CPU. Sets RELBIN and REL_TAG.
install_release() {  # name
  local n="$1" host repo prefer line url file tag new app
  host="$(rfield "$n" 2)"; repo="$(rfield "$n" 3)"; prefer="$(rfield "$n" 5)"
  [ "$ARCH" = aarch64 ] && [ "$(rfield "$n" 4)" != "-" ] && repo="$(rfield "$n" 4)"
  line="$(rel_pick "$host" "$repo" "$prefer")"
  case $? in 0) ;; 1) echo "no Linux $ARCH asset in $repo releases"; return 4 ;; *) return 1 ;; esac
  IFS=$'\t' read -r tag url file <<< "$line"
  REL_TAG="$tag"
  if [ "$UPDATE" = 1 ] && [ -f "$E/.built" ] && grep -q "^release $tag " "$E/.built" && [ -e "$E/run" ]; then
    echo "already newest ($tag)"; return 3
  fi
  echo "release $tag: $url"
  new="$E.new"; rm -rf "$new"; mkdir -p "$new/dl"
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && [[ "$url" == https://github.com/* ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fL --retry 3 --connect-timeout 20 "${auth[@]}" -o "$new/dl/$file" "$url" || return 1
  case "$file" in
    *.AppImage|*.appimage) unpack_appimage "$new/dl/$file" "$new" ;;
    *)
      mkdir -p "$new/pkg"
      case "$file" in
        *.zip) unzip -q "$new/dl/$file" -d "$new/pkg" ;;
        *) tar -xf "$new/dl/$file" -C "$new/pkg" ;;
      esac || return 1
      # archives often just wrap an AppImage (shadPS4, melonDS, Azahar ...)
      app="$(find "$new/pkg" -iname '*.appimage' -type f | head -1)"
      if [ -n "$app" ]; then
        unpack_appimage "$app" "$new"
      else
        # shellcheck disable=SC2046
        RELBIN="pkg/$(find_exe "$new/pkg" $(rfield "$n" 6))" || { echo "no executable found in $file"; return 1; }
      fi ;;
  esac
  rm -rf "$new/dl"
  [ -e "$new/$RELBIN" ] || { echo "unpacked, but $RELBIN is missing"; return 1; }
  rm -rf "$E"; mv "$new" "$E"      # replace the old install only once the new one is complete
}

# ------------------------------------------------------------------------------ install loop
declare -A RESULT
START_ALL=$(date +%s)
for n in $TODO; do
  E="$EMU/$n"; export E
  log="$LOGS/$n.log"
  t0=$(date +%s)
  : > "$log"
  prev_how=""; [ -f "$E/.built" ] && prev_how="$(cut -d' ' -f1 "$E/.built")"
  bin=""; how=""
  use_release=0
  if has_release "$n" && [ "$MODE" != source ]; then
    # on --update keep a source-built emulator on source unless it was a release before
    { [ "$UPDATE" != 1 ] || [ "$prev_how" = release ] || [ -z "$prev_how" ] || [ "$MODE" = release ]; } && use_release=1
  fi

  if [ "$use_release" = 1 ]; then
    say "Downloading $n  ${c_dim}(log: $log)${c_off}"
    RELBIN=""; REL_TAG=""
    install_release "$n" >>"$log" 2>&1
    rc=$?
    case $rc in
      0) bin="$RELBIN"; how="release $REL_TAG" ;;
      3) RESULT[$n]="ok up to date ($REL_TAG)"; ok "$n is already the newest release ($REL_TAG)"; continue ;;
      4) warn "$n: no official Linux $ARCH release"; tail -n 1 "$log" | sed 's/^/      /' ;;
      *) warn "$n: release download failed"; tail -n 3 "$log" | sed 's/^/      /' ;;
    esac
    rm -rf "$E.new"
  fi

  if [ -z "$bin" ]; then
    if [ "$MODE" = release ] || ! has_source "$n"; then
      RESULT[$n]="FAILED (no release for $ARCH$(has_source "$n" || echo ', no source build'))"; continue
    fi
    if [ "$(field "$n" 3)" = heavy ] && [ "$ALL" != 1 ] && [ -z "$ONLY" ] && [ "$MODE" != source ] && [ "$prev_how" = "" ]; then
      RESULT[$n]="skipped (no release; build it with --all or --only $n)"; continue
    fi
    say "Building $n from source  ${c_dim}(log: $log)${c_off}"
    build_deps "$n" >>"$log" 2>&1 || { RESULT[$n]="FAILED (build tools missing)"; continue; }
    mkdir -p "$E"
    # run outside an `if` so `set -e` really aborts the build at the first failing step
    ( set -eo pipefail; "build_$n" ) >>"$log" 2>&1
    rc=$?
    if [ "$rc" -ne 0 ]; then
      RESULT[$n]="FAILED"
      warn "$n failed. Last lines of the log:"
      tail -n 15 "$log" | sed 's/^/      /'
      continue
    fi
    bin="$(field "$n" 5)"
    how="source $(git -C "$SRC/$n" rev-parse --short HEAD 2>/dev/null || git -C "$SRC/$n-core" rev-parse --short HEAD 2>/dev/null || echo tarball)"
  fi

  if [ ! -e "$E/$bin" ]; then
    RESULT[$n]="FAILED ($bin missing)"; warn "$n: finished but $E/$bin is missing"; continue
  fi
  write_launcher "$n" "$bin"
  echo "$how $(date +%Y-%m-%d)" > "$E/.built"
  args="$(field "$n" 6)"; args="${args//@E@/$E}"
  # mupen64plus: GLideN64 when it was built, else its default (Rice)
  gfx=""; [ -f "$E/lib/mupen64plus/mupen64plus-video-GLideN64.so" ] && gfx="--gfx mupen64plus-video-GLideN64.so "
  args="${args//@GFX@/$gfx}"
  for s in $(field "$n" 2); do set_conf "system.$s.command" "$E/run $args"; done
  RESULT[$n]="ok $how ($(( ($(date +%s) - t0) / 60 )) min)"
  ok "$n ($how) -> $(field "$n" 2)"
done

# ------------------------------------------------------------------------------ summary
printf '\n%sSummary%s  (%s min total)\n' "$c_blue" "$c_off" "$(( ($(date +%s) - START_ALL) / 60 ))"
fails=0
for n in $TODO; do
  r="${RESULT[$n]}"
  case "$r" in
    ok*) printf '  %s✓%s %-12s %s\n' "$c_green" "$c_off" "$n" "${r#ok }" ;;
    skipped*) printf '  %s-%s %-12s %s\n' "$c_dim" "$c_off" "$n" "$r" ;;
    *) printf '  %s✗%s %-12s %s\n' "$c_red" "$c_off" "$n" "$r  - see $LOGS/$n.log"; fails=$((fails+1)) ;;
  esac
done
echo
echo "Emulators live in $EMU and WII-UU's settings were updated ($CONF)."
echo "Update everything later with:  $(basename "$0") --update"
echo "Games still need your own dumps; Switch/PS3 also need your own keys/firmware (see each emulator's docs)."
[ "$fails" -eq 0 ]
