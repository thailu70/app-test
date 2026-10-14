> **Important for an existing Traefik VPS:** Your server already runs Traefik on ports 80/443. Do **not** run `backend/deploy-vps.sh`, which starts Nginx on those same ports. Use the Traefik-specific guide and installer instead: [backend/DEPLOYMENT_TRAEFIK.md](backend/DEPLOYMENT_TRAEFIK.md) and [backend/deploy-traefik-vps.sh](backend/deploy-traefik-vps.sh). This preserves existing proxy containers and does not change UFW.

# RoutePass VPS deployment guide

This guide deploys the **API and PostgreSQL database** from the branch `audit/production-readiness-2026-10-09` behind HTTPS. It is intentionally explicit about two unfinished features: **live Telebirr checkout/callbacks and offline boarding sync are disabled** until provider signing and offline validation are implemented and tested. Do not accept real payments based on this branch.

## 1. Prepare your VPS and DNS

Use a fresh Ubuntu 22.04/24.04 LTS VPS (1–2 vCPU and 2 GB+ RAM is a reasonable starting point; size it after a load test). You need root/sudo access, an SSH port, and a domain or subdomain such as `api.example.com`.

At your DNS provider, create an **A record** for the API domain pointing to your VPS's public IPv4 address. Remove a stale AAAA record unless IPv6 is configured to reach this server. Wait until the domain resolves to the VPS before running Certbot. In both your cloud firewall and VPS firewall, allow inbound SSH, TCP 80, and TCP 443. PostgreSQL 5432 and API 3000 must not be public.

Connect to your VPS:

```bash
ssh <your-ssh-user>@<your-vps-ip>
```

Keep your actual SSH port to hand. The installer changes UFW rules, so pass the correct port as the third argument.

## 2. Fetch this branch and run the installer

```bash
sudo apt-get update
sudo apt-get install -y git
sudo mkdir -p /opt/routepass-source
sudo chown "$USER":"$USER" /opt/routepass-source
git clone https://github.com/thailu70/app-test.git /opt/routepass-source
cd /opt/routepass-source
git fetch origin
git checkout audit/production-readiness-2026-10-09
cd backend
sudo bash deploy-vps.sh api.example.com admin@example.com 22
```

Replace `api.example.com`, `admin@example.com`, and `22` with your own DNS name, email and SSH port. The script installs Docker/Compose and Certbot, generates strong unique secrets at `/var/www/routepass/.env`, initializes the PostgreSQL volume on first boot, gets a Let's Encrypt certificate, activates HTTPS, and starts certificate-renewal automation. The generated environment file is root-readable only. **Do not copy it into GitHub or share its contents.**

The installer is for a **fresh server / empty PostgreSQL volume**. It deliberately will not overwrite an existing `.env` whose `DOMAIN_NAME` differs. If you already have RoutePass data or containers, back up and follow the migration section below before deploying.

## 3. Verify that the stack is up

Run on the VPS:

```bash
cd /var/www/routepass
sudo docker compose ps
sudo docker compose logs --tail=100 db api nginx
curl -fsS https://api.example.com/api/health
curl -fsS https://api.example.com/api/ready
```

Expected health output is minimal JSON with `"status":"HEALTHY"`; readiness should return `"status":"READY"`. The health endpoint is liveness only. Readiness confirms that the database can answer a query. If readiness is failing, inspect logs; do not open ports 3000/5432 to work around it.

WebSocket connections use **`wss://api.example.com/ws`**. The Android client derives its WebSocket address from the configured HTTPS base URL.

Useful operations:

```bash
cd /var/www/routepass
sudo docker compose ps
sudo docker compose logs -f api
sudo docker compose restart api
sudo docker compose exec -T nginx nginx -t
sudo ufw status verbose
```

Do **not** run `docker compose down -v` on a live system; it deletes named persistent volumes and can destroy the database.

## 4. Create the first administrator

Production database initialization no longer inserts demo user accounts or shared default passwords. The first administrator is created through the secret-gated registration endpoint. It is permitted only while no administrator exists.

Use a unique password of at least 10 characters. Read the generated bootstrap secret from `/var/www/routepass/.env` on the server, use it for the initial request, then store the new account credentials securely. The secret should not be committed to source control or pasted into chat or a public support ticket.

Request body fields are:

