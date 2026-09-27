#!/usr/bin/env bash
# One-line installer for WII-UU (Linux, macOS, Raspberry Pi):
#   curl -fsSL https://wiiuu.stoppedwumm.net/get.sh | bash
#   curl -fsSL https://wiiuu.stoppedwumm.net/get.sh | bash -s -- --with-emulators
# Downloads the latest release zip and runs its install.sh with the given options.
set -euo pipefail
URL="${WIIUU_ZIP_URL:-https://wiiuu.stoppedwumm.net/download/WII-UU-latest.zip}"
for t in curl unzip; do
  command -v "$t" >/dev/null 2>&1 || { echo "Please install '$t' first (e.g. sudo apt install $t)." >&2; exit 1; }
done
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
echo "==> Downloading WII-UU"
curl -fL --retry 3 -o "$tmp/wiiuu.zip" "$URL"
unzip -q "$tmp/wiiuu.zip" -d "$tmp"
cd "$tmp"/WII-UU-*/
bash ./install.sh "$@"
