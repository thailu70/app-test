# Current verification: welcome screen fills the phone

APK 1.1.13/code 16, app source 3051888. Sizing-only correction to the existing native welcome screen. Portrait now uses the full safe width with intentional 16dp control gutters and 8dp beneath Continue. The photo adapts to the height left by the controls; the whole page is no longer uniformly scaled into a narrower column. Translated service cards share equal heights. Existing artwork, wording, language/service selection and destination callbacks remain in use; no authentication, API or backend changes.

Regression coverage checks actual rendered edges, native language touch-target heights, equal card bounds, bottom spacing, zero scroll containers and all destination actions in English, Amharic and Afaan Oromoo at 360 x 640, 393 x 852 and 412 x 915 dp. Landscape retains the existing contained presentation with reachable controls. The branding crop and photo crop adapt separately on short phones to retain the logo, faces and vehicle. Normal portrait uses a continuous photo.

Final Android run 39372791118 and CI 39372791047 passed. All 52 checks passed with zero failures/skips. Evidence artifact 12242287919 was extracted to `../welcome-size-1.1.13-release/`. Root and independent visual review passed the 393 x 852 Amharic, 360 x 640 Amharic and 412 x 915 Oromo captures: full portrait width, continuous normal/tall photo, legible branding, visible compact-photo subjects and Continue, aligned cards and no unintended outer gutters. The small-phone Oromo language option wraps without clipping. Existing matte motto backing and flat fills remain minor optical differences. These are native renderer checks, not physical-device installation tests. Android status/navigation areas remain reserved for system controls. Installable debug APK artifact: 12241679876.

final result: passed

---

# Passenger and driver authentication verification

APK 1.1.12/code 15, app source b425abd. Implemented the four approved native passenger/driver sign-in and sign-up designs, including photographic headers, PASSO identity, three language choices, editable forms and password visibility. Registration keeps SMS verification, active/custom commuter route selection and driver service/vehicle/company details; drivers still require approval.

Android run 39351724522 and CI 39351724500 passed. All 50 checks passed. All four native captures were paired directly with their approved mockups; initial hero, heading and language-spacing issues were fixed and recaptured. Independent visual review found no P0/P1/P2 issues. See [authentication verification](design-qa-auth.md) for source/evidence paths, test scope and limits.

final result: passed

---

# Native intercity passenger console verification

APK 1.1.11/code 14, source commit 899ed22. The intercity passenger flow now uses native Android screens instead of the travel WebView: route search and suggestions, date selection, real seat inventory, private booking access, Telebirr handoff/reconciliation, paid QR and short ticket numbers, recent tickets, route requests and native PDF saving. Existing backend payment/SMS logic is retained.

Android run 39343835824 and CI 39343835822 passed; all 39 automated checks passed. Native screenshots were compared directly with the supplied intercity reference. The initial spacing, compact receipt and large-text footer findings were corrected and recaptured. Final visual review has no P0/P1/P2 finding. Evidence, expected data-driven differences, P3 polish and testing limits are recorded in [intercity verification](design-qa-intercity.md).

final result: passed

---

# Daily route browsing verification

APK 1.1.10/code 13, commit 54f19c8. Removed the three-line commuter introduction, changed the banner subtitle to Find a daily route, and added safe top insets with a slightly lower Sign in button. Both top and hardware Back from passenger authentication now return to daily route browsing, including entry from My pass. Driver return behavior is retained.

Route cards and details show each tier's fare beneath its name. The existing Economy fare is used; missing fares show Not available with a small red dot, as the user specified. No prices were invented and no payment or backend logic was changed.

Android run 39337128694 and backend CI 39337128762 passed. All 21 checks passed, including two integration tests through the actual MainTransportApp for both Back paths. Reviewed native component preview 04-route-browse.png from artifact 12226853862 against the requested changes; it uses test route/fare data. Search, filters and navigation remain in the full page. Physical-device installation was not exercised.

final result: passed

---

# Welcome viewport fit verification

Sizing-only update, APK 1.1.9/code 12, commit e1f4053. The existing native welcome composition now scales uniformly to the available safe screen height without a scroll container. Artwork, colours, wording, control order, selections and callbacks are unchanged.

Android run 39332842362 and backend CI 39332842344 passed. All 18 checks passed, including complete Continue bounds and service/language/Continue taps without scrolling at 360 x 640 dp in English, Amharic and Afaan Oromoo. Reviewed native portrait and short-phone captures from verification artifact 12225660555; the full page and Continue fit. Landscape interaction also passed. No physical phone installation was performed.

final result: passed

---

# Driver portal verification

Driver portal version 1.1.8 passed the native Android visual review and all 17 automated checks on commit 5455358. Android build 39329916153 and backend CI 39329916243 succeeded. See [driver verification](design-qa-driver.md) for the source, screenshots, functional scope and physical-device testing limits. The earlier commuter review is in [commuter verification](design-qa-commuter.md).

final result: passed

---

# Welcome screen verification

Scope: welcome screen and direct service navigation. Passenger, driver and intercity portal redesigns are subsequent screens in the user's agreed one-screen-at-a-time process.

Reference: ChatGPT Image Oct 19, 2026, 07_15_26 AM.png (853 x 1844).

Implemented native service selection, language selection, Continue, visible selected states, accessible semantics, scrollable layout, and retained selection on rotation. Continue opens daily commute routes, driver authentication, or intercity route selection. Existing authentication, route request, booking and backend APIs remain in use.

The hero and the three decorative card images use measured regions of the supplied reference. They do not contain interactive labels or click targets. Service labels, descriptions and navigation are native Compose controls. The branded hero includes a native replacement tagline reflecting the approved pan-African motto.

Verification iterations:
- Replaced full-screen screenshot with native controls; interaction checks passed.
- Native screenshot capture initially timed out; changed capture to draw the activity window.
- Compared first portrait rendering: corrected abrupt hero crop, font weight and excessive vertical spacing.
- Corrected Android density scaling of source image regions after the next native screenshot exposed incorrect crops.
- Added landscape interaction test; corrected qualifier ordering in test configuration.

Language scope: English, Amharic and Afaan Oromoo welcome text. Existing downstream Oromo strings use English fallback; this is not a full-app Oromo translation.

Final evidence: GitHub Android run 39312336248, commit 7975882; native portrait 393 x 852 dp and landscape 800 x 360 dp. Final screenshots are in the welcome-verification artifact. Android build and both native interaction tests passed. Backend CI run 39312336379 passed.

Fidelity review:
- Typography: Outfit with explicit 400/600/700/900 variable weights; no thin-font regression. Amharic uses the platform fallback.
- Layout: hero, headline, language row, three service cards and Continue are in reference order. Narrow/landscape layouts scroll; controls remain reachable. Final card spacing brings Continue into the portrait view.
- Colors: cream, navy, teal and pastel mint/blue/sand match the design family. Native button fills are flat rather than the source's subtle gradients (P3 polish).
- Images: exact supplied decorative photo regions and badges, with density-aware bounds; no whole-page screenshot or invisible control zones. Resource decoding no longer upscales photographic artwork unnecessarily.
- Content: approved Go Further, Together motto and pan-African wording intentionally replace Addis-specific copy. Selection outline and checkmark are functional additions.

Remaining P3 polish: the native motto has a plain cream backdrop; exact typography and subtle gradient/elevation effects are not pixel-identical to the generated mockup. Full downstream Oromo localization is outside this welcome iteration. Physical-device installation was not performed in this environment.

This result verifies the working welcome recreation and navigation, not pixel identity across all four portals.

final result: passed
