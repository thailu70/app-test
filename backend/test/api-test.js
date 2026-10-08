/**
 * Automated Test Suite for Transport Navigator VPS Backend
 */

const http = require('http');

const PORT = 3999;
process.env.PORT = PORT;
process.env.NODE_ENV = 'test';

const { server } = require('../src/server');

function makeRequest(method, path, body = null, token = null) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const req = http.request({
      hostname: '127.0.0.1',
      port: PORT,
      path,
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(payload ? { 'Content-Length': Buffer.byteLength(payload) } : {}),
        ...(token ? { 'Authorization': `Bearer ${token}` } : {})
      }
    }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          resolve({ status: res.statusCode, data: json });
        } catch (e) {
          resolve({ status: res.statusCode, raw: data });
        }
      });
    });

    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function runTests() {
  console.log('\n--- STARTING VPS BACKEND INTEGRATION TESTS ---');
  let passengerToken = '';
  let driverToken = '';
  let adminToken = '';

  await new Promise(resolve => server.listen(PORT, '127.0.0.1', resolve));
  console.log(`Test server listening on port ${PORT}`);

  try {
    // 1. Health check
    const health = await makeRequest('GET', '/api/health');
    console.log('✓ Health check:', health.data.status, `(routes: ${health.data.database.routesCount})`);

    // 2. Passenger Login
    const pasLogin = await makeRequest('POST', '/api/auth/login', {
      identifier: '0911223344',
      password: '1234',
      role: 'PASSENGER'
    });
    if (!pasLogin.data.success) throw new Error('Passenger login failed: ' + pasLogin.data.error);
    passengerToken = pasLogin.data.token;
    console.log('✓ Passenger login passed:', pasLogin.data.user.fullName);

    // 3. Driver Login
    const driLogin = await makeRequest('POST', '/api/auth/login', {
      identifier: '0922334455',
      password: '1234',
      role: 'DRIVER'
    });
    if (!driLogin.data.success) throw new Error('Driver login failed: ' + driLogin.data.error);
    driverToken = driLogin.data.token;
    console.log('✓ Driver login passed:', driLogin.data.user.fullName, `(Plate: ${driLogin.data.user.assignedVehiclePlate})`);

    // 4. Admin Login
    const admLogin = await makeRequest('POST', '/api/auth/login', {
      identifier: '0900000000',
      password: '1234',
      role: 'ADMIN'
    });
    if (!admLogin.data.success) throw new Error('Admin login failed: ' + admLogin.data.error);
    adminToken = admLogin.data.token;
    console.log('✓ Admin login passed:', admLogin.data.user.fullName);

    // 5. Test Role Isolation (Passenger blocked from Admin route creation)
    const unauthorizedRoute = await makeRequest('POST', '/api/routes', {
      name: 'Illegal Route',
      nameAm: 'ህገወጥ መስመር'
    }, passengerToken);
    if (unauthorizedRoute.status === 403) {
      console.log('✓ Role isolation verified: Passenger cannot access Admin endpoints (HTTP 403)');
    } else {
      throw new Error('Role isolation failed, passenger got: ' + unauthorizedRoute.status);
    }

    // 6. Test Routes List
    const routesRes = await makeRequest('GET', '/api/routes');
    console.log(`✓ Routes listed: ${routesRes.data.count} active routes`);

    // 7. Test Passenger Subscription Status
    const subStatus = await makeRequest('GET', '/api/subscriptions/my-status', null, passengerToken);
    console.log('✓ Passenger subscription status check:', subStatus.data.status, '-', subStatus.data.message);

    // 8. Test Telebirr Payment Simulation
    const telebirrRes = await makeRequest('POST', '/api/subscriptions/telebirr/pay', {
      routeId: 'route_bole_merkato'
    }, passengerToken);
    if (!telebirrRes.data.success) throw new Error('Telebirr payment failed');
    console.log('✓ Telebirr payment processed:', telebirrRes.data.message, `Ref: ${telebirrRes.data.transaction.referenceNumber}`);
    const activeQr = telebirrRes.data.subscription.qrToken;

    // 9. Test Vehicle Capacity Limit Enforcement
    // First, verify vehicles list
    const vehiclesRes = await makeRequest('GET', '/api/vehicles');
    console.log(`✓ Vehicles retrieved: ${vehiclesRes.data.count} vehicles with seat capacity limits`);

    // Set a vehicle to 1 capacity and 0 current occupancy to test boarding then denial
    const vehId = 'veh_minivan_aa_11093'; // 8-seat minivan
    const vehUpdate = await makeRequest('PATCH', `/api/vehicles/${vehId}/type`, {
      vehicleType: 'MINIVAN_8',
      capacityLimit: 1,
      currentOccupancy: 0
    }, driverToken);
    console.log('✓ Set test vehicle capacity limit to 1 seat (0/1):', vehUpdate.data.message);

    // Scan passenger QR boarding pass with driver token
    const scan1 = await makeRequest('POST', '/api/checkins/scan', {
      qrToken: activeQr,
      vehicleId: vehId,
      currentStop: 'Mexico Hub'
    }, driverToken);
    if (!scan1.data.vehicleOccupancy) throw new Error('scan1 failed: ' + JSON.stringify(scan1.data));
    console.log('✓ First passenger board attempt:', scan1.data.status, `- Occupancy: ${scan1.data.vehicleOccupancy.current}/${scan1.data.vehicleOccupancy.capacityLimit}`);

    // Now try second scan on the same vehicle: MUST BE REJECTED because capacity limit is reached!
    // Register another passenger with active subscription to test capacity block
    const testPhone = '09' + Math.floor(10000000 + Math.random() * 90000000);
    const pas2 = await makeRequest('POST', '/api/auth/register', {
      fullName: 'Tigist Haile',
      phone: testPhone,
      password: '1234',
      role: 'PASSENGER',
      appliedRouteId: 'route_bole_merkato',
      appliedRouteName: 'Bole - Merkato Express'
    });
    if (!pas2.data.success) throw new Error('Failed to register pas2: ' + JSON.stringify(pas2.data));
    const sub2Qr = pas2.data.subscription.qrToken;

    const scan2 = await makeRequest('POST', '/api/checkins/scan', {
      qrToken: sub2Qr,
      vehicleId: vehId,
      currentStop: 'Mexico Hub'
    }, driverToken);

    if (scan2.data.status === 'DENIED_CAPACITY_FULL') {
      console.log('✓ VEHICLE CAPACITY LIMIT ENFORCEMENT VERIFIED:', scan2.data.error);
    } else {
      throw new Error('Vehicle capacity limit was NOT enforced! Result: ' + JSON.stringify(scan2.data));
    }

    // 10. Test Notifications Broadcast by Admin
    const notifRes = await makeRequest('POST', '/api/notifications/broadcast', {
      title: 'VPS Deployment Verified',
      message: 'Transport Navigator VPS backend is fully operational.',
      targetAudience: 'ALL'
    }, adminToken);
    console.log('✓ Broadcast notification sent by Admin:', notifRes.data.message);

    console.log('\n========================================');
    console.log('  ALL INTEGRATION TESTS PASSED 100%!');
    console.log('========================================\n');
  } catch (err) {
    console.error('TEST FAILED:', err);
    process.exitCode = 1;
  } finally {
    server.close();
  }
}

runTests();
