# Isolated app review environment

This is a separate synthetic deployment for the **same** Android app. The app's explicit Demo environment switch selects `https://passo.live/review/` and displays a persistent synthetic-data notice. Real trips, payments, SMS and production customer records are never part of it. This is not a hidden reviewer-only implementation or an override of production account, payment or departure rules.

## Isolation and resource budget

`backend/review/compose.yml` adds three independently named containers and two new persistent volumes. It never recreates the production API/database/Traefik services.

| Container | Memory cap | CPU cap | Network |
| --- | --- | --- | --- |
| PostgreSQL | 256 MiB | 0.5 | Internal review network only |
| Node API | 384 MiB | 0.5 | Internal review network only |
| Nginx prefix proxy | 32 MiB | 0.1 | Review network and existing Traefik network |

The total configured ceiling is 672 MiB and 1.1 CPUs, not a statement of measured utilization. Database uploads and logs consume additional disk. The API and database cannot reach the internet or the production database network. Only the prefix proxy connects to the existing ingress network; no review host ports are published. Its Traefik rule has priority 100 for `/review/`, strips that prefix, and forwards `/api/`, `/ws`, and the exact review company asset/page allowlist under `/company/`. No admin, join, shared asset tree, directory listing, or generic static tree is exposed. The native company-associated **driver** workflow and company-owner workspace both use synthetic accounts.

The separate `src/review/server.js` entry validates configuration before importing any DB/route. It refuses a production hostname/database/user, the normal origin, production data/upload paths, shared/short keys, configured SMS/payment providers, manual payment activation, and enabled test payments. The database must report exactly `passo_review` for both database and user. A private per-deployment marker must match on every restart/fixture refresh; initial adoption refuses any database already containing accounts. The normal `src/server.js` refuses `REVIEW_ENVIRONMENT=true` unless bootstrapped by the validated alternate entry.

Normal production auth, account deletion, subscription QR, payment and departure functions are reused. The review middleware relocates **only owned synthetic** route origins to a review driver's supplied fresh precise physical GPS fix and sets their synthetic departure to the current window. Then existing ownership, time, fix age/precision, radius, capacity, paid-ticket and duplicate-boarding checks execute normally. No device/mock GPS or production geofence exception is required. No request/header/account flag can activate this adapter in the production process.

## Private configuration and deployment

1. Generate new secrets **outside** the repository using `node backend/review/create-private-config.js ABSOLUTE_PRIVATE_PATH`. The command refuses in-repository paths, uses exclusive creation and prints no values. Restrict the resulting file to the owner (0600 on Linux; owner-only ACL on Windows). Do not copy production `.env` files, credentials or signing keys.
2. Place a protected copy at a dedicated VPS path such as `/etc/passo-review/review.env`. Preserve `REVIEW_INSTANCE_ID` and the database volume together; a different marker intentionally refuses an existing volume. Store another protected copy locally. All values must be different from production. `REVIEW_DEMO_PASSWORD` is the shared synthetic login password; put it only in Play Console's restricted App access credential fields.
3. From the checked-out source run `docker compose --env-file /etc/passo-review/review.env -f backend/review/compose.yml config --quiet`. Resolve topology validation through `config --format json | node backend/test/review-compose-test.js` without logging the resolved JSON, which contains private values.
4. Build/start **this stack only** with the same explicit Compose file/env arguments. The source schema is initialized without public development seed records. The alternate entry validates ownership, migrates additive schemas and seeds synthetic fixtures before it opens its listener.
5. Verify `/review/api/review/info` returns `environment: REVIEW`, `synthetic: true`, `paymentsEnabled: false`, `smsEnabled: false`; ordinary `/api/review/info` must remain 404. Review `/admin/`, `/join/`, `/shared/`, unknown company assets, reset/seed/admin API paths must be 404; `/review/company/` must display the synthetic company banner, strict review-path CSP and `X-Passo-Environment: REVIEW`. Verify live `/api/ready` and existing pages still work.
6. Sign into each supplied synthetic role from the final signed Android build in Demo. Test native sample-ticket import, foreign-location start/boarding/completion, cargo photo/status, acceptance/pending application states, and the disposable deletion. A production token must fail on review and a review token must fail on production. Do not inspect or export real accounts to perform that check.
7. Run a container health/egress test: review API can connect only to review-db/proxy; SMS/payment network calls remain unavailable and their credentials are absent. Inspect resource use under real closed-test concurrency before claiming an operating cost or VPS headroom.

