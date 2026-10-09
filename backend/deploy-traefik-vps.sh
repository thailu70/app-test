#!/usr/bin/env bash
# Deploy RoutePass behind an existing Traefik instance without taking over ports 80/443.
# Usage: sudo bash deploy-traefik-vps.sh api.example.com admin@example.com [TRAEFIK_CONTAINER]
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash deploy-traefik-vps.sh api.example.com admin@example.com [TRAEFIK_CONTAINER]" >&2
  exit 1
fi

DOMAIN_NAME="${1:-}"
ADMIN_EMAIL="${2:-}"
TRAEFIK_CONTAINER="${3:-traefik-traefik-1}"
APP_DIR="/var/www/routepass"
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! "${DOMAIN_NAME}" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]; then
  echo "Provide a real DNS hostname, e.g. api.example.com (not an IP address)." >&2
  exit 2
fi
if [[ ! "${ADMIN_EMAIL}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "Provide a valid administrative email address." >&2
  exit 2
fi
for cmd in docker curl getent openssl; do
  command -v "${cmd}" >/dev/null 2>&1 || {
    echo "Required command '${cmd}' is missing. Install it before continuing." >&2
    exit 2
  }
done
docker compose version >/dev/null

if ! docker inspect "${TRAEFIK_CONTAINER}" >/dev/null 2>&1; then
  echo "Traefik container '${TRAEFIK_CONTAINER}' was not found. No changes made." >&2
  exit 3
fi
if [[ "$(docker inspect -f '{{.State.Running}}' "${TRAEFIK_CONTAINER}")" != "true" ]]; then
  echo "Traefik container '${TRAEFIK_CONTAINER}' is not running. No changes made." >&2
  exit 3
fi
if [[ "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "${TRAEFIK_CONTAINER}")" != "host" ]]; then
  echo "This installer is specifically for the verified host-network Traefik setup. No changes made." >&2
  exit 3
fi

TRAEFIK_ARGS="$(docker inspect -f '{{range .Config.Cmd}}{{println .}}{{end}}' "${TRAEFIK_CONTAINER}")"
for expected in \
  '--providers.docker=true' \
  '--providers.docker.exposedbydefault=false' \
  '--entrypoints.web.address=:80' \
  '--entrypoints.websecure.address=:443' \
  '--certificatesresolvers.letsencrypt.acme.httpchallenge=true' \
  '--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web'; do
  if ! grep -Fqx -- "${expected}" <<<"${TRAEFIK_ARGS}"; then
    echo "Traefik is missing required setting ${expected}. No changes made." >&2
    exit 3
  fi
done

echo "Checking public DNS for ${DOMAIN_NAME}..."
DNS_IPV4="$(getent ahostsv4 "${DOMAIN_NAME}" | awk '{print $1}' | sort -u || true)"
if [[ -z "${DNS_IPV4}" ]]; then
  echo "The hostname does not currently resolve to an IPv4 address." >&2
  echo "Create/update its DNS A record to point to this VPS, wait for DNS propagation, then retry." >&2
  exit 4
fi
PUBLIC_IPV4="$(curl -4fsS --connect-timeout 3 --max-time 5 https://api.ipify.org 2>/dev/null || true)"
if [[ -n "${PUBLIC_IPV4}" ]] && ! grep -Fxq -- "${PUBLIC_IPV4}" <<<"${DNS_IPV4}"; then
  echo "DNS does not point to this VPS's detected public IPv4 address." >&2
  echo "Detected VPS IP: ${PUBLIC_IPV4}; hostname resolves to: ${DNS_IPV4//$'\n'/, }" >&2
  echo "Correct the DNS A record before requesting a certificate. No application changes made." >&2
  exit 4
fi

for name in routepass_api routepass_postgres routepass_nginx; do
  if docker inspect "${name}" >/dev/null 2>&1; then
    echo "A container named '${name}' already exists. This first-install script will not replace it." >&2
    echo "Inspect and back up the existing RoutePass installation before proceeding." >&2
    exit 5
  fi
done

if [[ ! -f "${APP_DIR}/.env" ]] && docker volume inspect routepass_postgres_data >/dev/null 2>&1; then
  echo "A RoutePass PostgreSQL volume already exists but the environment file is missing." >&2
  echo "For safety, this script will not attach to an unknown database volume." >&2
  exit 5
fi

echo "Preparing files in ${APP_DIR}..."
mkdir -p "${APP_DIR}" "${APP_DIR}/src"
for file in docker-compose.yml docker-compose.traefik.yml Dockerfile nginx.conf nginx.bootstrap.conf init-db.sql package.json package-lock.json; do
  [[ -f "${CURRENT_DIR}/${file}" ]] || {
    echo "Required source file missing: ${CURRENT_DIR}/${file}" >&2
    exit 6
  }
  install -m 0644 "${CURRENT_DIR}/${file}" "${APP_DIR}/${file}"
done
cp -R "${CURRENT_DIR}/src/." "${APP_DIR}/src/"
chmod -R go-rwx "${APP_DIR}"

echo "Preparing protected environment file..."
if [[ ! -f "${APP_DIR}/.env" ]]; then
  DB_PASS="$(openssl rand -hex 32)"
  JWT_SECRET="$(openssl rand -hex 32)"
  ADMIN_SECRET="$(openssl rand -hex 32)"
  QR_SECRET="$(openssl rand -hex 32)"
  cat >"${APP_DIR}/.env" <<EOF
NODE_ENV=production
PORT=3000
HOST=0.0.0.0
POSTGRES_DB=routepass_db
POSTGRES_USER=routepass
POSTGRES_PASSWORD=${DB_PASS}
JWT_SECRET=${JWT_SECRET}
JWT_EXPIRES_IN=30d
ADMIN_REGISTRATION_SECRET=${ADMIN_SECRET}
QR_SIGNING_KEY=${QR_SECRET}
PAYMENT_MODE=PRODUCTION
DOMAIN_NAME=${DOMAIN_NAME}
ROUTEPASS_DOMAIN=${DOMAIN_NAME}
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
  chmod 600 "${APP_DIR}/.env"
  EXISTING_DOMAIN="$(sed -n 's/^ROUTEPASS_DOMAIN=//p' "${APP_DIR}/.env" | head -n 1)"
  if [[ "${EXISTING_DOMAIN}" != "${DOMAIN_NAME}" ]]; then
    echo "An environment file already exists and ROUTEPASS_DOMAIN does not match ${DOMAIN_NAME}." >&2
    echo "No secrets were changed. Review /var/www/routepass/.env manually before retrying." >&2
    exit 5
  fi
  for key in POSTGRES_PASSWORD JWT_SECRET ADMIN_REGISTRATION_SECRET QR_SIGNING_KEY; do
    if ! grep -Eq "^(${key})=.+$" "${APP_DIR}/.env"; then
      echo "The existing environment file is missing ${key}. No changes made." >&2
      exit 5
    fi
  done
fi

cd "${APP_DIR}"
COMPOSE=(docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml)
echo "Validating Compose configuration..."
"${COMPOSE[@]}" config --quiet

echo "Starting RoutePass API and private PostgreSQL. Existing Traefik and 9router are not restarted..."
"${COMPOSE[@]}" up -d --build db api

echo "Waiting for the API/database readiness check..."
ready=0
for attempt in $(seq 1 45); do
  if "${COMPOSE[@]}" exec -T api curl -fsS --connect-timeout 2 --max-time 4 http://127.0.0.1:3000/api/ready >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "${ready}" -ne 1 ]]; then
  "${COMPOSE[@]}" ps
  "${COMPOSE[@]}" logs --tail=80 db api
  echo "The internal API readiness check failed. Do not expose API or PostgreSQL ports; inspect the logs above." >&2
  exit 7
fi

echo "Waiting for HTTPS routing and the Traefik Let's Encrypt certificate..."
public_ready=0
for attempt in $(seq 1 30); do
  if curl -fsS --connect-timeout 3 --max-time 6 "https://${DOMAIN_NAME}/api/ready" >/tmp/routepass-public-ready.$$.json 2>/dev/null; then
    public_ready=1
    break
  fi
  sleep 3
done
if [[ "${public_ready}" -ne 1 ]]; then
  rm -f "/tmp/routepass-public-ready.$$.json"
  "${COMPOSE[@]}" ps
  echo "Internal API readiness passed, but public HTTPS did not become ready." >&2
  echo "Check cloud/provider firewall allows inbound TCP 80 and 443, DNS A record, and Traefik logs:" >&2
  docker logs --tail=100 "${TRAEFIK_CONTAINER}" >&2 || true
  exit 8
fi

echo "API health/readiness response:"
cat "/tmp/routepass-public-ready.$$.json"
echo
rm -f "/tmp/routepass-public-ready.$$.json"
echo
"${COMPOSE[@]}" ps
cat <<EOF
RoutePass has been deployed behind the existing Traefik proxy.
API:       https://${DOMAIN_NAME}
Health:    https://${DOMAIN_NAME}/api/health
Readiness: https://${DOMAIN_NAME}/api/ready
WebSocket: wss://${DOMAIN_NAME}/ws

No UFW rules or existing proxy containers were changed.
Keep /var/www/routepass/.env private (mode 600). Do not paste its contents into chat or commit it to Git.
Live Telebirr checkout and offline boarding sync remain disabled until their required provider/server-side validation is implemented.
EOF
