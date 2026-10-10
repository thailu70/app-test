# RoutePass SMS OTP — Ethio Telecom

RoutePass passenger and driver sign-up now requires a verified SMS one-time password (OTP). The Android client never generates, receives, or stores the OTP itself; the backend creates and validates it.

## Provider

The integration targets Ethio Telecom's Simple Message Notification (SMN) API documented in the [Ethio Telecom SMN API Reference](https://docs.ethiotelecom.et/mohelpcenter/operation/en-us/smn/doc/download/pdf/smn-api.pdf), Direct SMS Messaging endpoint:

`POST /v2/{tenant_id}/notifications/sms`

The request body is `{ "endpoint": "tel:+2519XXXXXXXX", "message": "..." }`. RoutePass sends an HTTPS request with a server-side `Authorization: Bearer ...` header. Obtain the production API base URL, tenant/project ID, and access-token lifecycle from Ethio Telecom for your approved tenant. Do not assume a sandbox URL or use a token from another service.

## Required VPS environment variables

Set these only in `/var/www/routepass/.env` on the VPS. Do not put credentials in Android code, GitHub, screenshots, chat, or application logs.

- `OTP_HASH_SECRET`: unique random secret of at least 32 characters. Generate with `openssl rand -hex 32`. Do not reuse `JWT_SECRET` or `QR_SIGNING_KEY`.
- `ETHIO_SMS_API_BASE_URL`: HTTPS base URL issued for your Ethio Telecom SMN tenant, without the `/v2/{tenant_id}/notifications/sms` suffix.
- `ETHIO_SMS_TENANT_ID`: project/tenant ID issued by Ethio Telecom.
- `ETHIO_SMS_ACCESS_TOKEN`: valid bearer access token issued for that API. Rotate it according to Ethio Telecom's expiry/revocation policy.
- `OTP_TTL_SECONDS`: optional; default 300 (5 minutes; bounded by the service).
- `OTP_RESEND_COOLDOWN_SECONDS`: optional; default 60.
- `OTP_MAX_ATTEMPTS`: optional; default 5.

Example configuration shape (place actual values only in the VPS .env; never commit them):

```dotenv
OTP_HASH_SECRET=GENERATE_A_UNIQUE_64_HEX_CHARACTER_SECRET
OTP_TTL_SECONDS=300
OTP_RESEND_COOLDOWN_SECONDS=60
OTP_MAX_ATTEMPTS=5
ETHIO_SMS_API_BASE_URL=https://YOUR-ETHIO-TELECOM-SMN-API-BASE
ETHIO_SMS_TENANT_ID=YOUR_TENANT_ID
ETHIO_SMS_ACCESS_TOKEN=YOUR_SECRET_ACCESS_TOKEN
```

The placeholder values above are not credentials and will not work. The SMS integration fails closed with HTTP 503 until the three Ethio Telecom provider values are configured. No development OTP is returned to the app, and OTPs or access tokens are never logged.

## Secure flow

1. Android calls `POST /api/auth/otp/request` with `{ "phone": "09XXXXXXXX" }`.
2. The server normalizes supported Ethiopian mobile numbers to E.164, applies IP rate limits and a per-phone resend cooldown, generates a cryptographically random six-digit code, stores only an HMAC digest, and sends the SMS through Ethio Telecom.
3. Android calls `POST /api/auth/otp/verify` with `{ "phone": "+2519XXXXXXXX", "challengeId": "...", "code": "123456" }`.
4. The server checks expiry, one-time use, attempt count and the code using a timing-safe comparison. It returns no OTP.
5. The app submits `otpChallengeId` with `POST /api/auth/register`. Passenger/driver account creation requires a matching verified, unexpired, unused challenge; the challenge is consumed atomically with account creation. Admin bootstrap remains restricted by the existing admin registration secret and is not a public sign-up path.

The database table `otp_challenges` is created idempotently by the backend on first OTP use. Existing users and existing database records are not reinitialized.

## Configure and deploy safely

1. Obtain Ethio Telecom API access and verify the exact API base URL, token issuance/refresh requirements, and approved sender/message template with Ethio Telecom.
2. Back up the existing database before deployment.
3. Add the variables above to `/var/www/routepass/.env`; protect the file with `chmod 600 /var/www/routepass/.env`.
4. Update the compose file/source and rebuild only the API service. Do not run `docker compose down -v` or initialize the database.
5. Test request/verify with an approved test number, then test registration. Confirm invalid, expired, replayed, and over-attempt OTPs are rejected.
6. Monitor delivery via the provider's approved portal; RoutePass intentionally avoids logging phone numbers, OTPs, bearer tokens, and provider response bodies.

Ethio Telecom may require sender identity approval and specific network access before production delivery. A successful HTTP response from the SMS API means accepted by the provider, not necessarily delivered to the handset.
