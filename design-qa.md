# Taxi and shared ride design QA — 2026-11-15

## Current result — iteration 2

The original home-map P1 is **resolved**. The revised landing map fills the available height and the destination/vehicle/Group Ride sheet is anchored above navigation, eliminating the large unused ivory gap.

**One P2 remains:** `DriverCurrentRide` IN_PROGRESS shows its fare partially below navigation at 320 × 740 dp, while waiting/stop/End trip controls require scrolling at both tested widths. The approved reference keeps those core meter values and controls together. The actual action capture proves End trip is reachable, so this is a hierarchy/layout defect rather than a broken action. Compact the active map to approximately 140–160 dp, replace the full pickup/destination stack with a compact destination/contact row, and keep the primary controls above navigation. Root and Android worker were notified; a revised capture must be inspected before passing.

Final candidate evidence: run **43732141139**, commit `bfddf41cca6c8bc08de836d66159abad01e0ad89`. Artifact **14168299311**, 17,939,056 bytes, verified SHA-256 `f5a07a0fe8cf837401f09f5f21739f8c2a8613202e01a58c68972e4fe913cf2d`. Captures/XML are in `../play-release/1.1.32/final-validation/`.

Combined source/native comparisons were opened together: `home-comparison.png`, `requesting-comparison.png`, `accepted-comparison.png`, `driver-calls-comparison.png`, `driver-meter-comparison.png`, and `shared-comparison.png` in that directory. The approved references are the generated home, requesting, accepted, driver-call, driver-meter and passenger-group images listed in the conversation. Native frames are 786 × 1704 pixels (393 × 852 dp, 2×) or 640 × 1480 pixels (320 × 740 dp, 2×). The reference and larger native frame are normalized to 390 × 844 for comparison; narrow frames remain 320 × 740 for responsive inspection. Original captures remain unchanged.

Required fidelity surfaces, iteration 2:

- **Typography:** rounded product fonts and bold route/action hierarchy remain coherent; long routes wrap without overlap. Navigation labels are smaller than the reference (P3 polish). Active meter hierarchy is the P2 above.
- **Spacing/layout:** home gap fixed. Other captured flows scroll orderly at narrow width; the active meter needs compact persistent controls.
- **Colors/tokens:** cream, navy, mint and teal remain consistent; occupied/disabled seats are distinguishable.
- **Images/assets:** actual wordmark, real generated 3D tier images and sourced top-view car icons are present. No whole-screen mockup raster replaces controls.
- **Copy/content:** destination-only entry, tiers, Daily Commute, direct payment and per-seat group wording are coherent. Fixture fares, names and PINs are synthetic. Singular `1 seat(s)` is P3 copy polish.

State boundaries: the passenger status fixtures isolate the current-ride component beneath a brand header and omit the outer passenger navigation; this is not evidence of a production navigation regression. The group fixture is selected-trip seat booking, while its source shows find-seat/search, so those are different workflow states. The accepted fixture has no driver photo, which prevents portrait fidelity verification.

Online OSM tile downloads are deliberately disabled (`mapTiles=false`), so gray map grids are an expected deterministic-test state. Native route overlays and sourced cars are visible over synthetic route geometry. These captures do **not** establish real GPS, live road routing, online tile availability, physical-device background location or real FCM delivery. This is a disclosed verification limit, not a claim that the production map is broken. Browser-console checks do not apply to this native Compose/Robolectric run.

All six extracted XML suites passed: AuthLayout 17, CredentialStorage 3, RidePush 4, TaxiFlow 6, TaxiLayout 1 and TaxiStatesLayout 10 — **41 tests, zero failures/errors**. The 393/320 captures show enabled driver accept, End trip after scroll, occupied-seat rejection, seat selection, ETB 80 total and enabled Book 1 seat. Street-pickup and driver shared-offer form appearance is not included in this capture set.

### Next check

Inspect revised driver-meter captures at 393 × 852 and 320 × 740 dp against the approved meter reference. Do not pass from code or compilation alone.

## Historical iteration 1 report

## Evidence and normalization

