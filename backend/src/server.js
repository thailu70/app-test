/**
 * Transport Navigator - Production VPS Backend Server
 * Ethiopian Scheduled Transit Management System
 */

require('dotenv').config();
const http = require('http');
const path = require('path');
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const morgan = require('morgan');
const rateLimit = require('express-rate-limit');
const { WebSocketServer, WebSocket } = require('ws');
const { DB } = require('./db');
const { resolveActivePrincipal } = require('./middleware/auth');

if (process.env.NODE_ENV === 'production') {
  if (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 32) {
    throw new Error('[FATAL CONFIGURATION ERROR] JWT_SECRET must be at least 32 characters in production.');
  }
  if (process.env.PAYMENT_MODE !== 'PRODUCTION') {
    throw new Error('[FATAL CONFIGURATION ERROR] PAYMENT_MODE must be explicitly set to PRODUCTION.');
  }
  if (!process.env.QR_SIGNING_KEY || process.env.QR_SIGNING_KEY.length < 32) {
    throw new Error('[FATAL CONFIGURATION ERROR] QR_SIGNING_KEY must be at least 32 characters in production.');
  }
}

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

// The API is private behind one Nginx reverse proxy. Preserve the real client IP for
// rate limiting and access logs, without trusting an arbitrary chain of forwarded headers.
app.set('trust proxy', 1);

// Security & Middlewares
app.use(helmet({
  contentSecurityPolicy: false // Allow WebSocket handshakes & dev proxies
}));

const allowedOrigins = (process.env.ALLOWED_ORIGINS || '')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);

app.use(cors({
  origin(origin, callback) {
    // Native clients commonly omit Origin. Browser clients must be explicitly allowed in production.
    if (!origin || process.env.NODE_ENV !== 'production' || allowedOrigins.includes(origin)) {
      return callback(null, true);
    }
    return callback(new Error('Origin not allowed.'));
  },
  methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Requested-With']
}));

app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: true }));

if (process.env.NODE_ENV !== 'test') {
  // Security Policy: Never log sensitive tokens, PINs, passwords, or secrets in HTTP request logs
  morgan.token('safe-url', (req) => {
    return (req.originalUrl || req.url).replace(/([?&](?:token|password|pin|telebirrPin|secret|key)=)[^&]+/gi, '$1[REDACTED]');
  });
  app.use(morgan(':remote-addr - :remote-user [:date[clf]] ":method :safe-url HTTP/:http-version" :status :res[content-length]'));
}

// Rate Limiting
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 1000, // Max 1000 requests per 15 mins per IP
  standardHeaders: true,
  legacyHeaders: false,
  message: {
    success: false,
    error: 'Too many requests from this IP. Please try again later.'
  }
});
app.use('/api/', limiter);

// Welcome & API Status
app.get('/', async (req, res) => {
  res.json({
    service: 'Transport Navigator VPS API',
    region: 'Ethiopia (Addis Ababa)',
    status: 'ONLINE',
    version: '1.0.0',
    documentation: '/api/health',
    endpoints: {
      auth: '/api/auth',
      routes: '/api/routes',
      subscriptions: '/api/subscriptions',
      vehicles: '/api/vehicles',
      checkins: '/api/checkins',
      notifications: '/api/notifications',
      sync: '/api/sync'
    }
  });
});

// VPS System Health Check
app.get('/api/health', async (req, res) => {
  // Public liveness only. Never disclose passenger/operational counts or database errors.
  res.json({ status: 'HEALTHY', service: 'RoutePass API', timestamp: new Date().toISOString() });
});

app.get('/api/ready', async (req, res) => {
  try {
    await DB.prepare('SELECT 1 AS ok').get();
    res.json({ status: 'READY' });
  } catch (err) {
    console.error('[Readiness] database query failed:', err);
    res.status(503).json({ status: 'NOT_READY' });
  }
});

