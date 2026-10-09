# RoutePass Production-Readiness Audit

**Repository:** `thailu70/app-test`  
**Audit branch:** `audit/production-readiness-2026-10-09`  
**Audit date:** 2026-10-09  
**Scope inspected:** Android payment client, Android manifest/network configuration, Node.js API/authentication/payment/check-in/WebSocket code, database abstraction and PostgreSQL schema, Docker Compose, Dockerfile, Nginx, deployment script, environment examples, and existing test sources.

## Executive verdict

**Do not launch with real passengers or real money yet.** The repository has meaningful feature code, but several production-critical assumptions are not supported by the implementation. In particular, the PostgreSQL path appears incompatible with synchronous database calls used throughout the API, and the payment path has previously accepted unsigned webhook callbacks and simulated successful payments. This is a code-inspection audit, not a claim that every endpoint was executed.

## Findings

### P0 — Payment integrity and financial safety

1. **Webhook verification was fail-open.** In `backend/src/routes/subscriptions.js`, the old handler only checked the HMAC when both a secret and signature existed; otherwise it continued. It also allowed an unsolicited callback to create a transaction directly as `VERIFIED`. That could allow a forged callback to create false payment evidence.
   - **Branch fix:** webhook now rejects requests when `TELEBIRR_WEBHOOK_SECRET` is not configured, rejects missing/invalid signatures, compares signatures with `timingSafeEqual`, validates a positive amount and required references, checks an existing server-side order and amount, and refuses unknown orders.
   - **Important integration caveat:** the canonical HMAC payload in this code must be confirmed against the official Telebirr merchant integration specification. This patch deliberately fails closed; it does not prove that the existing payment flow is integrated correctly.

2. **Test mode can grant a paid subscription without a real payment.** `backend/src/routes/subscriptions.js` treats payment as verified whenever `PAYMENT_MODE !== 'PRODUCTION'`; the prior deployment script wrote `PAYMENT_MODE=TEST`. This is acceptable only in isolated development with test data, never in production.
   - **Branch fix:** deployment script now selects `PAYMENT_MODE=PRODUCTION`; Compose requires an explicit payment mode rather than silently defaulting to TEST.

3. **Android network failure could become a locally successful payment.** `app/src/main/java/com/example/core/payment/TelebirrGateway.kt` previously called `repository.processOfflinePayment(...)` after any exception. That helper creates an `OFFLINE_PASS` QR token and a local active subscription without server payment verification.
   - **Branch fix:** network/verification exceptions now return `PAYMENT_STATUS_UNKNOWN`; the app no longer treats an unreachable server as proof of payment.

4. **Payment flow is not yet demonstrably end-to-end.** The current pay endpoint attempts to verify a provider reference or idempotency key before recording a transaction, while the webhook patch now requires a pre-existing server-side order. A secure payment-initiation/order lifecycle, provider-signed callback contract, amount/currency/order binding, replay handling, and reconciliation still need to be implemented and tested against Telebirr's official sandbox.

### P0 — PostgreSQL production path likely does not work as advertised

5. **Database API is asynchronous for PostgreSQL but many route handlers use it synchronously.** In `backend/src/db.js`, PostgreSQL `prepare().get/run/all` return Promises. For example, `backend/src/routes/auth.js` calls `DB.prepare(...).get(...)` without `await`, then immediately treats the result as a row. Similar patterns exist across routes. SQLite mode is synchronous, which can hide this defect in local tests.
   - **Impact:** PostgreSQL-backed endpoints may receive Promises where they expect rows and fail or make incorrect decisions.
   - **Required remediation:** choose one consistent asynchronous database interface and convert all callers, or use a driver/adapter with a compatible contract. Then run the full API suite against a real PostgreSQL instance.

6. **The transaction wrapper does not reliably make route operations transactional in PostgreSQL.** `DB.transaction(fn)` opens a transaction on a checked-out client, but the route callback uses the global `DB.prepare(...)` interface, whose queries go through the pool rather than that checked-out client. The check-in capacity flow therefore cannot be assumed atomic in PostgreSQL.
   - **Required remediation:** expose a transaction-scoped query interface bound to the checked-out client; perform the read/lock/check/update on that client. Add concurrent integration tests that attempt to board beyond capacity.

7. **PostgreSQL payment status constraint conflicts with application code.** `backend/init-db.sql` constrains `payment_transactions.status` to `COMPLETED`, `PENDING`, or `FAILED`, while the webhook/verifier queries and writes `VERIFIED`. PostgreSQL will reject that status update.
   - **Required remediation:** define a consistent payment state machine and migration, then test it on PostgreSQL. Do not rely on SQLite behavior as proof of PostgreSQL compatibility.

### P1 — Authorization and data isolation

