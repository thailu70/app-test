# Transport Navigator - Production VPS Backend

Production REST API and Real-Time WebSocket Telemetry Server for the **Transport Navigator** Ethiopian Scheduled Transit Management System.

Built with Node.js 22, Express, Helmet, WebSockets, and embedded SQLite for zero-configuration, high-performance deployment on any Linux Virtual Private Server (VPS).

---

## Features

- **Isolated Role Architecture**: Strict authentication and authorization for Passengers, Transporters/Drivers, and Transit Operators/Admins. No cross-viewing or unauthorized data access.
- **Dynamic Subscription Engine**:
  - Automatically notifies passengers: *"The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass."*
  - Instant activation upon Telebirr checkout.
  - Cryptographically secure dynamic QR boarding tokens.
- **Vehicle Type Capacity Limit Enforcement**:
  - Automatic seat limits based on fleet vehicle type:
    - **Minivan**: 8 seats
    - **Minibus Taxi**: 14 seats
    - **Higer Bus**: 24 seats
    - **Anbessa City Bus**: 30+ seats
  - Driver scanner automatically blocks passenger boarding when full with `DENIED_CAPACITY_FULL`.
- **Live GPS Vehicle Telemetry**:
  - WebSocket (`ws://your-vps:3000/ws`) streaming vehicle positions, stop check-ins, and passenger occupancy alerts in real-time.
- **Offline Sync Gateway**:
  - Mobile apps can queue check-ins and payments offline and sync seamlessly when online.
- **Embedded Persistent Storage**:
  - Zero database setup required. Auto-initializes tables and pre-seeds Addis Ababa transit routes (Bole-Merkato, Megenagna-Torhailoch, Mexico-Saris), stops, vehicles, and test credentials.

---

## VPS Requirements & Sizing

| Component | Minimum | Recommended |
|---|---|---|
| **OS** | Ubuntu 20.04 / 22.04 / 24.04 LTS, Debian 11/12 | Ubuntu 24.04 LTS |
| **RAM** | 512 MB | 1 GB - 2 GB |
| **CPU** | 1 vCPU | 1 - 2 vCPU |
| **Disk** | 5 GB SSD | 10 GB+ SSD |
| **Cloud Providers** | Any (DigitalOcean, Hetzner, Linode, AWS EC2, Scaleway, Contabo) | Any |

---

## Deployment Option 1: 1-Click Automated Script (Recommended)

Upload the `backend/` folder to your VPS and run:

```bash
# 1. SSH into your VPS
ssh root@YOUR_VPS_IP

# 2. Upload or clone backend folder to /var/www/transport-backend
# (or upload using scp / rsync / git)

# 3. Run the installer
cd backend
chmod +x deploy-vps.sh
sudo bash deploy-vps.sh
```

The script will automatically:
1. Update system packages.
2. Install Node.js 22 LTS.
3. Configure UFW firewall rules for ports `80`, `443`, and `3000`.
4. Install all production npm dependencies.
5. Generate a cryptographically secure random `JWT_SECRET`.
6. Configure and start the `transport-backend` systemd service.
7. Verify API health at `http://YOUR_VPS_IP:3000/api/health`.

---

## Deployment Option 2: Docker Compose

If your VPS has Docker installed:

```bash
cd backend

# 1. Start in detached mode
docker compose up -d --build

# 2. Check container logs
docker compose logs -f

# 3. Verify healthcheck
curl http://localhost:3000/api/health
```

Database files are persisted in `./data/transport.db` on your host.

---

## Deployment Option 3: Manual Systemd Service

```bash
# 1. Install Node.js 22
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs git

# 2. Install dependencies
cd backend
npm install --omit=dev

# 3. Create environment file
cp .env.example .env
nano .env # Set your JWT_SECRET

# 4. Copy systemd service file
sudo cp transport-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now transport-backend

# 5. Check service status
sudo systemctl status transport-backend
```

---

## Production Nginx & Free SSL (Let's Encrypt) Setup

To connect your custom domain (e.g. `api.transport.et`):