// Mount Route Modules
app.use('/api/auth', require('./routes/auth'));
app.use('/api/companies', require('./routes/companies'));
app.use('/api/companies', require('./routes/company-portal'));
app.use('/api/routes', require('./routes/routes'));
app.use('/api/subscriptions/telebirr', require('./routes/telebirr'));
app.use('/api/subscriptions', require('./routes/subscriptions'));
app.use('/api/vehicles', require('./routes/vehicles'));
app.use('/api/trips', require('./routes/trips'));
app.use('/api/checkins', require('./routes/checkins'));
app.use('/api/admin', require('./routes/company-admin'));
app.use('/api/admin', require('./routes/admin'));
app.use('/api/complaints', require('./routes/complaints'));
app.use('/api/notifications', require('./routes/notifications'));
app.use('/api/sync', require('./routes/sync'));

// Browser portals are same-origin. External scripts let their route-specific CSP reject inline JS.
app.use('/company', (req, res, next) => {
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' wss:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
  res.setHeader('Cache-Control', 'no-store');
  next();
}, express.static(path.join(__dirname, '..', 'public', 'company'), { index: 'index.html', maxAge: 0, etag: false }));
app.use('/admin', (req, res, next) => {
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' wss:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
  res.setHeader('Cache-Control', 'no-store');
  next();
}, express.static(path.join(__dirname, '..', 'public', 'admin'), { index: 'index.html', maxAge: 0, etag: false }));

// 404 Handler
app.use(async (req, res) => {
  res.status(404).json({
    success: false,
    error: 'Endpoint not found.'
  });
});

// Global Error Handler
app.use((err, req, res, next) => {
  console.error('[Server Error]:', err);
  res.status(err.status || 500).json({
    success: false,
    error: 'Internal server error.'
  });
});

// HTTP & WebSocket Server Setup
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

const clients = new Set();
// In-memory cache for live vehicle locations to avoid unnecessary DB writes on every second update
const inMemoryVehicleLocations = new Map();
// Rate limiter: 1 GPS update per second per active driver
const driverLastGpsTime = new Map();

// Periodic flush of vehicle locations to database every 30 seconds
const FLUSH_INTERVAL_MS = 30000;
const locationFlushTimer = setInterval(async () => {
  if (inMemoryVehicleLocations.size === 0) return;
  const snapshot = Array.from(inMemoryVehicleLocations.entries());
  try {
    await DB.transaction(async (tx) => {
      for (const [vehicleId, loc] of snapshot) {
        const activeTrip = await tx.prepare("SELECT id FROM trips WHERE id=? AND vehicleId=? AND driverId=? AND routeId=? AND status='IN_PROGRESS'").get(loc.tripId, vehicleId, loc.driverId, loc.routeId);
        if (!activeTrip) continue;
        await tx.prepare(`
          INSERT INTO vehicle_live_locations (vehicle_id, latitude, longitude, speed, current_stop, updated_at)
          VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
          ON CONFLICT (vehicle_id) DO UPDATE SET
            latitude = excluded.latitude,
            longitude = excluded.longitude,
            speed = excluded.speed,
            current_stop = excluded.current_stop,
            updated_at = CURRENT_TIMESTAMP
        `).run(vehicleId, loc.latitude, loc.longitude, loc.speed, loc.currentStop);
        await tx.prepare(`
          UPDATE vehicles
          SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP
          WHERE id = ?
        `).run(loc.latitude, loc.longitude, vehicleId);
      }
    });
    for (const [vehicleId, loc] of snapshot) {
      if (inMemoryVehicleLocations.get(vehicleId)?.timestamp === loc.timestamp) inMemoryVehicleLocations.delete(vehicleId);
    }
  } catch (err) {
    // Keep the newest in-memory sample for the next retry if persistence is temporarily unavailable.
  }
}, FLUSH_INTERVAL_MS);

if (locationFlushTimer.unref) {
  locationFlushTimer.unref();
}

