#!/usr/bin/env bash
set -euo pipefail

stage=$1 admin=$2 prefix=$3
getent passwd "$admin" >/dev/null && {
  echo "Replacement account $admin already exists. Inspect it before retrying." >&2
  exit 1
}
# The outer SSH command has already authenticated sudo and refreshed apt metadata.
apt-get install -y default-jre network-manager xterm ffmpeg x11-utils python3 pulseaudio-utils kmod udev
java_major() {
  java -version 2>&1 | awk -F'"' '/version/ {split($2, parts, "."); print parts[1] == 1 ? parts[2] : parts[1]}'
}
if ! [ "$(java_major)" -ge 17 ]; then
  apt-get install -y openjdk-21-jre || apt-get install -y openjdk-17-jre
fi
if ! [ "$(java_major)" -ge 17 ]; then
  echo "The system Java must be version 17 or newer before provisioning." >&2
  exit 1
fi

install -d /etc/udev/rules.d /etc/modules-load.d
if [ ! -f /etc/udev/rules.d/60-wiiuu-uinput.rules ]; then
  printf '%s\n' 'KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"' \
    > /etc/udev/rules.d/60-wiiuu-uinput.rules
fi
printf '%s\n' uinput > /etc/modules-load.d/wiiuu-uinput.conf
if [ ! -c /dev/uinput ]; then modprobe uinput; fi
udevadm control --reload-rules
udevadm trigger --sysname-match=uinput
udevadm settle
test -c /dev/uinput

useradd --create-home --shell /bin/bash --groups sudo -- "$admin"
IFS= read -r password < "$stage/admin-password"
rm -f -- "$stage/admin-password"
printf '%s:%s\n' "$admin" "$password" | chpasswd
unset password
target_home="$(getent passwd "$admin" | cut -d: -f6)"
work="$(mktemp -d "$target_home/.wiiuu-install.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
install -o "$admin" -g "$(id -gn "$admin")" -m 600 "$stage/release.zip" "$work/release.zip"
chown "$admin:$(id -gn "$admin")" "$work"
# Unpack and run the selected WII-UU installer with the console user's privileges.
runuser -u "$admin" -- bash -eu -c '
  cd "$1"
  mkdir release
  unzip -oq release.zip -d release
  cd "release/$2"
  if ! unzip -Z1 wiiuu.jar wiiuu/ui/OemSelfTest.class | grep -Fxq wiiuu/ui/OemSelfTest.class; then
    echo "Use a WII-UU package that supports the OEM self-test." >&2
    exit 1
  fi
  WIIUU_OEM_PREREQUISITES_READY=1 bash install.sh --yes --no-shortcut --roms "$HOME/.wiiuu/roms"
' bash "$work" "$prefix"
data="$target_home/.wiiuu"
# Data supplied by the image is applied only to the fresh user created above.
if [ -d "$stage/files" ]; then cp -R -- "$stage/files/." "$data/"; fi
cat "$stage/defaults.properties" >> "$data/config.properties"
chown -R -- "$admin:$(id -gn "$admin")" "$data"
chmod 600 "$data/config.properties"
systemctl enable NetworkManager
"$target_home/.local/bin/wiiuu-console" install --user "$admin" --oem --yes
test -s /etc/wiiuu-console.conf
test -s /usr/share/xsessions/wiiuu.desktop
test -f /usr/local/share/wiiuu-console/oem
systemctl is-enabled --quiet wiiuu-console-boot.service
runuser -u "$admin" -- sh -eu -c '
  printf "%s\n" pending > "$1/oem-self-test"
' sh "$data"
