'use strict';

const { DB } = require('../db');
const schemas = new WeakMap();
const SERVICE_CLASSES = Object.freeze(['ECONOMY', 'DELUXE', 'VIP']);
const fail = (status, message) => { throw Object.assign(new Error(message), { status, code: 'COMMUTE_FARE_UNAVAILABLE' }); };

async function initCommutePricingSchema(db = DB) {
  // Transaction clients share the already-initialized application's schema.
  if (typeof db.exec !== 'function') return;
  if (!schemas.has(db)) {
    const pending = Promise.resolve(db.exec(`
      CREATE TABLE IF NOT EXISTS commute_route_terms (
        vehicle_id TEXT PRIMARY KEY REFERENCES vehicles(id) ON DELETE CASCADE,
        route_id TEXT NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
        driver_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        service_class TEXT NOT NULL CHECK (service_class IN ('ECONOMY','DELUXE','VIP')),
        monthly_fare_etb NUMERIC NOT NULL CHECK (monthly_fare_etb > 0 AND monthly_fare_etb <= 1000000),
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
      CREATE INDEX IF NOT EXISTS idx_commute_route_terms_route ON commute_route_terms(route_id, service_class);
      CREATE TABLE IF NOT EXISTS commute_subscription_terms (
        subscription_id TEXT PRIMARY KEY REFERENCES subscriptions(id) ON DELETE CASCADE,
        route_id TEXT NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
        vehicle_id TEXT,
        service_class TEXT NOT NULL CHECK (service_class IN ('ECONOMY','DELUXE','VIP')),
        monthly_fare_etb NUMERIC NOT NULL CHECK (monthly_fare_etb > 0 AND monthly_fare_etb <= 1000000),
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
      CREATE TABLE IF NOT EXISTS commute_payment_terms (
        order_id TEXT PRIMARY KEY REFERENCES telebirr_payment_orders(merchant_order_id) ON DELETE CASCADE,
        route_id TEXT NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
        vehicle_id TEXT,
        service_class TEXT NOT NULL CHECK (service_class IN ('ECONOMY','DELUXE','VIP')),
        monthly_fare_etb NUMERIC NOT NULL CHECK (monthly_fare_etb > 0 AND monthly_fare_etb <= 1000000),
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );
    `));
    schemas.set(db, pending);
    pending.catch(() => schemas.delete(db));
  }
  return schemas.get(db);
}

function serviceClass(value, required = true) {
  if ((value === undefined || value === null || value === '') && !required) return null;
  const result = typeof value === 'string' ? value.trim().toUpperCase() : '';
  if (!SERVICE_CLASSES.includes(result)) fail(400, 'Choose Economy, Deluxe or VIP.');
  return result;
}

function assignmentTerms(input = {}) {
  const selected = serviceClass(input.serviceClass);
  const fare = input.monthlyFareEtb;
  if (typeof fare !== 'number' || !Number.isFinite(fare) || fare <= 0 || fare > 1000000 || Math.abs(fare * 100 - Math.round(fare * 100)) > 0.000001) {
    fail(400, 'Enter a monthly fare per passenger greater than zero, up to ETB 1,000,000, with at most two decimal places.');
  }
  return { serviceClass: selected, monthlyFareEtb: Math.round(fare * 100) / 100 };
}

function view(row) {
  return row ? { serviceClass: row.service_class, monthlyFareEtb: Number(row.monthly_fare_etb),
    vehicleId: row.vehicle_id || null, routeId: row.route_id } : null;
}

async function offers(route, db) {
  await initCommutePricingSchema(db);
  if (!route || !route.id) return [];
  const fleet = await db.prepare(`
    SELECT v.id AS pricing_vehicle_id, v.driverId AS pricing_driver_id, v.vehicleType AS pricing_vehicle_type,
      t.service_class, t.monthly_fare_etb
    FROM vehicles v
    JOIN users u ON u.id=v.driverId AND u.role='DRIVER' AND u.status='ACTIVE' AND COALESCE(u.companyId,'')=''
    LEFT JOIN commute_route_terms t ON t.vehicle_id=v.id AND t.driver_id=v.driverId AND t.route_id=v.assignedRouteId
    WHERE v.assignedRouteId=? AND COALESCE(v.companyId,'')='' AND v.status IN ('IN_SERVICE','AVAILABLE')
    ORDER BY v.id
  `).all(route.id);
  const result = [];
  const legacy = Number(route.basePriceEtb);
  const configured = await db.prepare('SELECT vehicle_id FROM commute_route_terms WHERE route_id=? LIMIT 1').get(route.id);
  for (const vehicle of fleet) {
    if (!await require('./operational-onboarding').dailyDriverEligible(vehicle.pricing_driver_id, db)) continue;
    const fare = vehicle.monthly_fare_etb == null ? (configured ? 0 : legacy) : Number(vehicle.monthly_fare_etb);
    if (!Number.isFinite(fare) || fare <= 0 || fare > 1000000) continue;
    result.push({ routeId: route.id, vehicleId: vehicle.pricing_vehicle_id, vehicleType: vehicle.pricing_vehicle_type,
      serviceClass: vehicle.service_class || 'ECONOMY', monthlyFareEtb: fare });
  }
  // Existing clients and unassigned historical routes retain their previous
  // Economy tariff. New routes explicitly start at zero and have no such fare.
  if (!result.length && !configured && Number.isFinite(legacy) && legacy > 0 && legacy <= 1000000) {
    result.push({ routeId: route.id, vehicleId: null, serviceClass: 'ECONOMY', monthlyFareEtb: legacy });
  }
  return result;
}

