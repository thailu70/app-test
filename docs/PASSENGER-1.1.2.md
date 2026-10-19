# Passenger portal 1.1.2

## Screenshot audit

The three user-provided passenger screenshots were reviewed on 2026-10-17. The original images contain private account/boarding information and are retained only in the local audit folder, outside the repository.

1. **Home / pass summary — incorrect data and poor readability.** The driver card and pass summary showed different drivers and plates. Source inspection confirmed hardcoded sample vehicle, driver, pickup/destination and payment values. The dark card also hid the days remaining, while the crowded header and top tabs consumed space. Replace sample data with the current server subscription, show the assigned driver once, use readable white cards and move four primary destinations to bottom navigation.
2. **Vehicle location — misleading empty state.** A full city map appeared behind a “no vehicle assigned” message, although the driver card had an assignment. Tracking required an active trip; the driver endpoint did not. Native map content also escaped its bounds, overlapping text and duplicate attribution. Use one assignment selector, distinguish assignment from live GPS, and show a clipped map only after a genuine location report.
3. **My pass — crowded presentation and invalid QR encoding.** An oversized registration reminder displaced the boarding code; the status badge wrapped and technical text added clutter. Source inspection confirmed that the previous QR graphic was a hash-based pattern rather than an encoded server token. Use a compact reminder on Home only, a single-line status, and a real QR encoder with a quiet zone. Test decoding the actual rendered QR back to the exact input token.

The screenshot review identifies visible readability/reflow risks; it does not establish full accessibility compliance. Native card rendering at 360 dp and larger text, server regressions and QR decoding are automated. Full TalkBack, device-specific camera and physical boarding checks require a device.

## Resulting behavior

Home shows the current pass, real remaining days and schedules, one assigned driver, and a primary boarding-pass action. My pass focuses on a scannable code. Payments show recorded values and real statuses; More contains profile, location, attendance and support. Profile completion remains nonblocking for temporary administrator-activated passes.

The API selects the same latest daily commute subscription for pass status, driver details and tracking. Valid assigned vehicles remain assigned before departure. Fresh GPS on an active trip is live; old reports are stale or offline. Missing GPS never becomes a seeded city position. Ineligible passes and other companies' records cannot expose a location.

Android waits for the current session's server response instead of showing legacy Room sample passes. Successful empty responses clear that passenger's old pass. Logout clears account/payment state. Demo bootstrap data and invented payment-route defaults are removed. The unsupported local-only cancellation action is removed; passengers contact their administrator for changes.

Version **1.1.2 (code 6)** contains these Android changes. CI runs assignment regressions on SQLite and PostgreSQL, subscription cache tests, native layout rendering and QR decode tests, and builds the APK. The APK and preview artifacts are tied to the GitHub commit used for deployment.
