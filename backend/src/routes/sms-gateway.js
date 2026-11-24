'use strict';

const express = require('express');
const rateLimit = require('express-rate-limit');
const gateway = require('../services/phone-sms-gateway');

const router = express.Router();
const pairLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 5, standardHeaders: true, legacyHeaders: false });

function asyncRoute(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

async function requirePairedDevice(req, res, next) {
  try {
    const header = String(req.headers.authorization || '');
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
    const deviceId = await gateway.authenticateDevice(token);
    if (!deviceId) return res.status(401).json({ success: false, error: 'This gateway device is not paired.' });
    req.smsGatewayDeviceId = deviceId;
    return next();
  } catch (_) {
    return res.status(503).json({ success: false, error: 'Phone gateway is temporarily unavailable.' });
  }
}

router.post('/pair', pairLimiter, asyncRoute(async (req, res) => {
  const paired = await gateway.pairDevice({
    code: req.body?.code,
    deviceName: req.body?.deviceName,
    publicKey: req.body?.publicKey
  });
  res.json({ success: true, ...paired });
}));

router.use(requirePairedDevice);

router.post('/claim', asyncRoute(async (req, res) => {
  const job = await gateway.claimNextJob(req.smsGatewayDeviceId);
  res.json({ success: true, job });
}));

router.post('/jobs/:jobId/ack', asyncRoute(async (req, res) => {
  const success = req.body?.status === 'SENT';
  const acknowledged = await gateway.acknowledgeJob(
    req.smsGatewayDeviceId,
    String(req.params.jobId || '').slice(0, 80),
    req.body?.leaseToken,
    success,
    { failureCode: req.body?.failureCode }
  );
  if (!acknowledged) return res.status(409).json({ success: false, error: 'This message is no longer available.' });
  res.json({ success: true });
}));

module.exports = router;
