#!/bin/bash
# ==============================================================================
# ROUTEPASS / TRANSPORT NAVIGATOR - PRODUCTION VPS DEPLOYMENT SCRIPT
# Supported OS: Ubuntu 20.04 / 22.04 / 24.04 LTS, Debian 11 / 12
# Deploys: PostgreSQL 16 + Node.js 22 + Nginx HTTPS Reverse Proxy + Firewall
# Strict Firewall: Exposes ONLY ports 22, 80, 443 in production.
# ==============================================================================

set -e

echo "=================================================================="
echo "  ROUTEPASS - PRODUCTION VPS DEPLOYMENT INSTALLER"
echo "  Multi-Device Online Transit Management System"
echo "=================================================================="

# Check root privileges
if [ "$EUID" -ne 0 ]; then
  echo "[-] Please run as root or with sudo: sudo bash deploy-vps.sh"
  exit 1
fi

CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="/var/www/routepass"

echo "[1/6] Updating system packages..."
apt-get update -y
apt-get install -y curl wget git build-essential ufw openssl ca-certificates gnupg

# 2. Check Docker and Docker Compose
echo "[2/6] Checking Docker and Docker Compose..."
if ! command -v docker > /dev/null 2>&1; then
  echo "[+] Installing Docker Engine..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
fi

# 3. Setup Application Directory
echo "[3/6] Setting up application files at ${APP_DIR}..."
mkdir -p "${APP_DIR}"
cp -r "${CURRENT_DIR}/docker-compose.yml" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/Dockerfile" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/nginx.conf" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/init-db.sql" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/package.json" "${APP_DIR}/"
cp -r "${CURRENT_DIR}/package-lock.json" "${APP_DIR}/" 2>/dev/null || true
cp -r "${CURRENT_DIR}/src" "${APP_DIR}/"

# 4. Generate Production Secrets
echo "[4/6] Generating secure production environment variables..."
if [ ! -f "${APP_DIR}/.env" ]; then
  RANDOM_JWT_SECRET=$(openssl rand -hex 32)
  RANDOM_ADMIN_SECRET=$(openssl rand -hex 16)
  RANDOM_QR_SECRET=$(openssl rand -hex 32)
  RANDOM_DB_PASS=$(openssl rand -hex 16)

  cat <<EOF > "${APP_DIR}/.env"
NODE_ENV=production
PORT=3000
HOST=0.0.0.0
POSTGRES_DB=routepass_db
POSTGRES_USER=routepass
POSTGRES_PASSWORD=${RANDOM_DB_PASS}
DATABASE_URL=postgres://routepass:${RANDOM_DB_PASS}@db:5432/routepass_db
DATABASE_SSL=false
JWT_SECRET=${RANDOM_JWT_SECRET}
JWT_EXPIRES_IN=30d
ADMIN_REGISTRATION_SECRET=${RANDOM_ADMIN_SECRET}
QR_SIGNING_KEY=${RANDOM_QR_SECRET}
PAYMENT_MODE=TEST
EOF
  echo "[+] Generated secure production .env with unique cryptographic keys."
fi

# 5. Build and Launch Multi-Container Docker Stack
echo "[5/6] Building and starting Docker containers (PostgreSQL + API + Nginx)..."
cd "${APP_DIR}"
docker compose down || true
docker compose up -d --build

# 6. Configure Strict UFW Firewall
echo "[6/6] Configuring UFW Firewall (Exposing ONLY 22, 80, 443)..."
if command -v ufw > /dev/null 2>&1; then
  ufw default deny incoming
  ufw default allow outgoing
  ufw allow 22/tcp comment 'SSH'
  ufw allow 80/tcp comment 'HTTP'
  ufw allow 443/tcp comment 'HTTPS'
  # Port 3000 and 5432 are strictly blocked from public exposure!
  ufw --force enable || true
fi

# 7. Verification
echo "[*] Waiting for services to initialize..."
sleep 5
PUBLIC_IP=$(curl -s -4 icanhazip.com || hostname -I | awk '{print $1}')
HEALTH_STATUS=$(curl -s "http://127.0.0.1/api/health" || echo "FAILED")

echo ""
echo "=================================================================="
echo "  ROUTEPASS VPS DEPLOYMENT COMPLETE!"
echo "=================================================================="
echo "  Public IP        : ${PUBLIC_IP}"
echo "  API Endpoint     : http://${PUBLIC_IP}/api"
echo "  WebSocket Feed   : ws://${PUBLIC_IP}/ws"
echo "  Health Status    : ${HEALTH_STATUS}"
echo ""
echo "  To obtain a free SSL Certificate with Let's Encrypt:"
echo "    apt-get install -y certbot"
echo "    certbot certonly --webroot -w ${APP_DIR}/certbot/www -d yourdomain.et"
echo ""
echo "  Management Commands:"
echo "    - View logs       : cd ${APP_DIR} && docker compose logs -f"
echo "    - Container status: cd ${APP_DIR} && docker compose ps"
echo "    - Restart stack   : cd ${APP_DIR} && docker compose restart"
echo "=================================================================="
