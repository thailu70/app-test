# RoutePass (Transport Navigator) 🚍🇪🇹

**Real Multi-Device Scheduled Transit Management & Telebirr Ticketing System**  
Built for Addis Ababa, Ethiopia. Connects commuters, drivers, and transit operators in real-time across Android devices, a production VPS backend, private PostgreSQL database, and WebSocket live telemetry.

---

## 🖥️ Production Environment & Specifications
- **Target VPS**: `62.72.19.170` (Ubuntu 22.04 LTS)
- **Specifications**: 8 GB RAM, 2 vCPUs
- **Architecture**: Android App (Compose + Retrofit) ➔ Nginx HTTPS / WSS ➔ Node.js Express (:3000) ➔ Private PostgreSQL 16 (Port 5432)
- **Network Security**: Strict UFW firewall exposing ONLY ports 22, 80, and 443 in production.

---

## 🏛️ System Architecture

```text
 ┌──────────────────────────┐    ┌──────────────────────────┐    ┌──────────────────────────┐
 │     Passenger Phone      │    │     Driver / Van Phone   │    │      Admin Console       │
 │   - Commuter Portal      │    │   - Transporter Console  │    │   - Operator Center      │
 │   - Telebirr Checkout    │    │   - Camera QR Scanner    │    │   - Fleet & Routes       │
 │   - Live Vehicle Map     │    │   - Live GPS Uploader    │    │   - Audit & Complaints   │
 └─────────────┬────────────┘    └────────────┬─────────────┘    └────────────┬─────────────┘
               │                              │                               │
               │ HTTPS REST & WSS Telemetry   │ HTTPS REST & WSS Telemetry    │ HTTPS REST
               └──────────────────────┬───────┴───────────────────────────────┘
                                      │
                                      ▼
                        ┌───────────────────────────┐
                        │   Nginx Reverse Proxy     │
                        │   Ports 80 & 443 (SSL)    │
                        └─────────────┬─────────────┘
                                      │ Internal Network
                                      ▼
                        ┌───────────────────────────┐
                        │  Node.js / Express API    │
                        │  WebSocket Server (:3000) │
                        └─────────────┬─────────────┘
                                      │ Private Network
                                      ▼
                        ┌───────────────────────────┐
                        │   PostgreSQL 16 Database  │
                        │   (Atomic Transactions &  │
                        │    FOR UPDATE Locks)      │
                        └───────────────────────────┘
```

---

## 🚀 Key Audited & Enforced System Features

1. **True Multi-Device Online Synchronization**
   - Retrofit & OkHttp connect Android clients directly to the VPS backend at `62.72.19.170`.
   - Room operates strictly as an offline cache.
   - Any passenger registering or paying on one phone is immediately visible to drivers and admins on other devices.
   - Local fallback authentication bypasses have been completely removed.

2. **Role Isolation & Secure Authentication**
   - Three independent portals: Passenger, Driver, Admin.
   - Admin registration is strictly protected by `ADMIN_REGISTRATION_SECRET` (unrestricted public admin registration is forbidden).
   - Passwords secured using BCrypt (salt rounds 10). JWT tokens for stateless authorization.
   - In production, static fallback secrets are rejected at startup.

3. **Subscription Lifecycle & Server-Side Telebirr Payments**
   - Commuter registration creates a **PENDING** subscription with **UNPAID** status.
   - QR boarding passes are **never released** until payment is verified (`ACTIVE` + `PAID`).
   - Server-side Telebirr payments support **idempotency keys** to prevent duplicate charges.
   - **Zero Client-Side PIN Storage**: Commuter Telebirr PIN is never collected, requested, or transmitted to the application server. PIN authorization is strictly handled by Telebirr USSD/app.

4. **Cryptographic Server-Signed QR Passes**
   - QR tokens are HMAC-SHA256 signed on the server (`RP1:<subId>:<passengerId>:<routeId>:<expiry>:<signature>`).
   - All client-side signing secrets removed.
   - Driver camera scans are validated server-side for expiry, subscription status, route match, and duplicate check-in.

