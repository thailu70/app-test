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