Rollback stops only the `passo-review` Compose project. Keep its private config/volumes if review access is expected to resume. Do not remove production volumes or restart shared Traefik to roll back this stack.

## Reusable synthetic accounts

All accounts represent adults, and all named documents explicitly say they are synthetic rather than proof of verification. Their contact numbers have no SMS delivery path. Do not call them. No administrator credentials are provided. The synthetic company owner uses the same private review password as the other supplied demo accounts; it has no production identity or privileges.

| Phone (national) | Account | Review path |
| --- | --- | --- |
| `0900000101` | Approved passenger | Daily assigned paid sample pass/QR, driver and vehicle |
| `0900000102` | Approved daily driver | Assigned route, passenger roster, real foreground GPS service, start/scan/end |
| `0900000103` | Approved group driver | Proposed trip form, approved sample group trip, paid travelers, boarding/completion |
| `0900000104` | Approved cargo driver | Assigned synthetic request and non-personal sample cargo photo |
| `0900000105` | Approved company-associated driver | Assigned future intercity departure and paid ticket roster |
| `0900000106` | Pending daily applicant | Initial acceptance/status view; operational login stays denied |
| `0900000107` | Accepted daily applicant | Profile/documents and submission view; operational login stays denied |
| `0900000108` | Disposable passenger | In-app deletion with the same private password and `DELETE` confirmation |
| `0900000109` | Approved synthetic company owner | Company sign-in, fleet, routes, driver application review, assignments, departures, booking desk, roster and reports |
| `0900000110` | Submitted company-associated applicant | Complete synthetic documents for company-owner review; no initial independent-driver acceptance needed |

Scheduled refresh is private, idempotent and runs only in the review container every five minutes. It claims ownership for fixture IDs and refuses to overwrite an unowned collision. Future sample departures roll forward. Active boarding and completed state is retained; a completed sample gets a fresh journey ID. Existing user-uploaded application/profile changes are not repeatedly overwritten. Deleting a sample account preserves its retained deletion receipt, permanently invalidates the old ID/token, and provisions a **new ID** under the same synthetic phone on the next refresh. It never revives deleted credentials. Allow up to five minutes before reusing a deleted phone. Prefer the disposable passenger to avoid transport handover blockers on driver samples.

Guest group/cargo requests still require no registration, sign-in or OTP in Demo. Enter clearly synthetic data. Newly submitted requests show normal pending state; separately seeded assignments let the reviewer inspect the completed handover state without waiting for a person. Checkout intentionally stays unavailable, and already-paid samples demonstrate ticket/boarding presentation. No payment success endpoint exists in the review API, and all production free/test payment paths remain disabled.

Exported sample intercity PDFs and group HTML passes carry an explicit **synthetic demo / no real payment / not valid for real travel** warning, including when opened outside the app. Synthetic intercity samples never use the real-provider “Telebirr verified” label.

## Company workspace isolation

A URL path is **not** a browser origin boundary. The Android Demo company WebView therefore disables DOM storage, cookies and third-party cookies, uses a separate ephemeral company token, blocks frames/popups/external resources/service-worker networking, and intercepts allowed resources with a cookie-free no-redirect client. It checks the selected environment/session before and after each request and requires the review response marker. It never injects the native passenger/driver token. All navigation/resource paths stay under `/review/company/` or `/review/api/`; switching environments or closing the WebView destroys the token and bridge. Production company storage and sign-in behavior are unchanged.

