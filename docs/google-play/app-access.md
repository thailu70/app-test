# Google Play App access preparation

Updated 28 October 2026 for the **1.1.25 (28) candidate source**. The isolated synthetic backend is deployed and its original eight role/application/deletion accounts passed live API checks. Company-owner/company-applicant coverage is being added separately. Final rebuilt signed-bundle UI checks remain pending; backend checks and source inspection do not establish that every instruction works in the uploaded Android build. No production customer records were used.

## App access declaration

**All or some functionality is restricted.** Guests can browse daily/intercity/group trips and submit private group/cargo requests without registration, sign-in or OTP. Account/pass functions, company operations and driver functions require the relevant credentials and approval/assignment/transport state. A guest request's private capability protects its status and cargo photo.

Production OTP deferral does not remove acceptance, required documents, approval, payment confirmation, assignment, departure-time or GPS checks. Review uses a separate, explicitly selected **Demo** environment at `https://passo.live/review/`; it does not grant production access or change production eligibility. Synthetic data are labeled, no real SMS is sent and no money is collected. Online Telebirr checkout and production free/test payment bypasses remain disabled. The company booking desk separately supports recording tickets for cash already collected; disabled online checkout does not mean the absence of payment records.

The owner selected **18 and over only**. Supplied accounts represent adults. Adult-arranged transport can include a child beneficiary, who is not an app account user. This does not claim technical age/identity verification.

## Reusable access resources

These phone identifiers are fictional synthetic fixtures. Put the maintained password only in Play Console's restricted App access credential fields. Never commit passwords, private recovery capabilities or deployment secrets. Do not use personal/real-driver accounts or the former payment-test phone as reviewer access.

| Phone | Resource | Verification status / use |
| --- | --- | --- |
| `0900000101` | Approved passenger | Live synthetic API checked: assigned daily commute, paid sample pass and driver/vehicle |
| `0900000102` | Approved daily driver | Live synthetic API checked: approved profile/vehicle, assigned route and roster |
| `0900000103` | Approved group driver | Live synthetic API checked: approved trip, paid travelers/tickets and assignment resources; proposals use ordinary review state |
| `0900000104` | Approved cargo driver | Live synthetic API checked: assigned request and non-personal cargo photo |
| `0900000105` | Approved company-associated driver | Live synthetic API checked: intercity assignment and paid ticket roster; this is a driver, not a company-owner login |
| `0900000106` | Pending daily applicant | Live onboarding API checked: awaiting initial acceptance; enter through Driver application sign-in, not operational driver authentication |
| `0900000107` | Accepted daily applicant | Live onboarding API checked: acceptance complete, profile/documents unfinished; enter through Driver application sign-in |
| `0900000108` | Disposable passenger | Live deletion/replacement API checked; use for destructive deletion review |
| `0900000109` | Company owner | Implementation/deployment/Android validation in progress; do not claim working access yet |
| `0900000110` | Company-associated applicant | Additional ninth driver/passenger fixture in source; deployment/acceptance-review validation in progress |

The original eight rows were checked against the deployed synthetic API by the review-environment task. Newly added company fixtures/UI bridge need separate validation. The complete source bootstrap is intended to maintain nine driver/passenger application/account states plus the company owner; this is not a claim that all ten are already deployed.

The private fixture refresh runs every five minutes. Deletion permanently revokes the old account ID/token and retains its deletion receipt. On the next refresh, the same fictional phone is provisioned with a **new** account ID and the maintained private password. Allow up to five minutes before signing in again. Never reuse the deleted token. Prefer the disposable passenger: operational driver obligations can correctly prevent deletion.

## Reviewer instructions to verify in the final signed build

