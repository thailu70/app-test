# RoutePass API backend

Node.js/Express API and WebSocket service for RoutePass. The production topology is Docker Compose: PostgreSQL 16 on a private network, API on port 3000 exposed only to the Docker network, and Nginx on public ports 80/443 terminating HTTPS and proxying REST/WSS.

## Current safety boundaries

- Production refuses to start without a PostgreSQL `DATABASE_URL`, a `PAYMENT_MODE=PRODUCTION`, a sufficiently long `QR_SIGNING_KEY`, and explicit application secrets.
- Public self-registration can create passengers only. Admin bootstrap requires `ADMIN_REGISTRATION_SECRET` and is closed after the first admin. Administrators provision driver accounts and assign vehicles/routes.
- Driver trips, GPS telemetry and boarding checks verify server-side vehicle/trip assignments.
- PostgreSQL route calls use an asynchronous API. Transaction callbacks must use the transaction-scoped `tx.prepare(...)` interface so locks and writes remain on the same connection.
- Production Telebirr checkout and callbacks deliberately return `503 LIVE_TELEBIRR_NOT_CONFIGURED` until the official merchant checkout/signature/reconciliation flow is implemented and tested. Do not accept real-money payment through this build.
- Offline `/api/sync/push` boarding imports are disabled (HTTP 409) until a signed, verifiable offline protocol exists.
- PostgreSQL production initialization omits all default user credentials. The SQLite-only developer test fallback seeds mock accounts for the legacy integration test; never deploy SQLite/test mode publicly.

## Browser admin portal and account provisioning

After the admin portal files have been deployed, open `https://YOUR-DOMAIN/admin/`. The administrator signs in with the phone number used during first-admin setup (the phone number serves as the username) and their chosen password. To securely create the first administrator on the VPS, use `python3 /var/www/routepass/scripts/bootstrap-admin.py`; it reads the private `ADMIN_REGISTRATION_SECRET` from the deployment environment file and prompts for the password without echoing it. No default admin username/password is shipped.

Driver self-registration is intentionally rejected. An authenticated administrator creates drivers through `POST /api/admin/drivers`, choosing an available vehicle and active route. This prevents a public sign-up from creating an operational driver account without assignment.

## Deployment

Follow the root [VPS deployment guide](../DEPLOYMENT.md). It covers DNS, the domain-aware installer, TLS, bootstrap administrator creation, backups, application updates, Android URL configuration, and current feature restrictions.

The container stack files are `docker-compose.yml`, `Dockerfile`, `nginx.conf`, and `nginx.bootstrap.conf`. The bootstrap Nginx configuration is only for initial certificate issuance; the installer replaces it with the HTTPS configuration.

## Local backend checks

Use Node.js 22+ (the current tests rely on `node:sqlite`):

```bash
npm ci
find src test -type f -name '*.js' -print0 | xargs -0 -n1 node --check
npm test
```

PostgreSQL smoke check (requires a fresh PostgreSQL 16 instance with `init-db.sql` applied):

```bash
DATABASE_URL=postgres://routepass:YOUR_PASSWORD@127.0.0.1:5432/routepass_db \
NODE_ENV=test PAYMENT_MODE=PRODUCTION \
JWT_SECRET=development-only-secret-change-me \
ADMIN_REGISTRATION_SECRET=local-test-admin-secret-change-me \
QR_SIGNING_KEY=local-test-qr-secret-change-me \
node test/postgres-smoke.js
```

The test should be run against an isolated database only. The smoke test creates users/vehicle assignments and leaves test records in the database.

## Endpoints to verify after deployment

- `GET /api/health` — public liveness check with minimal output.
- `GET /api/ready` — database readiness.
- `GET /api/routes` — route list.
- `wss://your-domain/ws` — authenticated live telemetry.