wss.on('connection', async (ws, req) => {
  // Avoid JWT query parameters: reverse proxies commonly log full request URLs.
  // The Android client authenticates using the first in-band AUTHENTICATE message.
  let token = null;
  if (req.headers['authorization']) {
    const parts = req.headers['authorization'].split(' ');
    if (parts.length === 2 && parts[0] === 'Bearer') {
      token = parts[1];
    }
  }

  // Check initial authentication state
  ws.token = token;
  try {
    ws.user = token ? await resolveActivePrincipal(token) : null;
  } catch (err) {
    ws.user = null;
  }
  ws.authenticated = Boolean(ws.user);

  clients.add(ws);
  console.log(`[WebSocket] Client connected. Auth: ${ws.authenticated ? ws.user.role : 'GUEST'}. Active clients: ${clients.size}`);

  ws.send(JSON.stringify({
    type: 'CONNECTION_ESTABLISHED',
    authenticated: ws.authenticated,
    user: ws.user ? { id: ws.user.id, role: ws.user.role } : null,
    message: ws.authenticated
      ? 'Authenticated to RoutePass Real-Time Telemetry Stream'
      : 'Connected as GUEST. Please send AUTHENTICATE with Bearer token for authorized feeds.',
    timestamp: new Date().toISOString()
  }));

  const authRefreshTimer = setInterval(async () => {
    if (!ws.authenticated || !ws.token) return;
    try {
      const principal = await resolveActivePrincipal(ws.token);
      if (!principal) {
        ws.authenticated = false;
        ws.user = null;
        ws.monitoredRouteId = null;
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'AUTHENTICATION_FAILED', error: 'Account is no longer active.' }));
      } else {
        ws.user = principal;
      }
    } catch (_) {
      ws.authenticated = false;
      ws.user = null;
      ws.monitoredRouteId = null;
    }
  }, 60000);
  if (authRefreshTimer.unref) authRefreshTimer.unref();

  ws.on('message', async (message) => {
    try {
      const data = JSON.parse(message);

      // Handle Ping / Pong Heartbeat
      if (data.type === 'PING') {
        return ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      }

      // Handle In-band Authentication
      if (data.type === 'AUTHENTICATE') {
        const decoded = await resolveActivePrincipal(data.token);
        if (decoded) {
          ws.token = data.token;
          ws.user = decoded;
          ws.authenticated = true;
          return ws.send(JSON.stringify({
            type: 'AUTHENTICATION_SUCCESS',
            role: decoded.role,
            userId: decoded.id,
            timestamp: Date.now()
          }));
        } else {
          ws.authenticated = false;
          ws.user = null;
          ws.token = null;
          ws.monitoredRouteId = null;
          return ws.send(JSON.stringify({
            type: 'AUTHENTICATION_FAILED',
            error: 'Invalid or expired token.',
            timestamp: Date.now()
          }));
        }
      }

      // Handle Route Subscription / Corridor Filter for Connected Commuter
      if (data.type === 'SUBSCRIBE_ROUTE' && data.routeId) {
        if (!ws.authenticated) {
          return ws.send(JSON.stringify({
            type: 'SUBSCRIPTION_REJECTED',
            reason: 'AUTHENTICATION_REQUIRED'
          }));
        }
        const principal = await resolveActivePrincipal(ws.token);
        if (!principal) {
          ws.authenticated = false;
          ws.user = null;
          ws.token = null;
          ws.monitoredRouteId = null;
          return ws.send(JSON.stringify({ type: 'SUBSCRIPTION_REJECTED', reason: 'ACCOUNT_INACTIVE' }));
        }
        ws.user = principal;
        if (ws.user.role !== 'PASSENGER') {
          return ws.send(JSON.stringify({ type: 'SUBSCRIPTION_REJECTED', reason: 'PASSENGER_ROLE_REQUIRED' }));
        }
        const activeSubscription = await DB.prepare(
          "SELECT id FROM subscriptions WHERE passengerId = ? AND routeId = ? AND subscriptionStatus = 'ACTIVE' AND paymentStatus = 'PAID' AND daysRemaining > 0"
        ).get(ws.user.id, data.routeId);
        if (!activeSubscription) {
          return ws.send(JSON.stringify({ type: 'SUBSCRIPTION_REJECTED', reason: 'ACTIVE_ROUTE_SUBSCRIPTION_REQUIRED' }));
        }
        ws.monitoredRouteId = data.routeId;
        return ws.send(JSON.stringify({
          type: 'ROUTE_SUBSCRIBED',
          routeId: data.routeId,
          timestamp: Date.now()
        }));
      }

      // Handle Driver GPS Location Update
      if (data.type === 'DRIVER_LOCATION_UPDATE') {
        // Derive identity only from the already verified token. Never trust data.driverId or data.token.
        const driverId = ws.user?.id;
        const userRole = ws.user?.role;

        if (!ws.authenticated || !driverId || userRole !== 'DRIVER') {
          return ws.send(JSON.stringify({
            type: 'GPS_REJECTED',
            reason: 'UNAUTHORIZED_DRIVER',
            message: 'Only authenticated drivers or administrators may transmit live GPS telemetry.'
          }));
        }

        // Re-check account state as well as ownership for already-open WebSocket sessions.
        const principal = await resolveActivePrincipal(ws.token);
        if (!principal || principal.role !== 'DRIVER') {
          ws.authenticated = false;
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'ACCOUNT_INACTIVE' }));
        }
        ws.user = principal;

        // Vehicle and route ownership are server-side facts, not client-controlled fields.
        const vehicleId = data.vehicleId;
        if (!vehicleId || typeof vehicleId !== 'string') {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'MISSING_VEHICLE_ID' }));
        }
        const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
        if (!vehicle) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'UNKNOWN_VEHICLE' }));
        }
        if (vehicle.driverId !== driverId) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'VEHICLE_NOT_ASSIGNED' }));
        }
        if (typeof data.tripId !== 'string' || !data.tripId) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'MISSING_TRIP_ID' }));
        }
        const activeTrip = await DB.prepare(
          "SELECT id, routeId FROM trips WHERE id = ? AND vehicleId = ? AND driverId = ? AND status = 'IN_PROGRESS'"
        ).get(data.tripId, vehicle.id, driverId);
        if (!activeTrip) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'ACTIVE_TRIP_NOT_ASSIGNED' }));
        }
        const routeId = activeTrip.routeId;

        // Validate Coordinates
        const lat = parseFloat(data.latitude);
        const lng = parseFloat(data.longitude);
        const speed = parseFloat(data.speed || 0);

        if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180 || !Number.isFinite(speed) || speed < 0 || speed > 300) {
          return ws.send(JSON.stringify({
            type: 'GPS_REJECTED',
            reason: 'INVALID_COORDINATES',
            message: 'Latitude must be between -90 and 90, Longitude between -180 and 180.'
          }));
        }

        // Rate Limit: Strictly 1 GPS update per second per active driver
        const now = Date.now();
        const driverKey = driverId || vehicleId;
        const lastTime = driverLastGpsTime.get(driverKey) || 0;
        if (now - lastTime < 950) {
          return ws.send(JSON.stringify({
            type: 'RATE_LIMIT_EXCEEDED',
            reason: 'MAX_1_UPDATE_PER_SECOND',
            message: 'GPS telemetry throttled: maximum 1 update per second allowed.'
          }));
        }
        driverLastGpsTime.set(driverKey, now);

        // Update in-memory location cache (avoid immediate DB write)
        inMemoryVehicleLocations.set(vehicleId, {
          latitude: lat,
          longitude: lng,
          speed: speed,
          currentStop: data.currentStop || '',
          tripId: activeTrip.id,
          routeId,
          driverId,
          companyId: vehicle.companyId || '',
          timestamp: now
        });

        // Route is derived from the authoritative vehicle record loaded above.

        // Broadcast real GPS location strictly to authorized recipients
        broadcastAuthorized({
          type: 'VEHICLE_LOCATION_UPDATE',
          vehicleId: vehicleId,
          routeId: routeId || '',
          driverId,
          companyId: vehicle.companyId || '',
          latitude: lat,
          longitude: lng,
          speed: speed,
          currentStop: data.currentStop || '',
          timestamp: new Date(now).toISOString()
        }, routeId);

        ws.send(JSON.stringify({
          type: 'GPS_ACK',
          vehicleId: vehicleId,
          timestamp: now
        }));
      }
    } catch (e) {
      // Ignore malformed payloads
    }
  });

  ws.on('close', () => {
    clearInterval(authRefreshTimer);
    clients.delete(ws);
    console.log(`[WebSocket] Client disconnected. Total active clients: ${clients.size}`);
  });

  ws.on('error', (err) => {
    console.error('[WebSocket Error]:', err.message);
    clearInterval(authRefreshTimer);
    clients.delete(ws);
  });
});

