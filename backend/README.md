# RoutePass API backend

Node.js/Express API and WebSocket service for RoutePass. The production topology is Docker Compose: PostgreSQL 16 on a private network, API on port 3000 exposed only to the Docker network, and Nginx on public ports 80/443 terminating HTTPS and proxying REST/WSS.

## Current safety boundaries

- Production refuses to start without a PostgreSQL `DATABASE_URL`, a `PAYMENT_MODE=PRODUCTION`, a sufficiently long `QR_SIGNING_KEY`, and explicit application secrets.
- Passengers and drivers self-register. A driver must register their own commercial licence and vehicle details; the server creates an owned vehicle with no route. Only an authenticated administrator can assign an active route and assign that driver-owned vehicle to a passenger subscription. Admin bootstrap requires `ADMIN_REGISTRATION_SECRET` and is closed after the first admin.
- Driver trips, GPS telemetry and boarding checks verify server-side vehicle/trip assignments.
- PostgreSQL route calls use an asynchronous API. Transaction callbacks must use the transaction-scoped `tx.prepare(...)` interface so locks and writes remain on the same connection.
- The app now includes an H5 C2B hosted-checkout path using the official token, signed preorder, hosted checkout URL, signed-notification validation and server-side `queryOrder` reconciliation flow. The live path remains fail-closed with `503 LIVE_TELEBIRR_NOT_CONFIGURED` until the merchant settings are configured on the VPS and the approved sandbox has been fully tested. Do not process real customer payments until Ethio Telecom confirms production credentials and acceptance tests pass; see `TELEBIRR_H5_SETUP.md`.
- Offline `/api/sync/push` boarding imports are disabled (HTTP 409) until a signed, verifiable offline protocol exists.
- PostgreSQL production initialization omits all default user credentials. The SQLite-only developer test fallback seeds mock accounts for the legacy integration test; never deploy SQLite/test mode publicly.

## Browser admin portal and account provisioning

After the admin portal files have been deployed, open `https://YOUR-DOMAIN/admin/`. The administrator signs in with the phone number used during first-admin setup (the phone number serves as the username) and their chosen password. To securely create the first administrator on the VPS, use `python3 /var/www/routepass/scripts/bootstrap-admin.py`; it reads the private `ADMIN_REGISTRATION_SECRET` from the deployment environment file and prompts for the password without echoing it. No default admin username/password is shipped.

Drivers self-register with their own phone/password, commercial licence, vehicle plate, model and vehicle class. They cannot choose their own route at sign-up; the administrator assigns an active route later through the browser portal. Administrators cannot create driver accounts or transfer vehicle ownership.

## Manual test recharge

Free `TEST_AUTO` payments are disabled by default with `PAYMENT_TESTS_ENABLED=false`, for both daily commute and intercity. Account phone verification being optional does not enable these free payments. If the operator explicitly enables them later, daily commute additionally requires `PAYMENT_TEST_PASSENGER_ID` to identify one approved passenger account whose current phone is `0912469923`; a request body cannot configure that identity. Intercity still requires its existing reservation-bound SMS ownership proof. Disabling the switch blocks new test-code requests, verification and free grants, including previously issued proofs. It also blocks the legacy payment simulator, `ADMIN_TEST` recharges and Telebirr provider sandbox checkout, query and notification verification. Existing paid tickets remain valid. Ordinary production-provider payments retain their server confirmation checks.

The web administrator can temporarily activate a passenger subscription for testing. This creates an audited `ADMIN_TEST` record, issues a signed QR pass and does **not** represent a Telebirr payment. The endpoint requires `PAYMENT_TESTS_ENABLED=true`, `ALLOW_MANUAL_TEST_RECHARGE=true` and `NODE_ENV=test`. The legacy Telebirr payment simulator also requires both the global switch and `NODE_ENV=test`. Keep these disabled for real public operation. Entries from `ADMIN_TEST` are excluded from real-revenue totals. The separate explicitly enabled, audited `ADMIN_GRANT` business action remains independent of the test-payment switch.

## Existing VPS updates

For an already-running RoutePass installation behind the existing Traefik host, use [Updating an existing RoutePass VPS](UPDATING_EXISTING_VPS.md). Do not rerun the first-install script or delete the PostgreSQL volume.

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


## Driver applications and account recovery

Website and Android driver registrations use the same onboarding application. The legacy `/api/auth/register` driver contract delegates to this workflow and never grants an operational token. Daily drivers propose endpoints and a monthly whole-vehicle fee; matching routes calculate passenger fare multiplied by vehicle capacity. The initial route acceptance unlocks documents and profile completion. Final approval requires document completion and explicit submission. Account phone verification remains optional for DRIVER/PASSENGER; COMPANY verification policy is unchanged.

Review actions have separate purposes: `ACCEPT` accepts the initial daily proposal, `CORRECTIONS` requires a review note and retains accepted route/upload access, `APPROVE` completes review, and `REJECT` returns initial acceptance. `SUSPEND`/`REACTIVATE` preserve approval history; older broken suspended applications return to PENDING for repair rather than bypassing review. Saving an unchanged canonical phone does not invalidate approval. Previously untracked pending drivers are enrolled without granting acceptance or approval; established active legacy accounts retain their existing access.

`POST /api/account-recovery/request` accepts role and phone and always returns a generic response. `POST /api/account-recovery/reset` additionally requires the delivered six-digit SMS code and a new password of at least ten characters and at most 72 UTF-8 bytes. These recovery proofs remain mandatory even while signup OTP is optional. Codes are hashed, expire after ten minutes, have five guesses and are single-use. A successful reset revokes old normal, onboarding and account-management sessions without changing approval or phone-verification status. Login proofs bind the password hash and session version from one database snapshot.

Application decision SMS messages are queued transactionally and delivered after commit, with bounded retries and no historical backfill. Failed delivery does not undo approval. Live SMS delivery requires a working configured provider. Automated tests inject transports and do not send real SMS. Free test payments remain disabled unless separately and explicitly enabled in an isolated test environment.
