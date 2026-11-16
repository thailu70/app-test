# Taxi and shared Group Ride operations · 1.1.32

This update adds native taxi and shared-seat journeys beside the existing daily commute, charter/group travel, cargo and intercity services. Google Play is not published by the build or deployment process.

## Start the new services

1. Open **Admin → Taxi & shared rides → Fares & settings**. New bookings are disabled initially; no illustrative mockup fares are activated.
2. Enter the base fare, rate per kilometre, minimum fare, eligible waiting rate and free waiting allowances. Set the fixed Passo taxi fee and fixed shared vehicle-trip fee. Review tier multipliers, GPS/reporting intervals, nearby radius, arrival radius and schedule buffer.
3. Under **Driver approvals**, approve an operationally accepted driver and each eligible driver-owned vehicle, selecting Economy, Comfort or Mini-Van. Taxi approval is separate from the driver's other service assignments.
4. Credit the driver only after a verified top-up through the existing **Group travel & cargo** credit controls.
5. Enable the required service after reviewing these settings. Existing journeys retain their recorded fares and fees.

## Registration and navigation

The driver registration dropdown offers Meter Taxi, Daily Commute, Group Travel and Cargo Transport. Meter Taxi applicants enter licence, vehicle, contact and required document details without a commute route or monthly fee. They upload documents immediately and submit for admin review. Operational approval and taxi/vehicle eligibility still apply before working.

Passenger → Continue opens the destination-first taxi map. **Daily Commute** opens the existing route/subscription experience. Taxi signup does not create a daily-commute subscription. Existing passenger, driver and company authentication remains in use.

## Taxi lifecycle

- A passenger reviews a road-route estimate, selects a tier and sends a request. Nearby eligible drivers choose calls from a pool. Only one acceptance succeeds; passenger contact details and the boarding PIN are hidden from other drivers.
- Acceptance reserves the fixed Passo fee in existing driver credit. The passenger pays the driver directly; booking or completion does not claim that cash has been received.
- GPS verifies arrival at pickup. The passenger's boarding PIN starts an app-booked trip. A safely stopped available driver may instead create a street pickup after entering the route and recording the passenger's agreement to the estimate.
- The server calculates the fare from the recorded tariff, accepted GPS distance and eligible waiting. Ordinary traffic duration is never a time charge. Requested-stop waiting requires passenger approval; fresh stationary GPS evidence and configured caps prevent offline waiting inflation.
- Location batches carry ordered sequence numbers, retry safely and ignore duplicate points, weak fixes and implausible jumps. Missing evidence blocks automatic completion until uploads recover or admin resolves the case with an audited reason.
- Completion settles the fixed fee once. A valid cancellation releases its hold. Driver and vehicle locks prevent conflicting work across taxi, shared rides, daily commute, intercity and cargo/charter services.

### Passenger stops and the meter

An approved **Passenger stop** pauses distance within the existing stationary GPS radius; small GPS drift is recorded as evidence without being billed as travel. It does not end the trip. The configured free allowance counts down first, then the journey's snapshotted waiting rate applies while fresh stationary GPS supports waiting. Clear real movement resumes distance automatically; the driver or passenger may also end the stop. Delayed fixes from before approval and implausible jumps cannot end an approved stop or extend its waiting evidence. Ending a stop cannot charge previously suppressed stationary drift retrospectively. Raw GPS coordinates remain unchanged in the meter evidence.

Ride responses expose `distancePaused` (approved stationary STOP only), `waitingFreeRemainingSeconds` (whole seconds under current GPS coverage), `waitingRateEtbPerMinute` (the recorded tier-adjusted rate), `waitingFareEtb`, `distanceFareEtb`, `waitingState`, and `serverTime`. Waiting states distinguish `AWAITING_PASSENGER_APPROVAL`, `FREE_ALLOWANCE`, `BILLABLE_WAITING`, `GPS_PAUSED`, `LIMIT_REACHED` and `NONE`. The UI shows the free countdown and waiting charge separately from distance rather than implying that a passenger stop freezes all charges indefinitely.

## Shared Group Ride · pay per seat

A passenger can request a route, departure time and seats; an approved driver can accept and propose a per-seat fare. The requesting passenger explicitly books at that fare. Drivers may also publish a shared trip directly. Other passengers book available numbered seats on an open trip. Confirmed seat fares stay fixed when other bookings change.

The first confirmed booking reserves **one fixed Passo fee per vehicle trip**, never a fee for each passenger. Cancelling the last booking releases the hold; a later booking reserves it again with distinct ledger events. The service checks credit before confirming seats. Each booking has its own boarding confirmation, without exposing passenger phone numbers to other passengers.

Drivers open boarding, verify passenger PINs and start at the pickup location. Missing passengers can be marked as no-shows after the configured grace period. Arrival at the destination completes the journey and captures its one fixed fee. Admin can resolve a stuck shared trip with an audited reason. Unstarted stale offers expire and release held credit; active trips are not silently completed.

## Location, notifications and support

Nearby-car previews are approximate, anonymous locations of actual eligible available drivers. Assigned-trip tracking is available only to participants. Phone notifications contain references and generic event labels, never contact details, exact coordinates, PINs or private booking credentials. Permission, production environment, active-account and current-device binding checks remain mandatory. Requests stop appearing to a driver while another job is active.

Admin support can cancel or complete a stuck journey with a recorded reason. Account closure waits until active trips, shared seats and held credit are resolved. Closure removes live GPS and notification bindings; historical route, meter and financial evidence follows the existing retention policy. Raw terminal meter samples are pruned after the configured retention period.

## Release checks

Run the taxi lifecycle, HTTP authorization, registration, notifications, cross-service, account-closure and existing transport regression suites on SQLite and disposable PostgreSQL. Build the native app, review its real screen captures, verify package/version and sign the APK with the existing certificate. Back up the VPS before the additive schema update and verify public readiness, service defaults, web registration, assets and the downloadable APK checksum after deployment.
