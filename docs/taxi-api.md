# Passo rides API

All paths below are prefixed `/api/rides`. JSON responses use `success: true` plus named fields. Errors use `success:false,error,code`. Bearer auth is required except catalog, estimates and shared-trip discovery. Monetary fields are ETB, coordinates are decimal degrees, timestamps ISO UTC. Existing `/api/mobility` cargo/charter/group-ticket flows are unchanged.

## Common objects

`RouteInput`: `origin`, `destination`, `originLatitude`, `originLongitude`, `destinationLatitude`, `destinationLongitude`.

`Ride`: `id`, `passengerId`, `driverId`, `vehicleId`, `status` (`REQUESTED`, `ACCEPTED`, `AT_PICKUP`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`), `source` (`APP` or `STREET`), RouteInput, `tier` (`ECONOMY`,`COMFORT`,`MINI_VAN`), `distanceKm`, `durationMinutes`, `estimatedFareEtb`, `fareEtb`, `meterDistanceKm`, `billableWaitingSeconds`, `tripDurationSeconds`, `waiting` (null or `PICKUP`/`STOP`), `stopWaitingRequested`, `commissionEtb`, `settlement`, `createdAt`, `startedAt`, `completedAt`, `paymentMode: DIRECT_TO_DRIVER`, optional `boardingPin` (passenger owner only, never driver), optional `driverName`,`driverPhone`,`vehicleModel`,`vehiclePlate`,`vehicleType`,`passengerName`,`passengerPhone` (assigned owner/driver only), optional `location` = `{latitude,longitude,accuracyMeters,timestamp,stale}`, `routeGeometry` (GeoJSON LineString or null). Unassigned pool rows redact passenger identity and booking PIN. Completed/cancelled rides omit live location.

`Wallet`: `balanceEtb`, `heldEtb`, `availableEtb`.

## Taxi

- GET `/catalog`: `{settings,tiers}`. Settings include `enabled`, `sharedEnabled`, `baseFareEtb`, `perKmEtb`, `waitingPerMinuteEtb`, `minimumFareEtb`, `pickupGraceMinutes`, `stopGraceMinutes`, `fixedCommissionEtb`, `sharedFixedCommissionEtb`, `notificationRadiusKm`, `arrivalRadiusMeters`, `scheduleBufferMinutes`, `maxTripMinutes`. Tier array `{id,name,multiplier}`. Default services disabled until admin configures real tariffs.
- POST `/estimate` public or authenticated RouteInput + `tier`: `{estimate:{distanceKm,durationMinutes,estimatedFareEtb,routeGeometry,tier}}`. Road routing required, no straight-line fallback fare.
- GET `/nearby?latitude=&longitude=&tier=ECONOMY` public or authenticated: `{vehicles:[{latitude,longitude,tier}],approximate:true}`. At most 12 anonymous approximate presence cells (roughly 200 m grid), with no IDs, names, plates or contact details. Only fresh online, operationally and explicitly approved, available drivers appear. Busy, distant, stale and inactive drivers are excluded. Disabled taxi service returns an empty list. This optional map feed does not consume the fare-estimate mutation quota.
- GET `/me` PASSENGER: `{ride: Ride|null,rides:[Ride],sharedTrips:[SharedTrip],bookings:[SharedBooking]}`.
- POST `/requests` PASSENGER RouteInput + `tier`,`idempotencyKey`,`expectedFareEtb?`: `{ride}` (201). Request expires after 15 minutes. A stale expected fare rejects with `409 ESTIMATE_CHANGED` before inserting a ride.
- GET `/requests/:id` participant: `{ride}`.
- POST `/requests/:id/cancel` participant `{reason?}`: `{ride}`. Pre-trip only.
- POST `/requests/:id/stop-wait` PASSENGER `{approved:boolean}`: `{ride}`. Approves/rejects driver-requested stop waiting.
- GET `/driver/dashboard` DRIVER: `{eligible:boolean,online:boolean,vehicles:[{id,model,plateNumber,capacityLimit,tier}],wallet,calls:[Ride],ride:Ride|null,sharedTrips:[SharedTrip],sharedRequests:[SharedTrip]}`.
- PUT `/driver/availability` DRIVER `{online,vehicleId,latitude?,longitude?,accuracyMeters?,timestamp?}`: dashboard response. Online requires approved driver+vehicle; fresh GPS required to see nearby calls.
- POST `/driver/requests/:id/accept` DRIVER `{vehicleId}`: `{ride}`. Atomic first acceptance wins; reserves fixed fee.
- POST `/driver/street` DRIVER RouteInput + `tier`,`vehicleId`,`passengerConsent:true`,`idempotencyKey`,`latitude`,`longitude`,`accuracyMeters`,`timestamp`,`expectedFareEtb?`,`expectedCommissionEtb?`: `{ride}` (201). Starts manual-consent meter immediately from a fresh geofenced pickup position; no fake PIN. Changed fare/fee rejects with `409 ESTIMATE_CHANGED`. An existing idempotency reference returns its original ride.
- POST `/driver/trips/:id/arrive` DRIVER `{latitude,longitude,accuracyMeters,timestamp}`: `{ride}`. Geofenced arrival.
- POST `/driver/trips/:id/start` DRIVER `{pin}`: `{ride}`. Boarding PIN required for app booking. Upload a fresh pickup GPS position first; meter starts from that verified anchor.
- POST `/driver/trips/:id/locations` DRIVER `{points:[{sequence,latitude,longitude,accuracyMeters,timestamp}]}`: `{ride}`. Ordered batch up to120; old/duplicate points ignored, invalid jumps excluded, latest tracking still fresh. Client persist sequence across retries.
- POST `/driver/trips/:id/wait` DRIVER `{action:REQUEST_STOP|END_STOP}`: `{ride}`. Stop timing begins only after passenger approval; pickup wait accrues after grace only while fresh stationary GPS supports it. Waiting pauses on disconnected GPS and ends automatically on movement. Normal trip/traffic duration never billed. Passenger can also end approved stop waiting with `/requests/:id/stop-wait {approved:false}`.
- POST `/driver/trips/:id/complete` DRIVER `{latitude,longitude,accuracyMeters,timestamp,finalSequence?}`: `{ride}`. Geofenced completion; idempotent fare/fee settlement. Upload final recorded samples first. Unacknowledged final sequence returns `GPS_UPLOAD_REQUIRED`; absent/stale/gapped meter evidence requires audited support resolution (`METER_REVIEW_REQUIRED`). `Ride.lastSequence` acknowledges the highest received sample.
- POST `/driver/trips/:id/cancel` DRIVER `{reason?}`: `{ride}`. Pre-trip only; releases hold.

## Shared Group Ride (per-seat, distinct from charter)

`SharedTrip`: `id`,`initiatorRole`,`passengerId?`,`driverId?`,`vehicleId?`, RouteInput, `departureAt`,`seats`,`availableSeats`,`farePerSeatEtb`,`status` (`REQUESTED`,`OPEN`,`BOARDING`,`IN_PROGRESS`,`COMPLETED`,`CANCELLED`), `driverName?`,`vehicleModel?`,`vehiclePlate?`,`vehicleType?`,`bookings?`, `location?`, `routeGeometry`, `commissionEtb`, `settlement`. Passenger-created REQUESTED rows have `farePerSeatEtb:null`, `seats` requested seats; only driver acceptance fixes capacity and proposed fare. Others cannot book REQUESTED rows.

`SharedBooking`: `id`,`tripId`,`passengerId`,`seats` (count),`seatNumbers` (array),`farePerSeatEtb`,`totalEtb`,`status` (`CONFIRMED`,`BOARDED`,`COMPLETED`,`CANCELLED`),`boardingPin` passenger owner only; `passengerName`,`passengerPhone` assigned driver/owner only. Direct-to-driver settlement; booking itself never marks cash paid.

- GET `/shared/trips?origin=&destination=` public: `{trips:[SharedTrip]}` future OPEN published trips, no roster/private location/contact.
- GET `/shared/trips/:id` public or authenticated: `{trip,occupiedSeats:[number],booking:SharedBooking|null}`. Participant sees own booking and live location during active trip; driver sees private roster.
- POST `/shared/requests` PASSENGER RouteInput + `departureAt`,`seats`,`idempotencyKey`: `{trip}` (201).
- POST `/driver/shared/trips` DRIVER RouteInput + `departureAt`,`seats`,`farePerSeatEtb`,`vehicleId`,`idempotencyKey`: `{trip}` (201). Driver offers up to approved vehicle capacity.
- POST `/driver/shared/trips/:id/accept` DRIVER `{vehicleId,seats,farePerSeatEtb}`: `{trip}`. Accept passenger-created route; requester then explicitly books at proposed fare, no automatic financial consent.
- POST `/shared/trips/:id/bookings` PASSENGER `{seatNumbers:[number],idempotencyKey}`: `{booking,trip}`. Atomic seat reservation; fare fixed at booking. The first confirmed booking reserves the journey's fixed driver-credit fee; subsequent seat bookings do not multiply it. Insufficient credit rejects before confirming seats.
- POST `/shared/bookings/:id/cancel` owner: `{booking}` before individual boarding and trip start. If no confirmed/boarded bookings remain, the fixed fee hold releases; a later booking re-holds with a new auditable ledger version.
- POST `/shared/trips/:id/cancel` original requesting passenger: `{trip}` while REQUESTED or OPEN with no active passenger bookings.
- POST `/driver/shared/trips/:id/actions` DRIVER `{action:BOARDING|BOARD|NO_SHOW|START|COMPLETE|CANCEL,bookingId?,pin?,latitude?,longitude?,accuracyMeters?,timestamp?}`: `{trip}`. `BOARD` with bookingId+pin confirms individual boarding. `NO_SHOW` cancels an unboarded booking only after departure plus configured grace, with an audit record. Start requires fresh pickup GPS, confirmed boarders and a new actual-time schedule check; completion at destination settles once per vehicle journey. Revoking taxi approval prevents new starts but does not trap owned active-trip completion/cancellation.
- POST `/driver/shared/trips/:id/location` DRIVER `{latitude,longitude,accuracyMeters,timestamp}`: `{trip}` private participant tracking only.

## Admin

- GET/PUT `/admin/settings`: `{settings,tiers}`; PUT partial settings and optional `tiers:[{id,name,multiplier}]`. Enabling requires real base/km/minimum tariff values. Monetary settings affect new journeys only.
- GET `/admin/eligibility`: `{drivers:[{id,fullName,phone,approved,vehicles:[{id,model,plateNumber,capacityLimit,tier,approved}]}]}`.
- PUT `/admin/eligibility/:driverId` `{approved,vehicles:[{vehicleId,approved,tier}]}`: `{updated:true}`. Separate explicit driver+vehicle approval; operational onboarding still mandatory.
- GET `/admin/trips`: `{rides:[Ride],sharedTrips:[SharedTrip]}` limited recent operational list; private bookings admin-only.
- POST `/admin/trips/:id/resolve` `{action:CANCEL|COMPLETE,note}`: `{ride}` audited support resolution for a stuck/disputed taxi.
- POST `/admin/shared/trips/:id/resolve` `{action:CANCEL|COMPLETE,note}`: `{trip}` audited support resolution for a stuck shared journey. COMPLETE is permitted only after START; CANCEL releases reserved fee and cancels outstanding seat bookings.

No demo fares are activated automatically. The shared fixed commission is charged once per vehicle trip, never multiplied by occupied seats.

Additional configurable settings: `pollingSeconds` (integer 3–60, default 8), `gpsUploadSeconds` (integer 1–60, default 10), `noShowGraceMinutes` (default 10), `maxWaitingMinutes` (default 60), `minimumSharedFareEtb`, `maximumSharedFareEtb`. Shared commission policy is fixed to `PER_TRIP`. Grace minutes may be fractional.

Maintenance runs every minute. Unaccepted taxi requests expire after 15 minutes. Unstarted shared requests/offers expire one hour after planned departure, cancelling bookings and releasing reserved credit; active journeys require driver/support resolution. Terminal PIN ciphers and live shared positions are cleared. Raw meter GPS evidence is pruned after 30 days (environment `RIDE_GPS_RETENTION_DAYS`, bounded 7–90 days); fare totals and financial audit records remain.

## Meter Taxi registration

Driver signup accepts `serviceType: "TAXI"` through the existing `/api/onboarding/register` and native `/api/auth/register` paths. Required identity, licence, plate, vehicle model and active master vehicle type remain mandatory. No fixed departure/destination or monthly fee is needed; Taxi preferences are saved as `NOT_APPLICABLE`, with blank route and null monthly pricing. Stale hidden commute fields do not create a route assignment.

Drivers upload their licence, vehicle registration, insurance and photo immediately after signup, complete profile details and submit for the existing final administrator review. OTP remains optional under the existing phone policy. Final approval activates only their registered off-duty vehicle; a separate explicit ride/vehicle approval and real tariff configuration are still required for taxi operations. Taxi accounts cannot pass daily-commute allocation checks. Approved Daily Commute drivers retain their service and can receive separate taxi eligibility.

Onboarding snapshots expose `account.serviceType` and `account.driverServiceType`; normal `/api/auth/login` and `/api/auth/me` responses expose `user.driverServiceType` for native dashboard routing. Legacy untracked accounts retain the existing Daily Commute/company Intercity fallback. The existing `onboarding_accounts.service_type` is an unconstrained TEXT column on SQLite and PostgreSQL, so supporting TAXI adds no destructive table rebuild or data rewrite.

Passenger signup for Taxi sends no `appliedRouteId` or service class. The existing passenger registration path already permits a route-less account and creates no invented commuter subscription.
