# RoutePass (Transport Navigator) 🚍🇪🇹

**Real Multi-Device Scheduled Transit Management & Telebirr Ticketing System**  
Built for Addis Ababa, Ethiopia. Connects commuters, drivers, and transit operators in real-time across Android devices, a production VPS backend, private PostgreSQL database, and WebSocket live telemetry.

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

## 🚀 Key System Features

1. **True Multi-Device Online Synchronization**
   - Retrofit & OkHttp connect Android clients directly to the VPS backend.
   - Room operates strictly as an offline cache.
   - Any passenger registering or paying on one device is immediately visible to drivers and admins on other devices.

2. **Role Isolation & Secure Authentication**
   - **Passenger Portal**: Route selection, schedule viewing, Telebirr pass activation, digital QR pass.
   - **Transporter Console**: Route trip initialization, stop arrival broadcasting, QR boarding scanner, vehicle capacity enforcement.
   - **Operator Center (Admin)**: Full oversight of routes, vehicles, driver assignments, subscriptions, payment logs, complaints, and audit trails.
   - Admin registration is strictly protected by `ADMIN_REGISTRATION_SECRET`.
   - Passwords secured using BCrypt (salt rounds 10). JWT tokens for stateless authorization.

3. **Subscription Lifecycle & Telebirr Payments**
   - Commuter registration creates a **PENDING** subscription with **UNPAID** status.
   - QR boarding passes are **never released** until payment is verified (`ACTIVE` + `PAID`).
   - Server-side Telebirr payments support **idempotency keys** to prevent duplicate charges.
   - **Zero Client-Side PIN Storage**: Commuter Telebirr PIN is never collected or stored in Android.

4. **Cryptographic Server-Signed QR Passes**
   - QR tokens are HMAC-SHA256 signed on the server (`RP1:<subId>:<passengerId>:<routeId>:<expiry>:<signature>`).
   - All client-side signing secrets removed.
   - Tamper-proof and verifiable only by the backend.

5. **Atomic Vehicle Capacity Enforcement**
   - Standard capacities strictly enforced:
     - **MINIVAN**: 8 seats
     - **MINIBUS**: 14 seats
     - **HIGER**: 24 seats
     - **ANBESSA**: 30 seats
   - Server validates capacity inside an atomic PostgreSQL transaction with row-level locking (`SELECT ... FOR UPDATE`), preventing race conditions when multiple drivers scan simultaneously.

6. **Live GPS Tracking via WebSocket**
   - Transporters stream real-time GPS telemetry (`latitude`, `longitude`, `speed`, `currentStop`).
   - Server broadcasts coordinates to connected commuter devices in real-time.
   - Fake ETA and mock coordinates removed in favor of live telemetry.

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
│   │   ├── server.js                 # Express & WebSocket Server
│   │   ├── db.js                     # PostgreSQL & SQLite Data Layer
│   │   ├── middleware/auth.js        # JWT Authentication & Role Gates
│   │   └── routes/                   # Auth, Routes, Subscriptions, Checkins, Trips, Admin
│   ├── test/api-test.js              # Comprehensive Integration Test Suite
│   ├── init-db.sql                   # PostgreSQL Schema & Seed Data
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

## 🧪 Quick Test Run

### 1. Test VPS Backend
```bash
cd backend
npm install
node test/api-test.js
```

### 2. Build Android App
```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```
