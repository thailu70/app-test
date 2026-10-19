# Temporary admin pass activation

Set `ALLOW_ADMIN_PASS_ACTIVATION=true` in the existing protected VPS `.env`, then recreate only the API with the existing Compose files. Keep `NODE_ENV=production`, `PAYMENT_MODE=PRODUCTION` and `ALLOW_MANUAL_TEST_RECHARGE=false`.

In `/admin/`, open **Subscriptions**, choose **Activate 30 days**, and enter a reason. Only an authenticated active administrator can do this. The passenger and route must be active. Existing active passes are not extended by repeated clicks.

The API issues a signed QR pass and records `SUBSCRIPTION_ADMIN_ACTIVATED` with the administrator, subscription, reason and duration in the audit log. It does not create a payment transaction or add revenue. `paymentStatus=PAID` remains the legacy eligibility marker required by the existing Android/boarding clients; it is not evidence of a collected payment. The admin action and audit record identify the grant.

Endpoint: `POST /api/admin/subscriptions/:id/activate`, JSON `{ "days": 30, "reason": "Temporary admin approval" }`. Days must be a JSON integer from 1 through 90; reason must contain 3 through 200 characters. Returns 403 when disabled and 409 if the pass is already active or the passenger/route is inactive.

To stop new grants, set `ALLOW_ADMIN_PASS_ACTIVATION=false` and recreate only the API. Existing granted passes retain their recorded expiry; disabling new activation does not revoke them. No APK update is required.
