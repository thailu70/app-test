# Update an existing RoutePass VPS safely

This guide is for the already-running RoutePass deployment at `https://routepass.duckdns.org` behind the VPS's existing Traefik and 9Router. It updates the application API and web admin assets without reinstalling the database or replacing the proxy.

## 1. Fetch the latest application branch

SSH to your VPS as the administrator/root account, then run:

```bash
cd /opt/routepass-source
git fetch origin
git checkout feature/routepass-otp-geofence-payments
git pull --ff-only origin feature/routepass-otp-geofence-payments
```

If the pull refuses because the local checkout has uncommitted changes, stop and inspect those changes rather than forcing the pull.

## 2. Copy updated API, portal and Compose files

```bash
install -m 0644 backend/Dockerfile /var/www/routepass/Dockerfile
install -m 0644 backend/docker-compose.yml /var/www/routepass/docker-compose.yml
install -m 0644 backend/docker-compose.traefik.yml /var/www/routepass/docker-compose.traefik.yml
# Preserve uploaded files across API image rebuilds using a named Docker volume.
# The compose file adds routepass_app_data; do not remove existing volumes.
install -m 0644 backend/init-db.sql /var/www/routepass/init-db.sql

cp -R backend/src/. /var/www/routepass/src/
mkdir -p /var/www/routepass/public/admin
cp -R backend/public/. /var/www/routepass/public/

chmod 755 /var/www/routepass/src /var/www/routepass/public /var/www/routepass/public/admin
find /var/www/routepass/src -type d -exec chmod 755 {} +
find /var/www/routepass/src -type f -exec chmod 644 {} +
chmod 600 /var/www/routepass/.env
```

Do not replace or publish `/var/www/routepass/.env`. It contains production database and signing secrets.

## 3. Enable manual subscription recharge only while testing

The admin portal includes **Recharge 30 days (test)**. This is a test-only activation, not a Telebirr transaction. It writes provider `ADMIN_TEST`, emits an audit log, signs a QR pass and is excluded from the real-revenue total. The backend requires an explicit environment flag; manual recharge is disabled by default.

Because you are testing, run the following on the VPS to enable it temporarily:

```bash
cd /var/www/routepass
if grep -q '^ALLOW_MANUAL_TEST_RECHARGE=' .env; then
  sed -i 's/^ALLOW_MANUAL_TEST_RECHARGE=.*/ALLOW_MANUAL_TEST_RECHARGE=true/' .env
else
  printf '\nALLOW_MANUAL_TEST_RECHARGE=true\n' >> .env
fi
chmod 600 .env
```

**Before allowing real public subscriptions or treating any payment report as financial data, turn it back off** by using the same command with `false` instead of `true`, then rebuild only the API as shown below. Never expose or paste the `.env` contents into chat.

## 4. Rebuild only the RoutePass API

```bash
cd /var/www/routepass
docker compose -p routepass \
  -f docker-compose.yml \
  -f docker-compose.traefik.yml \
  up -d --build api
```

This does not drop PostgreSQL data and does not restart Traefik/9Router. Do not run the initial deployment script again on this existing installation. Do not run `docker compose down -v`.

## 5. Verify the API and web admin portal

```bash
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml ps
curl -fsS https://routepass.duckdns.org/api/ready
curl -sSI https://routepass.duckdns.org/admin/
```

The readiness endpoint should return `{"status":"READY"}`; the admin page should return an HTTP success response. Open [https://routepass.duckdns.org/admin/](https://routepass.duckdns.org/admin/) and sign in with the existing administrator phone number and password.

If readiness fails, inspect `docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml logs --tail=100 api`. Do not share logs that contain environment variables or secrets.

## 6. Configure RoutePass operations in the browser

1. **Manage routes:** create or edit the English/Amharic route names, distance, timetable, monthly tariff and ordered stops. Each stop line must be `English name | Amharic name | latitude | longitude`; use real coordinates, and enter at least two stops.
2. **Drivers & routes:** the driver registers through Android with their own phone/password, commercial licence, owned vehicle plate, model and vehicle type. Then the admin selects an active route and assigns it. The admin must not create the driver or transfer ownership of their vehicle.
3. **Subscriptions:** assign the driver-owned vehicle to a passenger subscription on the same route. For testing only, use **Recharge 30 days (test)**. The app refreshes the passenger's subscription status every 15 seconds.
4. **Tracking:** the driver opens the Android driver screen, allows Location permission, turns phone Location on, and starts a live trip after a route has been assigned. The app reports GPS only while the driver screen is open. The passenger's small map shows the vehicle only after the admin has assigned that vehicle and the driver phone has reported a real GPS fix.
5. **QR boarding:** the driver must start the actual assigned trip first, then scan the passenger's active signed QR pass with the camera. The VPS verifies the QR, subscription, route, trip, duplicate check-in and capacity. A sample/demo token or network failure must not be accepted.

## 7. Install the updated Android test APK

Download `app-debug.apk` from the successful RoutePass Android Actions run, transfer it to the phone and install it. Android may ask you to allow installation from your browser or file manager. The test build needs internet access, camera permission for QR scanning and foreground location permission for live GPS. Keep the driver app open on its trip screen while verifying GPS tracking.

The app now includes the H5 C2B hosted-checkout flow, but it stays fail-closed until the merchant's Telebirr settings are configured on the VPS and sandbox tests pass. The hosted flow creates a pending payment order, signs the H5 request server-side, opens the provider checkout, and activates a subscription only after the server independently confirms the order with Telebirr's query API. The browser return URL is not treated as proof of payment. See `TELEBIRR_H5_SETUP.md` for setup requirements.

Offline QR/boarding synchronization remains disabled. Do not process real money or advertise the app as production-ready until you have tested token issuance, hosted checkout, signed callbacks, order reconciliation, and subscription activation with Telebirr's approved sandbox credentials; then repeat acceptance testing after merchant production approval.

## 8. Passenger photos and driver documents (feature branch)

This feature branch adds `POST /api/profile-media/upload` and `GET /api/profile-media/:ownerId/:assetType`. Files are written to `ROUTEPASS_PRIVATE_MEDIA_DIR` (default `/app/data/private-media`) and are not served from the public admin directory. The Compose configuration mounts the persistent named volume `routepass_app_data` at `/app/data`; PostgreSQL remains in its separate existing volume.

After copying the files above, verify the rendered Compose config before rebuilding:

```bash
cd /var/www/routepass
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml config --quiet
docker volume inspect routepass_app_data >/dev/null 2>&1 || echo "The new private-media volume will be created on first up."
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml up -d --build api
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml ps
curl -fsS https://routepass.duckdns.org/api/ready
```

Do not run `docker compose down -v`. Verify the container is healthy before testing uploads. Only upload a non-sensitive test image first, then confirm it remains accessible after an API container recreation. Driver licence and trade licence documents are sensitive personal data: restrict VPS access, back up the media volume securely, and define retention/deletion procedures before public launch.

The driver roster depends on the administrator assigning each eligible passenger subscription to the specific vehicle via `subscription.vehicleId`. The API endpoint exists in this branch, but an admin UI workflow still needs completion before relying on it operationally. Run CI and manual staging checks before merging this draft PR.
