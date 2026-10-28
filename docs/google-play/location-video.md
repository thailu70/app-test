# Real Android foreground-location demonstration

The manually dispatched **Passo real Android location demonstration** workflow records an Android 15 emulator running the real driver screen, runtime permission dialogs, `DriverLocationService`, ongoing notification and notification Stop action. Its output is `Passo-driver-location.mp4`, accompanied by screenshots, the instrumentation result, service/notification diagnostics and assertion/video hashes. It is a real device-framebuffer recording, not generated animation or a sequence of rendered mockups.

Only a new instrumentation test and dedicated workflow are added. No release source, production authentication/GPS validation, payment policy, customer record, APK/AAB or upload key is changed. The existing driver screen API injection and location-service test factory provide an already-active synthetic trip; this video does not claim to demonstrate production login, administrative approval or payment. A visible banner identifies the data as synthetic. Device networking is disabled, the debug API origin is a closed loopback port and no SMS credentials are supplied. No real contacts appear.

The test taps **Share trip location**, pauses at the actual disclosure, taps **Allow and share**, accepts the real Android location and notification prompts, checks the actual ongoing foreground-service notification, waits for the emulator's fresh GNSS coordinates to pass unchanged production GPS validation, minimizes the app, displays the notification, taps its **Stop sharing** action and verifies that the notification/session disappear and uploads stop. If the platform reports a mock/stale location, the test fails rather than bypassing validation.

After a successful manual run:

1. Download the `passo-real-location-video` artifact and review the complete MP4, including readability, permission prompts, notification action and ending.
2. Confirm `assertions.json` reports `passed: true`, GPS uploads are positive, and `video-verification.json` exists. Failed-run recordings are diagnostic evidence only, never submission evidence.
3. Host the approved MP4 at a stable, publicly accessible HTTPS link without authentication or expiry, or use an unlisted video upload. Enter that real link in the Play foreground-location declaration. A GitHub artifact's temporary signed URL is unsuitable.
4. Describe the video as the implemented location-sharing flow with synthetic demonstration data. It is not evidence that separate, reusable reviewer accounts or a geographical/time-independent review environment already exist.

The production signed bundle `Passo-Mobility-1.1.24.aab` remains unchanged. Do not rebuild or replace that bundle merely to record this test.