- Source visual truth: `C:/Users/abc/.codex/generated_images/01a14bb0-8c94-7111-8ea5-bb6697be3f36/exec-74189b4b-771d-4dff-a5f3-7f274caf5ada.png`.
- Implementation: `../play-release/1.1.32/validation/01-passenger-home.png`, from successful GitHub run 43726686706, commit `8b131a5c72f9b88b4444807f512f840e4b1ebec7`.
- Artifact 14167241073 was downloaded using a refreshed capability and verified against SHA-256 `d511d3b9b3dabdc2d6eee6e048c7623840230cbb02e9e2e7b9382e77987b8901`.
- Source pixels: 852 × 1846, representing the selected approximately 390 × 844 logical design. Native capture: 786 × 1704, generated by Robolectric at 393 × 852 dp, xhdpi (2×). Both were resampled to 390 × 844 for comparison only; original files are unchanged. The sub-1% aspect difference does not explain the finding below.
- Full-view comparison: `../play-release/1.1.32/validation/home-comparison.png`.
- Focused control comparison: `../play-release/1.1.32/validation/home-controls-comparison.png`.
- State: passenger destination-first landing screen, no pickup or destination selected. The test selects Comfort before capture; selection-highlight differences from the Economy-selected source are therefore intentional.

## Findings

- **[P1] Map-to-sheet proportions and unused vertical space differ materially.**
  - Source: the map is the dominant region, approximately 510 logical pixels tall; the destination and vehicle sheet sits directly above persistent bottom navigation.
  - Implementation: map height is approximately 300 logical pixels. The content leaves approximately 140 logical pixels of empty ivory space between the Group Ride entry and bottom navigation.
  - Impact: the app looks sparse and disconnected rather than matching the chosen map-first composition. Above-the-fold proportions materially differ.
  - Fix: use the available screen height for the map, anchor the destination/vehicle/Group Ride sheet immediately above navigation, and retain the Group Ride addition within that sheet. Do not stretch a mockup image or invent map imagery.
  - Owner notified: `/root/taxi_android` and `/root`.

- **[P2 verification gap] Online map presentation is not evidenced by this native capture.**
  - The native test deliberately uses `mapTiles=false`; OSM consequently appears as a gray grid. This is a known capture-state limitation, not proof of a production map failure.
  - The static visual comparison cannot establish online road-tile appearance or road-route drawing. Obtain a device/emulator capture with actual permitted map data, or explicitly report that device/network verification remains outstanding. Do not replace the native map with the generated reference raster.

## Required fidelity surfaces

- **Fonts and typography:** rounded brand typography, bold destination heading and readable vehicle labels are coherent with the selected direction. Navigation labels are smaller than the reference; consider a modest increase as follow-up polish after layout correction. No clipping is visible in this 393 × 852 state.
- **Spacing and layout rhythm:** blocked by the P1 above. Vehicle tile spacing and destination padding are orderly. The added Group Ride link is authorized and is not itself design drift.
- **Colors and tokens:** cream, navy, mint and teal match the established Passo palette. The selected border is darker than the generated reference but remains consistent with the actual product tokens.
- **Image quality and assets:** the Passo wordmark is a real supplied image; all three tiers use real generated 3D vehicle assets. They are sharp and correctly contained. The implementation does not substitute the whole mockup for interactive controls. Online map imagery remains a verification gap.
- **Copy and content:** “Where to?”, Economy, Comfort, Mini-Van and Daily Commute match the agreed choices. “Group Ride · pay per seat” correctly expresses the latest shared-seat requirement. The landing screen has no visible current-location field as requested.

## Comparison history

1. **Iteration 1 — blocked:** source and native capture were opened together in the full-view composite and focused control composite. Found P1 region proportions/unused space and the map capture limitation. No post-fix capture has been reviewed yet.

## Test evidence and open states

- The successful TaxiLayoutTest checks destination entry, all three service tiers, Comfort selection, absence of a “Current location” field and the Daily Commute callback.
- This is native Compose/Robolectric evidence, not a browser prototype; browser-console checks do not apply to this capture.
- Passenger requesting/accepted/meter, driver call pool/street pickup/meter, shared booking and driver-offer frames are not included in the first artifact. They require actual native captures before a complete visual pass.
- The 320 dp responsive state and increased font scale are not evidenced by this artifact.

## Implementation checklist

1. Correct map weight and sheet anchoring without changing working navigation.
2. Capture revised native passenger landing at the same viewport and compare against the same source.
3. Inspect the remaining native flow states and a small-width capture.
4. Verify real map tiles/route rendering on an actual network-enabled device, or disclose the remaining verification limit.
5. Change this report to passed only after post-fix visual evidence has no actionable P0/P1/P2 differences.

final result: blocked
