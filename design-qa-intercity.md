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

Pending: recapture and compare the corrected layout.

final result: blocked
