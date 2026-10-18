'use strict';
const express = require('express');
const router = express.Router();
const { authenticate, requireRole } = require('../middleware/auth');
const { passengerAssignment } = require('../services/passenger-assignment');

router.get('/my-driver', authenticate, requireRole('PASSENGER'), async (req, res) => {
  try {
    const { subscription, vehicle, active } = await passengerAssignment(req.user.id);
    res.set('Cache-Control', 'private, no-store');
    res.json({ success: true,
      status: !subscription ? 'UNASSIGNED' : !active ? 'INACTIVE' : vehicle ? 'ASSIGNED' : 'UNASSIGNED',
      driver: vehicle ? {
        userId: vehicle.driverId, fullName: vehicle.driverName || '',
        phone: vehicle.assignment_driver_phone || '',
        photoUrl: `/api/profiles/${encodeURIComponent(vehicle.driverId)}/photo`,
        vehicleId: vehicle.id, subscriptionId: subscription.id,
        subscriptionStatus: subscription.subscriptionStatus,
        vehicleType: vehicle.vehicleType || '',
        vehicleColor: vehicle.assignment_vehicle_color || '',
        plateNumber: vehicle.plateNumber || '', model: vehicle.model || '', routeName: vehicle.routeName || ''
      } : null });
  } catch (error) {
    console.error('[Assigned driver]', error);
    res.status(500).json({ success: false, error: 'Could not load assigned driver.' });
  }
});
module.exports = router;
