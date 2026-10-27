# Group travel and cargo — 1.1.24

Driver registration at `/join/` now offers Daily commute, Group travel and Cargo. Group/cargo applicants supply identity, phone, vehicle and preferred route. Capacity comes from Master Data. These services have no monthly commute fee; group prices are proposed per seat with each trip.

Admin Applications retains initial acceptance → applicant documents/profile → final approval. Driver OTP remains optional and verification flags remain truthful. Final approval puts an owned OFF_DUTY group/cargo vehicle IN_SERVICE, preserving maintenance restrictions. Daily assignments exclude group/cargo drivers.

## Group tickets

Approved drivers propose trips with their current vehicle, endpoints, departure time, boarding location, seat capacity and ETB price per seat. Admin → Group & cargo approves or rejects each proposal. Only approved future trips with eligible operators appear publicly.

Passengers choose up to 20 seats and enter buyer details plus each traveler's name and phone. Reservations are atomic and initially last ten minutes; starting provider checkout extends the reservation up to 125 minutes. An expired reservation cannot reclaim reallocated seats after a late payment; support must review late settlements.

Only server-confirmed Telebirr settlement with matching order, currency and amount issues tickets. Test/free payments stay disabled. A retrying SMS outbox sends the buyer and travelers individual ticket numbers and private boarding-pass links. Reconciliation is idempotent. Native recovery stores only encrypted capabilities, separately from intercity bookings.

The assigned driver sees paid travelers and starts boarding with fresh precise GPS within the global Master Data radius and departure window (one hour before to thirty minutes after departure). Tickets and QR codes are trip-specific and single-use. One driver/vehicle can board only one group journey at a time.

## Requested routes and cargo

Group requests include headcount, vehicle type, travel date and route. Cargo adds description, private JPEG/PNG/WebP photo (maximum 5 MB) and optional pickup/delivery map pins. Cargo drafts can recover from upload interruptions and remain private until submitted. Unsubmitted drafts expire after seven days; private photo deletion retries on failure.

Admin assigns a service-approved driver and their current vehicle of the requested type, with enough group capacity. Quotes are administrative quotations, not payments or paid bookings. Customer and assigned driver receive assignment SMS. Private request status shows driver name/phone, plate and review notes. Photos require request capability, administrator authentication or assigned-driver authentication. Administrators reject open requests or complete assigned requests.

## Operations

- Add cargo truck/van types through Master Data before promoting cargo registration. Passenger capacity never implies cargo tonnage.
- Live checkout requires approved production Telebirr merchant configuration; missing configuration fails closed.
- SMS uses existing AfroMessage settings. Provider failures retry without undoing payment or assignment. Deployment does not backfill historical messages.
- Approved/boarding group trips and assigned transport requests block driver account closure pending handover. Unapproved proposals close with account deletion.
- Backend tests cover service scope, approvals, concurrent seats, expiry, provider settlement, SMS, private photos, assignment and draft cleanup. CI also checks PostgreSQL plus native contracts, state and rendered layouts.
