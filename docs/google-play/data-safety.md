# Google Play Data safety draft

Prepared from the Android/server source and official SDK documentation; updated 28 October 2026 for the 1.1.25 (28) candidate, including the controlled Company WebView. This records the categories/purposes being prepared in Console, not confirmation of its final save/submission or a completed signed-build UI audit. No production account records were inspected.

## Proposed top-level responses

- **Does the app collect data? Yes.** Accounts, journeys, payment records, uploaded documents and driver location leave the device. Controlled company-portal forms count as part of the app, and the QR SDK collects technical data.
- **Is collected user data encrypted in transit? Proposed: Yes, subject to release verification.** The release API uses HTTPS, production WebSockets use WSS, Android rejects cleartext remote traffic, and Google documents HTTPS for ML Kit telemetry. Verify the final merged manifest, map tile URLs and every configured payment/provider URL before submitting this answer. This is transport encryption, not a claim of end-to-end encryption.
- **Can users request deletion? Yes.** In-app Privacy & account and `https://passo.live/delete-account/` provide paths. Necessary historical records may remain as disclosed. Do not promise deletion of all financial or safety records.
- **Independent security review? Do not claim one.** Repository tests and this source review are not certification by an independent accredited body.
- **Data not shared? Do not select a blanket “not shared” answer without reviewing the recipient/exception section below.** Transport participants receive relevant personal, boarding and location information.

Public policy: `https://passo.live/privacy/`.
Public account/data deletion: `https://passo.live/delete-account/`.
Operator in the policy: Passo Mobility, Ethiopia.
Privacy contact: `thailu70@gmail.com`. Service support: `info@passo.live`.

## Confirmed audience

The owner selected **Adults only — 18+**. Account creation and app/account-portal use are for adults only. An adult parent, guardian or authorized adult institution representative may arrange transport for a child; the child is a transport beneficiary, not an app account user. This audience choice does not mean the service never handles a child's transport details, and it does not establish technical age or identity verification.

Proposed Play target-age selection: **18 and over only**, consistent with the owner's instruction. The web driver signup now displays an adults-only notice and requires an unchecked adult-confirmation checkbox before client-side submission, translated into English, Amharic and Afaan Oromoo. This is a client-side policy acknowledgement: it does not add a date-of-birth field, technical age verification, or a backend registration-contract change. Recheck the final Android release before describing its age acknowledgement as implemented. No legal retention, provider-contract or country-specific conclusion is inferred from this audience selection.

## Data-type worksheet

The proposed categories below are based on implemented flows, not merely permission names. “Optional” must be confirmed in the Console using Google's definition: an account/booking may require data even though a guest can browse routes without supplying it. Role-specific requirements must be explained accurately.

The current preparation selects **Approximate location, Precise location, Name, User IDs, Phone number, Other personal info, Purchase history, Photos, Files and docs, Contacts, App interactions, In-app search history, Other user-generated content, Other actions, Diagnostics, Other app performance data, and Device or other IDs**. All are declared collected and not processed ephemerally. Operational transport categories are declared shared for **App functionality**; search history and SDK technical categories are currently collected-only. Do not convert this into a claim that all provider relationships qualify for a sharing exception. Final Console details must match the saved form.

Purpose names below use Console terminology. Account identity uses **Account management** and, where used for authentication/eligibility, **Fraud prevention, security, and compliance**, in addition to **App functionality**. SDK diagnostics/performance/install identifiers use **Analytics**. No source-backed advertising/marketing or personalization purpose was identified. Service OTP/ticket/status SMS are transactional functionality, not evidence of a marketing program.

