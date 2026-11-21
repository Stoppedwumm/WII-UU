#!/usr/bin/env bash
# Install/update a loopback Node service. No proxy, TLS or firewall configuration is changed.
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo 'Run: sudo bash archive/install.sh' >&2; exit 1; }
node_bin="$(command -v node || true)"
[ -n "$node_bin" ] || { echo 'Install Node.js 22+ first.' >&2; exit 1; }
"$node_bin" -e 'if (+process.versions.node.split(".")[0] < 20) process.exit(1)' || { echo 'Node.js 20+ is required (22+ recommended).' >&2; exit 1; }
id wiiuu-archive >/dev/null 2>&1 || useradd --system --home /var/lib/wiiuu-archive --shell /usr/sbin/nologin wiiuu-archive
install -d -m 755 /opt/wiiuu-archive
install -d -m 750 -o wiiuu-archive -g wiiuu-archive /var/lib/wiiuu-archive
install -m 644 server.mjs diagnostics.mjs client.mjs package.json /opt/wiiuu-archive/
if [ ! -f /etc/wiiuu-archive.env ]; then
  umask 077
  archive_token="$("$node_bin" -e 'console.log(require("node:crypto").randomBytes(48).toString("hex"))')"
  cat > /etc/wiiuu-archive.env <<CONFIG
HOST=127.0.0.1
PORT=8081
PUBLIC_URL=https://wii-uu-archive.stoppedwumm.net
DATA_DIR=/var/lib/wiiuu-archive
ARCHIVE_TOKEN=$archive_token
CONFIG
fi
# Bootstrap the diagnostics admin login only once. Store a salted scrypt hash, not the password.
# Preserve existing archive token and data; print new admin credentials exactly once.
if ! grep -q '^ARCHIVE_ADMIN_PASSWORD_HASH=' /etc/wiiuu-archive.env; then
  IFS=$'\t' read -r admin_password admin_hash < <("$node_bin" -e '
    const {randomBytes,scryptSync}=require("node:crypto");
    const password=randomBytes(24).toString("base64url"), salt=randomBytes(16);
    console.log(password+"\t"+salt.toString("hex")+":"+scryptSync(password,salt,64).toString("hex"));
  ')
  if [ -z "$admin_password" ] || [ -z "$admin_hash" ]; then
    echo 'Failed to generate diagnostics admin credentials.' >&2
    exit 1
  fi
  printf '\nARCHIVE_ADMIN_USER=admin\nARCHIVE_ADMIN_PASSWORD_HASH=%s\n' "$admin_hash" >> /etc/wiiuu-archive.env
  chmod 600 /etc/wiiuu-archive.env
  printf '\nWII-UU diagnostics admin account created. Save these one-time credentials securely:\n'
  printf 'Username: admin\nPassword: %s\nDashboard: https://wii-uu-archive.stoppedwumm.net/admin/diagnostics\n\n' "$admin_password"
  unset admin_password admin_hash
fi
cat > /etc/systemd/system/wiiuu-archive.service <<SERVICE
[Unit]
Description=WII-UU release archive (Node.js, port 8081)
After=network.target

[Service]
Type=simple
User=wiiuu-archive
Group=wiiuu-archive
WorkingDirectory=/opt/wiiuu-archive
EnvironmentFile=/etc/wiiuu-archive.env
ExecStart=$node_bin /opt/wiiuu-archive/server.mjs
Restart=on-failure
RestartSec=3
StateDirectory=wiiuu-archive
StateDirectoryMode=0750
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/wiiuu-archive
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
SERVICE
systemctl daemon-reload
systemctl enable wiiuu-archive
systemctl restart wiiuu-archive
echo 'Installed. Cloudflare Tunnel target: http://127.0.0.1:8081'
echo 'Check: curl http://127.0.0.1:8081/healthz'
echo 'Config: /etc/wiiuu-archive.env; persistent releases: /var/lib/wiiuu-archive'
echo 'Token for GitHub secret: sudo sed -n "s/^ARCHIVE_TOKEN=//p" /etc/wiiuu-archive.env'
