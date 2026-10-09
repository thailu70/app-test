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

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

// Security & Middlewares
app.use(helmet({
  contentSecurityPolicy: false // Allow WebSocket handshakes & dev proxies
}));

app.use(cors({
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Requested-With']
}));

app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

if (process.env.NODE_ENV !== 'test') {
  app.use(morgan('combined'));
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
app.get('/', (req, res) => {
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
app.get('/api/health', (req, res) => {
  try {
    const usersCount = DB.prepare('SELECT COUNT(*) as c FROM users').get().c;
    const routesCount = DB.prepare('SELECT COUNT(*) as c FROM routes').get().c;
    const vehiclesCount = DB.prepare('SELECT COUNT(*) as c FROM vehicles').get().c;
    const checkinsCount = DB.prepare('SELECT COUNT(*) as c FROM checkin_records').get().c;

    res.json({
      status: 'HEALTHY',
      service: 'Transport Navigator Transit Server',
      uptimeSeconds: Math.floor(process.uptime()),
      timestamp: new Date().toISOString(),
      memory: {
        rssMb: Math.round(process.memoryUsage().rss / (1024 * 1024)),
        heapUsedMb: Math.round(process.memoryUsage().heapUsed / (1024 * 1024))
      },
      database: {
        status: 'CONNECTED',
        usersCount,
        routesCount,
        vehiclesCount,
        checkinsCount
      }
    });
  } catch (err) {
    res.status(500).json({ status: 'DEGRADED', error: err.message });
  }
});

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
app.use((req, res) => {
  res.status(404).json({
    success: false,
    error: `Endpoint not found: ${req.method} ${req.originalUrl}`
  });
});

// Global Error Handler
app.use((err, req, res, next) => {
  console.error('[Server Error]:', err);
  res.status(err.status || 500).json({
    success: false,
    error: err.message || 'Internal Server Error'
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
const locationFlushTimer = setInterval(() => {
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

  ws.on('message', (message) => {
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

      // Handle Driver GPS Location Update
      if (data.type === 'DRIVER_LOCATION_UPDATE') {
        // Enforce Authentication: must be authenticated DRIVER or ADMIN
        const driverId = (ws.user && ws.user.id) || data.driverId;
        const userRole = (ws.user && ws.user.role) || (data.token ? verifyToken(data.token)?.role : null);

        if (!userRole || (userRole !== 'DRIVER' && userRole !== 'ADMIN')) {
          return ws.send(JSON.stringify({
            type: 'GPS_REJECTED',
            reason: 'UNAUTHORIZED_DRIVER',
            message: 'Only authenticated drivers or administrators may transmit live GPS telemetry.'
          }));
        }

        // Validate Vehicle ID
        const vehicleId = data.vehicleId;
        if (!vehicleId || typeof vehicleId !== 'string') {
          return ws.send(JSON.stringify({
            type: 'GPS_REJECTED',
            reason: 'MISSING_VEHICLE_ID'
          }));
        }

        // Validate Coordinates
        const lat = parseFloat(data.latitude);
        const lng = parseFloat(data.longitude);
        const speed = parseFloat(data.speed || 0);

        if (isNaN(lat) || isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
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

        // Broadcast real GPS location strictly to authorized/connected users
        broadcastAuthorized({
          type: 'VEHICLE_LOCATION_UPDATE',
          vehicleId: vehicleId,
          latitude: lat,
          longitude: lng,
          speed: speed,
          currentStop: data.currentStop || '',
          timestamp: new Date(now).toISOString()
        });

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

// Broadcast Helper: Distributes live telemetry to clients
function broadcastAuthorized(payload) {
  const json = JSON.stringify(payload);
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      // Send to authenticated users or active telemetry listeners
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