| Play data type | Actual source-backed data | Purpose / collection context |
| --- | --- | --- |
| Personal info — Name | Passenger/driver, guest buyer/traveler, company representative and emergency contact names | App functionality; Account management for account identity; security where used for account/transport verification |
| Personal info — Phone number | Account, guest booking/request, company representative and emergency phone; transactional SMS destination | App functionality; Account management for sign-in; Fraud prevention, security, and compliance for verification/recovery |
| Personal info — User IDs | Account IDs, role/company association, assignments and authenticated references | App functionality; Account management; Fraud prevention, security, and compliance |
| Personal info — Other info | Driving licence, associated vehicle/registration information and vehicle monthly-fee proposal | App functionality; Fraud prevention, security, and compliance for application/vehicle eligibility |
| Contacts | Manually supplied emergency contact name/phone | App functionality/safety contact use. No Android address-book import or READ_CONTACTS |
| Financial info — Purchase history | Passes/tickets, amount, payment status/reference, renewal/boarding entitlement; company cash receipt/payment notes | App functionality; Fraud prevention, security, and compliance for settlement/entitlement reconciliation; retained |
| Photos and videos — Photos | Chosen profile photos, cargo photos and image-format verification documents | App functionality; security for identity/vehicle verification; role/feature dependent |
| Files and docs | Licence, insurance, vehicle registration and identity/business verification documents | App functionality; Fraud prevention, security, and compliance for approval/eligibility |
| Location — Precise location | Driver coordinates, accuracy/speed/time, departure/arrival checks; optional cargo pickup/delivery pins; company departure coordinates | App functionality; security for departure/boarding eligibility. Passenger/company schedule pins are not evidence that their device GPS is requested |
| Location — Approximate location | Coarse/network-provider driver fixes where permitted by the relevant fix requirements | App functionality; applicable departure security. ACCESS_COARSE_LOCATION is supported |
| App activity — App interactions | Route/seat selection, trip controls, boarding and scan validation | App functionality; security where validating paid/assigned transport |
| App activity — In-app search history | Intercity origin/destination/date availability queries and their logged requested URLs | App functionality; own non-test access logging retains requested URL/remote IP; do not call ephemeral |
| App activity — Other user-generated content | Complaints, cargo descriptions, route proposals, company route descriptions/review notes and cash receipt notes | App functionality/support/transport operations; optional free text except fields required by a particular request/booking |
| App activity — Other actions | Submitted applications, requests, account/security and operational status actions | App functionality; applicable Fraud prevention, security, and compliance |
| App info and performance — Diagnostics | ML Kit device/app/API configuration, feature events and error codes | Analytics; Google's SDK diagnostics/usage collection, not evidence of general crash-stack collection |
| App info and performance — Other app performance data | ML Kit latency, input/output sizes and feature metadata | Analytics |
| Device or other IDs | ML Kit per-installation identifiers; retained network identifiers where used for abuse/rate-limit security | Analytics for ML Kit; Fraud prevention, security, and compliance for security identifiers. No advertising-ID claim |

Additional conditional fields:

- **Email address / personal address are not selected:** native signup sends an empty email; actual company forms do not ask for email/address. A scan of non-admin public HTML found no such input. The active `/join/` implementation is hardcoded to DRIVER; its legacy company-address profile branch is inactive. Rendering an old backend-supported address in an application review is not evidence that the active UI collects that input. Do not add these types solely because an API schema supports them; recheck if the active UI changes.
- **User payment info is not selected for the current disabled-online-checkout release:** stored passenger/payer phone and transaction references are already covered above. No active card/bank/PIN input was identified; the backend rejects Telebirr PIN fields. Native passenger checkout launches an external browser, but this is **not universal**: the controlled Company WebView links to own `/travel/` and permits approved Telebirr HTTPS hosts in-place. Before enabling online checkout, assess the actual embedded-provider collection and update this declaration. Company manual cash booking already records purchase history; it is not a wallet, credit, banking or investment feature.
- **Messages:** complaints are service text, not evidence that the app reads SMS, the email inbox or private chat history. No READ_SMS, call-log or microphone permission is declared.
- **IP address/map area:** server logs and map providers receive network information. Declare inferred location if it is used to determine user location; the source review did not establish IP geolocation by Passo itself. Verify the map provider's handling separately.
- **Crash logs:** no dedicated crash-reporting SDK is active in Gradle. ML Kit reports its own error/diagnostic events; confirm whether these require the Crash logs subtype for the exact SDK rather than inventing a general crash collector.

## Controlled Company portal and required/optional collection

`MainActivity.CompanyPortalScreen` opens `HostedPortalScreen("/company/")`. Own-origin navigation stays inside this controlled WebView, including its fleet/route, intercity booking desk, application review, linked driver registration and public-travel pages. This is app functionality for Data safety; it cannot be excluded as merely an external website. An explicit external `ACTION_VIEW` path is different. Source changes for isolated Demo company access are still in progress; its final signed UI must be tested separately from this production-surface audit.

Actual company UI inputs include company/representative name, phone/password/invitation code, vehicle plate/model/type, route origins/destinations/schedules/fares, driver/vehicle assignments, departure latitude/longitude/radius, passenger name/phone/seat and a cash collection receipt/note. Application review also allows an optional note, and authorized company users can review their drivers' documents and fleet GPS. Company JSON objects contain no active email/address input. Profile fields merely supported by a backend or legacy unreachable form are not treated as newly collected UI fields.

Source requirements for the form's required/optional decision:

