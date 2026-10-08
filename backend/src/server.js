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

wss.on('connection', (ws, req) => {
  clients.add(ws);
  console.log(`[WebSocket] Client connected. Total active clients: ${clients.size}`);

  ws.send(JSON.stringify({
    type: 'CONNECTION_ESTABLISHED',
    message: 'Connected to Transport Navigator Live Telemetry Stream',
    timestamp: new Date().toISOString()
  }));

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message);
      // Handle client ping
      if (data.type === 'PING') {
        ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      } else if (data.type === 'DRIVER_LOCATION_UPDATE' && data.vehicleId) {
        // Update vehicle in database
        try {
          DB.prepare(`
            UPDATE vehicles
            SET currentLat = ?, currentLng = ?, updatedAt = CURRENT_TIMESTAMP
            WHERE id = ?
          `).run(parseFloat(data.latitude), parseFloat(data.longitude), data.vehicleId);
        } catch (dbErr) {
          // ignore or log
        }
        // Broadcast real GPS location to all connected passengers & admins
        broadcastWs({
          type: 'VEHICLE_LOCATION_UPDATE',
          vehicleId: data.vehicleId,
          latitude: parseFloat(data.latitude),
          longitude: parseFloat(data.longitude),
          speed: parseFloat(data.speed || 0),
          currentStop: data.currentStop || '',
          timestamp: new Date().toISOString()
        });
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

// Broadcast Helper
function broadcastWs(payload) {
  const json = JSON.stringify(payload);
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(json);
    }
  }
}

app.locals.broadcastWs = broadcastWs;

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
