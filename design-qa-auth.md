# Native authentication verification

Scope: approved passenger sign-in/sign-up and driver sign-in/sign-up mockups, with working native controls and the existing authentication/registration APIs.

Visual truth: sibling workspace files `../mockups/auth/passenger-sign-in-v2.png`, `passenger-sign-up.png`, `driver-sign-in.png`, `driver-sign-up.png`. Sign-in source images are 935 x 1683 pixels; signup sources are 853 x 1844. Native evidence is 786 x 1704 pixels at 393 x 852 dp, density 2. Full comparison panels normalize both sources and native output to 393 pixels wide without stretching; sign-in source height therefore differs from the phone viewport. This framing difference is recorded rather than treated as pixel identity.

Initial build d5fc1af needed a Material icon compatibility correction (92b4dd4). The next build compiled successfully and passed 48/49 checks. Its single failure concerned asynchronous Room fixture scheduling in the pending-driver success test; deterministic test executors and explicit route selection are being verified. No production payment, boarding or SMS delivery behavior changed.

Initial rendered comparisons: `../auth-1.1.12/compare-01-passenger-login.png`, `compare-02-passenger-sign-up.png`, `compare-03-driver-login.png`, `compare-04-driver-sign-up.png`.

## Initial visual findings

- P2: signup photography is about 80 dp too short, cropping the approved scene and moving the role/title too high. Increase the adaptive header and remove the internal height cap. Tighten form gaps and footer layout so the primary action still fits.
- P2: passenger login and driver signup headings are smaller than the approved visual hierarchy. Adjust the display size while retaining the shared Outfit font.
- P2: the driver header's Afaan Oromoo caption wraps unnecessarily. Give language controls more of the header row.

The role-specific photos are clean crops from the approved generated mockups. Branding, language buttons, labels, forms, verification steps and actions are real Compose controls; no full-screen screenshot or invisible tap zones are used. Empty passwords remain empty, rather than pretending credentials have been entered.

Account fields use in-memory state only. Signup sends a registration SMS, verifies its six-digit code, then collects the route or driver details. Changing the phone invalidates verification. Driver registration remains pending approval; passenger registration keeps active route selection or the existing structured custom-route request. A small ViewModel validation correction permits complete custom-route requests already accepted by the backend. Password help remains administrator assistance.

Pending: corrected native capture, full source/render comparison and passing final test run.

final result: blocked
