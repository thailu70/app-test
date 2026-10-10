# RoutePass OTP, Scheduled Departure, Boarding, and Manual Payment Requirements

## Account sign-up and OTP
- Passenger and driver sign-up must not create an accepted/usable account until the phone number is verified by a one-time password (OTP).
- OTPs must be random, short-lived, single-use, rate-limited, and stored as hashes; repeated requests must be throttled.
- Driver accounts may remain pending administrator approval after phone verification. OTP verification does not bypass driver approval.
- OTP delivery is SMS to the registered phone. Production must fail closed when the SMS provider is not configured; never display/log the OTP or use a fixed test OTP in production.

## Route direction and schedule
- A route supports one-way service or two scheduled directions: HOME_TO_WORK_SCHOOL and WORK_SCHOOL_TO_HOME.
- Each direction has an administrator-configured departure/pick-up stop, scheduled departure time, and route stop order/times. The driver may only start the corresponding assigned route/direction and only within its configured departure window.
- The driver app reminds the driver ahead of departure to arrive at the assigned departure stop. The backend is authoritative for assignments and schedule eligibility.

## Departure geofence and passenger notification
- The backend stores actual driver GPS reports. Arrival confirmation is enabled only when a fresh GPS report is within 50 metres of the assigned departure-stop coordinates.
- Inside the geofence, the driver must explicitly confirm arrival. GPS proximity alone must not mark arrival.
- Confirmed arrival creates an auditable event and notifies passengers assigned to that route/direction/vehicle that the vehicle has arrived and boarding is open.
- Passenger notifications must be persisted server-side and exposed to the app; WebSocket updates may provide immediate delivery, but clients must be able to retrieve missed notifications.

## Boarding and trip completion
- Only a valid active passenger subscription/QR for the route and direction may be checked in, and capacity limits must be enforced server-side.
- Successful QR check-in creates a boarding record and notifies that passenger that they are on board.
- The driver must explicitly complete the trip from the driver portal/app. Completion is accepted only for that driver's active trip and records completion time/status.
- Trip completion notifies affected passengers and is available in trip history. Duplicate completion and duplicate boarding must be idempotently rejected or safely replayed.

## Admin manual payment entry
- Admin can record an actual manual payment (for example, cash or bank transfer) with amount, currency, payment method, received date, reference/receipt, and notes.
- Each entry must be tied to a passenger and subscription, attributed to the logged-in admin, and audited. Duplicate references must be rejected.
- A subscription is activated and its signed QR generated only after the authorized admin records the payment as received. Manual payment is distinct from test recharge and from Telebirr; do not label it as a Telebirr transaction.

## Operational and security requirements
- Add backward-compatible migrations for existing SQLite/PostgreSQL databases; do not drop or reseed existing production data.
- Preserve existing admin credentials and infrastructure.
- Add automated tests for OTP expiry/replay/throttling, schedule and 50 m geofence rules, notifications, QR boarding, trip completion, payment reference uniqueness, and role authorization.
- Build and field-test Android camera/GPS/notification behavior before describing the system as production-ready.