// Broadcast Helper: Distributes live telemetry strictly to authorized recipients
function broadcastAuthorized(payload, targetRouteId = null) {
  const routeId = targetRouteId || payload.routeId || null;
  const json = JSON.stringify(payload);
  for (const client of clients) {
    if (client.readyState !== WebSocket.OPEN) continue;

    // 1. Mandatory Authentication: Unauthenticated guests never receive live telemetry
    if (!client.authenticated || !client.user) continue;

    // 2. Per-Recipient Authorization:
    const role = (client.user.role || '').toUpperCase();
    if (role === 'ADMIN') {
      client.send(json);
    } else if (role === 'COMPANY') {
      if (!payload.companyId || client.user.companyId !== payload.companyId) continue;
      client.send(json);
    } else if (role === 'DRIVER') {
      if (!payload.driverId || client.user.id !== payload.driverId) continue;
      client.send(json);
    } else if (role === 'PASSENGER') {
      // Commuters receive GPS telemetry strictly for their authorized/monitored transit corridor
      const commuterCorridor = client.monitoredRouteId;
      if (!commuterCorridor || !routeId || commuterCorridor !== routeId) {
        // Drop broadcast: recipient is not authorized/subscribed to this vehicle's corridor
        continue;
      }
      if (payload.type === 'PASSENGER_BOARDED') {
        const commuterSafePayload = { ...payload };
        delete commuterSafePayload.passengerName;
        delete commuterSafePayload.passengerId;
        delete commuterSafePayload.passengerPhone;
        delete commuterSafePayload.checkinId;
        client.send(JSON.stringify(commuterSafePayload));
      } else {
        client.send(json);
      }
    }
  }
}

