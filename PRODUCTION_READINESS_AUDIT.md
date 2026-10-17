# RoutePass Production Readiness Audit

**Audit date:** 2026-10-17
**Repository:** `thailu70/app-test`
**Target branch:** `feature/passo-dual-ownership-company-portal-2026-10-14`
**Scope:** Android passenger/driver/admin/company flows; admin and company web portals; authentication and roles; company tenant boundaries; routes, fleet, trips, GPS, check-ins, subscriptions, PostgreSQL behavior, CI and Android packaging.

## Verdict

The source needed several security and operational fixes. This change set addresses the code-level issues listed below and adds PostgreSQL regression coverage. **It is not a certification that the system is fully production-ready.** The final release decision depends on green GitHub Actions for this exact revision, a fresh APK build, an install check on a real Android device, and confirming the VPS has pulled this revision.

Payment and OTP are intentionally excluded from this audit’s implementation scope. Live Telebirr remains fail-closed; a debug prototype APK is not a Play Store release.

## VPS evidence supplied during this audit

The operator’s checks reported HTTP 200 from both the container-local and public `/api/ready` endpoints, healthy API and PostgreSQL containers, the expected Traefik host rule, and dated database/source backups. This confirms the checked VPS was healthy at that time. It does not prove the VPS is running the revision produced by this audit, and the backup restore path has not been exercised here.

## Changes implemented

- Protected HTTP requests now load the account’s current role and active status from PostgreSQL/SQLite. Existing driver and company sessions stop authorizing requests after suspension. Database verification failures fail closed as a temporary service error.
- WebSocket connections validate current accounts, periodically re-check sessions, and scope fleet updates to administrators, the owning company, the assigned driver, or a passenger’s subscribed route. GPS updates now persist to the live-location table as well as the vehicle record.
- Driver GPS endpoints require an active assigned trip, validate coordinates and speed, enforce a per-driver update limit, and only return fresh locations during an active trip. Public vehicle responses no longer expose driver identity, company IDs, or precise coordinates; authorized admin/company clients retain their scoped fleet view.
- Company portal endpoints now have a single route implementation. Company access codes use PostgreSQL boolean values, expire after seven days, are single-use under a transaction lock, and company signup/login have dedicated rate limits.
- Company route assignments now require a company-owned active route, driver and vehicle. Assignment checks and writes run in one transaction, prevent double assignment, block changes during active trips, create audit entries, and support soft unassignment without deleting company vehicles.
- Passenger rosters shown to companies are limited to active paid subscriptions on their active assignments.
- Manual test recharges require both the test environment and the explicit test flag. The admin screen hides this control outside test mode.
- The company portal now uses an external JavaScript file under a route-specific Content Security Policy, and its form fields have explicit accessible names. Admin route editing uses the existing labeled form instead of chained browser prompts.
- The Android prototype version is incremented to `1.0.2` / version code `3` so the updated build can replace the older APK on a device.
- PostgreSQL CI now exercises company invitation and registration, expired invitation rejection, company-owned route/vehicle/driver assignment, cross-company assignment rejection, active-trip unassignment protection, public vehicle data minimization, production test-recharge rejection, and token invalidation after suspension.

## Release gates still open

1. Run CI and the Android workflow against the final pushed commit; this audit must not be treated as passing until the PostgreSQL and Android jobs are green.
2. Install the generated debug APK on an Android device and walk the passenger, driver, admin, and company flows. CI proves compilation, not device behavior or visual acceptance.
3. Pull this exact GitHub commit on the VPS, then re-run `/api/ready`, public HTTPS, container health, backup, and key API checks. The VPS update is not performed by this document.
4. Keep real-money payment unavailable until the official Telebirr checkout, signed callback, order/amount binding, replay protection, and reconciliation pass sandbox and production review. OTP remains deferred by request.
5. A signed distribution APK needs the production keystore and signing secrets. They are not present in this workspace; the CI artifact is an installable **debug/test APK**.
6. Test backup restoration, production monitoring/alerting, and real load on the target VPS before opening the service to passengers.

## Audit limitations

This is a code audit and CI-backed prototype effort, not a penetration test, regulatory review, payment certification, Android device acceptance run, or proof of VPS deployment. The prior audit dated 2026-10-09 has been superseded by this report.
