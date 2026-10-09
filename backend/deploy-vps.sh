#!/usr/bin/env bash
# RoutePass initial VPS deployment for a fresh Ubuntu/Debian VPS.
# Usage: sudo bash deploy-vps.sh api.yourdomain.com admin@example.com [SSH_PORT]
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo bash deploy-vps.sh api.example.com admin@example.com [SSH_PORT]" >&2
  exit 1
fi
DOMAIN_NAME="${1:-}"
ADMIN_EMAIL="${2:-}"
SSH_PORT="${3:-22}"
if [[ ! "${DOMAIN_NAME}" =~ ^[A-Za-z0-9.-]+$ || "${DOMAIN_NAME}" != *.* ]]; then
  echo "Usage: sudo bash deploy-vps.sh api.yourdomain.com admin@example.com [SSH_PORT]" >&2
  exit 2
fi
if [[ ! "${ADMIN_EMAIL}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "A valid Let's Encrypt email address is required." >&2
  exit 2
fi
if [[ ! "${SSH_PORT}" =~ ^[0-9]{1,5}$ ]] || (( SSH_PORT < 1 || SSH_PORT > 65535 )); then
  echo "SSH_PORT must be between 1 and 65535." >&2
  exit 2
fi

CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="/var/www/routepass"
CERT_DIR="${APP_DIR}/certbot/conf"
WEBROOT_DIR="${APP_DIR}/certbot/www"
CERTBOT_WORK_DIR="${APP_DIR}/certbot/work"
CERTBOT_LOG_DIR="${APP_DIR}/certbot/logs"

echo "[1/7] Installing Docker, Certbot and firewall tooling..."
apt-get update
apt-get install -y ca-certificates curl git openssl ufw certbot
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker
docker compose version >/dev/null

echo "[2/7] Installing RoutePass files in ${APP_DIR}..."
mkdir -p "${APP_DIR}" "${CERT_DIR}" "${WEBROOT_DIR}" "${CERTBOT_WORK_DIR}" "${CERTBOT_LOG_DIR}"
for file in docker-compose.yml Dockerfile nginx.conf nginx.bootstrap.conf init-db.sql package.json package-lock.json; do
  if [[ ! -f "${CURRENT_DIR}/${file}" ]]; then
    echo "Required repository file not found: ${CURRENT_DIR}/${file}" >&2
    exit 3
  fi
  cp "${CURRENT_DIR}/${file}" "${APP_DIR}/${file}"
done
mkdir -p "${APP_DIR}/src"
cp -R "${CURRENT_DIR}/src/." "${APP_DIR}/src/"
chmod -R go-rwx "${APP_DIR}/certbot/conf" || true

echo "[3/7] Creating production secrets (existing .env will never be overwritten)..."
if [[ ! -f "${APP_DIR}/.env" ]]; then
  DB_PASS="$(openssl rand -hex 32)"
  JWT_SECRET="$(openssl rand -hex 32)"
  ADMIN_SECRET="$(openssl rand -hex 32)"
  QR_SECRET="$(openssl rand -hex 32)"
  cat > "${APP_DIR}/.env" <<EOF
NODE_ENV=production
PORT=3000
HOST=0.0.0.0
POSTGRES_DB=routepass_db
POSTGRES_USER=routepass
POSTGRES_PASSWORD=${DB_PASS}
DATABASE_URL=postgres://routepass:${DB_PASS}@db:5432/routepass_db
DATABASE_SSL=false
JWT_SECRET=${JWT_SECRET}
JWT_EXPIRES_IN=30d
ADMIN_REGISTRATION_SECRET=${ADMIN_SECRET}
QR_SIGNING_KEY=${QR_SECRET}
PAYMENT_MODE=PRODUCTION
DOMAIN_NAME=${DOMAIN_NAME}
ADMIN_EMAIL=${ADMIN_EMAIL}
ALLOWED_ORIGINS=https://${DOMAIN_NAME}
TELEBIRR_APP_ID=
TELEBIRR_APP_KEY=
TELEBIRR_SHORT_CODE=
TELEBIRR_PUBLIC_KEY=
TELEBIRR_WEBHOOK_SECRET=
TELEBIRR_QUERY_URL=
TELEBIRR_NOTIFY_URL=https://${DOMAIN_NAME}/api/subscriptions/telebirr/webhook
EOF
  chmod 600 "${APP_DIR}/.env"
else
  if ! grep -Fxq "DOMAIN_NAME=${DOMAIN_NAME}" "${APP_DIR}/.env"; then
    echo "Existing ${APP_DIR}/.env has a different/missing DOMAIN_NAME. Back it up and update DOMAIN_NAME/ADMIN_EMAIL/ALLOWED_ORIGINS manually before redeploying." >&2
    exit 4
  fi
  chmod 600 "${APP_DIR}/.env"
fi

echo "[4/7] Starting PostgreSQL and API with temporary HTTP-only ACME config..."
sed "s/__DOMAIN_NAME__/${DOMAIN_NAME}/g" "${APP_DIR}/nginx.bootstrap.conf" > "${APP_DIR}/nginx.conf"
cd "${APP_DIR}"
docker compose config >/dev/null
docker compose up -d --build db api nginx

echo "[5/7] Configuring firewall. Ensure SSH_PORT (${SSH_PORT}) is correct for this server..."
ufw allow "${SSH_PORT}/tcp" comment 'RoutePass SSH'
ufw allow 80/tcp comment 'RoutePass HTTP and ACME'
ufw allow 443/tcp comment 'RoutePass HTTPS'
ufw default deny incoming
ufw default allow outgoing
ufw --force enable
ufw status verbose

echo "Waiting for PostgreSQL/API readiness through Nginx..."
for attempt in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1/api/ready" >/dev/null; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    docker compose ps
    docker compose logs --tail=100 db api nginx
    echo "Service readiness failed; see logs above." >&2
    exit 5
  fi
  sleep 2
done

echo "Requesting TLS certificate for ${DOMAIN_NAME}..."
# Public DNS A/AAAA records must already point to this VPS and TCP/80 must be reachable.
certbot certonly --non-interactive --agree-tos --email "${ADMIN_EMAIL}" \
  --webroot -w "${WEBROOT_DIR}" \
  --config-dir "${CERT_DIR}" --work-dir "${CERTBOT_WORK_DIR}" --logs-dir "${CERTBOT_LOG_DIR}" \
  -d "${DOMAIN_NAME}"

echo "[6/7] Enabling HTTPS-only Nginx and verifying configuration..."
# Render the committed HTTPS template, not the temporary bootstrap file.
sed "s/__DOMAIN_NAME__/${DOMAIN_NAME}/g" "${CURRENT_DIR}/nginx.conf" > "${APP_DIR}/nginx.conf"
docker compose exec -T nginx nginx -t
docker compose exec -T nginx nginx -s reload

# Certbot renews using the custom certificate directories; reload Nginx after a successful renewal.
cat > /usr/local/sbin/routepass-reload-nginx <<'EOF'
#!/usr/bin/env bash
set -e
cd /var/www/routepass
docker compose exec -T nginx nginx -s reload
EOF
chmod 750 /usr/local/sbin/routepass-reload-nginx
cat > /etc/cron.d/routepass-certbot-renew <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
17 3 * * * root certbot renew --quiet --config-dir ${CERT_DIR} --work-dir ${CERTBOT_WORK_DIR} --logs-dir ${CERTBOT_LOG_DIR} --deploy-hook /usr/local/sbin/routepass-reload-nginx
EOF
chmod 644 /etc/cron.d/routepass-certbot-renew

echo "[7/7] Verifying public health endpoints..."
curl --fail --silent --show-error "https://${DOMAIN_NAME}/api/health"
echo
curl --fail --silent --show-error "https://${DOMAIN_NAME}/api/ready"
echo

cat <<EOF
RoutePass containers have been started with HTTPS.
API:       https://${DOMAIN_NAME}
Health:    https://${DOMAIN_NAME}/api/health
Readiness: https://${DOMAIN_NAME}/api/ready
WebSocket: wss://${DOMAIN_NAME}/ws

IMPORTANT:
- Real Telebirr payment checkout is NOT production-validated yet. Do not accept real-money payments until the provider's official integration contract, signatures, callback flow and reconciliation have passed sandbox tests.
- Create the first administrator through POST /api/auth/register using role=ADMIN and ADMIN_REGISTRATION_SECRET from ${APP_DIR}/.env. This bootstrap is denied once an admin exists.
- Existing PostgreSQL volumes are not reinitialized by init-db.sql. Back up existing data and run the documented migration/seed-account cleanup before using this release against an old volume.
- The environment file is root-only. Never commit it or share its secrets.
EOF
