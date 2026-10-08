# RoutePass API Specification 📡

Production REST & WebSocket API specification for RoutePass (Ethiopian Scheduled Transit System).

---

## 🔐 Authentication & Global Headers

All protected endpoints require the HTTP `Authorization` header containing a valid JWT token:

```http
Authorization: Bearer <jwt_token>
Content-Type: application/json
Accept: application/json
```

---

## 1. 🔑 Authentication Endpoints (`/api/auth`)

### 1.1 Register New Account
`POST /api/auth/register`

- **Access**: Public
- **Note**: Admin accounts require a valid `adminSecret`.
- **Note**: Commuter subscriptions created at registration start as **PENDING** and **UNPAID**. No QR pass is issued until paid.

**Request Body:**
```json
{
  "fullName": "Dawit Mengistu",
  "phone": "+251911223344",
  "email": "dawit@gmail.com",
  "password": "secure_password",
  "role": "PASSENGER", // "PASSENGER" | "DRIVER" | "ADMIN"
  "appliedRouteId": "route_bole_merkato",
  "appliedRouteName": "Bole - Merkato Express",
  "adminSecret": "" // Required only when role is "ADMIN"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "usr_pas_a91b2c",
    "role": "PASSENGER",
    "fullName": "Dawit Mengistu",
    "phone": "+251911223344",
    "status": "ACTIVE"
  },
  "subscription": {
    "id": "sub_usr_pas_a91b2c_m5k291",
    "routeId": "route_bole_merkato",
    "status": "PENDING",
    "paymentStatus": "UNPAID",
    "daysRemaining": 0,
    "qrToken": null
  },
  "message": "Account created. Subscription is PENDING payment via Telebirr."
}
```

---

### 1.2 User Login
`POST /api/auth/login`

- **Access**: Public
- **Phone Formats**: Accepts both `+2519...` and `09...` formats.

**Request Body:**
```json
{
  "phone": "+251911223344",
  "password": "secure_password",
  "role": "PASSENGER" // Optional: enforces portal isolation
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "usr_pas_a91b2c",
    "role": "PASSENGER",
    "fullName": "Dawit Mengistu",
    "phone": "+251911223344"
  }
}
```

---

## 2. 🎫 Commuter Subscriptions (`/api/subscriptions`)

### 2.1 Get My Subscription Status
`GET /api/subscriptions/my-status`

- **Access**: `PASSENGER` role required.
- **Rule**: `qrToken` is returned **ONLY** when `status === 'ACTIVE'` and `paymentStatus === 'PAID'`.

**Response (When Active & Paid):**
```json
{
  "success": true,
  "isSubscribed": true,
  "status": "ACTIVE",
  "paymentStatus": "PAID",
  "subscription": {
    "id": "sub_usr_pas_123",
    "routeId": "route_bole_merkato",
    "routeName": "Bole - Merkato Express",
    "daysRemaining": 30,
    "qrToken": "RP1:sub_123:usr_456:route_789:1795000000:7a9f2bc8",
    "morningSchedule": "06:30",
    "eveningSchedule": "17:30"
  }
}
```

**Response (When Pending / Unpaid):**
```json
{
  "success": true,
  "isSubscribed": false,
  "status": "PENDING",
  "paymentStatus": "UNPAID",
  "qrToken": null,
  "message": "The passenger is not subscribed. Please pay and subscribe for the selected route to activate your pass.",
  "messageAm": "ተሳፋሪው አልተመዘገበም። እባክዎ ለተመረጠው መስመር በቴሌብር ከፍለው ይመዝገቡ።"
}
```

---

### 2.2 Telebirr Server-Side Payment Checkout
`POST /api/subscriptions/telebirr/pay`

- **Access**: `PASSENGER` role required.
- **Security**: Never requests or stores Telebirr PIN on client.
- **Idempotency**: Supports `x-idempotency-key` header to prevent duplicate charges.

**Request Body:**
```json
{
  "routeId": "route_bole_merkato",
  "phone": "+251911223344",
  "idempotencyKey": "IDEM-RANDOM-UUID-12345"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "message": "Telebirr payment verified. Subscription is now ACTIVE!",
  "paymentMode": "TEST",
  "transaction": {
    "id": "tx_tb_9a8b7c",
    "referenceNumber": "TB-ET-20261008-8921",
    "amountEtb": 2500.0,
    "provider": "Telebirr",
    "status": "COMPLETED"
  },
  "subscription": {
    "id": "sub_usr_pas_123",
    "status": "ACTIVE",
    "paymentStatus": "PAID",
    "daysRemaining": 30,
    "qrToken": "RP1:sub_123:usr_456:route_789:1795000000:7a9f2bc8"
  }
}
```

---

## 3. 🚌 Driver Trips & Capacity Validation

### 3.1 Start Trip
`POST /api/trips/start`

