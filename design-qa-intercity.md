# Native intercity verification

Scope: replace the passenger `/travel/` WebView with native Android search, seat selection, booking, Telebirr handoff, paid tickets and PDF saving. Company management remains separate.

Source visual truth: `C:/Users/abc/Desktop/ChatGPT Image Oct 19, 2026, 07_16_15 AM.png`, also retained as `app/src/main/res/drawable-nodpi/passo_intercity_reference.png` (853 x 1844 pixels).

Initial implementation: commit b18ce42, Android run 39342469277. Native render evidence is in `../intercity-1.1.11/intercity-previews/`; comparisons `../intercity-1.1.11/compare-01-home.png`, `compare-01b-route-cards.png`, `compare-01c-recent-ticket-community.png`. Source and implementation are placed in the same comparison images, normalized to 393 pixels wide. Native capture is 786 x 1704 at 393 x 852 dp, density 2. Narrow large-text capture is 720 x 1600 at 360 x 800 dp and font scale 1.3. Reference height scales to 850 pixels; screenshots represent different scroll positions and real/test-data states rather than one static full-page image.

## Initial findings

- P2: Search panel overlap is reduced by list spacing and its visual offset leaves excess space below search. Fix: place hero and search in one item, report the overlapping panel's actual occupied height.
- P2: Recent-ticket card is disproportionately tall because the compact layout stacks ticket labels and its action below the main row. Fix: use a compact photo/details/QR row with native ticket controls.
- P2: Community captions truncate at larger text sizes. Fix: place library icons above flexible captions as in the reference.

Full-view and focused review covered hero/wordmark, search fields, photo card, paid ticket and footer. Fonts use the shared Outfit family and platform Amharic fallback. Cream/navy/teal/mint tokens and rounded surfaces retain the supplied design family. A clean generated scenic hero replaces the source hero's baked-in UI; photo crops and the supplied wordmark are decorative assets. Every search field, label, button, tab and booking credential is native.

Expected product differences: routes and prices come from the existing API, never mock popularity/distances/times. Cards scroll horizontally at readable phone sizes. Recent tickets display actual server-issued QR and short ticket numbers. Unsupported parcel/group tabs say Not available yet. The headline uses the approved inclusive pan-African wording. Telebirr opens its approved secure provider checkout and the app verifies payment again on return.

## Functional verification

Android build and CI succeeded on b18ce42. Native tests exercised editable city suggestions without losing focus, search, reserved-seat blocking, seat hold, passenger details, server-confirmed paid ticket, rendered QR decoding, Back, empty routes, language switching and 1.3 text scale. State tests cover private booking access, authoritative payment refresh, checkout URL validation, private PDF streaming, late responses after Back and parallel saved-ticket recovery. Tests use isolated fixtures; no live SMS or payment was sent. The live public departures API returned success with zero upcoming departures. Physical-device installation and live Telebirr settlement were not exercised.

## Corrected layout verification

Final app source: 899ed22, APK 1.1.11/code 14. Android run 39343835824 and CI 39343835822 succeeded. All 39 checks passed with zero failures/skips. Installable APK artifact: 12229993658 (47,155,825-byte ZIP); verification artifact: 12229888922.

Post-fix screenshots: `../intercity-1.1.11-final/intercity-previews/01-home.png`, `01b-route-cards.png`, `01c-recent-ticket-community.png`, `02-seats.png`, `03-ticket.png`, `04-no-routes.png`, `05-narrow-large-text.png`, `06-narrow-recent-ticket.png`. Full comparisons `../intercity-1.1.11-final/compare-01-home.png`, `compare-01b-route-cards.png` and `compare-01c-recent-ticket-community.png` place reference and native render together at 393 pixels wide. Focused checks used the readable hero/search and receipt/footer regions in these same comparison images; the 720-pixel narrow captures were also inspected at full resolution.

The three initial P2 findings are resolved: hero/search overlap now occupies its actual layout height, the compact receipt is approximately 140 dp tall at standard text size with a small source photo and actual credentials, and footer icons sit above captions that wrap without truncating. Independent visual review confirmed no remaining P0/P1/P2 issue.

Required surfaces reviewed after correction:
- Typography: shared Outfit weights, clear hierarchy, native input text and labels, Amharic platform fallback. Larger translated text expands instead of clipping.
- Spacing/layout: consistent phone margins, actual 20 dp hero/card overlap, compact receipt, flexible scrolling and usable controls. Data cards intentionally remain larger than the reference's three miniature columns.
- Colors/tokens: cream, teal, navy and mint retained; confirmed/reserved/selected states are distinct and legible.
- Image fidelity: scenic coach hero, supplied wordmark, clean route/receipt photos and traveler crop inspected. Interactive UI is not baked into images. Library icons and QR rendering remain native.
- Copy/content: pan-African motto, actual routes/fare/seat/ticket data, honest empty state, and explicit unavailable service tabs. No fabricated distance, ETA, ticket or popularity.

Post-fix native tests also open saved tickets through See all and View ticket and verify that the same server ticket number returns. Remaining P3 polish: the narrow 1.3-scale header motto wraps, the compact Oromo caption is tight, and the flat native fills differ slightly from the source's gradients. Physical-device installation, Android Keystore recovery on real hardware, live Telebirr settlement and real SMS delivery remain untested in this UI iteration. No backend or VPS service change was needed for the native client.

Implementation checklist: all P2 corrections complete; final build/test/visual evidence inspected; no blocking findings.

final result: passed
