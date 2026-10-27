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

async function dailyDriverEligible(driverId, db = DB) {
  if (!await driverEligible(driverId, db)) return false;
  let enrollment;
  try { enrollment = await db.prepare("SELECT * FROM onboarding_accounts WHERE account_id=? AND role='DRIVER'").get(driverId); }
  catch (error) {
    if (error.code !== '42P01' && !/no such table: onboarding_accounts/.test(error.message || '')) throw error;
  }
  // Older daily/company accounts keep their existing scope. Explicit mobility
  // registrations cannot acquire a commute assignment through legacy endpoints.
  return !['GROUP_TRAVEL','CARGO'].includes(enrollment?.service_type);
}

async function assertDailyDriverEligible(driverId, db = DB) {
  if (!await dailyDriverEligible(driverId, db)) throw Object.assign(new Error('Choose an approved daily commute driver for this assignment.'), { status: 409, code: 'DAILY_COMMUTE_DRIVER_REQUIRED' });
}

async function commuteOperationalAccount(req, res, next) {
  try {
    if (req.user?.role === 'DRIVER' && !await dailyDriverEligible(req.user.id)) return res.status(409).json({ success: false, code: 'DAILY_COMMUTE_DRIVER_REQUIRED', error: 'This driver account is not approved for daily commute operations.' });
    return operationalAccount(req, res, next);
  } catch (error) { next(error); }
}

async function operationalAccount(req, res, next) {
  try {
    const eligible = req.user?.role === 'DRIVER' ? await driverEligible(req.user.id)
      : req.user?.role === 'COMPANY' ? await isOperationallyEligible(req.user.id, 'COMPANY') : true;
    if (!eligible) return res.status(409).json({ success: false, code: 'ONBOARDING_REQUIRED', error: incomplete().message });
    return next();
  } catch (error) { next(error); }
}

module.exports = { driverEligible, assertDriverEligible, dailyDriverEligible, assertDailyDriverEligible, commuteOperationalAccount, operationalAccount, incomplete };
