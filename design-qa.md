# Passo Mobility website design QA

Source visual truth: second displayed website concept, `exec-4ece64e5-c452-4661-aec4-a4ddc979d30c.png` in the owner generated_images folder.
Implementation: actual backend homepage at http://127.0.0.1:18561/.
Initial implementation screenshot: ../passo-home-desktop-900.png.
Comparison: source 900 x 1748; implementation 900 x 1841; CSS viewport 900 x 1000, browser screenshot density 1. Both images opened together in the same comparison input; no browser/device frames or density rescaling.
State: public homepage, English, menu closed.

## Initial findings

- [P2] Driver recruitment photograph has a hard vertical boundary at 23% width and text crosses the bright portrait area. Source has a continuous dark teal band with a standing driver centered. Generate the matching band photograph and cover the full region, with dark negative space around the subject.
- [P2] Driver headline wraps after “New” instead of after “vehicle.”. Preserve its two-line hierarchy using separate translated lines.
- [P2] About photograph crop removes the top of the commuter head. Adjust crop position while keeping the existing region height.
- [P2] Service body text is smaller and denser than the source; use at least 14px at the 900px comparison width. Compact the intentionally added test-APK download note so it does not dominate this section.

## Fidelity surfaces

Typography: Outfit matches the reference direction; headline hierarchy and baseline reviewed. Driver wrapping and service body density need the changes above. Ethiopic fallback requires phone verification after translation.
Spacing: header/hero end at 72/527px, essentially matching source 71/526px. Main section proportions need the download note adjustment; lower region comparison will be repeated.
Colors: ivory, navy and teal surfaces, white hero text, outline CTA and service tints match the target direction. Driver contrast is blocked until background correction.
Images: real generated photographs are separate local assets; source logo reused; official MIT Tabler icon assets. No whole UI screenshot is used as a page. Driver image and story crop remain actionable.
Copy: website routes are honest; daily commute uses Android app, intercity uses existing /travel/. Only daily commute driver registration is advertised. OTP/documents occur after initial administrator acceptance (intentional correction to mock copy).

## Verification still required

- Recapture 900px desktop after fixes; compare in the same input with source.
- Verify 1440px desktop and 390px phone, language changes, mobile menu, no overflow, portal navigation and console errors.
- Update APK version/link only after successful native build and publication.

## Completed comparison and interaction pass

All initial P2 findings were corrected and captured again. Final English comparison: ../passo-home-desktop-900-final.png. Combined side-by-side comparison: ../passo-website-design-comparison.png, opened for direct visual judgment. Source is900x1748; implementation is884x1808 because the browser excludes its scrollbar from the900x1000 CSS viewport at density1. Source was proportionally normalized to884px content width before comparison. Nothing was stretched.

Additional browser evidence: ../passo-home-desktop-1440.png (1440x1024 CSS,1424x2704 content); ../passo-home-mobile-final.png (390x844 CSS,375x3156 content), ../passo-home-mobile-amharic.png (375x3043) and ../passo-home-mobile-oromo.png (375x3253). Linked flows: ../passo-daily-driver-registration-mobile.png, ../passo-driver-signin-mobile.png, ../passo-privacy-mobile.png, ../passo-account-privacy-mobile.png.

Final five-surface review: Outfit/Noto variable-weight ranges are declared correctly. Translated headings wrap without horizontal overflow. Header/hero proportions follow the selected concept. Continuous dark-teal driver photography restores contrast and the intended standing pose. Driver heading breaks after vehicle; service text is readable. Separate optimized WebP photographs, source logo and licensed local Tabler icons are actual assets; the page uses HTML controls and never a whole mockup screenshot. Copy correctly places phone verification/documents after initial admin acceptance; the APK test-build caption is an intentional product addition.

Focused comparisons: hero heading/actions, service typography, story crop, driver heading/background/steps and language controls were inspected at readable size in original browser captures, alongside the normalized full-page comparison.

Interactions checked in the browser: Find a ride scrolls to services; driver Sign in opens login; Create account shows daily-driver fields only; company/intercity registration selectors are absent; actual vehicle-type catalogue loads; mobile menu opens and Escape dismisses it; Amharic/Oromo switch copy and selected states; no horizontal overflow; privacy/deletion links open actual pages. Error logs were empty. No real account, SMS, payment or deletion was submitted in browser QA.

APK link targets1.1.20 and must be verified after publication before declaring production complete. Website does not claim Google Play availability.

## Follow-up polish

P3: Footer decorative texture is omitted. Regenerated photographs preserve subjects/art direction rather than exact original pixels. The test-build caption and accurate approval explanation add modest lower-section height; controls and hierarchy remain intact.

final result: passed
