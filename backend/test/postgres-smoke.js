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

    result = await requestJson(baseUrl, '/api/subscriptions/telebirr/pay', 'POST', {
      routeId: 'route_bole_merkato',
      idempotencyKey: 'ci-live-payment-must-be-blocked'
    }, passengerToken);
    assert.equal(result.status, 503, 'Production-mode real-money payment must fail closed until Telebirr integration is validated');
    assert.equal(result.data.code, 'LIVE_TELEBIRR_NOT_CONFIGURED');

    result = await requestJson(baseUrl, '/api/admin/drivers', 'POST', {
      fullName: 'CI Driver',
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      licenseNumber: 'CI-DL-1',
      companyName: 'CI Transport',
      vehicleId: 'veh_higer_aa_34921',
      routeId: 'route_bole_merkato'
    }, adminToken);
    assert.equal(result.status, 201, 'Admin-only driver provisioning transaction must commit');
    const driver = result.data.driver;
    assert.equal(driver.appliedRouteId, 'route_bole_merkato');

    result = await requestJson(baseUrl, '/api/auth/login', 'POST', {
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      role: 'DRIVER'
    });
    assert.equal(result.status, 200, 'Created driver must be able to login');
    const driverToken = result.data.token;

    result = await requestJson(baseUrl, '/api/trips/start', 'POST', {
      routeId: 'route_bole_merkato'
    }, driverToken);
    assert.equal(result.status, 200, 'Assigned driver must be able to start trip');
    assert.equal(result.data.trip.status, 'IN_PROGRESS');

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