```bash
# 1. Install Nginx and Certbot
sudo apt-get install -y nginx certbot python3-certbot-nginx

# 2. Copy Nginx configuration
sudo cp nginx.conf /etc/nginx/sites-available/transport-backend
sudo ln -s /etc/nginx/sites-available/transport-backend /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx

# 3. Obtain free SSL certificate
sudo certbot --nginx -d api.yourdomain.et
```

---

## Connecting the Android App to your VPS

In your Android app or `.env`:
Set your VPS endpoint:

```properties
# Mobile App .env or BuildConfig
SERVER_URL=http://YOUR_VPS_IP:3000
# Or with SSL:
# SERVER_URL=https://api.yourdomain.et
```

---

## REST API Reference

### 1. Authentication (`/api/auth`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `POST` | `/api/auth/register` | Register Passenger, Driver, or Operator | Public |
| `POST` | `/api/auth/login` | Login with phone and password/PIN | Public |
| `GET` | `/api/auth/me` | Fetch authenticated user profile | Bearer Token |

**Pre-seeded Test Credentials**:
- **Passenger**: Phone `0911223344` / Password `1234`
- **Driver**: Phone `0922334455` / Password `1234`
- **Operator/Admin**: Phone `0900000000` / Password `1234`

### 2. Routes & Stops (`/api/routes`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `GET` | `/api/routes` | List all active transit lines | Public |
| `GET` | `/api/routes/:id` | Get route details with ordered stops | Public |
| `POST` | `/api/routes` | Create new transit route | `ADMIN` |
| `DELETE` | `/api/routes/:id` | Remove route | `ADMIN` |

### 3. Subscriptions & Telebirr (`/api/subscriptions`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `GET` | `/api/subscriptions/my-status` | Get subscription status & dynamic QR pass | `PASSENGER` |
| `POST` | `/api/subscriptions/subscribe` | Select transit route for pass | `PASSENGER` |
| `POST` | `/api/subscriptions/telebirr/pay` | Pay fare with Telebirr and activate pass | `PASSENGER` |
| `GET` | `/api/subscriptions/verify-qr/:token` | Validate QR token authenticity | Authenticated |

### 4. Vehicles & Capacity Limits (`/api/vehicles`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `GET` | `/api/vehicles` | List fleet vehicles & occupancy rates | Public |
| `GET` | `/api/vehicles/:id` | Get vehicle details | Public |
| `PATCH` | `/api/vehicles/:id/type` | Set vehicle type (8/14/24/30) & limit | `DRIVER` / `ADMIN` |
| `POST` | `/api/vehicles/:id/location` | Broadcast GPS telemetry via WebSocket | `DRIVER` |

### 5. Check-ins & Attendance (`/api/checkins`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `POST` | `/api/checkins/scan` | Driver scans passenger QR boarding pass with strict capacity enforcement | `DRIVER` |
| `GET` | `/api/checkins/trip/:tripId` | Get passenger attendance for trip | Authenticated |

### 6. Notifications & Broadcasts (`/api/notifications`)

| Method | Endpoint | Description | Role Required |
|---|---|---|---|
| `GET` | `/api/notifications` | Fetch notifications filtered for caller role | Authenticated |
| `POST` | `/api/notifications/broadcast` | Dispatch transit alerts to ALL, PASSENGERS, or TRANSPORTERS | `ADMIN` |

### 7. Real-Time WebSocket Stream (`/ws`)

Connect to `ws://YOUR_VPS_IP:3000/ws` to receive live events:
- `VEHICLE_LOCATION_UPDATE`
- `PASSENGER_BOARDED`
- `NOTIFICATION_BROADCAST`

---

## Server Maintenance Commands

```bash
# Check service status
sudo systemctl status transport-backend

# View live application logs
sudo journalctl -u transport-backend -f

# Restart application
sudo systemctl restart transport-backend

# Backup SQLite database
cp /var/www/transport-backend/data/transport.db /var/backups/transport_$(date +%F).db
```
