# Google Play Data safety draft

Prepared from the Android and server source on 24 October 2026. This is a review worksheet, not a submitted Play Console declaration. The owner must confirm the outstanding items below against the exact signed release and the live provider configuration. No production account records were inspected.

## Proposed top-level responses

- **Does the app collect data? Yes.** Accounts, journeys, payments, uploaded documents and driver location leave the device. The QR SDK also collects technical data.
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

| Play data type | Actual source-backed data | Purpose / collection context |
| --- | --- | --- |
| Personal info — Name | Passenger/driver name; guest ticket name; emergency contact name | Account management, app functionality, safety; required for the corresponding signup/booking/contact step |
| Personal info — Phone number | Account, guest booking and emergency contact phone; SMS recipient | Account management, app functionality, service communications, fraud prevention/security |
| Personal info — User IDs | Server account IDs, role/company association and authenticated account references | Account management, app functionality, security |
| Personal info — Other info | Driving licence number, associated vehicle/registration information and whole-vehicle fee proposal | Driver application review, allocation and transport operation; role dependent |
| Contacts — review classification | Manually supplied emergency contact name/phone | Safety/contact details; the app does **not** read the Android address book. Confirm this classification rather than treating absence of READ_CONTACTS as absence of contact data |
| Financial info — Purchase history | Passes, bookings, amount, payment status, order/provider references, renewals and boarding entitlement | App functionality, accounting and fraud prevention; stored, not ephemeral |
| Photos and videos — Photos | Chosen profile photos and image-format verification documents | Application/profile functions and authorized participant display; role/feature dependent |
| Files and docs | Chosen licence, insurance, vehicle registration, identity/business documents; PDF or image uploads | Application review and identity/vehicle verification; driver requirements differ from passenger requirements |
| Location — Precise location | Driver device coordinates, GPS accuracy, speed/time where supplied and departure/arrival checks | Trip tracking, departure eligibility, arrival and boarding functions; user permission and driver action |
| Location — Approximate location | Coarse/network-provider driver fixes are accepted when permission/fix requirements permit them | Same transport functions. Do not claim precise-only when ACCESS_COARSE_LOCATION is supported |
| App activity — App interactions / other actions | Route proposals/requests, trip start/end, boarding/scan validation and account/security events | App functionality and fraud prevention/security; choose the applicable current Console subtype rather than declaring advertising profiling |
| App activity — In-app search history | Intercity origin/destination/date queries sent for availability; requested URLs may also be retained in server access logs | App functionality; verify actual log retention before marking this ephemeral |
| App activity — Other user-generated content | In-app complaint/support text | Support and transport operations. Review the final Console subtype wording |
| App info and performance — Diagnostics / other performance data | ML Kit device/app configuration, latency/performance, SDK events and errors | SDK diagnostics and usage analytics. Exact Console subtypes should follow Google's current ML Kit disclosure |
| Device or other IDs | ML Kit per-installation identifiers | SDK diagnostics and usage analytics; do not omit because no advertising ID is requested |

Additional conditional fields:

- **Email address:** the API supports email, but current native signup passes an empty email. A generated server value derived from the phone number is not proof that the app asks for a real email address. Include email if the exact release has a user-facing flow that actually sends one; confirm the final supported surfaces.
- **User payment info:** Passo stores payer/account phone and transaction references, and opens official Telebirr checkout. The backend explicitly rejects Telebirr PIN fields. Confirm whether the final release embeds payment-provider web content or opens an external browser, and map wallet/account identifiers accordingly. Do not claim that Passo collects card numbers, bank credentials or Telebirr PINs without evidence.
- **Messages:** complaints are service text, not evidence that the app reads SMS, the email inbox or private chat history. No READ_SMS, call-log or microphone permission is declared.
- **IP address/map area:** server logs and map providers receive network information. Declare inferred location if it is used to determine user location; the source review did not establish IP geolocation by Passo itself. Verify the map provider's handling separately.
- **Crash logs:** no dedicated crash-reporting SDK is active in Gradle. ML Kit reports its own error/diagnostic events; confirm whether these require the Crash logs subtype for the exact SDK rather than inventing a general crash collector.

## Sharing and recipients

Actual transfers include:

1. Assigned drivers receive the passenger roster/boarding information. Passengers receive relevant assigned-driver contact, profile, vehicle and location information. Relevant transport companies and authorized staff receive operational/application data. These are not advertising transfers.
2. Afro Message receives the SMS destination and message, including verification/recovery or transport status text.
3. The VPS hosting provider processes server data. Telebirr processes payment checkout/order data. OpenStreetMap map requests expose IP address and requested tiles/map areas to the map provider.
4. Google receives ML Kit technical telemetry. Google's SDK disclosure states it is encrypted in transit and not further shared with third parties; that statement must not be generalized to all Passo data transfers.

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
- Server use/visibility: `backend/src/services/onboarding.js`, `backend/src/routes/profiles.js`, `commute-passenger.js`, `vehicles.js`, `intercity.js`, `telebirr.js` and `complaints.js`.
- Deletion: `backend/src/services/account-deletion.js`, `backend/public/delete-account/`, and `app/src/main/java/com/example/ui/screens/profile/AccountPrivacyScreen.kt`.
- Privacy: `backend/public/privacy/index.html`.

Official references, reviewed 24 October 2026:

- [Google Play Data safety definitions and form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en).
- [Google ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- [Google Play account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
- [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).
- [Location access and foreground-service policy](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en).
