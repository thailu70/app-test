# RoutePass 1.1.1 — company data and driver GPS

Company fleet, driver, route and intercity pages use the admin console design. Each data source has its own failure message and retry. A failed refresh preserves previous results; requests time out after 12 seconds. The company name identifies the current workspace. An empty workspace only shows its own records.

Both consoles include **Driver GPS**, with a searchable vehicle table and map. Administrators see daily commute vehicles; companies see only their own fleet. GPS reports are never inferred from seeded vehicle coordinates. Live means an active journey and a report within 120 seconds. Old reports retain their time and appear stale or offline.

Install Android **1.1.1 (version code 5)** for the intercity location fix. The driver must allow precise location, confirm departure arrival and keep the driver screen open. GPS uploads continue every five seconds while stationary. Intercity boarding stays active until the driver completes the route. Reassigned vehicles cannot publish for an old departure.

The map uses locally bundled Leaflet 1.9.4 and OpenStreetMap tiles with visible attribution, ordinary browser caching and origin-only referrers. Portal headers allow these tile images; authentication remains same-origin. See the [tile provider policy](https://operations.osmfoundation.org/policies/tiles/). If tiles are unavailable, driver coordinates and report ages remain available.

PostgreSQL company ownership is normalized consistently with SQLite, so active driver scope comes from current database ownership rather than an old token claim.

Validation includes independent company section recovery/timeouts, session clearing, tenant isolation, genuine/live/stale/offline/missing GPS, scheduled vehicle ownership and intercity upload lifecycle. CI also exercises these APIs against PostgreSQL and builds the installable Android APK.
