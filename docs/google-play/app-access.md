# Google Play App access draft

Prepared from source on 24 October 2026. No reviewer accounts or passwords have been created or verified by this audit. This document is not ready to paste into Play Console until the owner completes the access resources below. Never put real passenger or driver data in reviewer accounts or screenshots.

## App access declaration

**Draft selection: All or some functionality is restricted.** Guest users can browse daily/intercity routes, but passenger account/pass functions and driver operations are restricted by credentials, approval, assignment, payment and trip eligibility.

Google requires reusable reviewer access to all functionality, available regardless of the reviewer's location. Account/signup OTP is currently deferred for drivers and passengers. That does not remove driver acceptance/document review, payment confirmation, camera/location permission, GPS departure radius or schedule checks.

The owner has confirmed an **Adults only — 18+** audience. Only adults may create accounts or use the app/account portals. Parents, guardians or authorized adult institution representatives may arrange transport for children; those children are transport beneficiaries, not account users. Reviewer accounts must represent adults. This is an audience/use condition, not a claim that automatic age or identity verification exists.

## Owner must supply these resources

| Resource | Required preparation | Current status |
| --- | --- | --- |
| Approved passenger account | Synthetic name/phone, reusable password, assigned route and active paid/demo boarding entitlement with visible assigned driver/vehicle | Not supplied or verified |
| Approved daily driver account | Synthetic profile, clearly marked synthetic verification documents, final approval, owned vehicle, assigned route and synthetic passenger roster | Not supplied or verified |
| Driver application account | Synthetic pending/accepted application so onboarding, document upload and status/corrections can be reviewed without waiting for a real administrator | Not supplied or verified |
| Intercity ticket/boarding resources | Synthetic published departure, seat/ticket and valid QR/ticket number usable by the reviewer, with instructions for acquiring/reopening it | Not supplied or verified |
| Company-driver coverage | If retained in the Android release, synthetic approved company-associated driver and usable assigned journey | Not supplied or verified |
| Region/time-independent access | A bounded review/demo environment or documented review mechanism for functions currently gated by Ethiopian GPS coordinates, real departure windows and ticket/payment state | Not implemented or verified |
| Privacy/deletion review | Disposable synthetic accounts eligible for deletion; replacement credentials/instructions if a reviewer deletes one | Not supplied or verified |

Credentials must be maintained in Play Console's restricted App access fields, not committed to this repository. Do not use the operator's personal account, a real driver account, the test-payment phone `0912469923`, or an expiring signed download link as reviewer access.

## Reviewer instruction outline

Complete the credential fields in Play Console and then use a short instruction set like this, adjusted to the verified release:

1. Open Passo and choose English, አማርኛ or Afaan Oromoo.
2. Choose **Passenger** for daily commute discovery. Browse public routes; use the supplied passenger sign-in to view its assigned commute, boarding pass, driver/vehicle, payments and Privacy & account.
3. Choose **Driver** and sign in using the supplied approved driver account. Review the assigned route, synthetic roster, trip controls, document/profile and privacy screens.
4. To examine live location sharing, follow the separately supplied review-environment trip instructions. Tap **Share trip location**, read the disclosure, grant location and notification permissions, and confirm the ongoing notification/Stop action. Stop sharing after the test.
5. To examine boarding, scan the supplied synthetic paid pass/QR or use the supplied ticket-number path where available. Describe the expected confirmation and ensure it works outside Ethiopia and outside a single short departure window.
6. Choose **Intercity Travel** to view available future departures, search a route, choose a seat and open the supplied synthetic recent ticket. Specify any test checkout steps that do not require spending real money or possessing an Ethiopian wallet.
7. Use **Privacy & account** for the policy and deletion flow. State which disposable credential can be deleted and how a replacement is obtained without contacting a person during review.

Do not state that these steps are verified until they have been completed against the final signed build. The Android app does not need to give Play reviewers a production administrator account.

## Material submission blockers

1. **No personal developer account yet.** The owner must create and verify it. Google's current new-personal-account production gate requires a closed test with at least 12 testers continuously opted in for 14 days, followed by the production-access application. An internal test is useful first but does not replace that gate.
2. **No complete reviewer access package.** A blank fresh driver account cannot reach operational features until manual acceptance, required documents, final approval and assignment. “Please register and wait for approval” is not reusable reviewer access.
3. **GPS and schedule restrictions.** The source enforces actual departure eligibility. A reviewer abroad cannot satisfy an Addis pickup geofence. Do not weaken production authentication, payment confirmation or GPS checks globally to solve review access; prepare a bounded synthetic review environment and verify it.
4. **Location declaration/video.** The app declares a location foreground service and can track a driver with the screen locked. Prepare the relevant Play Console foreground-service declaration and demonstration video showing disclosure, user initiation, permission, ongoing notification and Stop. Review Google's location policy for whether the particular minimized-use implementation also triggers its background-location review; absence of ACCESS_BACKGROUND_LOCATION alone is not a policy exemption.
5. **Accurate Data safety and privacy.** Include ML Kit installation identifiers and diagnostics/usage analytics. Resolve provider/transport-company sharing classifications and the remaining owner confirmations in `data-safety.md` before submitting.
6. **Adult audience implementation check.** The owner selected **18 and over only**. Web driver signup has a translated 18+ notice and required adult-confirmation checkbox for client-side submission. This self-affirmation is a policy acknowledgement, not technical age verification, and does not change backend registration contracts. Verify the final Android acknowledgement separately. Adult-arranged school transport does not make the child an app account user, and does not mean child-related transport records are absent.
7. **Real release and store readiness.** Verify the persistent signing/upload-key arrangement, signed AAB, final package/version, complete screenshots/store text, content rating, payment-provider configuration and functioning support/privacy links. This source audit does not certify those operational items or claim Play publication.

## Source evidence

- Welcome/role routing: `app/src/main/java/com/example/MainActivity.kt` and `ui/screens/home/PassoWelcomeScreen.kt`.
- Account/application contracts: `backend/src/routes/auth.js`, `routes/onboarding.js`, `services/onboarding.js` and `services/operational-onboarding.js`.
- Approval/assignment controls: `backend/src/routes/admin.js`, `company-portal.js`, `trips.js`, `intercity.js` and their eligibility helpers.
- Driver location disclosure/service: `app/src/main/java/com/example/ui/screens/driver/DriverTripScreen.kt` and `core/location/DriverLocationService.kt`.
- Booking/payment confirmation: `app/src/main/java/com/example/data/api/IntercityPassengerApi.kt`, `backend/src/routes/intercity.js`, `telebirr.js` and `services/payment-policy.js`.
- Privacy/deletion: `app/src/main/java/com/example/ui/screens/profile/AccountPrivacyScreen.kt` and `backend/public/delete-account/`.

Official references, reviewed 24 October 2026:

- [Reusable review credentials and complete app access](https://support.google.com/googleplay/android-developer/answer/15748846?hl=en).
- [Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [Foreground-service declaration/video requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).
- [Location access policy](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en).
- [Account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
