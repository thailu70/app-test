# Daily commuter UI verification

Scope: daily commute route discovery and the passenger commuter dashboard, continuing the approved PASSO welcome design.

Reference: ChatGPT Image Oct 19, 2026, 07_16_56 AM.png. Compared with native 393 x 852 dp component screenshots from GitHub Android run 39320604054, commit 7c7cef6. The component preview uses explicit test assignment data and a placeholder portrait slot; production loads the authenticated driver's actual portrait.

Changes: cream/navy/teal surfaces, shared Outfit typography, real native brand/language header, skyline illustration, combined schedule/driver/vehicle card, mint GPS panel, navy live-token boarding card, refreshed navigation, compact route cards/search/filter controls and route request panel. All selectors and callbacks remain native controls.

Data constraints intentionally reflected in the UI:
- No ETA or pickup-distance value is returned by the tracking API, so the design does not invent the reference's sample 5-minute ETA.
- The vehicle API has no vehicle-photo field, so actual model/color/type/plate information is displayed rather than assigning the reference's sample vehicle photograph to a real passenger.
- Pickup/return stop labels use stored route-stop IDs. Evening return is explicitly identified rather than presented as morning arrival.
- The server's active/paid/remaining-days checks still gate QR display. The exact server-issued token is used.

Visual iterations:
1. First native screenshot found an oversized trip panel and language section. Tightened the driver block, combined header/languages, and reduced footer spacing.
2. Corrected the QR test finder to inspect the unmerged semantics tree under its clickable parent. The rendered QR decoded successfully.
3. Final screenshot found compact Oromo text wrapping. Changed compact label to Oromoo, retaining full Afaan Oromoo accessibility description, and confirmed one-line rendering.

Fidelity surfaces checked: Outfit weights and fallback fonts; cream/navy/teal/mint palette; 22-24 dp card radii; route/driver spacing; actual portrait rendering hook; skyline asset; icon consistency; real text/selection states; scroll access to QR/payment actions. The source illustration is intentionally recreated, not pixel-identical.

Validation: Android build passed; seven welcome/commuter/QR/pass-presentation tests passed; backend CI run 39320604080 passed. Native screenshots include the home summary, scrolled boarding panel, and QR capture. Actual live GPS updates, telephone dialer launch and physical-device installation were not exercised in this environment.

Remaining P3 follow-ups: broader Oromo translation for legacy payment/help screens; production-data visual checks on a physical phone; optional vehicle-photo support if added to the API. Existing request, authentication, activation, payment, profile, history, support and tracking flows are retained.

final result: passed
