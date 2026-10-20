# Native authentication verification

Scope: approved passenger sign-in/sign-up and driver sign-in/sign-up mockups, with working native controls and the existing authentication/registration APIs.

Visual truth: sibling workspace files `../mockups/auth/passenger-sign-in-v2.png`, `passenger-sign-up.png`, `driver-sign-in.png`, `driver-sign-up.png`. Sign-in source images are 935 x 1683 pixels; signup sources are 853 x 1844. Native evidence is 786 x 1704 pixels at 393 x 852 dp, density 2. Full comparison panels normalize both sources and native output to 393 pixels wide without stretching; sign-in source height therefore differs from the phone viewport. This framing difference is recorded rather than treated as pixel identity.

Initial build d5fc1af needed a Material icon compatibility correction (92b4dd4). The next build compiled successfully and passed 48/49 checks. Its single failure concerned the pending-driver success fixture. Deterministic Room executors and explicit route selection corrected the test while preserving every pending-approval assertion. No production payment, boarding or SMS delivery behavior changed.

Initial rendered comparisons: `../auth-1.1.12/compare-01-passenger-login.png`, `compare-02-passenger-sign-up.png`, `compare-03-driver-login.png`, `compare-04-driver-sign-up.png`.

## Initial visual findings

- P1: signup photography is about 80 dp too short, cropping the approved scene and moving the role/title too high. Increase the adaptive header and remove the internal height cap. Tighten form gaps and footer layout so the primary action still fits.
- P2: passenger login and driver signup headings are smaller than the approved visual hierarchy. Adjust the display size while retaining the shared Outfit font.
- P2: the driver header's Afaan Oromoo caption wraps unnecessarily. Give language controls more of the header row.

The role-specific photos are clean crops from the approved generated mockups. Branding, language buttons, labels, forms, verification steps and actions are real Compose controls; no full-screen screenshot or invisible tap zones are used. Empty passwords remain empty, rather than pretending credentials have been entered.

Form drafts and OTP tokens use in-memory UI state; no new account-state persistence was added. Signup sends a registration SMS, verifies its six-digit code, then collects the route or driver details. Changing the phone invalidates verification. Driver registration remains pending approval; passenger registration keeps active route selection or the existing structured custom-route request. A small ViewModel validation correction permits complete custom-route requests already accepted by the backend. Password help remains administrator assistance.

## Final correction and verification

Final app source: b425abd; APK 1.1.12/code 15. Android run 39351724522 and CI 39351724500 succeeded. All 50 tests passed with zero failures and zero skips. APK artifact 12233324042 is a 52,466,120-byte ZIP containing the installable debug APK; native verification artifact is 12233313980.

Post-fix evidence: `../auth-1.1.12-final/compare-01-passenger-login.png`, `compare-02-passenger-sign-up.png`, `compare-03-driver-login.png`, `compare-04-driver-sign-up.png`. These panels place the actual approved source and captured implementation together. Focused comparisons covered the hero/wordmark/language areas, input and CTA geometry, headings, progress and footer text. Narrow evidence: `../auth-1.1.12-final/auth-previews/07-small-driver-login-oromo.png` and `08-small-driver-sign-up-oromo.png`, 720 x 1600 pixels at 360 x 800 dp, density 2 and font scale 1.2.

Initial visual findings are resolved: both signup heroes now retain the approved person/minibus composition; adaptive native sizing allows the normal portrait forms and actions to fit. Passenger signup badge is centered. Display headings match the intended emphasis. The wider driver language area keeps all three language captions on one line at standard size.

Required fidelity surfaces:
- Typography: shared Outfit family, strong display weight, readable editable input text and 38 sp display headings where the source calls for larger type. Platform Amharic fallback and enlarged Oromo wrapping were inspected.
- Spacing/layout: source hierarchy and photo proportions restored; signup gaps tightened, native fields remain at least 52 dp and primary buttons at least 50 dp. Normal portrait CTA/footer content is visible. Small enlarged-text layouts scroll to secondary content without hiding the primary action.
- Colors/tokens: cream #FFFBF4, navy #06263C, teal #00695F, mint #E4F3E9 and muted field borders consistently match the approved family.
- Images/icons: exact approved photographic crops and the road wordmark, with clean fades into cream. Native Material icons, functioning password visibility controls and editable text replace all rasterized interface elements.
- Copy/content: approved role headings, motto and account wording retained. Phone verification precedes route/driver details. Driver approval is clearly stated. No fabricated credentials, social sign-in or password-reset service was introduced.

Meaningful checks exercised both login roles, repeated identical login failures, password confirmation before requesting SMS, OTP verification, changed-phone re-verification, pending driver submission with licence/vehicle data, passenger active-route submission and custom-route registration metadata. Existing welcome, commuter, driver, intercity and boarding QR checks also passed. UI tests use isolated APIs and an in-memory database; no live SMS or payment was sent.

Independent visual review of all four paired captures and both small Oromo captures found no remaining P0/P1/P2. Remaining P3/native differences: flat button fills, a clean cream branding backdrop, minor optical spacing differences and naturally wrapped text at larger settings. Sign-in source framing is shorter than the native phone viewport. Physical-device installation and actual SMS delivery were not tested in this UI iteration. Existing backend endpoints require no VPS service change.

Implementation checklist: approved screens implemented as native components; original API/session/approval flows connected; visual corrections recaptured; all checks passed; no blocking findings.

final result: passed
