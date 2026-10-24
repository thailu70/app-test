'use strict';
const { DB } = require('../db');
const { isOperationallyEligible } = require('./onboarding');

const incomplete = () => Object.assign(new Error('Complete the required application steps, documents and approval before passenger operations.'),
  { status: 409, code: 'ONBOARDING_REQUIRED' });

async function driverEligible(driverId, db = DB) {
  const driver = await db.prepare("SELECT id,companyId FROM users WHERE id=? AND role='DRIVER'").get(driverId);
  if (!driver || !await isOperationallyEligible(driverId, 'DRIVER', db)) return false;
  return !driver.companyId || await isOperationallyEligible(driver.companyId, 'COMPANY', db);
}

async function assertDriverEligible(driverId, db = DB) {
  if (!await driverEligible(driverId, db)) throw incomplete();
}

async function operationalAccount(req, res, next) {
  try {
    const eligible = req.user?.role === 'DRIVER' ? await driverEligible(req.user.id)
      : req.user?.role === 'COMPANY' ? await isOperationallyEligible(req.user.id, 'COMPANY') : true;
    if (!eligible) return res.status(409).json({ success: false, code: 'ONBOARDING_REQUIRED', error: incomplete().message });
    return next();
  } catch (error) { next(error); }
}

module.exports = { driverEligible, assertDriverEligible, operationalAccount, incomplete };
