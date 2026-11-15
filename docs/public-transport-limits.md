# Public cargo and group request traffic

This server-only change uses the existing Android `X-Request-Secret` and
`X-Booking-Secret` headers. No mobile changes, APK release, schema migration or
new cookies are required. Request and booking responses and authorization remain
unchanged.

Carrier NAT can place many customers behind one public IP. Previously, one
customer refreshing status, quotes and location every 15 seconds could use 180
of the shared 300 mobility reads allowed over 15 minutes.

The capacity gate still runs first: reject new work at 90% CPU or RAM and recover
when both return to 85%. Explicitly allowlisted public transport routes use a
separate shared-network backstop of 6,000 requests per minute. Other anonymous
API traffic, login, OTP, company authentication and geocoder-specific protections
are unchanged.

| Traffic | Default allowance |
| --- | --- |
| Public browsing without a validated private capability | 600 reads per minute per IP |
| Private cargo/group request or booking reads | 600 reads per 15 minutes per validated request or booking |
| Private request/booking mutations | 60 actions per 15 minutes per validated request or booking |
| Other public mobility mutations | 600 actions per 15 minutes per IP |
| New cargo/group drafts | 120 per hour per IP |
| Photo uploads | 20 per hour per private request, also 120 per hour per IP |
| Invalid/missing private capabilities | 300 attempts per minute per IP, plus the relevant public route limit |

Private quotas are selected only after reusing the business service's existing
capability validation. Raw headers, IDs, phone numbers and request bodies cannot
create trusted quotas. Invalid attempts do not consume a validated customer's
private quota, but the coarse network backstop still applies to all traffic.
Limit responses are JSON with the existing `success`/`error` fields and standard
`Retry-After` guidance. Cargo photos retain the existing 5 MB size ceiling.

The larger shared-network creation/upload ceilings remain finite abuse
protections. An anonymous client has no verified per-person identity before its
first request is created; the server does not treat an entered phone number as
proof of identity.

Verification:

```sh
cd backend
node --test test/api-traffic-test.js test/public-transport-traffic-test.js test/resource-pressure-test.js test/mobility-test.js test/mobility-security-test.js test/mobility-marketplace-test.js test/mobility-push-test.js test/mobility-geo-test.js
```
