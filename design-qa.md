# Taxi and shared ride design QA — 2026-11-15

## Result

The reviewed native screens have no remaining actionable P0/P1/P2 visual findings. The home-map spacing P1 and active-meter hierarchy P2 are resolved in post-fix captures. This is a native UI/layout pass within the deterministic test scope; it does not certify physical-device GPS, online routing or real phone notification delivery.

## Source and implementation evidence

- Selected visual sources: C:/Users/abc/.codex/generated_images/01a14bb0-8c94-7111-8ea5-bb6697be3f36/.
- Home: exec-74189b4b-771d-4dff-a5f3-7f274caf5ada.png.
- Requesting: exec-bccc53d5-9eb9-4fe4-9eaa-4e5759a32c16.png.
- Accepted passenger: exec-8c5a0215-b49d-4a11-ba1c-15e7349df1fb.png.
- Driver calls: exec-e3f65fc8-fbb5-4f0f-b012-48c8b7b7d18a.png.
- Driver meter: exec-1a798cff-3a94-454e-8dd3-45c98b73de58.png.
- Passenger Group Ride: exec-34483ca1-fb24-480a-aded-b604da5fa7d9.png.
- Latest implementation: GitHub run 43735694830, app commit 9e09f7e930f1092a241664937b6022cb48c34fb6.
- Latest validation artifact: 14170701243; 18,239,106 bytes; verified SHA-256 d6cb45f6cde8a3901a96eb9e60f187ca8afdc5f1d195de7ba421cdd97ecb284b.
- Post-fix captures and XML: ../play-release/1.1.32/meter-postfix/.
- Post-fix combined source/native evidence: ../play-release/1.1.32/meter-postfix/meter-postfix-comparison.png.
- Focused large-text evidence: ../play-release/1.1.32/meter-postfix/driver-meter-320-large-text.png.
- Other combined full-view evidence: ../play-release/1.1.32/final-validation/home-comparison.png, requesting-comparison.png, accepted-comparison.png, driver-calls-comparison.png and shared-comparison.png.

## Viewports and matching

Native captures are 786 × 1704 pixels for 393 × 852 dp at xhdpi (2×), and 640 × 1480 pixels for 320 × 740 dp at xhdpi. The additional meter capture uses the 320 × 740 dp viewport with font scale 1.2. The source home image is 852 × 1846 pixels and the other generated sources use the same approximately 390 × 844 logical portrait direction.

In combined boards, the source and 393 dp capture are normalized to 390 × 844 for visual comparison. The narrow capture remains 320 × 740 logical pixels for responsive inspection. Original files are unchanged. Tiny source/native aspect differences are not filed as layout defects.

The landing test selects Comfort while its source selects Economy; that highlight difference is intentional. Passenger status fixtures render the actual current-ride component beneath the brand header and omit its outer passenger navigation; that fixture boundary is not evidence of a production navigation defect. The group capture is selected-trip seat booking while its source shows find-seat/search, so palette, fare clarity, route hierarchy and controls were compared without pretending those are identical workflow states.

## Comparison history

1. **Iteration 1 — P1 found:** run 43726686706, commit 8b131a5c72f9b88b4444807f512f840e4b1ebec7. The native home used a roughly 300 dp map and left roughly 140 dp unused ivory space above bottom navigation. Evidence: ../play-release/1.1.32/validation/home-comparison.png and home-controls-comparison.png. The worker was asked to give the map the measured remaining height and anchor the destination/vehicle/Group Ride sheet above navigation.
2. **Iteration 2 — home P1 resolved, meter P2 found:** run 43732141139, commit bfddf41cca6c8bc08de836d66159abad01e0ad89. The combined final-validation/home-comparison.png visibly confirms the home gap is gone. The expanded state set revealed that driver meter fare/actions were below the first viewport, particularly at 320 dp. Evidence: final-validation/driver-meter-comparison.png and original driver-meter-320.png. End trip worked after scrolling, but its initial hierarchy differed materially from the approved meter.
3. **Iteration 3 — both findings resolved:** run 43735694830. The active-meter map is compact, destination/contact information is condensed, and Passenger stop plus End trip are anchored above bottom navigation. The combined meter-postfix-comparison.png shows the source with initial native 393 and 320 frames. The separate 320 dp / font-scale-1.2 capture shows ETB 187.50 fare, 3.20 km distance, 00:00 billable waiting, Passenger stop and End trip all visible without scrolling. No overlap or hidden primary control remains in these captures.

## Required fidelity surfaces

- **Fonts and typography:** rounded product typography, bold fares and route/action hierarchy match the selected direction. Long routes wrap cleanly at 320 dp. The enlarged-text meter retains readable key values and actions.
- **Spacing and layout rhythm:** home fills its available height without the former gap. The active meter now keeps essential information and controls in the initial viewport. Other narrow list/detail flows scroll in an orderly way. The added Group Ride entry is authorized content and accounts for some home-sheet height difference from the earlier home-only source.
- **Colors and tokens:** cream, navy, mint and teal remain consistent across captures. Selected, disabled and occupied-seat states remain distinguishable. Slightly darker product-token highlights are accepted rather than treated as new branding.
- **Image quality and assets:** the real Passo wordmark, generated isolated 3D vehicle assets and sourced top-view car icons are present and sharp. No whole-screen mockup raster substitutes for interactive controls. The accepted-driver fixture has no profile photo, so a real driver's portrait is outside this visual evidence.
- **Copy and content:** destination-only entry, Economy/Comfort/Mini-Van, Daily Commute, connecting status, direct payment and per-seat Group Ride wording reflect the agreed behavior. Test names, fares, PINs and positions are synthetic fixtures, not customer records.

## Functional evidence inspected

The latest extracted XML reports AuthLayoutTest 17, CredentialStorageTest 3, RidePushTest 4, TaxiFlowTest 6, TaxiLayoutTest 1 and TaxiStatesLayoutTest 11: **42 tests, zero failures and zero errors**.

Native interaction coverage includes tier selection, the Daily Commute callback, absence of a current-location input, requesting and accepted states, enabled driver acceptance, occupied-seat rejection, selecting a seat with the correct total, and enabled booking at both widths. The revised meter tests assert fare, distance, waiting and End trip visibility without scrolling at 393 dp, 320 dp and enlarged text. Actual PNG inspection confirms those assertions rather than relying on build success alone.

## Verification limits and follow-up polish

- Online OSM tiles are deliberately disabled in deterministic tests (mapTiles=false), so gray grid backgrounds are expected. Route overlays and sourced car icons use synthetic fixtures. These captures do not prove live road routing, online tile availability, GPS/geofence behavior, background location on a physical phone or actual FCM phone delivery.
- This is native Compose/Robolectric evidence; browser-console checks do not apply.
- Street-pickup and driver shared-offer form appearance are not present in this capture set. Their complete visual appearance has not been verified here.
- P3: the narrow header shortens Daily Commute to Daily, navigation labels are smaller than the generated reference, and singular seat(s) copy can be polished. These do not hide a primary action or change pricing/booking meaning.

## Implementation checklist

- [x] Open source and native evidence together.
- [x] Resolve the home spacing P1 and inspect a post-fix capture.
- [x] Resolve the active-meter P2 and inspect initial 393/320/enlarged-text captures.
- [x] Check typography, layout, color, assets and copy.
- [x] Verify native test results and preserve their fixture/device boundaries.
- [ ] Perform physical-device GPS/routing/FCM acceptance separately before claiming those live behaviors are verified.

final result: passed
