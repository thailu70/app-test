# PASSO 1.1 operations

## Passenger and driver profiles

After signing in, open the account/profile icon or the driver's **More → Documents & profile** menu. Daily commute drivers upload a photo, licence, registration and insurance, plus emergency contact name/phone and vehicle color. Passengers are prompted to complete registration with photo and identity document. Each upload is limited to 5 MB; JPEG/PNG photos and PDF/JPEG/PNG documents are accepted. Original identity/vehicle documents are private and visible only to their owner and authorized operator. Driver/passenger roster photos are authorized through exact vehicle assignment.

Uploads persist in the private `routepass_uploads_data` Docker volume; they are never served as public assets. Back up this volume alongside the database. Rebuilding the API retains documents. Do not delete this volume. Uploaded files are validated for format and size; malware scanning and human document authenticity verification are not provided.

Passengers see the assigned driver's photo, phone, vehicle type/model, color and plate. The assignment must reference the same commute route and exact vehicle.

## Departure and boarding

An admin configures commute departure coordinates and radius from **Manage routes → Departure area**. Radius defaults to 300 metres and accepts 25–5000 metres (diameter is twice the radius). The final route stop supplies the return departure point. If no coordinates are configured, the ordered route stop is the fallback. Company operators configure the same settings on their own dated intercity departures.

The server accepts arrival only within ±30 minutes of the scheduled departure, using Ethiopia time, with GPS no older than 120 seconds, accuracy within 100 metres, and a position inside the configured radius. Missing or invalid schedules/location fail closed. Starting a commute trip is the arrival confirmation. Once confirmed, the assigned driver can scan passengers until completing the trip. Completion requires ownership and an active journey, with no destination GPS check. A pre-update trip without arrival proof must be ended and a new eligible trip started.

Driver rosters show assigned, boarded, pending and inactive passengers with authorized photos. Company intercity rosters show seat, booking, payment and boarding state.

## Intercity guests and company desk

**Home → Intercity Travel** opens the shared guest booking flow: choose a dated company departure, select a seat, enter name/phone and complete provider checkout. An account is not required. If no route matches, request a route/date from a selected company. Seat holds expire after 10 minutes; a database seat lock prevents double booking. Retain the private booking browser session/capability to retrieve or reconcile a ticket.

Companies use **Intercity bookings, passenger roster, tickets & performance** from their portal. Create company fleet, approve a company driver and create an Intercity route, then publish dated departures with seat capacity and fares. Manual registration requires explicitly recording cash collected and a receipt note; it generates a signed boarding QR and downloadable PDF showing seat, route, date/time, passenger name/phone and fare. This records company-reported cash rather than a Telebirr transfer. Use the operator's normal cash controls.

Guest online tickets issue only after independent server-side provider confirmation. Browser returns and passenger requests cannot mark payment settled. Telebirr remains unavailable when merchant configuration is incomplete; no simulated payment is offered publicly. Production merchant credentials and approved acceptance testing remain necessary for live online collection.

## Separation and performance

Commute administrators see commute routes, independent drivers, passengers, payments and reports. Company operators see only their own intercity routes/drivers/departures/bookings. Registered commute passengers and intercity guest tickets use separate records. Cross-operator requests and direct record mutations are denied. Company invitations remain an admin provisioning function.

The admin overview/reports offer daily, weekly and monthly charts for trips, boardings, revenue and new passengers, with sortable tables. Company reports offer paid tickets, boarding and revenue charts plus sorted rosters/CSV export. Reporting periods use Ethiopia time. Admin pass grants do not count as collected revenue.

The passenger, driver and web portals use shared dark neutral surfaces and green accents. The APK uses the same hosted company/guest flow to avoid duplicate booking implementations and preserve server seat/payment validation.
