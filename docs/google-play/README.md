# Passo Mobility: Google Play release

## Owner-approved release identity

- App name: **Passo Mobility**
- Permanent Android application ID: **live.passo.app**
- First Play candidate: **1.1.23 (26)**
- Developer account: personal; display name **Passo mobility** in the owner's Console
- Audience: **18 and over only**. Signup and guest intercity checkout require an adult self-acknowledgment. This is not technical age verification.
- Production API origin: **https://passo.live/**; API routes include `/api/`.
- Suggested category: Travel & Local. Download is free; transport bookings and passes can require separate payment.
- Initial availability should match the operators' actual service area. The owner must select supported countries in Console; branding does not establish route availability across Africa.

The package differs from the old automatically named sideload test app. Existing server accounts remain usable; device-local guest tickets/preferences do not migrate to a different Android package. Keep the old test app or preserve its tickets until they are no longer needed.

## Release artifacts and signing

The `Passo Google Play candidate` workflow tests the app, builds a non-debuggable **unsigned** release AAB, validates it with checksum-pinned Google bundletool, checks the approved package/version/HTTPS-only manifest, and checks all bundled 64-bit native ELF libraries for 16 KB page compatibility. It never receives the upload private key.

The owner-side `tools/sign-play-bundle.ps1` signs a verified candidate using a private local PKCS12 upload key and validates its JAR signature. Private key material and passwords must stay outside Git and all public download folders. Back them up securely. The public upload certificate may be retained with release evidence. Do not upload the unsigned artifact to Play Console.

For a new Play app, use Play App Signing and let Google protect the app signing key. The local upload key authenticates future bundle uploads; it is separate from Google's key used to sign device installations. Preserve the upload key for subsequent releases.

Local signing usage (paths refer to the owner's protected files):

```powershell
./tools/sign-play-bundle.ps1 -BundlePath /path/to/verified-unsigned.aab -OutputPath /path/to/Passo-Mobility-1.1.23.aab -SigningConfigPath /private/path/signing.json -JavaHome /path/to/verified-jdk
```

Every release must have a new version code. Confirm the APKs Google generates in the internal track and inspect the Play pre-launch report before expanding testing. Unit/render tests and ELF checks do not replace physical-device testing, including Android 16 and a 16 KB-page device/emulator.

## Store materials

- [Listing text](store-listing.md)
- [Data safety worksheet](data-safety.md) — owner review still required; not a submitted declaration
- [App access worksheet](app-access.md) — synthetic reviewer resources and geographically usable access still required
- `assets/app-icon-512.png`: 512×512 RGBA PNG; original Passo pin-P vector, no pre-rounded corners
- `assets/feature-graphic-1024x500.png`: 1024×500 RGB PNG
- `assets/*.svg`: editable vector sources
- `app/build/play-store-previews/`: screenshots generated from actual native composables with isolated demo route data; no production accounts or contact details. Export as opaque PNG before uploading.

Graphic alt text: “Passo Mobility. Go Further, Together. Daily commutes and intercity journeys, with a teal bus and route.”

Do not claim live Google Play availability or place a Google Play badge on the website until a real store listing is available.

## Current external gate

The owner's personal account exists. The Console says Google is reviewing the submitted identity documents; contact-phone verification follows identity approval. Clicking Create app currently displays **“Complete account verifications to create apps.”** No app entry, bundle upload or public rollout has been completed.

Once verified:

1. Finish contact-phone and Android-device verification when Console requests them.
2. Create the app with the approved name, default language English, type App, and free download. The owner must accept any legal declarations at the creation step.
3. Upload the signed AAB to an internal test and configure Play App Signing.
4. Complete store listing, privacy/deletion URLs, content rating, 18+ target audience, ads, App access, Data safety and foreground-location service declaration/video using the exact release behavior. Resolve the worksheet's owner-dependent items; do not invent reviewer credentials or provider agreements.
5. Prepare reusable synthetic reviewer resources. Production GPS, payment and approval protections must not be globally bypassed for a review.
6. Complete a closed test with at least **12 testers continuously opted in for 14 days**, collect meaningful feedback, fix issues and then apply for production access.
7. Submit the production release only after account/app requirements and review are satisfied. Google determines acceptance and timing.

No tester invitations, legal declarations, account credentials or real payments were submitted by these preparation tools.

Official references:

- [Developer account setup](https://support.google.com/googleplay/android-developer/answer/6112435)
- [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Android App Bundles](https://developer.android.com/guide/app-bundle)
- [App signing](https://developer.android.com/studio/publish/app-signing)
- [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Store graphic requirements](https://support.google.com/googleplay/android-developer/answer/9866151)