1. On Welcome, open **About & demo**, explicitly choose **Demo**, and confirm the persistent synthetic-data notice. Environment changes cancel API/GPS/WebSocket work and isolate account/private-ticket stores. **Exit demo** returns to live service; do not enter demo credentials there.
2. Choose **Passenger** and sign in with `0900000101`. Review the daily commute, pass/QR, driver/vehicle, payments and Privacy & account. Demo contains already-paid synthetic examples; do not attempt real payment.
3. After an approved synthetic sign-in, open **About & demo → Load sample tickets & requests**. This imports only protected synthetic capabilities from the selected environment. **View sample journeys** opens native intercity while retaining the account. Verify these buttons/restored-ticket behavior in the final signed build.
4. Choose **Driver**, sign in with `0900000102`, and review the roster and location disclosure/start/notification/Stop controls. The isolated backend relocates only owned synthetic departure origins/times to a fresh physical GPS fix, then executes normal ownership, time, accuracy, capacity and paid-boarding checks. It works outside Ethiopia without a production GPS exception or simulated GPS.
5. Use `0900000103`, `0900000104` and `0900000105` for group, cargo and company-associated driver functions. Import sample resources where needed. New guest group/cargo requests use synthetic contact details, cargo description/photo and optional pins; they enter ordinary pending state. Separately seeded assignments demonstrate the assigned/completed handover state without waiting for an administrator.
6. For pending/accepted onboarding, sign out and return to **Welcome → Driver → application sign-in** using `0900000106` or `0900000107`. The onboarding API accepts these credentials to show status/documents. Operational driver sign-in denial before approval is expected; reviewers should not wait for a person or bypass it.
7. Company-owner/company-applicant instructions remain **in progress**. The planned Demo company view must use only the isolated prefix/backend and synthetic credentials, with no production browser session/storage exposure. Do not submit this access as complete before deployment and final signed Android checks.
8. Use `0900000108` for **Privacy & account → Delete account**, its maintained password and the displayed `DELETE` confirmation. The old session must stop working. Allow the refresh interval above before signing in with a replacement ID for that phone.

Guest status recovery is capability-protected and environment-specific. Never copy its secret response into public documents, screenshots, build logs or store text. Review does not require production administrator credentials.

## Foreground-location demonstration

A real Android emulator recording demonstrates disclosure, user initiation, runtime permission, minimized ongoing notification and its Stop action with synthetic fixtures. CI run **40817341731** passed. This is not a recording of the final rebuilt signed bundle or evidence that every reviewer-account flow was manually exercised.

- Public HTTPS video: `https://passo.live/company/downloads/passo-driver-location-1.1.25.mp4`
- SHA-256: `9662b417dee267d7651dbd8981a0fa554f0c836290d7a7362affeb4c14d608a8`
- Size: 6,552,038 bytes; HTTPS GET `200`, `video/mp4` and byte-range `206` verified.
- Recording method/assertions: [location-video.md](location-video.md).

## Remaining release gates

- Finish company-owner/applicant deployment and Android review access, then exercise all final signed-build paths. Preserve normal live auth/GPS/payment behavior.
- Verify uploaded package/version, signing and release assets. App record **4975117772972314556**, package `live.passo.app`, exists; the original internal test is active. Current closed-release preparation is separate.
- Complete current Console App content/Data safety/location forms and policy/deletion checks. Data safety includes the controlled Company portal and ML Kit telemetry; see [data-safety.md](data-safety.md).
- The personal account's production gate requires the closed test and production-access application. Internal testing does not replace the closed-test duration. This document does not assert that the closed-test clock or production publication has started.
- Recheck the final Android age acknowledgement against the owner's 18+ audience. Self-affirmation does not establish verified age.

## Evidence

Source: `MainActivity.kt`, `core/environment/`, Demo controls, `HostedPortalScreen.kt`, native driver/passenger/intercity/mobility screens, `backend/src/review/`, and [review-environment.md](review-environment.md). Current live/company status above takes precedence over earlier preparation wording in linked documents.

Official references:

- [Reusable review credentials and complete app access](https://support.google.com/googleplay/android-developer/answer/15748846?hl=en).
- [Testing requirements for personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- [Foreground-service declaration/video](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).
- [Location access policy](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en).
- [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en).
