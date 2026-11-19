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
install -m 644 server.mjs client.mjs package.json /opt/wiiuu-archive/
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
