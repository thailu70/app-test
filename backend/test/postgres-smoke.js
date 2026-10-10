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

    result = await requestJson(baseUrl, '/api/routes', 'POST', {
      name: 'CI Sample Route',
      nameAm: 'የፈተና መስመር',
      description: 'PostgreSQL async write smoke test',
      distanceKm: 2.5,
      basePriceEtb: 100,
      stops: [{
        stopName: 'CI Stop',
        stopNameAm: 'የፈተና ማቆሚያ',
        latitude: 9.01,
        longitude: 38.76,
        maxCapacity: 12
      }]
    }, adminToken);
    assert.equal(result.status, 201, 'Route creation and awaited stop inserts must work');
    assert.equal(result.data.stops.length, 1, 'Nested route stop must have been inserted');
    const createdRouteId = result.data.route.id;

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
