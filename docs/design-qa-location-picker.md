# Location picker — option 3 implementation QA

Reference: selected compact-sections mockup, centre screen, `exec-06d44eff-0354-42f7-b793-f9688d381918.png`.

Implemented app-owned elements: Passo cream background and existing wordmark/language controls; clear pickup/destination title; rounded native search field and current-location action; real OpenStreetMap view; teal location icon; attribution and recenter control; white selected-address panel and full-width teal confirmation button. Existing Photon location API, phone GPS permission checks, and coordinate return callback remain in use. The optional place-name callback fills the caller's route label from a genuine search result.

The map is measured inside a finite Compose slot. Its Android `FrameLayout` uses `MATCH_PARENT` within that slot, clips child drawing explicitly, and the Compose slot also clips. Moving between keyboard/orientation layouts reparents the same map safely. Portrait keeps confirmation below the map; landscape uses a scrollable control column beside the bounded map. Compact height keeps confirmation fixed while search/map content can scroll.

Regression tests: `MobilityLocationPickerTest` checks that the initial viewport is not a selection, selecting a search result returns its actual name/coordinates, the native map never measures outside its frame, the frame matches the Compose map slot, and confirmation/search remain inside portrait and landscape viewports. It captures real native-map screenshots with tile downloads disabled for deterministic offline layout testing.

Visual comparison is pending CI Android rendering. No local Android SDK/Gradle toolchain is available in this workspace. Expected screenshots: `app/build/mobility-previews/05-location-picker-portrait.png` and `06-location-picker-landscape.png`. Offline captures verify layout and clipping; a connected-device check is additionally required for live map tiles/GPS.

final result: blocked
