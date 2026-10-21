const assert = require('node:assert/strict');
const { server, runDatabaseMigrations } = require('../src/server');
const { DB } = require('../src/db');

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

  // Existing VPS databases may have the legacy SQLite-compatible TEXT timestamp type.
  // Exercise the same expiration backfill used during startup against that schema.
  await DB.prepare("INSERT INTO transport_companies (id,name,status) VALUES ('ci_legacy_expiry_company','CI Legacy Expiry Company','ACTIVE') ON CONFLICT (id) DO NOTHING").run();
  await DB.prepare('ALTER TABLE transport_company_access_codes ALTER COLUMN created_at DROP DEFAULT').run();
  await DB.prepare('ALTER TABLE transport_company_access_codes ALTER COLUMN created_at TYPE TEXT USING created_at::text').run();
  await DB.prepare('ALTER TABLE transport_company_access_codes ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP::text').run();
  await DB.prepare("INSERT INTO transport_company_access_codes (id,company_id,code_hash,created_by,revoked,expires_at,created_at) VALUES ('ci_legacy_expiry_code','ci_legacy_expiry_company','ci-legacy-hash',NULL,FALSE,NULL,'2026-10-01 12:00:00+00')").run();
  await runDatabaseMigrations();
  const legacyExpiry = await DB.prepare("SELECT EXTRACT(EPOCH FROM (CAST(expires_at AS TIMESTAMP) - CAST(created_at AS TIMESTAMP))) / 86400 AS days FROM transport_company_access_codes WHERE id='ci_legacy_expiry_code'").get();
  assert.equal(Number(legacyExpiry.days), 7, 'Invitation expiry migration must support legacy text created_at columns');

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

    result = await requestJson(baseUrl, '/api/master-data/vehicle-types');
    assert.equal(result.status, 200);
    assert.equal(result.data.vehicleTypes.length, 4, 'Master data migration seeds the four existing vehicle types');
    result = await requestJson(baseUrl, '/api/master-data/vehicle-types', 'POST', { code: 'CI_COACH_50', name: 'CI Coach', capacity: 50 }, adminToken);
    assert.equal(result.status, 201, 'Admin creates a new PostgreSQL vehicle type');
    result = await requestJson(baseUrl, '/api/master-data/gps-radius', 'GET', null, adminToken);
    assert.equal(result.data.overrideEnabled, false);
    result = await requestJson(baseUrl, '/api/master-data/gps-radius', 'PUT', { radiusMeters: 450 }, adminToken);
    assert.equal(result.status, 200); assert.equal(result.data.radiusMeters, 450);
    assert.equal((await DB.prepare("SELECT value FROM app_settings WHERE key='GPS_BOARDING_RADIUS_METERS'").get()).value, '450');
    assert.equal((await requestJson(baseUrl, '/api/master-data/gps-radius', 'PUT', { radiusMeters: 24 }, adminToken)).status, 400);
    result = await requestJson(baseUrl, '/api/master-data/gps-sharing', 'GET', null, adminToken);
    assert.equal(result.data.intervalSeconds, 5);
    assert.equal((await requestJson(baseUrl, '/api/master-data/gps-sharing', 'PUT', { intervalSeconds: 61 }, adminToken)).status, 400);
    result = await requestJson(baseUrl, '/api/master-data/gps-sharing', 'PUT', { intervalSeconds: 20 }, adminToken);
    assert.equal(result.status, 200); assert.equal(result.data.intervalSeconds, 20);
    assert.equal((await DB.prepare("SELECT value FROM app_settings WHERE key='GPS_SHARING_INTERVAL_SECONDS'").get()).value, '20');

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

    const priorNodeEnv = process.env.NODE_ENV;
    process.env.NODE_ENV = 'production';
    result = await requestJson(baseUrl, `/api/admin/subscriptions/${encodeURIComponent(passengerSubscriptionId)}/recharge`, 'POST', { days: 30 }, adminToken);
    process.env.NODE_ENV = priorNodeEnv;
    assert.equal(result.status, 403, 'Manual test recharge must stay disabled if production has the test flag set');

    // Explicit admin grants also work in production without creating payment records.
    await DB.prepare("UPDATE subscriptions SET subscriptionStatus='PENDING', paymentStatus='UNPAID', qrToken=NULL WHERE id=?").run(passengerSubscriptionId);
    const beforeGrantPayments = (await DB.prepare('SELECT COUNT(*) AS c FROM payment_transactions').get()).c;
    process.env.ALLOW_ADMIN_PASS_ACTIVATION = 'true';
    process.env.NODE_ENV = 'production';
    result = await requestJson(baseUrl, `/api/admin/subscriptions/${encodeURIComponent(passengerSubscriptionId)}/activate`, 'POST', { days: 30, reason: 'CI temporary administrator grant' }, adminToken);
    assert.equal(result.status, 200, 'Explicit admin grant must work in production');
    assert.equal(result.data.noPaymentCollected, true);
    assert.match(result.data.subscription.qrToken, /^RP1:/);
    const firstGrantEnd = result.data.subscription.endDate;
    result = await requestJson(baseUrl, `/api/admin/subscriptions/${encodeURIComponent(passengerSubscriptionId)}/activate`, 'POST', { days: 30, reason: 'CI duplicate grant attempt' }, adminToken);
    assert.equal(result.status, 409, 'Duplicate grants must not extend an active pass');
    assert.equal((await DB.prepare('SELECT endDate FROM subscriptions WHERE id=?').get(passengerSubscriptionId)).endDate, firstGrantEnd);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS c FROM payment_transactions').get()).c, beforeGrantPayments, 'Admin grant must not create a payment');
    delete process.env.ALLOW_ADMIN_PASS_ACTIVATION;
    process.env.NODE_ENV = priorNodeEnv;

    // Company portal signup must hit the current route, consume a PostgreSQL boolean
    // access code once, and reject invitations that have expired.
    result = await requestJson(baseUrl, '/api/admin/companies/invite', 'POST', { name: 'CI Passo Company' }, adminToken);
    assert.equal(result.status, 201, 'Administrator should issue a company invitation');
    const companyId = result.data.company.id;
    const companyCode = result.data.registrationCode;
    assert.equal(result.data.company.invitationExpiresInDays, 7);

    result = await requestJson(baseUrl, '/api/companies/register', 'POST', {
      companyName: 'CI Passo Company', fullName: 'CI Company Admin', phone: '+251900000120',
      password: 'CI_Company_Password#2026', secretCode: companyCode
    });
    assert.equal(result.status, 201, 'Invited company should register using its unexpired single-use code');
    const companyToken = result.data.token;
    result = await requestJson(baseUrl, '/api/companies/me', 'GET', null, companyToken);
    assert.equal(result.status, 200, 'Company portal profile route must resolve to its authenticated implementation');
    assert.equal(result.data.company.id, companyId);

    result = await requestJson(baseUrl, '/api/admin/companies/invite', 'POST', { name: 'CI Expired Company' }, adminToken);
    assert.equal(result.status, 201, 'Administrator should issue a second company invitation');
    const expiredInvite = result.data;
    await DB.prepare("UPDATE transport_company_access_codes SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE company_id=?").run(expiredInvite.company.id);
    result = await requestJson(baseUrl, '/api/companies/register', 'POST', {
      companyName: 'CI Expired Company', fullName: 'Expired Company Admin', phone: '+251900000121',
      password: 'CI_Expired_Company#2026', secretCode: expiredInvite.registrationCode
    });
    assert.equal(result.status, 403, 'Expired company invitations must be rejected');
    result = await requestJson(baseUrl, '/api/admin/companies/invite', 'POST', { name: 'CI Expired Company' }, adminToken);
    assert.equal(result.status, 201, 'Administrator should be able to reissue an expired invitation');
    assert.notEqual(result.data.registrationCode, expiredInvite.registrationCode, 'Reissue must replace the expired code');
    result = await requestJson(baseUrl, '/api/companies/register', 'POST', {
      companyName: 'CI Expired Company', fullName: 'Renewed Company Admin', phone: '+251900000123',
      password: 'CI_Renewed_Company#2026', secretCode: result.data.registrationCode
    });
    assert.equal(result.status, 201, 'Reissued company invitation should register successfully');

    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Company Driver', phone: '+251900000122', password: 'CI_Company_Driver#2026',
      role: 'DRIVER', licenseNumber: 'CI-COMPANY-DL-1', companyName: 'CI Passo Company'
    });
    assert.equal(result.status, 201, 'Company driver self-registration should create a pending driver without a personal vehicle');
    const companyDriver = result.data.user;
    result = await requestJson(baseUrl, `/api/companies/operations/drivers/${encodeURIComponent(companyDriver.id)}/approval`, 'PATCH', { action: 'APPROVE' }, companyToken);
    assert.equal(result.status, 200, 'Company should approve its own driver');

    result = await requestJson(baseUrl, '/api/companies/operations/vehicles', 'POST', {
      plateNumber: '3-CI-0200', model: 'CI Company Coach', vehicleType: 'CI_COACH_50'
    }, companyToken);
    assert.equal(result.status, 201, 'Company should register its own vehicle');
    const companyVehicleId = result.data.vehicle.id;
    assert.equal(result.data.vehicle.capacityLimit, 50, 'New catalog type must pass the migrated PostgreSQL vehicle-type constraint');
    result = await requestJson(baseUrl, '/api/master-data/vehicle-types/CI_COACH_50', 'PATCH', { name: 'CI Premium Coach', capacity: 52 }, adminToken);
    assert.equal(result.status, 200);
    assert.equal((await DB.prepare('SELECT capacityLimit FROM vehicles WHERE id=?').get(companyVehicleId)).capacityLimit, 50, 'Catalog edits retain registered physical vehicle capacity');
    await require('../src/services/master-data').initMasterDataSchema(DB);
    assert.equal((await DB.prepare("SELECT capacity FROM vehicle_types WHERE code='CI_COACH_50'").get()).capacity, 52, 'Repeated master-data migration must preserve administrator edits');

    result = await requestJson(baseUrl, '/api/companies/operations/routes', 'POST', {
      name: 'CI Company Route', nameAm: 'የኩባንያ መስመር', routeType: 'INTERCITY',
      origin: 'Addis Ababa', destination: 'Hawassa', departureTime: '08:00', distanceKm: 270, basePriceEtb: 500
    }, companyToken);
    assert.equal(result.status, 201, 'Company should create a route under its own tenant ID');
    const companyRouteId = result.data.route.id;

    result = await requestJson(baseUrl, '/api/companies/operations/assignments', 'POST', {
      routeId: createdRouteId, driverId: companyDriver.id, vehicleId: companyVehicleId
    }, companyToken);
    assert.equal(result.status, 403, 'Company must not assign its assets to another tenant’s route');

    result = await requestJson(baseUrl, '/api/companies/operations/assignments', 'POST', {
      routeId: companyRouteId, driverId: companyDriver.id, vehicleId: companyVehicleId
    }, companyToken);
    assert.equal(result.status, 201, 'Company may assign its own active driver and vehicle to its route');
    const companyAssignmentId = result.data.assignment.id;
    // Read every dataset used by the company portal against the production adapter.
    for (const [path, key, expectedId] of [
      ['/api/companies/operations/fleet', 'vehicles', companyVehicleId],
      ['/api/companies/operations/drivers', 'drivers', companyDriver.id],
      ['/api/companies/operations/routes', 'routes', companyRouteId]
    ]) {
      const dataset = await requestJson(baseUrl, path, 'GET', null, companyToken);
      assert.equal(dataset.status, 200, path + ' must load independently');
      assert.ok(dataset.data[key].some(row => row.id === expectedId), path + ' must include the company record');
    }
    result = await requestJson(baseUrl, '/api/companies/operations/summary', 'GET', null, companyToken);
    assert.equal(result.status, 200, 'Company summary must load on PostgreSQL');
    assert.equal(result.data.summary.activeAssignments, 1);
    result = await requestJson(baseUrl, '/api/companies/operations/assignments', 'POST', {
      routeId: companyRouteId, driverId: companyDriver.id, vehicleId: companyVehicleId
    }, companyToken);
    assert.equal(result.status, 409, 'Company must not create duplicate active assignments');

    result = await requestJson(baseUrl, '/api/auth/login', 'POST', {
      phone: '+251900000122', password: 'CI_Company_Driver#2026', role: 'DRIVER'
    });
    assert.equal(result.status, 200, 'Approved company driver should be able to log in');
    const companyDriverToken = result.data.token;
    assert.equal((await DB.prepare('SELECT companyId FROM users WHERE id=?').get(companyDriver.id)).companyId, companyId, 'PostgreSQL must expose company ownership in the shared API shape');
    const minimalDriverToken = require('../src/middleware/auth').signToken({ id: companyDriver.id, role: 'DRIVER' });
    assert.equal((await require('../src/middleware/auth').resolveActivePrincipal(minimalDriverToken)).companyId, companyId, 'Driver company scope must come from the database, including tokens without a company claim');
    result = await requestJson(baseUrl, '/api/trips/start', 'POST', {
      routeId: companyRouteId, vehicleId: companyVehicleId
    }, companyDriverToken);
    assert.ok([403, 404].includes(result.status), 'Company intercity journeys must not enter the commute trip workflow');
    result = await requestJson(baseUrl, '/api/intercity/company/departures', 'POST', {
      routeId: companyRouteId, vehicleId: companyVehicleId, driverId: companyDriver.id,
      departureAt: new Date(Date.now() + 5 * 60000).toISOString(), latitude: 9.02, longitude: 38.77,
      radiusMeters: 300, seats: 14, fareEtb: 500
    }, companyToken);
    assert.equal(result.status, 201, 'Company should schedule a dated intercity departure');
    const companyDepartureId = result.data.departure.id;
    result = await requestJson(baseUrl, `/api/intercity/driver/departures/${companyDepartureId}/arrival`, 'POST', {
      location: { latitude: 9.02, longitude: 38.77, accuracyMeters: 5, timestamp: Date.now() }
    }, companyDriverToken);
    assert.equal(result.status, 200, 'Assigned driver should confirm fresh GPS within the departure time window');
    assert.equal(result.data.departure.status, 'BOARDING');
    result = await requestJson(baseUrl, '/api/vehicles/my-location', 'POST', {
      latitude: 9.02, longitude: 38.77, speed: 0, currentStop: 'Company departure'
    }, companyDriverToken);
    assert.equal(result.status, 200, 'Intercity driver must publish GPS while boarding');
    result = await requestJson(baseUrl, '/api/fleet/tracking', 'GET', null, companyToken);
    assert.equal(result.status, 200, 'Company GPS map must load');
    assert.ok(result.data.vehicles.some(v => v.id === companyVehicleId && v.location.status === 'LIVE'));
    result = await requestJson(baseUrl, '/api/fleet/tracking', 'GET', null, adminToken);
    assert.equal(result.status, 200, 'Administrator GPS map must load');
    assert.equal(result.data.vehicles.some(v => v.id === companyVehicleId), false, 'Company GPS must remain private from commute admin');
    result = await requestJson(baseUrl, `/api/intercity/driver/departures/${companyDepartureId}/roster`, 'GET', null, companyDriverToken);
    assert.equal(result.status, 200, 'Assigned company driver should see the intercity roster');
    assert.deepEqual(result.data.roster, []);

    result = await requestJson(baseUrl, `/api/companies/operations/assignments/${encodeURIComponent(companyAssignmentId)}`, 'DELETE', null, companyToken);
    assert.equal(result.status, 409, 'Company cannot unassign a vehicle while its trip is active');
    result = await requestJson(baseUrl, `/api/intercity/driver/departures/${companyDepartureId}/complete`, 'POST', {}, companyDriverToken);
    assert.equal(result.status, 200, 'Company driver should complete the active journey without destination GPS');
    result = await requestJson(baseUrl, `/api/companies/operations/assignments/${encodeURIComponent(companyAssignmentId)}`, 'DELETE', null, companyToken);
    assert.equal(result.status, 200, 'Company should be able to remove an idle assignment without deleting its vehicle');

    result = await requestJson(baseUrl, '/api/vehicles', 'GET');
    assert.equal(result.status, 200, 'Public vehicle directory should remain available');
    assert.equal(Object.hasOwn(result.data.vehicles[0], 'driverId'), false, 'Public vehicle directory must not expose driver IDs');
    assert.equal(Object.hasOwn(result.data.vehicles[0], 'currentLat'), false, 'Public vehicle directory must not expose precise coordinates');
    result = await requestJson(baseUrl, '/api/vehicles', 'GET', null, adminToken);
    assert.equal(result.status, 200, 'Administrator fleet view should retain authorized driver details');
    assert.equal(Object.hasOwn(result.data.vehicles[0], 'driverId'), true);

    result = await requestJson(baseUrl, `/api/admin/drivers/${encodeURIComponent(companyDriver.id)}/approval`, 'PATCH', { status: 'SUSPENDED' }, adminToken);
    assert.ok([403, 404].includes(result.status), 'Commute administrator must not manage a company intercity driver');
    result = await requestJson(baseUrl, `/api/companies/operations/drivers/${encodeURIComponent(companyDriver.id)}/approval`, 'PATCH', { action: 'REJECT' }, companyToken);
    assert.equal(result.status, 200, 'Company should be able to deactivate its own driver');
    result = await requestJson(baseUrl, '/api/trips/active', 'GET', null, companyDriverToken);
    assert.equal(result.status, 401, 'Previously issued bearer token must stop working after driver suspension');

    await DB.prepare("UPDATE transport_companies SET status='INACTIVE' WHERE id=?").run(companyId);
    result = await requestJson(baseUrl, '/api/companies/me', 'GET', null, companyToken);
    assert.equal(result.status, 401, 'Previously issued company token must stop working after company suspension');

    // A driver self-registers, awaits admin approval, then receives a route assignment.
    result = await requestJson(baseUrl, '/api/auth/register', 'POST', {
      fullName: 'CI Driver Owner',
      phone: '+251900000103',
      password: 'CI_Driver_Password#2026',
      role: 'DRIVER',
      licenseNumber: 'CI-DL-1',
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

    result = await requestJson(baseUrl, '/api/vehicles/my-location', 'POST', {
      latitude: 9.01, longitude: 38.76, speed: 1.2, currentStop: 'Off-duty attempt'
    }, driverToken);
    assert.equal(result.status, 409, 'Driver must not publish live GPS before starting an assigned trip');

    await DB.prepare('UPDATE routes SET morningDeparture=? WHERE id=?').run(new Date(Date.now() + 3 * 3600000).toISOString().slice(11, 16), 'route_bole_merkato');

    result = await requestJson(baseUrl, '/api/trips/start', 'POST', {
      routeId: 'route_bole_merkato',
      vehicleId: driverVehicleId,
      location: { latitude: 8.995, longitude: 38.788, accuracyMeters: 5, timestamp: Date.now() }
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

    result = await requestJson(baseUrl, '/api/commute/my-driver', 'GET', null, passengerToken);
    assert.equal(result.status, 200);
    assert.equal(result.data.driver.vehicleId, driverVehicleId, 'Driver card must match the tracked vehicle');
    result = await requestJson(baseUrl, '/api/subscriptions/my-status', 'GET', null, passengerToken);
    assert.equal(result.data.subscription.vehicleId, driverVehicleId, 'Pass, driver card and tracking must share the assignment');
    await DB.prepare("UPDATE trips SET status='COMPLETED' WHERE vehicleId=? AND status='IN_PROGRESS'").run(driverVehicleId);
    result = await requestJson(baseUrl, '/api/vehicles/tracking', 'GET', null, passengerToken);
    assert.equal(result.status, 200);
    assert.equal(result.data.vehicle.id, driverVehicleId, 'Completing a trip must not pretend the passenger is unassigned');
    assert.equal(result.data.vehicle.trackingStatus, 'OFFLINE');

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

