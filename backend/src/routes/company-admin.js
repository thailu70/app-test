const express = require('express');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const router = express.Router();

// Isolated company invitation endpoint; existing admin routes remain unchanged.
router.post('/companies/invite', authenticate, requireRole('ADMIN'), async (req, res) => {
  try {
    const name = String(req.body.name || '').trim();
    if (name.length < 2) return res.status(400).json({ success: false, error: 'Company name is required.' });
    const existing = await DB.prepare('SELECT id FROM transport_companies WHERE LOWER(name)=LOWER(?)').get(name);
    if (existing) return res.status(409).json({ success: false, error: 'Company already exists or has an outstanding invitation.' });
    const code = crypto.randomBytes(5).toString('hex').toUpperCase();
    const id = 'company_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
    const hash = await bcrypt.hash(code, 12);
    await DB.transaction(async (tx) => {
      await tx.prepare("INSERT INTO transport_companies (id,name,status) VALUES (?,?,'INVITED')").run(id, name);
      await tx.prepare("INSERT INTO transport_company_access_codes (id,company_id,code_hash,created_by,revoked) VALUES (?,?,?,?,0)")
        .run('ccode_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16), id, hash, req.user.id);
      await tx.prepare("INSERT INTO audit_logs (action,userId,role,details) VALUES ('COMPANY_INVITE_CREATED',?,'ADMIN',?)")
        .run(req.user.id, 'Created single-use company invitation for ' + name);
    });
    res.status(201).json({ success: true, company: { id, name, status: 'INVITED' }, registrationCode: code, message: 'Copy this code now. It is shown only once.' });
  } catch (e) {
    console.error('[Admin] company invite failed:', e);
    res.status(500).json({ success: false, error: 'Could not create company invitation.' });
  }
});

module.exports = router;
