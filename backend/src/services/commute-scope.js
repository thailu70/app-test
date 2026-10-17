const { DB } = require('../db');
const { resolveActivePrincipal } = require('../middleware/auth');
const commuteRoute = a => `COALESCE(${a ? a + '.' : ''}companyId, '') = '' AND COALESCE(${a ? a + '.' : ''}routeType, 'DAILY_COMMUTE') = 'DAILY_COMMUTE'`;
async function optionalPrincipal(req) {
  if (!req.headers.authorization) return null;
  const header = req.headers.authorization;
  const user = header.startsWith('Bearer ') ? await resolveActivePrincipal(header.slice(7)) : null;
  if (!user) throw Object.assign(new Error('Invalid or inactive authentication.'), { status: 401 });
  return user;
}
async function adminScope(req, res, next) {
  try {
    const parts = req.path.split('/').filter(Boolean);
    const id = parts[1];
    if (id && ['drivers', 'subscriptions', 'vehicles', 'routes'].includes(parts[0])) {
      let record;
      if (parts[0] === 'drivers') record = await DB.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER' AND COALESCE(companyId,'')=''").get(id);
      if (parts[0] === 'vehicles') record = await DB.prepare("SELECT id FROM vehicles WHERE id=? AND COALESCE(companyId,'')=''").get(id);
      if (parts[0] === 'routes') record = await DB.prepare(`SELECT id FROM routes WHERE id=? AND ${commuteRoute('')}`).get(id);
      if (parts[0] === 'subscriptions') record = await DB.prepare(`SELECT s.id FROM subscriptions s JOIN routes r ON r.id=s.routeId WHERE s.id=? AND ${commuteRoute('r')}`).get(id);
      if (!record) return res.status(404).json({ success: false, error: 'Daily commute record not found.' });
    }
    next();
  } catch (err) { console.error('[Scope] admin:', err); res.status(500).json({success:false,error:'Could not validate record scope.'}); }
}
module.exports = { commuteRoute, optionalPrincipal, adminScope };
