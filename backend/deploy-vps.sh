#!/bin/bash
# ==============================================================================
# TRANSPORT NAVIGATOR - 1-CLICK VPS DEPLOYMENT SCRIPT
# Supported OS: Ubuntu 20.04 / 22.04 / 24.04 LTS, Debian 11 / 12
# ==============================================================================

set -e

echo "=================================================================="
echo "  TRANSPORT NAVIGATOR - VPS PRODUCTION INSTALLER"
echo "  Addis Ababa Transit Management System Backend"
echo "=================================================================="

# 1. Check Root Privileges
if [ "$EUID" -ne 0 ]; then
  echo "[-] Please run as root or with sudo: sudo bash deploy-vps.sh"
  exit 1
fi

APP_DIR="/var/www/transport-backend"
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[1/7] Updating system packages..."
apt-get update -y
apt-get install -y curl wget git build-essential ufw openssl

# 2. Install Node.js 22 LTS if not installed or outdated
echo "[2/7] Checking Node.js installation..."
if ! command -v node > /dev/null 2>&1 || [ "$(node -v | cut -d'.' -f1 | tr -d 'v')" -lt 20 ]; then
  echo "[+] Installing Node.js 22 LTS from NodeSource..."
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

echo "    Node.js version: $(node -v)"
echo "    NPM version    : $(npm -v)"

# 3. Setup Application Directory
echo "[3/7] Setting up application directory at ${APP_DIR}..."
mkdir -p "${APP_DIR}"
mkdir -p "${APP_DIR}/data"
mkdir -p "${APP_DIR}/logs"

# Copy project files
cp -r "${CURRENT_DIR}/package.json" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/package-lock.json" "${APP_DIR}/" 2>/dev/null || true
cp -r "${CURRENT_DIR}/src" "${APP_DIR}/"

# 4. Install Dependencies
echo "[4/7] Installing production dependencies..."
cd "${APP_DIR}"
npm install --omit=dev

# 5. Generate Secure Environment Variables
echo "[5/7] Configuring environment variables..."
if [ ! -f "${APP_DIR}/.env" ]; then
  RANDOM_SECRET=$(openssl rand -hex 32)
  cat <<EOF > "${APP_DIR}/.env"
NODE_ENV=production
PORT=3000
HOST=0.0.0.0
JWT_SECRET=${RANDOM_SECRET}
JWT_EXPIRES_IN=30d
DATA_DIR=${APP_DIR}/data
EOF
  echo "[+] Generated new secure JWT secret in .env"
fi

# 6. Configure Systemd Service
echo "[6/7] Installing systemd service..."
cat <<EOF > /etc/systemd/system/transport-backend.service
[Unit]
Description=Transport Navigator Transit Backend Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}
ExecStart=$(which node) src/server.js
Restart=always
RestartSec=5
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=transport-backend
EnvironmentFile=${APP_DIR}/.env

LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable transport-backend
systemctl restart transport-backend

# 7. Configure Firewall (UFW)
echo "[7/7] Configuring UFW firewall..."
if command -v ufw > /dev/null 2>&1; then
  ufw allow 22/tcp || true
  ufw allow 80/tcp || true
  ufw allow 443/tcp || true
  ufw allow 3000/tcp || true
  # Do not force enable ufw if user has disabled it
fi

# Verification
sleep 3
PUBLIC_IP=$(curl -s -4 icanhazip.com || hostname -I | awk '{print $1}')
STATUS=$(curl -s "http://127.0.0.1:3000/api/health" || echo "FAILED")

echo ""
echo "=================================================================="
echo "  DEPLOYMENT COMPLETE!"
echo "=================================================================="
echo "  Backend Status : ${STATUS}"
echo "  Server URL     : http://${PUBLIC_IP}:3000"
echo "  API Health     : http://${PUBLIC_IP}:3000/api/health"
echo "  WebSocket Feed : ws://${PUBLIC_IP}:3000/ws"
echo ""
echo "  Useful Management Commands:"
echo "    - Check status : systemctl status transport-backend"
echo "    - View logs    : journalctl -u transport-backend -f"
echo "    - Restart app  : systemctl restart transport-backend"
echo "    - Stop app     : systemctl stop transport-backend"
echo "=================================================================="
