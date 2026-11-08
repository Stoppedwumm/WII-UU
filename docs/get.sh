#!/usr/bin/env bash
# One-line installer for WII-UU (Linux, macOS, Raspberry Pi):
#   curl -fsSL https://wiiuu.stoppedwumm.net/get.sh | bash
#   curl -fsSL https://wiiuu.stoppedwumm.net/get.sh | bash -s -- --with-emulators
# Runs maintenance first and downloads the release only when an install or repair needs it.
set -euo pipefail
URL="${WIIUU_ZIP_URL:-https://wiiuu.stoppedwumm.net/download/WII-UU-latest.zip}"
command -v curl >/dev/null 2>&1 || { echo "Please install curl first." >&2; exit 1; }
installer="$(curl -fsSL "${WIIUU_INSTALL_SCRIPT_URL:-https://wiiuu.stoppedwumm.net/download/install.sh}")"
if bash -c "$installer" install.sh --bootstrap "$@"; then
  exit 0
else
  result=$?
  [ "$result" = 42 ] || exit "$result"
fi
command -v unzip >/dev/null 2>&1 || { echo "Please install unzip first." >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "==> Downloading WII-UU"
curl -fL --retry 3 -o "$tmp/wiiuu.zip" "$URL"
unzip -q "$tmp/wiiuu.zip" -d "$tmp" </dev/null
rm -f "$tmp/wiiuu.zip"
cd "$tmp"/WII-UU-*/
WIIUU_SKIP_MAINTENANCE=1 bash ./install.sh "$@"