8. **WebSocket GPS authorization trusts client-controlled identifiers.** In `backend/src/server.js`, the GPS handler can derive the driver identity from `data.driverId`, accepts client-supplied `vehicleId` and `routeId`, and does not verify that the authenticated driver is assigned to that vehicle. A valid driver token could potentially spoof another vehicle's location or route.
   - **Required remediation:** derive driver identity exclusively from the verified token; authorize the vehicle against a server-side assignment; derive route from the database; reject unknown/unassigned vehicles; and test cross-vehicle spoofing.

9. **Authenticated check-in endpoints lack role and ownership restrictions.** `GET /api/checkins/recent` and `GET /api/checkins/trip/:tripId` require authentication but do not require an admin/dispatcher/assigned driver role. The recent endpoint returns passenger contact data through joined records.
   - **Required remediation:** enforce least-privilege role and resource-level authorization, and minimize passenger PII in responses.

10. **Public self-registration allows DRIVER accounts with client-selected assignment fields.** `backend/src/routes/auth.js` accepts `role=DRIVER` and client-supplied vehicle/route assignment values during public registration. Role gating alone does not verify a driver's identity or authorization to operate a vehicle.
   - **Required remediation:** driver accounts should be invited or approved by an authorized operator; assignments must be created server-side by an authorized admin.

### P1 — Deployment and infrastructure

11. **Nginx does not enforce HTTPS as currently written.** `backend/nginx.conf` proxies requests over port 80, while the HTTPS server block and HTTP-to-HTTPS redirect are commented out. The deployment script prints an HTTP API URL and does not itself install/configure a valid domain certificate.
   - **Required remediation:** configure a real domain and valid certificate, enable HTTPS and redirect HTTP to HTTPS, verify WebSocket upgrades over WSS, and test certificate renewal before release.

12. **Docker Compose previously contained predictable secret fallbacks.**
   - **Branch fix:** database password, JWT secret, admin-registration secret, and payment mode now require explicit environment values instead of silently using hard-coded defaults.
   - **Caveat:** operators must still replace all placeholder values in `backend/.env.example`; placeholders are not production secrets.

13. **Seeded credentials are unsafe for a real deployment.** The SQLite fallback seeds known admin/driver/passenger accounts using password `123456`; `backend/init-db.sql` also seeds default users with a hash described as the hash for `123456`. Production must never retain these accounts or known credentials.
   - **Required remediation:** remove demo users from production migrations; use a separate explicit development seed command; rotate any credentials on an environment that has already been deployed.

14. **Health endpoint exposes operational counts and returns raw database error messages.** `backend/src/server.js` exposes user/route/vehicle/check-in counts publicly and includes `err.message` in a 500 response.
   - **Required remediation:** keep public liveness output minimal, restrict detailed readiness/metrics to trusted monitoring, and return generic error messages to clients.

### P1 — Test quality and release evidence

15. **The GPS load test is not a load test of the production WebSocket implementation.** `backend/test/gps-load-test.js` builds a separate mock server with its own in-memory logic. Passing it would not validate the real authorization, database writes, or broadcasts in `backend/src/server.js`.

16. **SQLite-only success would not validate PostgreSQL behavior.** The existing API test file logs a suite of scenarios, but the repository state alone does not establish that those tests were executed against PostgreSQL. The asynchronous adapter and schema-state mismatch make that distinction critical.

17. **No tests were executed as part of this audit.** This session can inspect and commit repository files through the GitHub integration, but it does not provide a project runtime to run Gradle, Docker Compose, Node tests, or a real PostgreSQL service. No passing-test claim is made.

## Changes made on this branch

- Fail-closed Telebirr webhook checks and no unsolicited verified transactions.
- Removed Android offline payment-success fallback.
- Removed insecure Docker Compose fallbacks for secrets/payment mode.
- Changed generated deployment configuration to select production payment mode explicitly.
- Updated the environment example to document the webhook secret and query URL.

## Required release gates before production

1. Fix the PostgreSQL async adapter and transaction-scoped queries.
2. Complete and verify the Telebirr order/checkout/webhook integration using the official merchant specification and sandbox.
3. Remove default seeded credentials from production and implement driver approval/assignment authorization.
4. Fix WebSocket vehicle ownership checks and check-in data access controls.
5. Enable HTTPS/WSS with a valid domain certificate and verify deployment from a clean VPS.
6. Add CI that runs Android unit/build checks, backend tests against both SQLite (if retained) and PostgreSQL, dependency/security scanning, and payment/WebSocket authorization regression tests.
7. Run the tests and review the diff before merging this branch.

**Release recommendation: NO-GO** until P0 findings are resolved and tested. This branch is a security-hardening start, not a declaration that RoutePass is production-ready.
