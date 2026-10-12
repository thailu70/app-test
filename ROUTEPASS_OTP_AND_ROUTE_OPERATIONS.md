# RoutePass OTP, departure and manual-payment changes

This branch adds the first implementation pass for the requested signup and route-operations workflow.

## SMS OTP registration
- `POST /api/auth/otp/request` sends a six-digit code through the server-side Ethio Telecom SMS adapter.
- `POST /api/auth/otp/verify` checks expiry and attempt limits and returns a short-lived registration proof.
- In production, passenger and driver self-registration must include that proof for the same phone number by default.
- **Controlled testing exception:** when `ROUTEPASS_TEST_MODE=true` is explicitly set on the private VPS, passenger registration may skip OTP. Driver registration still requires OTP. The Android app reads this flag from `GET /api/config/public` and labels the passenger flow as test mode.
- The admin's **Recharge 30 days (test)** action requires the separate `ALLOW_MANUAL_TEST_RECHARGE=true` flag. It creates an `ADMIN_TEST` record and is not a real payment.
- OTP values are stored as hashes, expire after five minutes, and are never returned by the SMS request endpoint. SMS credentials stay in server environment variables.

### Required production configuration
Set `ETHIO_TELECOM_SMS_API_URL`, `ETHIO_TELECOM_SMS_API_TOKEN`, and `ETHIO_TELECOM_SMS_SENDER_ID` in the private VPS environment file, not in Git. The adapter currently expects a JSON request containing `to`, `sender`, and `message` and Bearer-token authentication. **The organization must configure the approved Ethio Telecom endpoint and confirm its actual request/response contract before production use**; no gateway URL or credentials are guessed or embedded.

## Departure, boarding and route completion
- Drivers can start only their admin-assigned route and must submit a fresh GPS fix plus an explicit arrival confirmation.
- The server checks the GPS fix against the route's configured departure endpoint within a 50-metre radius. OUTBOUND uses the first ordered stop; INBOUND uses the last.
- In production, the server checks the admin-configured morning/evening departure time in Ethiopia time. Defaults permit arrival from 30 minutes before through 60 minutes after the scheduled time; operations can configure the windows with `ROUTEPASS_DEPARTURE_EARLY_WINDOW_MINUTES` and `ROUTEPASS_DEPARTURE_LATE_WINDOW_MINUTES`.
- Successful arrival persists a passenger-facing notice and broadcasts `VEHICLE_ARRIVED`; QR scan success broadcasts `PASSENGER_BOARDED`; trip completion persists a passenger notice and broadcasts `TRIP_COMPLETED`.
- The Android driver screen now asks for explicit arrival confirmation and offers outbound (home to work/school) and inbound (work/school to home) direction choices. The server remains authoritative for geofence and schedule checks.

## Route type and manual payments
- Administrators can configure route service type as `ONE_WAY` or `TWO_WAY`.
- `POST /api/admin/subscriptions/:id/manual-payment` records an offline payment only after the administrator supplies an amount matching the subscription tariff, a unique receipt/reference, and a method (`CASH`, `BANK_TRANSFER`, or `OTHER`). The action is audited and activates the subscription/QR pass for check-in. It is separate from the staging-only test recharge.

## Release checks still required
- Confirm Ethio Telecom's approved API contract and send a real staging OTP.
- Run backend SQLite and PostgreSQL tests and build the Android app.
- Test geofence rejection at more than 50 metres, acceptance within 50 metres, early/late schedule rejection, QR boarding, completion notices, and manual payment duplicate-reference rejection on staging.
- Confirm the app's notification screen refreshes/filters these notices as intended. WebSocket broadcasts are real-time events; push notifications are not claimed by this change.

## Admin directory and live fleet map
- The admin-only passenger directory shows passenger contact/account data, latest subscription status, assigned vehicle/driver, and profile photo.
- The driver directory provides private previews of the driver's profile photo, licence, vehicle photo and trade licence. Media is fetched through the authenticated private-media API, not public static URLs.
- The admin overview map polls the admin-only live-tracking endpoint every 15 seconds and shows only actual reported GPS coordinates with route, current stop, trip status and GPS freshness. A driver must report GPS from the app; the map does not fabricate locations.

## Passenger photos, transporter documents and attendance roster

- `POST /api/profile-media/upload` accepts JSON fields `assetType`, `fileName`, `contentType`, and base64 `dataBase64`. Maximum file size is 5 MB. Accepted formats are JPEG, PNG, WebP and PDF; the server checks file signatures and stores files outside the public web root with restrictive permissions.
- Passengers may upload a profile photo. Drivers/transporters may upload a driver's licence, a vehicle photo, and a trade licence. Documents are served only through an authenticated endpoint; raw upload files are not exposed through static public hosting.
- `GET /api/rosters/my` provides the signed-in driver's passenger list and QR-scan attendance status, or the passenger's assigned driver/vehicle details when an active paid assignment exists.
- Driver portal UI includes document-upload controls and a roster showing PRESENT after a successful QR boarding scan; passenger portal includes a profile-photo upload and assigned-driver name.
- Private media needs durable VPS storage. Configure `ROUTEPASS_PRIVATE_MEDIA_DIR` to a persistent, backed-up directory mounted into the backend container; do not mount it as a public static directory.
- A driver roster must be populated from explicit passenger-to-vehicle assignments. Route-only membership is not sufficient where multiple vehicles operate the same route. Confirm that the admin assignment workflow sets each subscription's `vehicleId` before operational use.

## Release status for this feature

The feature branch has code changes for private media upload and roster APIs plus Android controls. It has not been confirmed deployed to the VPS. The Android APK must be built from this branch and installed on a physical device before calling the update complete. Test photo/document upload, access-control denial for unrelated accounts, persistence after container restart, passenger/driver roster assignment, and attendance updates after scanning a QR code.
