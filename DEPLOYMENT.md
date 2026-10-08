# RoutePass - Production VPS & Android Deployment Guide 🚀

This document details the exact, tested commands to deploy the **RoutePass (Transport Navigator)** backend to an Ubuntu 20.04 / 22.04 / 24.04 LTS VPS, configure production security, and build/install the Android APK.

---

## 📋 Part 1: Deploying Backend to Ubuntu VPS

### Step 1: Connect to VPS via SSH
```bash
ssh root@YOUR_VPS_IP
```

### Step 2: Clone or Copy Project Files
```bash
# Update and install git
apt-get update -y && apt-get install -y git curl ufw

# Create application directory
mkdir -p /var/www/routepass
cd /var/www/routepass

# If cloning from GitHub:
git clone https://github.com/thailu70/app-test.git .
cd backend
```

### Step 3: Run the 1-Click Automated Deployment Script
```bash
chmod +x deploy-vps.sh
./deploy-vps.sh
```

The script will automatically:
1. Install Docker Engine and Docker Compose.
2. Generate cryptographically strong `JWT_SECRET`, `ADMIN_REGISTRATION_SECRET`, and database credentials in `.env`.
3. Launch PostgreSQL 16, Node.js API, and Nginx reverse proxy containers.
4. Initialize the PostgreSQL database schema and seed Ethiopian transit routes and vehicles.
5. Configure the UFW firewall to expose **ONLY ports 22 (SSH), 80 (HTTP), and 443 (HTTPS)**.

---

### Step 4: Manual Docker Compose Deployment (Alternative)

If you prefer deploying with Docker Compose directly:

```bash
cd /var/www/routepass/backend

# 1. Configure production environment
cp .env.example .env
nano .env   # Update JWT_SECRET and POSTGRES_PASSWORD

# 2. Build and start containers in detached mode
docker compose up -d --build

# 3. Verify container health
docker compose ps
docker compose logs -f api
```

---

### Step 5: Configure Production Firewall (Strict Exposure)

In production, only SSH and Web traffic should be reachable from the internet. PostgreSQL (port 5432) and Node API (port 3000) remain strictly private on the internal Docker network.

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
ufw enable
ufw status verbose
```

---

### Step 6: Enable Free HTTPS / SSL with Let's Encrypt

```bash
# Install Certbot
apt-get install -y certbot

# Issue certificate for your domain (e.g., api.yourdomain.et)
certbot certonly --webroot -w /var/www/routepass/backend/certbot/www -d api.yourdomain.et

# Reload Nginx inside Docker
docker compose exec nginx nginx -s reload
```

---

### Step 7: Verify VPS Deployment Health

```bash
# Check REST API Health
curl -i http://localhost/api/health

# Expected response:
# HTTP/1.1 200 OK
# {"status":"HEALTHY","service":"Transport Navigator Transit Server", ...}
```

---

## 📱 Part 2: Building & Installing the Android APK

### Step 1: Configure VPS URL in Android App
In `app/src/main/java/com/example/data/api/ApiClient.kt`, configure your VPS URL:
```kotlin
// For Local Testing with Emulator:
// private var baseUrl: String = "http://10.0.2.2:3000/"

// For Production VPS:
private var baseUrl: String = "http://YOUR_VPS_IP/" // or "https://api.yourdomain.et/"
```

You can also switch the URL dynamically in the app via `ApiClient.setBaseUrl("http://YOUR_VPS_IP/")`.

---

### Step 2: Build Android Debug APK
Run the Gradle assemble task from the project root:

```bash
gradle :app:assembleDebug
```

The compiled APK will be generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

### Step 3: Run Local Automated Unit & JVM Tests
```bash
gradle :app:testDebugUnitTest
```

---

### Step 4: Install APK on Android Device or Emulator

#### Via ADB (Android Debug Bridge):
```bash
# 1. Connect Android phone via USB with USB Debugging enabled
adb devices

# 2. Install APK onto connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Launch the app
adb shell am start -n com.aistudio.transportnavigator.edxpkq/com.example.MainActivity
```

#### Via Direct Download:
1. Upload `app-debug.apk` to your VPS or cloud storage.
2. Open the download link on your Android smartphone browser.
3. Tap the downloaded APK and confirm "Install unknown apps" to complete installation.

---

## 🛠️ Management & Monitoring Commands

| Action | Command |
| :--- | :--- |
| **View API Logs** | `docker compose logs -f api` |
| **View Database Logs** | `docker compose logs -f db` |
| **View Nginx Logs** | `docker compose logs -f nginx` |
| **Restart Stack** | `docker compose restart` |
| **Stop All Containers** | `docker compose down` |
| **Execute Postgres Shell** | `docker compose exec db psql -U routepass -d routepass_db` |
| **Run Backend Tests** | `node backend/test/api-test.js` |
