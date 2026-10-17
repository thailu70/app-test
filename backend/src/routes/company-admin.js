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
    const existing = await DB.prepare('SELECT id, status FROM transport_companies WHERE LOWER(name)=LOWER(?)').get(name);
    if (existing && existing.status !== 'INVITED') return res.status(409).json({ success: false, error: 'An active or previously registered company already uses this name.' });
    const code = crypto.randomBytes(5).toString('hex').toUpperCase();
    const id = existing?.id || ('company_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16));
    const accessCodeId = 'ccode_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
    const hash = await bcrypt.hash(code, 12);
    const expiresAtExpression = DB.isPostgres ? "CURRENT_TIMESTAMP + INTERVAL '7 days'" : "datetime('now', '+7 days')";
    const invite = await DB.transaction(async (tx) => {
      if (existing) {
        const lockSuffix = tx.isPostgres ? ' FOR UPDATE' : '';
        const company = await tx.prepare('SELECT id,status FROM transport_companies WHERE id=?' + lockSuffix).get(id);
        if (!company || company.status !== 'INVITED') return { status: 'COMPANY_CHANGED' };
        await tx.prepare("UPDATE transport_company_access_codes SET revoked=TRUE WHERE company_id=? AND revoked=FALSE").run(id);
      } else {
        await tx.prepare("INSERT INTO transport_companies (id,name,status) VALUES (?,?,'INVITED')").run(id, name);
      }
      await tx.prepare(`INSERT INTO transport_company_access_codes (id,company_id,code_hash,created_by,revoked,expires_at) VALUES (?,?,?,?,FALSE,${expiresAtExpression})`)
        .run(accessCodeId, id, hash, req.user.id);
      await tx.prepare('INSERT INTO audit_logs (action,userId,role,details) VALUES (?,?,?,?)')
        .run(existing ? 'COMPANY_INVITE_REISSUED' : 'COMPANY_INVITE_CREATED', req.user.id, 'ADMIN', (existing ? 'Reissued' : 'Created') + ' single-use company invitation for ' + name);
      return { status: existing ? 'REISSUED' : 'CREATED' };
    });
    if (invite.status === 'COMPANY_CHANGED') return res.status(409).json({ success: false, error: 'Company registration changed while creating the invitation. Refresh and try again.' });
    res.status(201).json({ success: true, company: { id, name, status: 'INVITED', invitationExpiresInDays: 7 }, registrationCode: code, message: 'Copy this code now. It is shown only once and expires in 7 days.' });
  } catch (e) {
    console.error('[Admin] company invite failed:', e);
    res.status(500).json({ success: false, error: 'Could not create company invitation.' });
  }
});

module.exports = router;
