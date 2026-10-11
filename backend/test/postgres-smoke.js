const assert = require('node:assert/strict');
const { server } = require('../src/server');

const host = '127.0.0.1';
const bootstrapSecret = process.env.ADMIN_REGISTRATION_SECRET;
const requestJson = async (baseUrl, path, method = 'GET', body = null, token = null) => {
  const response = await fetch(new URL(path, baseUrl), {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  let data = {};
  try { data = await response.json(); } catch (_) {}
  return { status: response.status, data };
};

async function run() {
  assert.ok(process.env.DATABASE_URL, 'DATABASE_URL must target the PostgreSQL CI service');
  assert.ok(bootstrapSecret, 'ADMIN_REGISTRATION_SECRET is required for the smoke test');

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, host, resolve);
  });
  const address = server.address();
  const baseUrl = `http://${host}:${address.port}/`;

  try {
    let result = await requestJson(baseUrl, '/api/ready');
    assert.equal(result.status, 200, 'PostgreSQL readiness must pass');

    result = await requestJson(baseUrl, '/api/routes');
    assert.equal(result.status, 200, 'Routes query must work with PostgreSQL');
    assert.ok(Array.isArray(result.data.routes), 'Route list must be an array');

    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Administrator',
      phone: '+251900000101',
      password: 'CI_Admin_Password#2026',
      role: 'ADMIN',
      adminSecret: bootstrapSecret
    });
    assert.equal(result.status, 201, 'First-admin registration must work against PostgreSQL');
    const adminToken = result.data.token;
    assert.ok(adminToken, 'Admin JWT must be returned');

    result = await requestJson(baseUrl, '/api/admin/passengers', 'GET', null, adminToken);
    assert.equal(result.status, 200, 'Admin passenger directory query must work against PostgreSQL');
    assert.ok(Array.isArray(result.data.passengers), 'Passenger directory must be an array');

    result = await requestJson(baseUrl, '/api/admin/tracking', 'GET', null, adminToken);
    assert.equal(result.status, 200, 'Admin live-tracking query must work against PostgreSQL');
    assert.ok(Array.isArray(result.data.vehicles), 'Admin tracking vehicles must be an array');

    result = await requestJson(baseUrl, '/api/config/public');
    assert.equal(result.status, 200, 'Public config endpoint must be available');
    assert.equal(result.data.testMode, false, 'Test mode must remain disabled by default');

    result = await requestJson(baseUrl, '/api/routes', 'POST', {
      name: 'CI Sample Route',
      nameAm: 'የፈተና መስመር',
      description: 'PostgreSQL async write smoke test',
      distanceKm: 2.5,
      basePriceEtb: 100,
      stops: [
        {
          stopName: 'CI Start',
          stopNameAm: 'የፈተና መነሻ',
          latitude: 9.01,
          longitude: 38.76,
          maxCapacity: 12
        },
        {
          stopName: 'CI Destination',
          stopNameAm: 'የፈተና መድረሻ',
          latitude: 9.02,
          longitude: 38.77,
          maxCapacity: 12
        }
      ]
    }, adminToken);
    assert.equal(result.status, 201, 'Route creation and awaited stop inserts must work');
    assert.equal(result.data.stops.length, 2, 'Both endpoint stops must have been inserted in order');
    const createdRouteId = result.data.route.id;

    // Exercise the structured passenger request flow on PostgreSQL, not just SQLite.
    const routeRequestPhone = '+251900000104';
    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Route Request Passenger',
      phone: routeRequestPhone,
      password: 'CI_Route_Request_Password#2026',
      role: 'PASSENGER',
      requestedRoute: 'Requested route: CI Start → CI Destination | Journey: Round-trip | Outbound pickup: 07:10 | Return pickup: 18:15',
      requestedOrigin: 'CI Start',
      requestedDestination: 'CI Destination',
      requestedJourneyType: 'ROUND_TRIP',
      requestedOutboundPickupTime: '07:10',
      requestedReturnPickupTime: '18:15'
    });
    assert.equal(result.status, 201, 'Passenger route request must register against PostgreSQL');
    const routeRequestPassenger = result.data.user;
    assert.equal(result.data.routeRequest.status, 'PENDING');

    result = await requestJson(baseUrl, '/api/admin/route-requests?status=PENDING', 'GET', null, adminToken);
    assert.equal(result.status, 200, 'Administrator route request list must work against PostgreSQL');
    const routeRequestRow = (result.data.requests || []).find(request =>
      request.passenger_id === routeRequestPassenger.id || request.passengerId === routeRequestPassenger.id
    );
    assert.ok(routeRequestRow, 'PostgreSQL must persist and list the structured passenger route request');
    assert.equal(routeRequestRow.origin, 'CI Start');
    assert.equal(routeRequestRow.destination, 'CI Destination');
    assert.equal(routeRequestRow.journey_type, 'ROUND_TRIP');
    assert.equal(routeRequestRow.outbound_pickup_time, '07:10');
    assert.equal(routeRequestRow.return_pickup_time, '18:15');

    result = await requestJson(baseUrl, '/api/admin/route-requests/' + encodeURIComponent(routeRequestRow.id) + '/assign', 'PATCH', {
      routeId: createdRouteId
    }, adminToken);
    assert.equal(result.status, 200, 'Administrator must be able to assign a matching route request in PostgreSQL');
    assert.equal(result.data.subscription.paymentStatus, 'UNPAID');
    assert.equal(result.data.subscription.subscriptionStatus, 'PENDING');
    assert.equal(result.data.subscription.outboundPickupTime, '07:10');
    assert.equal(result.data.subscription.returnPickupTime, '18:15');

    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Passenger',
      phone: '+251900000102',
      password: 'CI_Passenger_Password#2026',
      role: 'PASSENGER',
      appliedRouteId: 'route_bole_merkato'
    });
    assert.equal(result.status, 201, 'Passenger registration must work against PostgreSQL');
    const passengerToken = result.data.token;
    const passengerSubscriptionId = result.data.subscription.id;

    result = await requestJson(baseUrl, '/api/subscriptions/telebirr/pay', 'POST', {
      routeId: 'route_bole_merkato',
      idempotencyKey: 'ci-live-payment-must-be-blocked'
    }, passengerToken);
    assert.equal(result.status, 503, 'Production-mode real-money payment must fail closed until Telebirr integration is validated');
    assert.equal(result.data.code, 'LIVE_TELEBIRR_NOT_CONFIGURED');

    result = await requestJson(baseUrl, `/api/admin/subscriptions/${encodeURIComponent(passengerSubscriptionId)}/recharge`, 'POST', {
      days: 30
    }, adminToken);
    assert.equal(result.status, 200, 'Admin manual test recharge should activate the pass');
    assert.equal(result.data.testOnly, true, 'Manual recharge must be marked test-only');
    assert.equal(result.data.transaction.provider, 'ADMIN_TEST', 'Manual recharge must not masquerade as Telebirr');
    assert.match(result.data.subscription.qrToken, /^RP1:/, 'Manual recharge must issue a signed QR pass');

    // A driver self-registers, awaits admin approval, then receives a route assignment.
    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Driver Owner',
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      role: 'DRIVER',
      licenseNumber: 'CI-DL-1',
      companyName: 'CI Transport',
      assignedVehiclePlate: '3-CI-0001',
      vehicleModel: 'CI Minibus',
      vehicleType: 'MINIBUS_14'
    });
    assert.equal(result.status, 201, 'Driver owner self-registration must create the pending driver and owned vehicle');
    const driver = result.data.user;
    assert.equal(driver.role, 'DRIVER');
    assert.equal(driver.status, 'PENDING');
    assert.equal(result.data.token, null, 'Pending driver registration must not create a login session');
    assert.equal(driver.assignedVehiclePlate, '3-CI-0001');
    assert.equal(driver.appliedRouteId, '', 'A newly registered driver must not choose their own route');

    result = await requestJson(baseUrl, '/api/auth/login', 'POST', {
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      role: 'DRIVER'
    });
    assert.equal(result.status, 403, 'Pending driver must not log in before admin approval');
    assert.equal(result.data.code, 'PENDING');

    result = await requestJson(baseUrl, `/api/admin/drivers/${encodeURIComponent(driver.id)}/approval`, 'PATCH', {
      status: 'ACTIVE'
    }, adminToken);
    assert.equal(result.status, 200, 'Admin should explicitly approve a driver before route assignment');

    result = await requestJson(baseUrl, `/api/admin/drivers/${encodeURIComponent(driver.id)}/route`, 'PATCH', {
      routeId: 'route_bole_merkato'
    }, adminToken);
    assert.equal(result.status, 200, 'Admin should assign an active route to the approved driver-owned vehicle');
    assert.equal(result.data.vehicle.plateNumber, '3-CI-0001');
    const driverVehicleId = result.data.vehicle.id;

    result = await requestJson(baseUrl, '/api/auth/login', 'POST', {
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      role: 'DRIVER'
    });
    assert.equal(result.status, 200, 'Approved driver must be able to log in');
    const driverToken = result.data.token;

    result = await requestJson(baseUrl, '/api/trips/start', 'POST', {
      routeId: 'route_bole_merkato',
      vehicleId: driverVehicleId,
      direction: 'OUTBOUND',
      latitude: 8.995,
      longitude: 38.788,
      arrivalConfirmed: true
    }, driverToken);
    assert.equal(result.status, 200, 'Approved driver with an assigned route must be able to start trip');
    assert.equal(result.data.trip.status, 'IN_PROGRESS');

    result = await requestJson(baseUrl, '/api/vehicles/my-location', 'POST', {
      latitude: 9.01, longitude: 38.76, speed: 1.2, currentStop: 'CI Stop'
    }, driverToken);
    assert.equal(result.status, 200, 'Driver GPS update must be accepted by backend');
    assert.equal(result.data.latitude, 9.01);

    result = await requestJson(baseUrl, '/api/admin/subscriptions/' + encodeURIComponent(passengerSubscriptionId) + '/assignment', 'PATCH', {
      vehicleId: driverVehicleId
    }, adminToken);
    assert.equal(result.status, 200, 'Admin must assign the approved driver-owned vehicle to the passenger subscription');

    result = await requestJson(baseUrl, '/api/vehicles/tracking', 'GET', null, passengerToken);
    assert.equal(result.status, 200, 'Passenger tracking endpoint must work for active assigned subscription');
    assert.equal(result.data.vehicle.id, driverVehicleId);
    assert.equal(result.data.vehicle.hasGpsLocation, true, 'Passenger tracking must return server-confirmed GPS coordinates');
    assert.equal(result.data.vehicle.latitude, 9.01);

    // No test should produce a server-confirmed live payment in production mode.
    console.log('PostgreSQL API smoke test passed: readiness, read, write, transaction, auth, and payment fail-closed.');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

run().catch((err) => {
  console.error(err);
  process.exitCode = 1;
  server.close(() => {});
});
