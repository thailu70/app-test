'use strict';

const { DB } = require('../db');
const { remainingSubscriptionDays } = require('./subscription-days');
const { dailyDriverEligible } = require('./operational-onboarding');

const LEGACY_DEMO_STOPS = new Set(['stop_atlas', 'stop_merkato']);

// Resolve the selected route's current stops without changing stored assignments or QR tokens.
// A deleted legacy ID is unambiguous only on a route with exactly two stops. An explicit ID
// belonging to another route is never used, except the two historical demo defaults.
async function populatePassengerStopLocations(subscription) {
  const stops = await DB.prepare('SELECT id, stopName, stopNameAm FROM route_stops WHERE routeId = ? ORDER BY stopOrder ASC, id ASC')
    .all(subscription.routeId);
  const ownStops = new Map(stops.map(stop => [stop.id, stop]));
  const unknownIds = [...new Set([subscription.pickupStopId, subscription.destinationStopId]
    .filter(id => id && !ownStops.has(id) && !LEGACY_DEMO_STOPS.has(id)))];
  const foreignStops = unknownIds.length ? await DB.prepare('SELECT id FROM route_stops WHERE id IN (' + unknownIds.map(() => '?').join(',') + ')')
    .all(...unknownIds) : [];
  const foreignIds = new Set(foreignStops.map(stop => stop.id));
  function resolve(id, fallback) {
    if (!id) return fallback;
    if (ownStops.has(id)) return ownStops.get(id);
    if (stops.length === 2 && !foreignIds.has(id)) return fallback;
    return null;
  }
  const pickup = resolve(subscription.pickupStopId, stops[0] || null);
  const destination = resolve(subscription.destinationStopId, stops.length > 1 ? stops[stops.length - 1] : null);
  subscription.pickupStopId = pickup?.id || subscription.pickupStopId || null;
  subscription.destinationStopId = destination?.id || subscription.destinationStopId || null;
  subscription.pickupStopName = pickup?.stopName || '';
  subscription.pickupStopNameAm = pickup?.stopNameAm || '';
  subscription.destinationStopName = destination?.stopName || '';
  subscription.destinationStopNameAm = destination?.stopNameAm || '';
  return subscription;
}

// Status, driver details and tracking select the same pass, never another vehicle
// on its route or an older pass after the current pass is pending/cancelled.
async function passengerAssignment(passengerId) {
  const subscription = await DB.prepare(`
    SELECT s.*, r.name AS routeName, r.nameAm AS routeNameAm, r.morningDeparture, r.eveningDeparture
    FROM subscriptions s
    JOIN routes r ON r.id=s.routeId AND COALESCE(r.companyId,'')=''
      AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
    WHERE s.passengerId=? ORDER BY s.updatedAt DESC, s.id DESC LIMIT 1
  `).get(passengerId);
  if (!subscription) return { subscription: null, vehicle: null, active: false };
  await populatePassengerStopLocations(subscription);
  subscription.daysRemaining = remainingSubscriptionDays(subscription);
  const active = subscription.subscriptionStatus === 'ACTIVE' && subscription.paymentStatus === 'PAID' && Number(subscription.daysRemaining) > 0;
  let vehicle = subscription.vehicleId ? await DB.prepare(`
    SELECT v.*, r.name AS routeName, r.morningDeparture,
      u.fullName AS driverName, u.phone AS assignment_driver_phone,
      p.vehicle_color AS assignment_vehicle_color,
      l.latitude, l.longitude, l.speed, l.current_stop AS currentStop,
      l.updated_at AS lastGpsAt, t.id AS assignment_trip_id
    FROM vehicles v
    JOIN routes r ON r.id=v.assignedRouteId AND r.id=? AND COALESCE(r.companyId,'')=''
      AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'
    JOIN users u ON u.id=v.driverId AND u.role='DRIVER' AND u.status='ACTIVE' AND COALESCE(u.companyId,'')=''
    LEFT JOIN user_profiles p ON p.user_id=u.id
    LEFT JOIN vehicle_live_locations l ON l.vehicle_id=v.id
    LEFT JOIN trips t ON t.vehicleId=v.id AND t.driverId=v.driverId AND t.routeId=r.id AND t.status='IN_PROGRESS'
    WHERE v.id=? AND COALESCE(v.companyId,'')=''
    ORDER BY t.startTime DESC LIMIT 1
  `).get(subscription.routeId, subscription.vehicleId) : null;
  if (vehicle && !await dailyDriverEligible(vehicle.driverId)) vehicle = null;
  return { subscription, vehicle: vehicle || null, active };
}

module.exports = { passengerAssignment, populatePassengerStopLocations };
