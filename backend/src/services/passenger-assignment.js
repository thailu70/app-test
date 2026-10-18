'use strict';

const { DB } = require('../db');

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
  const active = subscription.subscriptionStatus === 'ACTIVE' && subscription.paymentStatus === 'PAID' && Number(subscription.daysRemaining) > 0;
  const vehicle = subscription.vehicleId ? await DB.prepare(`
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
  return { subscription, vehicle: vehicle || null, active };
}

module.exports = { passengerAssignment };