- Name/phone are required for account creation and the applicable guest booking/request; company registration requires company name, representative name and phone. Password/authentication collection is account/security use.
- Driver licence, vehicle details, profile photo, emergency contact and required verification documents gate final driver approval. A passenger or browsing guest need not upload driver documents; that does not make the driver requirement disappear.
- Driver GPS is user-started but required for applicable operational boarding/transport checks. Company schedule coordinates are required in that form. Cargo request pins are optional. These are different uses within the same location categories.
- Company paid cash booking requires passenger name/phone and a payment note/collection confirmation. Purchase/boarding history is retained. No financial-account or card details are requested by that form.
- Route descriptions and application-review notes are optional. Cargo description/photo and requester contact are required by their request flow; do not label all free-text/photo collection optional.
- Search is manually optional and default trip lists can load without an entered search. ML Kit has no app-level telemetry opt-out in this source. Server security/access logs are automatic for the corresponding requests.

Google's global required/optional answer must cover every current supported role/version; guest browsing alone is not evidence that every category is optional. Apply the documented source requirements rather than an invented uniform setting.

The company token is kept in `sessionStorage`. The public-site language preference and guest capability have their own local storage contexts. Local browser storage alone does not imply a new server-collected category; authentication requests and API operations still transmit identifiers. The portal uses locally bundled Leaflet rather than a third-party analytics/CDN script. Map tiles themselves come directly over HTTPS from OpenStreetMap.

## Sharing and recipients

Actual transfers include:

1. Assigned drivers receive the passenger roster/boarding information. Passengers receive relevant assigned-driver contact, profile, vehicle and location information. Relevant transport companies and authorized staff receive operational/application data. These are not advertising transfers.
2. Afro Message receives the SMS destination and message, including verification/recovery or transport status text.
3. The VPS hosting provider processes server data. Telebirr processes payment checkout/order data. OpenStreetMap map requests expose IP address and requested tiles/map areas to the map provider.
4. Google receives ML Kit technical telemetry. Google's SDK disclosure states it is encrypted in transit and not further shared with third parties; that statement must not be generalized to all Passo data transfers.

