# Compact cargo request: visual verification

- Source visual truth: `C:/Users/abc/.codex/generated_images/01a14bb0-8c94-7111-8ea5-bb6697be3f36/exec-06d44eff-0354-42f7-b793-f9688d381918.png`, selected option 3, left cargo form.
- Rendered implementation: `../play-release/1.1.27/validation-5183d86/app-test/app-test/app/build/mobility-previews/05-cargo-compact-request.png`.
- Combined comparison: `../play-release/1.1.27/cargo-form-comparison-5183d86.png`.
- State: filled route and pickup section expanded, cargo/contact sections collapsed, consent checked, actual selected photo represented by an isolated test fixture extracted from the source. Fixture names/contact details differ intentionally from the mockup and are never production data.
- Native viewport: 393 x 852 dp, density 2, implementation PNG 786 x 1704 pixels.
- Normalization: source form content cropped from the 1448 x 1086 image at `(44,190)-(486,1070)` and scaled to 393 pixels wide. Native form content downsampled to 393 pixels wide. Header excluded from both form comparisons; the form component itself owns no app header.

## Iteration 1

- [P2] Expanded route card has excessive vertical rhythm. Separate place labels and inherited 24 sp line heights for small text made the card substantially taller than the selected source. The Review request button fell below the initial viewport.
  - Fix applied: floating labels within outlined fields, labelled date/time controls, one combined road-distance/timezone line, explicit small-text line heights, and tighter section spacing. No fields, consent, photo upload or request submission logic removed.
  - Verification added: the filled form must display the Review request button before any scrolling at 393 x 852 dp.
- [P2] Review screenshot captured the Activity behind the modal rather than the actual review dialog.
  - Fix applied: screenshot helper selects the active dialog decor window when a dialog is showing. Functional tests already verified that review precedes actual submission.

## Required fidelity surfaces

- Fonts/typography: established Outfit font and navy/teal hierarchy preserved. Small text inherited excessive line height; corrected in the pending iteration.
- Spacing/layout rhythm: three numbered rounded sections, route expansion and collapsed summaries match the selected hierarchy. Pending capture must confirm the density correction.
- Colors/tokens: existing cream, white, navy and teal tokens match the reference direction. No replacement palette introduced.
- Image quality: production thumbnail decodes the user's selected image bytes. The source cement thumbnail is used only as an isolated test fixture. No mockup screenshot is displayed as the app UI.
- Copy/content: section names and primary Review request action follow the source. Explicit Ethiopia time and computed road distance are preserved functional details; customer price estimates remain absent.

## Functional evidence

Commit `5183d86935f73fda063ef77d33d9af5328748612`, candidate run `41195957885`: 149 tests, zero failures/errors/skips. These include accordion state retention, photo presence, required location, explicit age/contact consent, review before submit, guest request recovery, typed notification navigation and notification environment isolation. A new capture is required after the density correction.

## Remaining gate

Capture the revised form and actual review modal, compare against the source in one combined image, and resolve any remaining P0/P1/P2 differences. The offline map test deliberately disables tile downloads; it verifies map bounds and actual selected coordinates rather than online tile appearance.

final result: blocked