app.locals.broadcastWs = broadcastAuthorized;

async function backfillCompanyInvitationExpirations() {
  const query = DB.isPostgres
    ? "UPDATE transport_company_access_codes SET expires_at = CAST(created_at AS TIMESTAMP) + INTERVAL '7 days' WHERE expires_at IS NULL"
    : "UPDATE transport_company_access_codes SET expires_at = datetime(created_at, '+7 days') WHERE expires_at IS NULL";
  return DB.prepare(query).run();
}

// Apply additive schema migrations before accepting traffic. This keeps existing VPS databases
// compatible with newer GPS, company-portal, and route types without replacing their data.
async function startServer() {
  await DB.prepare(`
    CREATE TABLE IF NOT EXISTS transport_companies (
      id VARCHAR(64) PRIMARY KEY,
      name VARCHAR(120) NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
  `).run();
  await DB.prepare('CREATE UNIQUE INDEX IF NOT EXISTS idx_transport_companies_name_ci ON transport_companies (LOWER(name))').run();
  // PostgreSQL production constraint migration. SQLite does not support DROP CONSTRAINT.
  if (process.env.DATABASE_URL) {
    await DB.prepare('ALTER TABLE transport_companies DROP CONSTRAINT IF EXISTS transport_companies_status_check').run();
    await DB.prepare("ALTER TABLE transport_companies ADD CONSTRAINT transport_companies_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'INVITED'))").run();
  }
  await DB.prepare('ALTER TABLE transport_companies ADD COLUMN IF NOT EXISTS phone VARCHAR(40)').run();
  await DB.prepare('ALTER TABLE transport_companies ADD COLUMN IF NOT EXISTS representative_name VARCHAR(120)').run();
  await DB.prepare('ALTER TABLE transport_companies ADD COLUMN IF NOT EXISTS password_hash TEXT').run();
  await DB.prepare('ALTER TABLE transport_companies ADD COLUMN IF NOT EXISTS registration_secret_hash TEXT').run();
  await DB.prepare('CREATE TABLE IF NOT EXISTS transport_company_access_codes (id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64) NOT NULL REFERENCES transport_companies(id) ON DELETE CASCADE, code_hash TEXT NOT NULL, created_by VARCHAR(64), revoked BOOLEAN NOT NULL DEFAULT FALSE, expires_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)').run();
  await DB.prepare('ALTER TABLE transport_company_access_codes ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP').run();
  await backfillCompanyInvitationExpirations();
  await DB.prepare('ALTER TABLE users ADD COLUMN IF NOT EXISTS companyId VARCHAR(64)').run();
  await DB.prepare('ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS companyId VARCHAR(64)').run();
  await DB.prepare('ALTER TABLE routes ADD COLUMN IF NOT EXISTS companyId VARCHAR(64)').run();
  await DB.prepare("ALTER TABLE routes ADD COLUMN IF NOT EXISTS routeType VARCHAR(30) NOT NULL DEFAULT 'DAILY_COMMUTE'").run();
  await DB.prepare("ALTER TABLE routes ADD COLUMN IF NOT EXISTS origin VARCHAR(120) NOT NULL DEFAULT ''").run();
  await DB.prepare("ALTER TABLE routes ADD COLUMN IF NOT EXISTS destination VARCHAR(120) NOT NULL DEFAULT ''").run();
  await DB.prepare("ALTER TABLE routes ADD COLUMN IF NOT EXISTS departureTime VARCHAR(10) NOT NULL DEFAULT '06:30'").run();
  await DB.prepare('CREATE TABLE IF NOT EXISTS company_route_assignments (id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64) NOT NULL REFERENCES transport_companies(id), route_id VARCHAR(64) NOT NULL REFERENCES routes(id), driver_id VARCHAR(64) NOT NULL REFERENCES users(id), vehicle_id VARCHAR(64) NOT NULL REFERENCES vehicles(id), status VARCHAR(20) NOT NULL DEFAULT \'ACTIVE\', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)').run();
  await DB.prepare('CREATE INDEX IF NOT EXISTS idx_company_assignments_company ON company_route_assignments(company_id, status)').run();
  await DB.prepare(`
    CREATE TABLE IF NOT EXISTS vehicle_live_locations (
      vehicle_id VARCHAR(64) PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
      latitude NUMERIC(10,6) NOT NULL,
      longitude NUMERIC(10,6) NOT NULL,
      speed NUMERIC(7,2) DEFAULT 0,
      current_stop VARCHAR(100) DEFAULT '',
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
  `).run();

  await DB.prepare("CREATE TABLE IF NOT EXISTS telebirr_payment_orders (merchant_order_id VARCHAR(100) PRIMARY KEY, idempotency_key VARCHAR(100) UNIQUE, prepay_id VARCHAR(200), checkout_url TEXT, passenger_id VARCHAR(64) NOT NULL REFERENCES users(id), subscription_id VARCHAR(64) NOT NULL REFERENCES subscriptions(id), route_id VARCHAR(64) NOT NULL REFERENCES routes(id), amount_etb NUMERIC(10,2) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', payment_order_id VARCHAR(200), transaction_id VARCHAR(200), last_query_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)").run();
  await DB.prepare('CREATE INDEX IF NOT EXISTS idx_telebirr_payment_orders_passenger ON telebirr_payment_orders(passenger_id, created_at)').run();

  server.listen(PORT, HOST, () => {
    console.log('RoutePass API listening on the configured internal listener');
    console.log('REST API /api/health and /api/ready; WebSocket /ws');
    console.log(`Environment: ${process.env.NODE_ENV || 'production'}`);
  });
}

if (process.env.NODE_ENV !== 'test') {
  startServer().catch((err) => {
    console.error('[Startup] Database migration failed:', err);
    process.exit(1);
  });
}

// Graceful Shutdown
function gracefulShutdown(signal) {
  console.log(`[Server] Received ${signal}. Shutting down gracefully...`);
  server.close(() => {
    console.log('[Server] HTTP and WebSocket listeners closed.');
    process.exit(0);
  });
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

module.exports = { app, server, backfillCompanyInvitationExpirations };