The [OSMF privacy policy, automatic-data section](https://osmfoundation.org/wiki/Privacy_Policy#Personal_data_we_receive_automatically) describes retained network/device/request records used for operations and security, including IP/browser/device/OS/referrer/time and requested pages; map CDNs may use geographic routing. This supports actual non-ephemeral map-network disclosure, not a claim that Passo geolocates IP addresses or embeds website analytics. The collected-only technical snapshot above must not silently exclude applicable OSM network-identifier/device-metadata transfers. Conservatively disclose applicable **Device or other IDs** sharing for map functionality/security unless a genuine sharing exception is established; ML Kit's no-further-sharing statement cannot establish that exception for OSM.

Google's form has exceptions for qualifying service-provider processing and certain user-initiated or consented transfers. **The owner must confirm provider agreements, company roles and which exceptions genuinely apply.** Do not assume that independent transport companies, all map services or an SDK vendor automatically qualify as Passo-only processors. Conservative draft: disclose the participant transfers for the affected types unless a documented Console exception applies.

## Permissions, local processing and persistence

- Camera permission is used for the live boarding QR scanner. Image frames are decoded on device with bundled ML Kit; decoded tokens are validated by the server. Separately selected photos/documents are uploaded and must still be declared.
- Driver sharing is explicitly started, uses an ongoing notification with Stop, and can continue while minimized/locked during a valid active trip. It stops on sign-out, stopped sharing, ended trip or failed authorization/lease. **Describe this behavior; do not advertise “no background location” solely because ACCESS_BACKGROUND_LOCATION is absent.**
- Passenger map views display the assigned vehicle's position; the reviewed passenger screens do not request the passenger phone's GPS location.
- Room contains local account/travel data. Android backup rules exclude databases and the encrypted guest-ticket capability preferences. Guest-ticket access is protected using an Android Keystore-backed store. Local-only storage does not erase the need to disclose server collection.
- No ads SDK, active Firebase Analytics/Auth, contact-list import or audio recording was found in the reviewed Gradle/manifest. ML Kit diagnostics remain present despite the commented Firebase dependencies.

## Deletion and retention to verify

The implemented flow removes sign-in contact/profile details, replaces the password, revokes account access, removes private files (with retry on cleanup failure), cancels current passes, and clears current driver live location. It retains a tombstone/deletion receipt and necessary historical payment, journey, complaint and audit records. Active transport obligations require handover and a support request path is supplied. Guest intercity bookings have a separate email deletion request path.

Owner confirmations still needed: actual retention schedule for each retained record and backup, hosting/provider contracts and regions, provider-side deletion handling, and a monitored response process for privacy requests. Do not invent retention periods or claim all SDK/provider records are erased by deleting a Passo account. Adult-managed information about a child transport beneficiary remains covered by these data-handling and deletion practices.

## Source evidence

- Android permissions/service: `app/src/main/AndroidManifest.xml`.
- Active SDKs: `app/build.gradle.kts` (bundled ML Kit barcode scanning, CameraX, ZXing, osmdroid; commented Firebase integrations).
- Permission disclosure/start-stop controls: `app/src/main/java/com/example/ui/screens/driver/DriverTripScreen.kt` and `core/location/DriverLocationSharing.kt`.
- Actual uploads/session lease: `app/src/main/java/com/example/core/location/DriverLocationService.kt`.
- Passenger vehicle map/provider: `app/src/main/java/com/example/ui/components/MiniVehicleMap.kt`.
- Account/booking/document payloads: `app/src/main/java/com/example/data/api/NetworkModels.kt`, `DriverApplicationModels.kt`, `IntercityPassengerApi.kt`, and `ProfileApi.kt`.
- Controlled portal routing/storage/forms: `app/src/main/java/com/example/ui/screens/home/HostedPortalScreen.kt`, `core/environment/AppEnvironment.kt`, `backend/public/company/{index.html,company.js,intercity.html,intercity.js}`, `backend/public/shared/{applications.js,fleet-map.js}`, and linked `backend/public/join/` / `backend/public/travel/`.
- Server use/visibility: `backend/src/services/onboarding.js`, `backend/src/routes/profiles.js`, `commute-passenger.js`, `vehicles.js`, `intercity.js`, `telebirr.js` and `complaints.js`.
- Deletion: `backend/src/services/account-deletion.js`, `backend/public/delete-account/`, and `app/src/main/java/com/example/ui/screens/profile/AccountPrivacyScreen.kt`.
- Privacy: `backend/public/privacy/index.html`.

Official references, rechecked 28 October 2026:

- [Google Play Data safety definitions and form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
- [Google ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- [Google Play account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
- [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).
- [Location access and foreground-service policy](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en).

## Group travel and cargo update

Version 1.1.26 adds customer-selected driver quotes, estimated cargo weight, pickup time, road distance, prepaid driver service-fee balances and ledger references, completion/dispute actions, and verified-booking star ratings/comments. Reviews are held for administrator moderation. Driver location is shared with the private booking holder during an active custom service; the current custom-service uploader runs while the driver screen is visible and stops on physical completion. Customers can optionally request their current GPS position to choose a pickup pin. Place searches and road routes are processed by Passo-hosted Photon/OSRM; map tiles retain the existing OpenStreetMap provider context. The existing Location, Purchase history, App interactions, Search history and Other user-generated content categories must cover these contexts. Credits are manually added by administrators only after separately verifying a payment; no card/bank/PIN input or live top-up checkout is introduced.

Group booking collects the buyer and each traveler’s name and phone, chosen seats and individual ticket references. The buyer and travelers receive private boarding-pass SMS links after verified payment. Cargo/private-route requests collect contact details, requested route/date, vehicle type, group size or cargo description and photo, plus optional pickup/delivery map coordinates. Matching approved available drivers can view request details, coordinates and cargo photos to quote; customer name and phone are revealed only to the selected driver and authorized administrators (plus the capability-holding customer). Include these additional names/phones/photos/location contexts when reviewing the worksheet above. Unsubmitted cargo drafts expire after seven days; submitted operational records follow the disclosed retention rules. Quotes and assignments do not automatically collect payment.

## Optional phone notifications (1.1.27; not yet submitted to Google Play)

Firebase Cloud Messaging / Firebase Installations adds device or other identifiers for app functionality and notification delivery. Analytics is not enabled. Tokens are encrypted on Passo's server and scoped to the authenticated driver or a request capability held in encrypted local storage. Android runtime opt-in controls notification display. Generic data-only notifications carry a request identifier, event, scope and service kind, without names, phone numbers, precise coordinates, photos or capability secrets. Driver notification matching uses a recently shared location (up to 24 hours), not continuous background idle-driver GPS. Logged-out drivers are revoked locally before server cleanup. Guest notifications remain bound to a privately saved request, without account registration. Before the next Google Play update, review Device or other IDs and app-functionality collection/processing declarations against Firebase's current privacy disclosure. This release must not be uploaded or submitted to Play until the owner authorizes it.
