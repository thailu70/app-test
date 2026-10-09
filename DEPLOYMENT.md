# RoutePass - Production VPS & Android Deployment Guide 🚀

This document details the exact, verified deployment instructions for **RoutePass (Transport Navigator)** on your Ubuntu VPS (**62.72.19.170**, 8 GB RAM, 2 vCPUs), including database migration from SQLite to PostgreSQL, strict firewall rules, SSL/TLS reverse proxy, repository data cleanup, and rollback procedures.

---

## 🖥️ VPS Host Specifications
- **Host IP**: `62.72.19.170`
- **Memory**: 8 GB RAM
- **Compute**: 2 vCPUs
- **Operating System**: Ubuntu 20.04 / 22.04 / 24.04 LTS
- **Production Exposed Ports**: `22` (SSH), `80` (HTTP), `443` (HTTPS)
- **Internal Private Services**: PostgreSQL 16 (Port 5432 - private Docker network), Node.js API (Port 3000 - private internal proxy)

---

## 📋 Part 1: Initial VPS Setup & Repository Sanitization

### Step 1: Connect to VPS
```bash
ssh root@62.72.19.170
```

### Step 2: Clean Public Repository History (Remove Exposed Secrets / DB)
If `backend/data/transport.db` or `.env` files were ever committed to git in the past, clean them from git tracking and git history:

```bash
# 1. Untrack database files and local environment files
git rm --cached backend/data/transport.db 2>/dev/null || true
git rm --cached -r backend/data/ 2>/dev/null || true
git rm --cached backend/.env 2>/dev/null || true

# 2. Commit the removal
git commit -m "Security: Remove database artifacts and secrets from version control"

# 3. If purging from entire git history (optional but recommended for public repos):
# Install git-filter-repo or BFG
# bfg --delete-files transport.db
# git reflog expire --expire=now --all && git gc --prune=now --aggressive
```

Both `.gitignore` and `backend/.gitignore` are pre-configured to strictly ignore `*.db`, `*.sqlite`, `*.backup`, and `.env*`.

---

## 🗄️ Part 2: Database Migration & Backup (SQLite ➔ PostgreSQL)

Before switching or updating the production database authority to PostgreSQL, create an immutable backup of existing data.

### Automated Backup Command
```bash
cd /var/www/routepass/backend
npm run backup
```
This generates a timestamped copy in `backend/data/backups/transport_YYYY-MM-DDTHH-mm-ss.db.backup`.

### Migrating to Persistent PostgreSQL
1. The stack deploys **PostgreSQL 16 Alpine** inside Docker on a dedicated persistent volume `routepass_postgres_data`.
2. Initial schema, indexes, and Ethiopian transit seeds (Bole ↔ Merkato, Megenagna, Mexico, standard vehicle types) are automatically applied on first boot via `backend/init-db.sql`.
3. To manually run schema initialization or inspect PostgreSQL:
```bash
docker compose exec db psql -U routepass -d routepass_db -f /docker-entrypoint-initdb.d/01-init.sql
```

---

## 🚀 Part 3: 1-Click Production VPS Deployment

### Quick Automated Deployment:
```bash
cd /var/www/routepass/backend
chmod +x deploy-vps.sh
sudo ./deploy-vps.sh
```

### Manual Step-by-Step Deployment:
```bash
cd /var/www/routepass/backend

# 1. Create production environment file with secure random secrets
cp .env.example .env

# Generate cryptographically secure keys
JWT_KEY=$(openssl rand -hex 32)
ADMIN_KEY=$(openssl rand -hex 16)
QR_KEY=$(openssl rand -hex 32)
DB_PASS=$(openssl rand -hex 16)

cat <<EOF > .env
NODE_ENV=production
PORT=3000
HOST=0.0.0.0
DATABASE_URL=postgres://routepass:${DB_PASS}@db:5432/routepass_db
POSTGRES_DB=routepass_db
POSTGRES_USER=routepass
POSTGRES_PASSWORD=${DB_PASS}
JWT_SECRET=${JWT_KEY}
JWT_EXPIRES_IN=30d
ADMIN_REGISTRATION_SECRET=${ADMIN_KEY}
QR_SIGNING_KEY=${QR_KEY}
PAYMENT_MODE=TEST
DATA_DIR=/app/data
EOF

# 2. Build and launch Docker multi-container stack
docker compose up -d --build

# 3. Configure strict UFW firewall (Expose ONLY ports 22, 80, 443)
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
ufw --force enable
ufw status verbose
```

---

## 🔒 Part 4: Production HTTPS / TLS with Let's Encrypt

When a public domain (e.g. `transit.yourdomain.et`) points to `62.72.19.170`:

```bash
# 1. Install certbot
apt-get install -y certbot

# 2. Obtain free Let's Encrypt certificate
certbot certonly --webroot -w /var/www/routepass/backend/certbot/www -d transit.yourdomain.et

# 3. Enable HTTPS server block in backend/nginx.conf and reload Nginx:
docker compose exec nginx nginx -s reload
```

---

## 🧪 Part 5: Verification & Load Testing

Run all backend integration and GPS concurrency tests directly on the VPS:

```bash
cd /var/www/routepass/backend

# 1. Run all 13 core REST and WebSocket integration tests:
npm test

# 2. Run real-time GPS load concurrency tests (10, 25, 50, and 100 active drivers):
npm run test:gps

# 3. Check live HTTP health check endpoint:
curl -i http://62.72.19.170/api/health
```

Expected output:
```json
{
  "status": "HEALTHY",
  "service": "Transport Navigator Transit Server",
  "environment": "production",
  "database": "CONNECTED",
  "version": "1.0.0"
}
```

---

## 📱 Part 6: Building & Installing the Android APK

### Step 1: Configure Central VPS URL
In `app/src/main/java/com/example/data/api/ApiClient.kt`, the application is pre-configured to communicate with your VPS:
```kotlin
const val DEFAULT_VPS_HOST = "62.72.19.170"
const val DEFAULT_HTTP_URL = "http://62.72.19.170:3000/" // or "https://62.72.19.170/"
```

### Step 2: Compile Debug APK
From the project root:
```bash
gradle :app:assembleDebug
```
The resulting APK is generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Install on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔄 Part 7: Rollback Instructions

If a faulty deployment occurs or maintenance requires reverting:

### Rollback Application Containers:
```bash
cd /var/www/routepass/backend
# Stop current stack
docker compose down

# Check previous git commit / docker image
git log -n 5 --oneline
git checkout <PREVIOUS_STABLE_COMMIT>

# Rebuild and start
docker compose up -d --build
```

### Restore Database from Backup:
```bash
# SQLite rollback:
cp backend/data/backups/transport_<TIMESTAMP>.db.backup backend/data/transport.db

# PostgreSQL rollback:
docker compose exec -T db psql -U routepass -d routepass_db < backend/data/backups/postgres_<TIMESTAMP>.sql
```
