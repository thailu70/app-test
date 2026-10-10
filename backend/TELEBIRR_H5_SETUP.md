# RoutePass — Ethio Telecom Telebirr H5 C2B setup

This integration uses the official hosted H5 checkout flow. RoutePass creates and signs orders on the server, sends the passenger to the Telebirr checkout page, and activates the pass only after a server-to-server order-status query confirms the merchant reference, amount and currency.

## Before testing

Obtain the merchant's approved H5 C2B integration settings from Ethio Telecom/Telebirr. Do not use values from another merchant or invent credentials. You will need:

- Fabric App ID and App Secret for `applyFabricToken`.
- Merchant App ID and six-digit Merchant Code.
- The merchant RSA private key required to sign H5 C2B requests.
- The applicable Telebirr/provider public key for validating signed notifications (confirm the correct key and format with your merchant integration contact).
- Confirmation that H5 checkout, queryOrder and server-to-server notifications are enabled for the merchant profile.

Private keys and secrets must remain in `/var/www/routepass/.env`; never commit them to GitHub, put them in the Android APK, paste them into chat, or print the .env file to a terminal screenshot.

## Configure the VPS

Edit the existing secret file locally on the server (do not replace the other database/JWT settings):

```bash
sudo nano /var/www/routepass/.env
```

Add or update these entries using the values from the merchant's official integration pack:

```dotenv
# Keep PAYMENT_MODE=PRODUCTION to route authenticated passenger payments to hosted H5 checkout.
# Use the test gateway below until the merchant has passed sandbox acceptance.
PAYMENT_MODE=PRODUCTION
TELEBIRR_ENVIRONMENT=test
TELEBIRR_FABRIC_APP_ID=<merchant-provided-fabric-app-id>
TELEBIRR_APP_SECRET=<merchant-provided-app-secret>
TELEBIRR_MERCHANT_APP_ID=<merchant-provided-merchant-app-id>
TELEBIRR_MERCHANT_CODE=<six-digit-merchant-code>
TELEBIRR_PRIVATE_KEY=<merchant-private-key-pem-or-base64-der>
TELEBIRR_PUBLIC_KEY=<provider-public-key-for-notification-verification>
TELEBIRR_NOTIFY_URL=https://routepass.duckdns.org/api/subscriptions/telebirr/webhook
TELEBIRR_REDIRECT_URL=https://routepass.duckdns.org/api/subscriptions/telebirr/return
```

The angle-bracket values are placeholders, **not literal values**. Replace each with the actual approved value. For a PEM private key, preserve the complete PEM block. If storing PEM as one line in an env file, represent line breaks as literal `\\n`.

Then protect the file and rebuild only the API:

```bash
chmod 600 /var/www/routepass/.env
cd /var/www/routepass
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml config --quiet
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml up -d --build api
curl -fsS https://routepass.duckdns.org/api/ready
```

Do not run `docker compose down -v`, initialize the database, reinstall RoutePass, or restart Traefik/9Router. The app performs an additive startup migration for its payment-order table.

## Endpoints and trust boundaries

- `POST /api/subscriptions/telebirr/pay`: authenticated passenger creates a hosted checkout. It returns a pending order and `checkoutUrl`; it does not activate the pass.
- `GET /api/subscriptions/telebirr/status/:merchantOrderId`: the authenticated owner polls the order. The backend calls Telebirr `queryOrder`; it verifies merchant reference, amount and ETB before settlement.
- `POST /api/subscriptions/telebirr/webhook`: validates the notification signature and independently queries Telebirr before settling. Notification payload alone never activates a pass.
- `GET /api/subscriptions/telebirr/return`: the browser return only triggers a new server-side query. Return URL parameters alone are never proof of payment.

## Sandbox acceptance checklist

Use a sandbox test account and a low-risk test fare configured by the operator. Confirm that (1) a missing/invalid setting returns `LIVE_TELEBIRR_NOT_CONFIGURED`, (2) the fabric-token request succeeds, (3) preorder returns `prepay_id`, (4) the official hosted checkout opens, (5) cancel/fail does not activate the pass, (6) only a successful provider query creates a `VERIFIED` transaction and a server-signed QR pass, (7) replaying the same idempotency key does not create a second order, and (8) notification retry/query behavior works. Record callback examples with sensitive values redacted.

Do not switch `TELEBIRR_ENVIRONMENT` to `production` until Ethio Telecom confirms the merchant's production credentials and acceptance tests pass against the production gateway. Do not handle actual customer money until the owner approves the live cutover. Disable manual admin test recharge after testing by setting `ALLOW_MANUAL_TEST_RECHARGE=false` and restarting only the API.

## Android behavior

On an H5 checkout response, the app opens the allow-listed Telebirr hosted checkout URL externally and polls the authenticated server status endpoint. It never collects a Telebirr PIN and does not locally activate a QR pass from a browser redirect.
