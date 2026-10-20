'use strict';

const crypto = require('crypto');
const DAY_MS = 86400000;

// The database counter is written at activation. Read the actual expiry on
// every status request so it decreases without a background database job.
function subscriptionExpiry(subscription) {
  const parts = String(subscription.qrToken || '').split(':');
  if (parts.length === 6 && parts[0] === 'RP1' && parts[1] === subscription.id &&
      parts[2] === subscription.passengerId && parts[3] === subscription.routeId && /^\d+$/.test(parts[4])) {
    const key = process.env.QR_SIGNING_KEY?.trim() || process.env.JWT_SECRET?.trim() ||
      (process.env.NODE_ENV !== 'production' ? 'routepass_production_qr_hmac_secret_2026' : '');
    const signature = key ? crypto.createHmac('sha256', key).update(parts.slice(1, 5).join(':')).digest('hex').slice(0, 16) : '';
    const expiry = Number(parts[4]);
    if (signature && signature === parts[5] && Number.isSafeInteger(expiry) && expiry > 0) return expiry;
  }

  const value = String(subscription.endDate || '').trim();
  // Date-only expiries were issued at UTC midnight by the activation service.
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const parsed = Date.parse(value + 'T00:00:00.000Z');
    return Number.isFinite(parsed) && new Date(parsed).toISOString().slice(0, 10) === value ? parsed : null;
  }
  if (/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  const legacy = /^(\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4})$/i.exec(value);
  if (legacy) {
    const month = ['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'].indexOf(legacy[2].toLowerCase());
    const parsed = Date.UTC(Number(legacy[3]), month, Number(legacy[1]));
    const date = new Date(parsed);
    return date.getUTCFullYear() === Number(legacy[3]) && date.getUTCMonth() === month && date.getUTCDate() === Number(legacy[1]) ? parsed : null;
  }
  return null;
}

function remainingSubscriptionDays(subscription, now = Date.now()) {
  if (subscription.paymentStatus && subscription.paymentStatus !== 'PAID') return 0;
  if (subscription.subscriptionStatus && !['ACTIVE', 'EXPIRING'].includes(subscription.subscriptionStatus)) return 0;
  const expiry = subscriptionExpiry(subscription);
  if (expiry !== null) return Math.max(0, Math.ceil((expiry - now) / DAY_MS));
  // Preserve legacy records with no usable expiry; never invent a new duration.
  const stored = Number(subscription.daysRemaining);
  return Number.isFinite(stored) ? Math.max(0, Math.ceil(stored)) : 0;
}

module.exports = { subscriptionExpiry, remainingSubscriptionDays };