- **Access**: `DRIVER` role required.

**Request Body:**
```json
{
  "routeId": "route_bole_merkato",
  "direction": "OUTBOUND"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "trip": {
    "id": "trip_m5k891_ab12",
    "routeId": "route_bole_merkato",
    "direction": "OUTBOUND",
    "currentStop": "Bole Medhanialem",
    "currentOccupancy": 0,
    "capacityLimit": 24,
    "status": "IN_PROGRESS"
  }
}
```

---

### 3.2 Driver QR Boarding Scan
`POST /api/checkins/scan`

- **Access**: `DRIVER` role required.
- **Validation Rules**:
  1. Validates server-side HMAC signature.
  2. Confirms active & paid subscription.
  3. Enforces atomic vehicle capacity limit (`SELECT ... FOR UPDATE`).
  4. Blocks duplicate boarding on the same trip.

**Request Body:**
```json
{
  "qrToken": "RP1:sub_123:usr_456:route_789:1795000000:7a9f2bc8",
  "tripId": "trip_m5k891_ab12",
  "currentStop": "Bole Atlas"
}
```

**Response (When Boarding Approved - 200 OK):**
```json
{
  "success": true,
  "status": "VERIFIED_BOARDED",
  "message": "Commuter boarding pass verified. Boarding granted.",
  "passenger": {
    "id": "usr_pas_456",
    "name": "Dawit Mengistu",
    "daysRemaining": 30
  },
  "occupancy": {
    "current": 1,
    "maxCapacity": 24,
    "availableSeats": 23,
    "isFull": false
  }
}
```

**Response (When Vehicle Full - 409 Conflict):**
```json
{
  "success": false,
  "status": "DENIED_CAPACITY_FULL",
  "error": "VEHICLE FULL: Capacity limit reached (24/24 seats). Cannot board additional passengers based on HIGER_24 vehicle limit.",
  "errorAm": "ተሽከርካሪው ሞልቷል፡ የተሳፋሪ ገደብ ተደርሷል (24/24)። ተጨማሪ ተሳፋሪ መጫን አይቻልም።"
}
```

**Response (When Duplicate Check-in - 409 Conflict):**
```json
{
  "success": false,
  "status": "ALREADY_CHECKED_IN",
  "error": "Passenger has already boarded this scheduled trip."
}
```

---

## 4. 🛰️ Real-Time Telemetry WebSocket (`ws://<vps_ip>/ws`)

Connect to the WebSocket endpoint for live vehicle GPS tracking and boarding notifications.

### 4.1 Driver Sends Live GPS Telemetry
Client sends to Server:
```json
{
  "type": "DRIVER_LOCATION_UPDATE",
  "vehicleId": "veh_higer_aa_34921",
  "driverId": "usr_drv_kassahun",
  "tripId": "trip_m5k891_ab12",
  "latitude": 9.006214,
  "longitude": 38.780125,
  "speed": 34.2,
  "currentStop": "Bole Atlas"
}
```

### 4.2 Server Broadcasts Vehicle Location to Commuters
Server broadcasts to all connected Passenger devices:
```json
{
  "type": "VEHICLE_LOCATION_UPDATE",
  "vehicleId": "veh_higer_aa_34921",
  "latitude": 9.006214,
  "longitude": 38.780125,
  "speed": 34.2,
  "currentStop": "Bole Atlas",
  "timestamp": "2026-10-08T19:45:00.000Z"
}
```

### 4.3 Server Broadcasts Passenger Boarding Event
```json
{
  "type": "PASSENGER_BOARDED",
  "checkinId": "chk_9a12b3",
  "passengerName": "Dawit Mengistu",
  "stopName": "Bole Atlas",
  "vehicleId": "veh_higer_aa_34921",
  "currentOccupancy": 12,
  "capacityLimit": 24,
  "isFull": false
}
```

---

## 5. 🛡️ Admin & Fleet Management (`/api/admin`)

- `GET /api/admin/stats`: Real-time system overview (revenue, passengers, check-ins, vehicles).
- `GET /api/admin/drivers`: List all registered commercial transporters.
- `GET /api/admin/subscriptions`: Full subscription registry.
- `GET /api/admin/payments`: Financial audit ledger.
- `GET /api/admin/complaints`: Commuter service feedback and resolution statuses.
- `GET /api/admin/audit-logs`: System event audit trail.

---

## 6. 💓 System Health Check (`/api/health`)
`GET /api/health`

**Response (200 OK):**
```json
{
  "status": "HEALTHY",
  "service": "Transport Navigator Transit Server",
  "uptimeSeconds": 1420,
  "database": {
    "status": "CONNECTED",
    "usersCount": 5,
    "routesCount": 3,
    "vehiclesCount": 4,
    "checkinsCount": 12
  }
}
```