The Demo bridge is `window.PassoReviewSession.getToken()/setToken(token)/clearToken()`, with a per-WebView memory token that survives company index/intercity navigation but never reaches disk. The review adapter never evaluates localStorage, sessionStorage, IndexedDB or cookies. If opened in an ordinary external browser without the native bridge, it falls back to document memory and requires sign-in again after cross-document navigation. Do not treat review and production pages in an ordinary same-origin browser as separate security origins; full isolation here depends on the controlled native WebView.

Review HTML reuses the company components with exact aliased assets, a persistent synthetic banner, `connect-src https://passo.live/review/api/`, no forms leaving the document, no frames/workers, and no external map resources. The fetch adapter validates every URL, omits credentials, rejects redirects and prefixes legacy shared-component API paths. Fleet GPS uses a local synthetic grid with coordinates in the existing scoped table; it does not fetch public map tiles. Synthetic PDF tickets and exported reports carry demo/no-real-payment labels. `openDocument(path, safeFilename)` delegates only exact scoped driver documents/company PDF tickets to a cookie-free no-redirect native download; the company token is memory-only and only sent to protected owned-document/ticket endpoints. Signed ticket capabilities use no bearer. Downloads validate the review marker, PDF/PNG/JPEG MIME and file signature, and a 5 MiB bound before the Android save dialog. `exportCsv(csv)` has a fixed CSV MIME/filename, a 2 MiB UTF-8 bound and a save dialog; no general file/URL bridge exists.

The owner fixture is preverified **only in the isolated owned review database**, with plainly synthetic company documents and no provider/SMS call. Normal company OTP, readiness, operational eligibility, tenant scoping and payment rules remain active in production. Company application review in Demo is restricted to this owned company principal and the existing company-scoped application guards; independent applicants/admin operations remain inaccessible. New company registration/invitations and real checkout are unavailable. The booking desk simulates the existing cash-collection workflow entirely inside the isolated database and explicitly states no money is collected.

## Native sample-import contract

`GET api/review/info` is public inside the selected review prefix and reports the environment flags above. `GET api/review/resources` requires a valid approved synthetic account token and returns only synthetic recovery capabilities:

```json
{
  "success": true,
  "environment": "REVIEW",
  "synthetic": true,
  "paymentsEnabled": false,
  "smsEnabled": false,
  "groupBooking": {"id": "sample-id", "bookingSecret": "private-recovery-capability"},
  "intercityBooking": {"id": "sample-id", "bookingSecret": "private-recovery-capability"},
  "requests": [{"id": "sample-id", "requestSecret": "private-recovery-capability", "kind": "CARGO"}]
}
```

Do not log/save this response in build artifacts. Import it only into the environment-specific protected stores. Every API-generated ticket/photo path retains `/review/api/`; absolute boarding-pass links use `https://passo.live/review/api/`. Privacy policy can open the canonical public `https://passo.live/privacy/` without sending native authorization or account state. Returning to live service requires sign-in again and cancels demo API/GPS/WebSocket work first.

## Automated checks

`test/review-company-portal-test.js` checks the exact asset mapping, CSP, storage-free bridge session and cookie-free scoped fetch. The company HTTP integration exercises preverified owner login, scoped application/document approval, route/fleet/assignment creation, departure publication, manual synthetic ticket/PDF, roster/reports and tenant rejection. Existing production company UI/application suites still exercise their original sessionStorage behavior.

`test/review-environment-test.js` covers rejection of unsafe environment settings/DB adoption, approved and pending role sign-in, protected sample recovery/URLs, ownership collision, paid-only tickets, no admin/seed/reset/external services, foreign-location standard boarding gates, and deletion revocation/replacement. It runs against disposable SQLite and a **separate** PostgreSQL CI service (`passo_review` database/user); it rejects arbitrary supplied PostgreSQL targets. The Compose test asserts private-only DB/API networks, no host ports/shared volumes/provider credentials, origin-prefix routing and the resource caps. Existing production auth/payment/departure suites must pass as well.

Before completing Play's App access declaration, verify these instructions against the final uploaded signed build and the deployed review URL. Source/unit tests alone are not evidence of a live reviewer login or successful store publication.