5. **Atomic Vehicle Capacity Enforcement**
   - Standard vehicle capacities:
     - **MINIVAN**: 8 seats
     - **MINIBUS**: 14 seats
     - **HIGER**: 24 seats
     - **ANBESSA**: 30 seats
   - Server validates capacity inside an atomic PostgreSQL transaction with row-level locking (`SELECT ... FOR UPDATE`), preventing race conditions when multiple drivers scan simultaneously.

6. **Secure GPS Tracking & Rate Limiting**
   - Transporters stream real-time GPS telemetry (`latitude`, `longitude`, `speed`, `currentStop`).
   - Authenticated WebSockets with JWT token verification.
   - Rate limiting: strictly 1 GPS update per second per active driver.
   - Coordinate validation: latitudes (-90 to +90) and longitudes (-180 to +180).
   - In-memory buffering eliminates unnecessary database write bottlenecks during high driver concurrency.

---

## 🧪 Verified Test Suites

### 1. Backend Core Integration Tests (13/13 Passing)
```bash
cd backend
npm test
```
Tests health check, role authentication, admin protection, subscription lifecycles, Telebirr idempotency, real driver trips, boarding checks, duplicate prevention, and atomic capacity limits.

### 2. Real-Time GPS Concurrency Load Tests (100% Passing)
```bash
cd backend
npm run test:gps
```
Simulates 10, 25, 50, and 100 concurrent active drivers emitting 1 GPS update per second over authenticated WebSockets. All tiers achieve 100% throughput with 0 rate limit errors and zero database bottlenecks.

### 3. Database Backup Utility
```bash
cd backend
npm run backup
```
Creates safe timestamped backups before migrations in `backend/data/backups/`.

### 4. Android Build & Unit Tests
```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

---

## 📂 Project Structure

```text
├── app/                              # Android Application (Kotlin & Jetpack Compose)
│   ├── src/main/java/com/example/
│   │   ├── data/api/                 # Retrofit API Service, OkHttp Client & WebSocket
│   │   ├── data/repository/          # TransportRepository (VPS Backend source of truth)
│   │   ├── data/entity/              # Room offline cache entities
│   │   ├── core/payment/             # TelebirrGateway (Server-side checkout)
│   │   ├── core/qr/                  # QrSecurityEngine (Server-verified scanner)
│   │   ├── ui/screens/               # Compose Screens (Auth, Passenger, Driver, Admin)
│   │   └── ui/viewmodel/             # MainViewModel & state flows
├── backend/                          # Production VPS Backend
│   ├── src/
│   │   ├── server.js                 # Express & WebSocket Server (buffered GPS & telemetry)
│   │   ├── db.js                     # PostgreSQL & SQLite Data Layer
│   │   ├── backup.js                 # Database backup utility
│   │   ├── middleware/auth.js        # JWT Authentication & Role Gates
│   │   └── routes/                   # Auth, Routes, Subscriptions, Checkins, Trips, Admin
│   ├── test/api-test.js              # 13 Integration Test Scenarios
│   ├── test/gps-load-test.js         # 10, 25, 50, 100 Driver GPS Concurrency Tests
│   ├── init-db.sql                   # PostgreSQL Production Schema & Seed Data
│   ├── docker-compose.yml            # Multi-container production stack
│   ├── Dockerfile                    # Node.js Alpine container
│   ├── nginx.conf                    # Nginx SSL Reverse Proxy & WebSocket Config
│   ├── deploy-vps.sh                 # 1-Click Ubuntu VPS Deployment Script
│   └── .env.example                  # Environment Variables Template
├── API.md                            # Complete REST & WebSocket API Documentation
├── DEPLOYMENT.md                     # Step-by-Step VPS & Android Setup Guide
└── README.md                         # Project Overview
```

---

## 📖 Deployment Quick Reference
See [`DEPLOYMENT.md`](DEPLOYMENT.md) for complete instructions.
See [`API.md`](API.md) for full REST & WebSocket API endpoints.
