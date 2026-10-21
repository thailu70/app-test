# CPU and RAM admission threshold

Normal API work pauses when sampled CPU or memory usage reaches 90%. It resumes when both readings are at or below 85%. Rejected work receives HTTP 503, `SERVER_BUSY`, and `Retry-After: 10`. Resource sampling runs every two seconds; it is an admission threshold, not an operating-system hard cap.

Measurements include the VPS and any smaller Docker CPU or memory allowance. Linux available memory and reclaimable file cache are accounted for. Existing work is allowed to finish. New WebSocket connections receive close code 1013 while busy; established connections retain their heartbeat and receive a retry notice for new work.

Verified active accounts no longer share the general 1,000-request IP quota. Anonymous traffic and the existing login, OTP, and guest-service protections retain their limits. Health probes have a separate allowance and remain reachable during pressure.

Admin → Settings shows live cached readings from the ADMIN-only `/api/admin/system/resources` endpoint. Its simulator is a browser-only preview of the same thresholds. Moving simulation controls does not change server metrics, admission state, or resource consumption.

## Admin Master Data

Admin → Master Data manages vehicle type names, seating capacities and availability. Type codes remain fixed. Catalog edits apply to new registrations and type assignments; existing vehicles retain their physical capacities. Driver signup and the company fleet forms load the active catalog.

The global GPS boarding radius accepts 25–5,000 metres and applies to existing and future daily commute and intercity departure checks. Until the first global save, existing route/departure radii remain in use. Coordinates, precise/fresh GPS checks and the departure time window are preserved.

Driver GPS sharing accepts 1–60 whole seconds, with the existing five-second cadence as the default. APK 1.1.18 reads this setting while the driver portal is open and refreshes it every 30 seconds. One shared upload gate prevents duplicate GPS/network provider uploads and overlapping requests. Fresh local fixes remain available for boarding checks even with a longer upload interval. Failed settings requests retain the last good interval.

CPU/RAM admission works with existing app versions. Install APK 1.1.18 to apply the configurable GPS upload interval and updated vehicle catalog.
