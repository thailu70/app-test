# Cargo option 3: design QA

## Scope and visual truth

Selected visual reference: `C:/Users/abc/.codex/generated_images/01a14bb0-8c94-7111-8ea5-bb6697be3f36/exec-06d44eff-0354-42f7-b793-f9688d381918.png` (1448 × 1086 pixels), option 3, Compact sections. Existing Passo type, palette, reusable native controls, API bindings and private guest request capabilities remain the implementation foundation.

Verified implementation commit: `9aa65290ddca7ab5ce62fc5c9945d03268ee0836`, candidate run [41222377316](https://github.com/thailu70/app-test/actions/runs/41222377316). The native candidate compiled, assembled and validated successfully.

## Captured evidence

All actual images below are rendered native Compose output, rather than a mockup presented as a working screen.

- Source and actual form together: `../play-release/1.1.27/cargo-form-comparison-9aa6529.png`.
- Source and actual picker together: `../play-release/1.1.27/location-picker-comparison-9aa6529.png`.
- Source and actual driver content together: `../play-release/1.1.27/cargo-driver-comparison-9aa6529.png`.
- Actual filled cargo form: `../play-release/1.1.27/validation-9aa6529/app-test/app-test/app/build/mobility-previews/05-cargo-compact-request.png`.
- Actual review dialog: `../play-release/1.1.27/validation-9aa6529/app-test/app-test/app/build/mobility-previews/06-cargo-review.png`.
- Place picker portrait and landscape: same mobility-previews directory, `05-location-picker-portrait.png` and `06-location-picker-landscape.png`.
- Driver Current and Requests: `../play-release/1.1.27/validation-9aa6529/app-test/app-test/app/build/driver-previews/cargo-compact-current.png` and `cargo-compact-requests.png`.

The form reference was cropped to `(44,190)-(486,1070)` and normalized to 393 pixels wide. The implementation is a 393 × 852 dp native component rendered at density 2 (786 × 1704 pixels), downsampled to 393 pixels wide for the combined comparison. Shared app headers were excluded from both form crops. This is a component-viewport check, not a claim that every expanded field and the app header fit without scrolling. The location picker separately includes its own full native header.

Picker normalization uses source `(508,106)-(943,1073)` including its header; driver normalization uses `(965,190)-(1407,990)` excluding the shared header and app-owned navigation. These comparisons were opened with both source and implementation visible in the same image. The real Passo wordmark replaces the mockup's plain-text logo. The driver fixture has no cargo photo, so it correctly shows the existing cargo icon; delivery details still contain the photo when available. Existing icons and flat token fills are retained native equivalents. Fixture names, dates, and weights are test inputs rather than product copy.

## Findings and corrections

1. **Resolved P2, form density:** inherited small-text line heights and separate field labels made the initial route card too tall, placing Review request below the initial viewport. Floating outline labels, compact date/time fields, explicit line heights and a combined road-distance/timezone hint corrected this. The new initial-view assertion and actual screenshot both show Review request without scrolling at the verified component size.
2. **Resolved P2, missing dialog evidence:** the previous capture read the Activity behind the dialog. The helper now selects the active dialog window. The actual modal, including Edit details and Send request, is captured.
3. **Resolved P2, modal surface token:** the true dialog capture exposed the default Material lavender surface, which differed from the cream Passo palette. The final correction explicitly sets CommuteCream, zero tonal elevation, navy content and a bold title. The new actual modal screenshot was opened and inspected; it matches the Passo palette with readable details and both actions visible.

## Five fidelity surfaces

| Surface | Result |
| --- | --- |
| Typography | Existing Outfit type, navy headings, teal controls and clear section hierarchy match the selected direction. Longer real labels and system dates remain readable. |
| Spacing/layout | Three numbered rounded accordion cards, outlined route inputs, two-column date/time, actual thumbnail, consent, readiness and primary review action follow the source hierarchy. No overlap or clipping in the captured states. |
| Color/tokens | Form, picker, driver and final review modal use existing cream/white/mint/navy/teal. No default lavender modal surface remains. |
| Images | Production thumbnails decode user-selected bytes; the cement image is an isolated test resource. Offline map preview disables network tiles, so its grid proves bounds/pin layout, not online tile appearance. |
| Copy/content | Route & pickup, Cargo & vehicle, Contact details and Review request follow the source. Real road km/Ethiopia time and operational wallet/status explanations are retained. Customer-side price estimates remain absent. |

## Functional verification

Validation artifact `13049964648`, SHA-256 `f13e5ea5d03e8ef36b25ea347466d47663586d163bd6f2c9b6e0c0f65cbae49d`, was downloaded and verified before extraction. JUnit XML reports **149 tests, 0 failures, 0 errors, 0 skipped**.

Focused tests cover:

- Accordion state retention, required pickup/destination, explicit age/contact consent, actual photo preview, and review confirmation before submit.
- Location picker selected name/coordinates and portrait/landscape map bounds.
- Driver single current-job hierarchy, compact functional sections and preserved scheduled group journey proposal.
- Saved guest capability notification navigation without an account; rejection of unknown request, invalid scope, custom links or secrets.
- Production/demo isolation, private messaging service, durable logout revocation and conservative notification registration.
- Contextual location prompts, settings return behavior and suppression in demo.

All three final workflows passed: candidate 41222377316, general CI 41222377317, and Android test APK 41222377335. The final form/picker/driver images are byte-identical to the previous inspected render; only the review modal palette/title changed and was freshly inspected. No actionable P0/P1/P2 visual findings remain in the verified states.

Remaining evidence limits: online OpenStreetMap tile appearance requires a connected device; enlarged fonts and every field combination were not visually captured. Functional long-form scrolling is intentionally preserved. These are coverage limits rather than defects observed in the verified screenshots.

Real-device FCM delivery is not asserted by screenshot or native unit-test evidence. No Google Play upload is included in this release.

final result: passed

