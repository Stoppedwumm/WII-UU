#!/usr/bin/env bash
set -euo pipefail

stage=$1 admin=$2 prefix=$3
getent passwd "$admin" >/dev/null && {
  echo "Replacement account $admin already exists. Inspect it before retrying." >&2
  exit 1
}
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
  bash install.sh --yes --no-shortcut --roms "$HOME/.wiiuu/roms"
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