```json
{
  "fullName": "Your Administrator",
  "phone": "+2519XXXXXXXX",
  "email": "admin@yourdomain.com",
  "password": "A-unique-password-of-10+-characters",
  "role": "ADMIN",
  "adminSecret": "<ADMIN_REGISTRATION_SECRET from the VPS environment>"
}
```

Send that JSON to `POST https://api.example.com/api/auth/register` with `Content-Type: application/json`. The successful response contains the Bearer token. Store that token securely. The endpoint rate limits registration/login. There are no seeded admin credentials to try.

Drivers cannot self-register. After signing in as an admin, create each driver using `POST /api/admin/drivers` with a Bearer admin token and required fields `fullName`, `phone`, `password` (12+ characters), `licenseNumber`, `companyName`, `vehicleId`, and `routeId`. The server makes the assignment and records an audit entry.

## 5. Important production feature gates

### Telebirr — not yet enabled

This branch makes production payment and webhook endpoints return HTTP 503 with `LIVE_TELEBIRR_NOT_CONFIGURED`. That is intentional: the source does not yet demonstrate a validated merchant checkout/order lifecycle, official callback signature format, provider transaction/amount binding, idempotency and reconciliation. **Do not remove this guard simply by adding environment secrets.** Complete the integration against Telebirr's official merchant specifications and sandbox, add tests for altered amount, wrong order, replay, invalid signature and failed/late callback, then enable the endpoint only after those checks pass.

In `PAYMENT_MODE=TEST`, the app has simulated payment behavior. Never use TEST mode with a public endpoint, real passengers, or real accounting.

### Offline check-in sync — disabled

`POST /api/sync/push` currently returns HTTP 409 because unsigned client-originated boarding records could bypass QR, trip, payment and capacity checks. Do not treat locally cached check-ins as authoritative until a server-verifiable offline protocol is implemented.

## 6. Build the Android app for your domain

Build from the repository root on your development machine or CI machine—not on the VPS. In Android Studio, set the Gradle project property `ROUTEPASS_API_BASE_URL` to your HTTPS API root. From a machine with compatible Java/Android SDK/Gradle tooling, the build command is:

```bash
gradle :app:assembleDebug -PROUTEPASS_API_BASE_URL=https://api.example.com/
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or distribute through your internal testing channel. The default URL `https://api.example.com/` is a placeholder; configure your domain for each build. Direct public access to port 3000 is intentionally blocked.

For a release build, configure a dedicated release keystore and protect its passwords in your build environment. The current signing configuration expects alias `upload`; do not publish a release signed with a debug key. Confirm that the HTTPS certificate is trusted by Android and test sign-in, route list, live WSS telemetry and driver location after installing the APK.

## 7. Backups and updates

Make a database backup **before every update or manual schema migration**:

```bash
sudo install -d -m 700 /var/backups/routepass
cd /var/www/routepass
sudo sh -c 'umask 077; docker compose exec -T db pg_dump -U routepass -d routepass_db > /var/backups/routepass/db-$(date +%F-%H%M%S).sql'
```

Copy backups off the VPS and test restores periodically. A backup stored only on the same VPS does not protect against loss of the VPS or disk.

For an application update, fetch the approved commit/branch under `/opt/routepass-source`, review the diff, back up the database, then copy `backend/src`, `backend/Dockerfile`, `backend/docker-compose.yml`, `backend/nginx.conf`, `backend/package.json`, and `backend/package-lock.json` into `/var/www/routepass`. Do not replace the active `nginx.conf` with the bootstrap template. Then run:

```bash
cd /var/www/routepass
sudo docker compose config
sudo docker compose up -d --build
sudo docker compose ps
curl -fsS https://api.example.com/api/ready
```

**Schema caveat:** Docker runs `init-db.sql` automatically only when PostgreSQL initializes a new empty data directory. It is not a migration system; existing named volumes are not automatically upgraded by copying a new SQL file. Write/review a migration for an existing database, take a backup, and execute it explicitly before applying code that requires the change. For an existing installation, inspect and disable any old demo accounts created with known test passwords before opening the service.

## 8. CI and release status

The branch adds a GitHub Actions workflow intended to run JavaScript syntax checks, the backend SQLite integration suite, an API smoke test against PostgreSQL, and an Android debug build. Review the workflow result on the pull request before merging. Do not infer success from the presence of the workflow file alone.

This deployment guide documents the proposed procedure; it does not claim that your VPS, DNS, certificate, credentials, or Telebirr merchant account have been tested from this session.
