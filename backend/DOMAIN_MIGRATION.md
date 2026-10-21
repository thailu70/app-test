# passo.live domain migration

The main domain is `https://passo.live`. The portals remain at `/admin/` and `/company/`; the app uses `/api/` and `/ws` on the same host. APK 1.1.19 (22) defaults to the new host. Build-time `ROUTEPASS_API_BASE_URL` overrides still configure API, portal and map URLs together.

Keep `routepass.duckdns.org` active while installed APKs and payment callbacks still use it. The Traefik Compose override has separate primary and legacy TLS routers pointing to the same API service. It does not redirect legacy API or webhook requests. Existing accounts, JWT/QR keys, database and uploads are shared unchanged.

Existing VPS environment changes:

```dotenv
ROUTEPASS_DOMAIN=passo.live
ROUTEPASS_LEGACY_DOMAIN=routepass.duckdns.org
PUBLIC_BASE_URL=https://passo.live
ALLOWED_ORIGINS=https://passo.live,https://routepass.duckdns.org
```

Retain additional existing CORS origins. Preserve explicit monthly Telebirr callback overrides until the provider approves a callback-domain change. For intercity, retain its separately registered callback using:

```dotenv
TELEBIRR_INTERCITY_NOTIFY_URL=https://routepass.duckdns.org/api/intercity/payments/webhook
```

Fresh deployments may leave callback overrides blank to derive them from `PUBLIC_BASE_URL`. Ticket PDF links remain relative to the current host so private ticket capabilities are not redirected to a different configured origin.

Before changing live routing, point the apex A record to the VPS, check authoritative and public DNS (including AAAA/CAA), and back up the environment, Compose files, application files and database. Keep the current Traefik container and ACME storage. Render the merged Compose configuration to confirm both Host rules before recreating only the API service.

Verify HTTPS with normal certificate validation on both domains: readiness, portals, route discovery, authenticated app requests, WebSocket authentication, CORS and the published APK checksum. Test legacy webhook routing only with an empty unauthenticated body: it must fail validation and never redirect or settle a payment.
