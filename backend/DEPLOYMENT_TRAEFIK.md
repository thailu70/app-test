# RoutePass deployment behind the existing Traefik VPS proxy

Use this guide when Traefik is already running on the VPS. It is designed for the inspected server configuration with:

- Ubuntu 22.04/24.04 and Docker Compose
- Traefik in host networking mode, using Docker provider labels
- HTTP entrypoint `web` on port 80 and HTTPS entrypoint `websecure` on port 443
- Existing certificate resolver named `letsencrypt`, using the HTTP challenge
- `--providers.docker.exposedbydefault=false`

**Do not run `deploy-vps.sh` on a host where Traefik already occupies ports 80 and 443.** That script starts Nginx on those ports. This guide uses `deploy-traefik-vps.sh`, which does not restart Traefik, does not stop existing containers, does not modify UFW, and does not publish the RoutePass API or database ports to the internet.

## 1. Create and verify a DNS hostname

Create a DNS hostname such as `routepass-test-4827.duckdns.org` and set its IPv4 A record to the VPS public IPv4 address. Use a hostname you control. Do not share your DNS provider's account token.

On the VPS, verify resolution, replacing the example hostname:

```bash
getent ahostsv4 routepass-test-4827.duckdns.org
```

The returned IPv4 address must be the VPS address. Also ensure the hosting provider's external firewall/security group permits inbound TCP 80 and 443. Do not open TCP 3000 or 5432 publicly.

## 2. Fetch this branch

From your SSH terminal (logged in to the VPS), run:

```bash
apt-get update
apt-get install -y git curl
if [ -d /opt/routepass-source/.git ]; then
  cd /opt/routepass-source
  git fetch origin
  git checkout audit/production-readiness-2026-10-09
  git pull --ff-only origin audit/production-readiness-2026-10-09
else
  mkdir -p /opt
  git clone --single-branch --branch audit/production-readiness-2026-10-09 \
    https://github.com/thailu70/app-test.git /opt/routepass-source
fi
```

Review the script before running it:

```bash
cd /opt/routepass-source/backend
less deploy-traefik-vps.sh
```

## 3. Deploy RoutePass

Replace the DNS hostname and email with your own. The email is stored in the RoutePass environment file for administration; certificate issuance is handled by the existing Traefik resolver configuration.

```bash
cd /opt/routepass-source/backend
bash deploy-traefik-vps.sh routepass-test-4827.duckdns.org you@example.com
```

The installer verifies that the named Traefik container is running in host mode and has the expected Docker provider, entrypoints, and resolver. It verifies DNS before copying files, generates secrets if no environment file exists, stores them in `/var/www/routepass/.env` with restrictive permissions, and starts only the RoutePass API and PostgreSQL services. If checks fail, follow the printed diagnostic; do not bypass DNS/TLS checks.

It intentionally does **not** change UFW or your existing Traefik/9router containers. Traefik discovers the API by its explicit Docker labels and routes the hostname to port 3000 on the private Docker bridge. PostgreSQL has no published host port.

## 4. Verify services

```bash
cd /var/www/routepass
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml ps
docker compose -p routepass -f docker-compose.yml -f docker-compose.traefik.yml logs --tail=100 db api
curl -fsS https://routepass-test-4827.duckdns.org/api/health
curl -fsS https://routepass-test-4827.duckdns.org/api/ready
```

Replace the sample host with your actual hostname. Health should return a minimal healthy status; readiness checks database connectivity. WebSocket traffic uses `wss://YOUR-HOST/ws`.

If HTTPS fails, first verify the DNS A record, the hosting provider's inbound TCP 80/443 firewall, then inspect `docker logs --tail=100 traefik-traefik-1`. Never work around TLS failures by publishing port 3000. Avoid running `docker compose down -v`, because it can delete the PostgreSQL data volume.

## 5. Build an APK for the real hostname

After the public readiness endpoint succeeds, open the repository's [RoutePass CI workflow](https://github.com/thailu70/app-test/actions/workflows/routepass-ci.yml). Select **Run workflow**, choose branch `audit/production-readiness-2026-10-09`, and enter the API URL with a trailing slash, for example:

```text
https://routepass-test-4827.duckdns.org/
```

After all jobs succeed, download the `routepass-android-debug-apk` artifact, extract `app-debug.apk`, and install it on your Android phone. The workflow artifact expires after seven days; save a copy locally. This is a debug/test APK, not a Play Store release.

## Important production limits

The current branch intentionally returns an unavailable response for live Telebirr checkout/webhook functionality until the official merchant checkout, callback-signature validation, transaction reconciliation, and sandbox tests are implemented. Offline boarding sync also remains disabled until server-verifiable offline records exist. Test login and route viewing only with test accounts until further acceptance testing is complete. Do not process real-money payments or declare the app production-ready based only on a successful deployment.
