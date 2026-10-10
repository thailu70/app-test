# Update an existing RoutePass VPS safely

This guide is for the already-running RoutePass deployment at `https://routepass.duckdns.org` behind the VPS's existing Traefik and 9Router. It updates the application API and web admin assets without reinstalling the database or replacing the proxy.

## 1. Fetch the latest application branch

SSH to your VPS as the administrator/root account, then run:

```bash
cd /opt/routepass-source
git fetch origin
git checkout audit/production-readiness-2026-10-09
git pull --ff-only origin audit/production-readiness-2026-10-09
```

If the pull refuses because the local checkout has uncommitted changes, stop and inspect those changes rather than forcing the pull.

## 2. Copy updated API, portal and Compose files

```bash
install -m 0644 backend/Dockerfile /var/www/routepass/Dockerfile
install -m 0644 backend/docker-compose.yml /var/www/routepass/docker-compose.yml
install -m 0644 backend/docker-compose.traefik.yml /var/www/routepass/docker-compose.traefik.yml
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

## 4. Configure SMS OTP before rebuilding

Passenger and driver registration now requires a verified OTP. Before rebuilding, add a dedicated OTP hashing secret and your Ethio Telecom SMN credentials to the existing `/var/www/routepass/.env`. Do not paste or commit this file.

Generate the OTP hashing secret on the VPS if it is missing:

```bash
cd /var/www/routepass
if ! grep -q '^OTP_HASH_SECRET=' .env || grep -q '^OTP_HASH_SECRET=

```bash
cd /var/www/routepass
docker compose -p routepass \
  -f docker-compose.yml \
  -f docker-compose.traefik.yml \
  up -d --build api
```

This does not drop PostgreSQL data and does not restart Traefik/9Router. Do not run the initial deployment script again on this existing installation. Do not run `docker compose down -v`.

## 6. Verify the API and web admin portal

```bash
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml ps
curl -fsS https://routepass.duckdns.org/api/ready
curl -sSI https://routepass.duckdns.org/admin/
```

The readiness endpoint should return `{"status":"READY"}`; the admin page should return an HTTP success response. Open [https://routepass.duckdns.org/admin/](https://routepass.duckdns.org/admin/) and sign in with the existing administrator phone number and password.

If readiness fails, inspect `docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml logs --tail=100 api`. Do not share logs that contain environment variables or secrets.

## 7. Configure RoutePass operations in the browser

1. **Manage routes:** create or edit the English/Amharic route names, distance, timetable, monthly tariff and ordered stops. Each stop line must be `English name | Amharic name | latitude | longitude`; use real coordinates, and enter at least two stops.
2. **Drivers & routes:** the driver registers through Android with their own phone/password, commercial licence, owned vehicle plate, model and vehicle type. Then the admin selects an active route and assigns it. The admin must not create the driver or transfer ownership of their vehicle.
3. **Subscriptions:** assign the driver-owned vehicle to a passenger subscription on the same route. For testing only, use **Recharge 30 days (test)**. The app refreshes the passenger's subscription status every 15 seconds.
4. **Tracking:** the driver opens the Android driver screen, allows Location permission, turns phone Location on, and starts a live trip after a route has been assigned. The app reports GPS only while the driver screen is open. The passenger's small map shows the vehicle only after the admin has assigned that vehicle and the driver phone has reported a real GPS fix.
5. **QR boarding:** the driver must start the actual assigned trip first, then scan the passenger's active signed QR pass with the camera. The VPS verifies the QR, subscription, route, trip, duplicate check-in and capacity. A sample/demo token or network failure must not be accepted.

## 8. Install the updated Android test APK

Download `app-debug.apk` from the successful RoutePass Android Actions run, transfer it to the phone and install it. Android may ask you to allow installation from your browser or file manager. The test build needs internet access, camera permission for QR scanning and foreground location permission for live GPS. Keep the driver app open on its trip screen while verifying GPS tracking.

The app now includes the H5 C2B hosted-checkout flow, but it stays fail-closed until the merchant's Telebirr settings are configured on the VPS and sandbox tests pass. The hosted flow creates a pending payment order, signs the H5 request server-side, opens the provider checkout, and activates a subscription only after the server independently confirms the order with Telebirr's query API. The browser return URL is not treated as proof of payment. See `TELEBIRR_H5_SETUP.md` for setup requirements.

Offline QR/boarding synchronization remains disabled. Do not process real money or advertise the app as production-ready until you have tested token issuance, hosted checkout, signed callbacks, order reconciliation, and subscription activation with Telebirr's approved sandbox credentials; then repeat acceptance testing after merchant production approval.
 .env; then
  sed -i '/^OTP_HASH_SECRET=/d' .env
  printf '\\nOTP_HASH_SECRET=%s\\n' "$(openssl rand -hex 32)" >> .env
fi
chmod 600 .env
```

Then edit `/var/www/routepass/.env` privately and set the real values issued by Ethio Telecom:

```dotenv
ETHIO_SMS_API_BASE_URL=https://YOUR-ISSUED-SMN-API-BASE
ETHIO_SMS_TENANT_ID=YOUR_TENANT_ID
ETHIO_SMS_ACCESS_TOKEN=YOUR_VALID_BEARER_TOKEN
OTP_TTL_SECONDS=300
OTP_RESEND_COOLDOWN_SECONDS=60
OTP_MAX_ATTEMPTS=5
```

The placeholder values are examples only and will not send SMS. Obtain the tenant-specific API base URL, access-token/refresh procedure, and sender approval from Ethio Telecom. See [ETHIO_TELECOM_SMS_OTP.md](ETHIO_TELECOM_SMS_OTP.md). If provider configuration is missing or invalid, OTP requests fail closed with HTTP 503; the app does not accept a local/demo code.

## 5. Rebuild only the RoutePass API

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
