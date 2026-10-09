/**
 * Transport Navigator - Production VPS Backend Server
 * Ethiopian Scheduled Transit Management System
 */

require('dotenv').config();
const http = require('http');
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const morgan = require('morgan');
const rateLimit = require('express-rate-limit');
const { WebSocketServer, WebSocket } = require('ws');
const { DB } = require('./db');
const { verifyToken } = require('./middleware/auth');

if (process.env.NODE_ENV === 'production') {
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
});;

// Mount Route Modules
app.use('/api/auth', require('./routes/auth'));
app.use('/api/routes', require('./routes/routes'));
app.use('/api/subscriptions', require('./routes/subscriptions'));
app.use('/api/vehicles', require('./routes/vehicles'));
app.use('/api/trips', require('./routes/trips'));
app.use('/api/checkins', require('./routes/checkins'));
app.use('/api/admin', require('./routes/admin'));
app.use('/api/complaints', require('./routes/complaints'));
app.use('/api/notifications', require('./routes/notifications'));
app.use('/api/sync', require('./routes/sync'));

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
  for (const [vehicleId, loc] of inMemoryVehicleLocations.entries()) {
    try {
      DB.prepare(`
        UPDATE vehicles
        SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(loc.latitude, loc.longitude, vehicleId);
    } catch (err) {
      // In-memory update succeeded; ignore temporary DB busy
    }
  }
}, FLUSH_INTERVAL_MS);

if (locationFlushTimer.unref) {
  locationFlushTimer.unref();
}

wss.on('connection', (ws, req) => {
  // Extract token from query param or auth header
  let token = null;
  try {
    const urlObj = new URL(req.url, 'http://localhost');
    token = urlObj.searchParams.get('token');
  } catch (e) {}

  if (!token && req.headers['authorization']) {
    const parts = req.headers['authorization'].split(' ');
    if (parts.length === 2 && parts[0] === 'Bearer') {
      token = parts[1];
    }
  }

  // Check initial authentication state
  ws.user = token ? verifyToken(token) : null;
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

  ws.on('message', async (message) => {
    try {
      const data = JSON.parse(message);

      // Handle Ping / Pong Heartbeat
      if (data.type === 'PING') {
        return ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      }

      // Handle In-band Authentication
      if (data.type === 'AUTHENTICATE') {
        const decoded = verifyToken(data.token);
        if (decoded) {
          ws.user = decoded;
          ws.authenticated = true;
          return ws.send(JSON.stringify({
            type: 'AUTHENTICATION_SUCCESS',
            role: decoded.role,
            userId: decoded.id,
            timestamp: Date.now()
          }));
        } else {
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

        if (!ws.authenticated || !driverId || !['DRIVER', 'ADMIN'].includes(userRole)) {
          return ws.send(JSON.stringify({
            type: 'GPS_REJECTED',
            reason: 'UNAUTHORIZED_DRIVER',
            message: 'Only authenticated drivers or administrators may transmit live GPS telemetry.'
          }));
        }

        // Vehicle and route ownership are server-side facts, not client-controlled fields.
        const vehicleId = data.vehicleId;
        if (!vehicleId || typeof vehicleId !== 'string') {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'MISSING_VEHICLE_ID' }));
        }
        const vehicle = await DB.prepare('SELECT * FROM vehicles WHERE id = ?').get(vehicleId);
        if (!vehicle) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'UNKNOWN_VEHICLE' }));
        }
        if (userRole === 'DRIVER') {
          const driver = await DB.prepare('SELECT assignedVehiclePlate FROM users WHERE id = ?').get(driverId);
          const isAssigned = vehicle.driverId === driverId;
          if (!isAssigned) {
            return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'VEHICLE_NOT_ASSIGNED' }));
          }
        }
        const routeId = vehicle.assignedRouteId;
        if (!routeId) {
          return ws.send(JSON.stringify({ type: 'GPS_REJECTED', reason: 'VEHICLE_ROUTE_NOT_ASSIGNED' }));
        }

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
          timestamp: now
        });

        // Route is derived from the authoritative vehicle record loaded above.

        // Broadcast real GPS location strictly to authorized recipients
        broadcastAuthorized({
          type: 'VEHICLE_LOCATION_UPDATE',
          vehicleId: vehicleId,
          routeId: routeId || '',
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
    clients.delete(ws);
    console.log(`[WebSocket] Client disconnected. Total active clients: ${clients.size}`);
  });

  ws.on('error', (err) => {
    console.error('[WebSocket Error]:', err.message);
    clients.delete(ws);
  });
});

// Broadcast Helper: Distributes live telemetry strictly to authorized recipients
function broadcastAuthorized(payload, targetRouteId = null) {
  const json = JSON.stringify(payload);
  for (const client of clients) {
    if (client.readyState !== WebSocket.OPEN) continue;

    // 1. Mandatory Authentication: Unauthenticated guests never receive live telemetry
    if (!client.authenticated || !client.user) continue;

    // 2. Per-Recipient Authorization:
    const role = (client.user.role || '').toUpperCase();
    if (role === 'ADMIN' || role === 'DRIVER') {
      // Operations dispatchers and commercial drivers have fleet-wide telemetry visibility
      client.send(json);
    } else if (role === 'PASSENGER') {
      // Commuters receive GPS telemetry strictly for their authorized/monitored transit corridor
      const commuterCorridor = client.monitoredRouteId || client.user.appliedRouteId;
      if (targetRouteId && commuterCorridor && commuterCorridor !== targetRouteId) {
        // Drop broadcast: recipient is not authorized/subscribed to this vehicle's corridor
        continue;
      }
      client.send(json);
    }
  }
}

app.locals.broadcastWs = broadcastAuthorized;

// Start Server
if (process.env.NODE_ENV !== 'test') {
  server.listen(PORT, HOST, () => {
    console.log(`================================================================`);
    console.log(`  TRANSPORT NAVIGATOR - VPS PRODUCTION TRANSIT SERVER`);
    console.log(`================================================================`);
    console.log(`  REST API URL   : http://${HOST}:${PORT}`);
    console.log(`  WebSocket URL  : ws://${HOST}:${PORT}/ws`);
    console.log(`  Health Check   : http://${HOST}:${PORT}/api/health`);
    console.log(`  Environment    : ${process.env.NODE_ENV || 'production'}`);
    console.log(`  Local Database : ./data/transport.db`);
    console.log(`================================================================`);
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

module.exports = { app, server };
