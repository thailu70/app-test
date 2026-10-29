# Passo Mobility: Google Play release

## Owner-approved release identity

- App name: **Passo Mobility**
- Permanent Android application ID: **live.passo.app**
- Current Play candidate: **1.1.24 (27)**
- Developer account: personal; display name **Passo mobility** in the owner's Console
- Created Play app record: **4975117772972314556**, type **App**, free download, default language **en-US**
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
./tools/sign-play-bundle.ps1 -BundlePath /path/to/verified-unsigned.aab -OutputPath /path/to/Passo-Mobility-1.1.24.aab -SigningConfigPath /private/path/signing.json -JavaHome /path/to/verified-jdk
```

Every release must have a new version code. Confirm the APKs Google generates in the internal track and inspect the Play pre-launch report before expanding testing. Unit/render tests and ELF checks do not replace physical-device testing, including Android 16 and a 16 KB-page device/emulator.

The current signed bundle is `group-cargo-release/Passo-Mobility-1.1.24.aab` in the owner's workspace (44,540,389 bytes; SHA-256 `7634f83f4bcfbe4a498d28178387801b1d4284327966bfb377541ad7bb4305ea`). Its JAR signature was verified locally, and its merged manifest identifies `live.passo.app`, version 1.1.24/code 27, minimum SDK 24 and target SDK 36, without the debuggable flag. The release evidence's `bundle.unsigned` metadata describes the original CI input, not this signed upload file. The signing certificate is self-signed as expected for an Android upload key. No private signing material is needed in Play Console.

Version 1.1.24 adds group/cargo driver registration, proposed group trips with admin approval, multi-traveler seat reservations and private group/cargo transport requests. Submitting a group or cargo request requires no passenger account, sign-in or OTP. Cargo supports a photo and optional pickup/delivery pins. Admin assignments and customer status are live. Live Telebirr checkout is not configured and free/test payments remain disabled; the owner chose to launch registration and requests first. Do not describe the current release as accepting paid ticket purchases. Individual group tickets and boarding-pass SMS require verified payment once production checkout is enabled.

## Store materials

- [Listing text](store-listing.md)
- [Data safety worksheet](data-safety.md) — owner review still required; not a submitted declaration
- [App access worksheet](app-access.md) — synthetic reviewer resources and geographically usable access still required
- `assets/app-icon-512.png`: 512×512 RGBA PNG; original Passo pin-P vector, no pre-rounded corners
- `assets/feature-graphic-1024x500.png`: 1024×500 RGB PNG
- `assets/*.svg`: editable vector sources
- `play-release/store-assets/` in the owner's workspace: five opaque RGB 1080×1920 phone screenshots, the 512×512 icon and 1024×500 feature graphic, prepared from actual native composables with isolated demo route data. The group/cargo preview renders are in `group-cargo-release/previews/`; review their dimensions and presentation before choosing additional store screenshots. Never use production accounts or contact details.

The five existing phone screenshots were visually checked on 28 October 2026. The only operational examples shown are the clearly labeled `Bole - CMC · Demo` route and `Passo Demo Transport`/`Demo coach` trip from `PlayStorePreviewTest.kt`. They contain no passenger/driver names, phone numbers, account IDs, private boarding codes or production customer records; their PNG metadata dictionaries are empty. They show the actual welcome, daily discovery/fare and intercity discovery composables. They do not yet demonstrate the new group/cargo request screens.

Graphic alt text: “Passo Mobility. Go Further, Together. Daily commutes and intercity journeys, with a teal bus and route.”

Do not claim live Google Play availability or place a Google Play badge on the website until a real store listing is available.

## Current Console status

On 28 October 2026 the app record was created after the owner approved Google's three app-creation declarations. Console identifies app **4975117772972314556** as **Passo Mobility**, `live.passo.app`, a free App with default language English (United States). Google accepted version **1.1.24 (27)** and the internal-testing release is **Active**, available to the owner's authorized one-user tester list. The opt-in link is `https://play.google.com/apps/internaltest/4700536309219426213`. Console temporarily calls the unreviewed app `live.passo.app (unreviewed)`. Closed testing has not started; Production is locked until the 12-tester/14-day requirement and other setup/review requirements are met. Complete any remaining account, contact-phone or Android-device checks when Console requests them.

Next steps:

1. Finish contact-phone and Android-device verification when Console requests them.
2. Join the active internal test with the approved Google account, install Google's generated build and perform device checks. Internal testing does not count toward the required closed test.
3. Complete store listing, privacy/deletion URLs, content rating, 18+ target audience, ads, App access, Data safety and foreground-location service declaration/video using the exact release behavior. Resolve the worksheet's owner-dependent items; do not invent reviewer credentials or provider agreements.
4. Prepare reusable synthetic reviewer resources. Production GPS, payment and approval protections must not be globally bypassed for a review.
5. Complete a closed test with at least **12 testers continuously opted in for 14 days**, collect meaningful feedback, fix issues and then apply for production access.
6. Submit the production release only after account/app requirements and review are satisfied. Google determines acceptance and timing.

The owner authorized the three app-creation declarations. No tester invitations, app-content/Data safety declarations, reviewer credentials or real payments have been submitted by these preparation steps.

Official references:

- [Developer account setup](https://support.google.com/googleplay/android-developer/answer/6112435)
- [Personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Android App Bundles](https://developer.android.com/guide/app-bundle)
- [App signing](https://developer.android.com/studio/publish/app-signing)
- [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Store graphic requirements](https://support.google.com/googleplay/android-developer/answer/9866151)
