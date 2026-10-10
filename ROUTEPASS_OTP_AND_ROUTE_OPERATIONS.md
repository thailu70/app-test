# RoutePass OTP, departure and manual-payment changes

This branch adds the first implementation pass for the requested signup and route-operations workflow.

## SMS OTP registration
- `POST /api/auth/otp/request` sends a six-digit code through the server-side Ethio Telecom SMS adapter.
- `POST /api/auth/otp/verify` checks expiry and attempt limits and returns a short-lived registration proof.
- In production, passenger and driver self-registration must include that proof for the same phone number.
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