async function getRouteOffers(routeId, db = DB) {
  const route = await db.prepare("SELECT * FROM routes WHERE id=? AND active=TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'").get(routeId);
  return route ? offers(route, db) : [];
}

async function getRoutePricing(route, db = DB) {
  const available = await offers(route, db);
  const serviceFares = Object.fromEntries(SERVICE_CLASSES.map(name => [name, null]));
  for (const offer of available) {
    if (serviceFares[offer.serviceClass] == null || offer.monthlyFareEtb < serviceFares[offer.serviceClass]) serviceFares[offer.serviceClass] = offer.monthlyFareEtb;
  }
  return { ...route, basePriceEtb: serviceFares.ECONOMY || 0, serviceFares,
    monthlyPassengerFareEtb: available.length ? Math.min(...available.map(offer => offer.monthlyFareEtb)) : null,
    pricingSource: available.some(offer => offer.vehicleId) ? 'DRIVER_ROUTE_ASSIGNMENT' : available.length ? 'LEGACY_ROUTE' : 'UNCONFIGURED' };
}

async function getSubscriptionTerms(subscriptionId, db = DB) {
  await initCommutePricingSchema(db);
  return view(await db.prepare('SELECT * FROM commute_subscription_terms WHERE subscription_id=?').get(subscriptionId));
}

async function getPaymentTerms(orderId, db = DB) {
  await initCommutePricingSchema(db);
  return view(await db.prepare('SELECT * FROM commute_payment_terms WHERE order_id=?').get(orderId));
}

async function saveSubscriptionTerms(tx, subscriptionId, fare, routeId = fare.routeId) {
  const terms = assignmentTerms(fare);
  await tx.prepare(`INSERT INTO commute_subscription_terms(subscription_id,route_id,vehicle_id,service_class,monthly_fare_etb)
    VALUES(?,?,?,?,?) ON CONFLICT(subscription_id) DO UPDATE SET route_id=excluded.route_id,vehicle_id=excluded.vehicle_id,
      service_class=excluded.service_class,monthly_fare_etb=excluded.monthly_fare_etb,updated_at=CURRENT_TIMESTAMP`)
    .run(subscriptionId, routeId, fare.vehicleId || null, terms.serviceClass, terms.monthlyFareEtb);
}

async function savePaymentTerms(tx, orderId, fare, routeId = fare.routeId) {
  const terms = assignmentTerms(fare);
  await tx.prepare(`INSERT INTO commute_payment_terms(order_id,route_id,vehicle_id,service_class,monthly_fare_etb)
    VALUES(?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING`)
    .run(orderId, routeId, fare.vehicleId || null, terms.serviceClass, terms.monthlyFareEtb);
  const existing = view(await tx.prepare('SELECT * FROM commute_payment_terms WHERE order_id=?').get(orderId));
  if (!existing || existing.routeId !== routeId || existing.vehicleId !== (fare.vehicleId || null) || existing.serviceClass !== terms.serviceClass || existing.monthlyFareEtb !== terms.monthlyFareEtb) {
    fail(409, 'This payment order already belongs to different service terms.');
  }
}

async function resolveCommuteFare({ route, passengerId, serviceClass: requestedClass, db = DB }) {
  const explicit = serviceClass(requestedClass, false);
  route = route?.id ? await db.prepare("SELECT * FROM routes WHERE id=? AND active=TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'").get(route.id) : null;
  if (!route) fail(409, 'An active daily commute route is required before checkout.');
  const available = await offers(route, db);
  const sub = passengerId ? await db.prepare('SELECT * FROM subscriptions WHERE passengerId=? ORDER BY updatedAt DESC,id DESC LIMIT 1').get(passengerId) : null;
  const sameRoute = sub && sub.routeId === route.id;
  const saved = sameRoute ? await getSubscriptionTerms(sub.id, db) : null;
  const assigned = sameRoute && sub.vehicleId ? available.find(offer => offer.vehicleId === sub.vehicleId) : null;
  const selected = explicit || (saved?.routeId === route.id ? saved.serviceClass : null) || assigned?.serviceClass || 'ECONOMY';
  if (sameRoute && sub.paymentStatus === 'PAID' && sub.vehicleId && !assigned) fail(409, 'Your assigned driver is no longer available at a configured fare. Ask Passo to review the assignment before renewal.');
  if (assigned && sub.paymentStatus === 'PAID' && selected !== assigned.serviceClass) fail(409, 'The selected service class differs from your assigned vehicle. Ask Passo to update the assignment first.');
  const matching = available.filter(offer => offer.serviceClass === selected);
  const chosen = assigned?.serviceClass === selected ? assigned : matching.sort((a, b) => a.monthlyFareEtb - b.monthlyFareEtb || String(a.vehicleId).localeCompare(String(b.vehicleId)))[0];
  if (!chosen) fail(409, 'No priced ' + selected.toLowerCase() + ' driver assignment is available for this route.');
  return { ...chosen };
}

module.exports = { SERVICE_CLASSES, initCommutePricingSchema, assignmentTerms, getRoutePricing, getRouteOffers,
  resolveCommuteFare, getSubscriptionTerms, saveSubscriptionTerms, getPaymentTerms, savePaymentTerms };
