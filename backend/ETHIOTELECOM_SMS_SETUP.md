# RoutePass — Ethio Telecom SMS OTP setup

RoutePass requires phone verification before account registration is accepted. OTP codes are generated on the backend, stored as HMAC digests (never as plain text), expire after 5 minutes, and allow at most five verification attempts. A verified OTP is single-use and bound to the normalized phone number and the `SIGNUP` purpose. Request and verification endpoints are rate limited.

## Required VPS environment settings

Keep these values in `/var/www/routepass/.env` only. Do not add real values to GitHub, Android resources, logs, or support messages.

```dotenv
OTP_HASH_SECRET=REPLACE_WITH_A_LONG_RANDOM_SECRET
ETHIOTELECOM_SMS_API_URL=
ETHIOTELECOM_SMS_API_TOKEN=
ETHIOTELECOM_SMS_AUTH_HEADER=Authorization
ETHIOTELECOM_SMS_AUTH_SCHEME=Bearer
ETHIOTELECOM_SMS_SENDER_ID=
ETHIOTELECOM_SMS_PAYLOAD_MODE=ethiotelecom-smn
```

Generate the OTP HMAC secret on the VPS with `openssl rand -hex 32`. If `OTP_HASH_SECRET` is omitted, RoutePass may use `JWT_SECRET` as the HMAC key, but a dedicated secret is recommended.

**Important provider setup:** Ethio Telecom provisions enterprise/developer access and credentials separately. Obtain the production/sandbox API URL, authentication header/scheme, approved sender identity, and exact request-body contract from your Ethio Telecom account representative/developer portal. The integration supports these request-body modes:

- `ethiotelecom-smn`: `{ "endpoint": "sms:+2519…", "message": "…" }`, matching the body shape in Ethio Telecom's published Simple Message Notification API reference (`POST /v2/{tenant_id}/notifications/sms`). The full HTTPS URL must include the account-issued host and tenant/project path.
- `to-message`: `{ "to": "+2519…", "message": "…" , "sender": "…" }`. Use this only if the specific API endpoint provisioned for RoutePass documents that alternative contract.

Set `ETHIOTELECOM_SMS_PAYLOAD_MODE` to the documented contract. Set `ETHIOTELECOM_SMS_AUTH_HEADER` and `ETHIOTELECOM_SMS_AUTH_SCHEME` to the provider-issued authentication format. Do not guess production URLs or credentials. Until an endpoint and credentials from Ethio Telecom are populated, OTP requests fail closed with a service-unavailable response and registration cannot bypass verification.

The documented provider interface varies by enterprise product/account; the currently configured `ETHIOTELECOM_SMS_API_URL` and payload mode must match the endpoint issued to RoutePass. The app intentionally does not store provider credentials.

## Safe deployment

1. Back up PostgreSQL before deploying.
2. Add the environment values on the VPS and use `chmod 600 /var/www/routepass/.env`.
3. Rebuild/restart only the `api` service after validating Compose.
4. Test OTP with a real authorized test phone. Confirm no code appears in API responses or logs.
5. Verify invalid, expired, reused, and over-attempt OTPs are rejected.

The `OTP_TEST_MODE` response helper is available only under `NODE_ENV=test`; never set it on the live VPS.
