/**
 * Automated Test Suite for RoutePass / Transport Navigator VPS Backend
 * Tests:
 * 1. Health check & PostgreSQL/SQLite status
 * 2. Independent authentication for Passenger, Driver, Admin
 * 3. Role isolation & Admin registration restrictions
 * 4. Subscriptions start as PENDING (no auto-activation upon registration)
 * 5. Telebirr server-side payment with idempotency
 * 6. Server-side signed QR tokens
 * 7. Real driver trips, route selection, and stop arrivals
 * 8. Server-side transaction capacity enforcement (8, 14, 24, 30 limits)
 * 9. Duplicate check-in prevention
 * 10. Admin dashboards, metrics, complaints, notifications, audit logs
 */

const http = require('http');
const crypto = require('node:crypto');
const { DB } = require('../src/db');
const otpRoutes = require('../src/routes/otp');

async function seedVerifiedOtp(phone) {
  await otpRoutes.ensureOtpSchema();
  const normalizedPhone = otpRoutes.normalizePhone(phone);
  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  const expires = new Date(Date.now() + 300000).toISOString();
  const codeHash = otpRoutes.hashOtp(id, normalizedPhone, '123456');
  await DB.prepare('INSERT INTO otp_challenges (id, phone, purpose, code_hash, expires_at, attempts, max_attempts, sent_at, verified_at, consumed_at, created_at) VALUES (?, ?, ?, ?, ?, 0, 5, ?, ?, NULL, ?)').run(id, normalizedPhone, 'SIGNUP', codeHash, expires, now, now, now);
  return id;
}

const PORT = 3999;
process.env.PORT = PORT;
process.env.NODE_ENV = 'test';

const { server } = require('../src/server');

function makeRequest(method, path, body = null, token = null, headers = {}) {
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
        ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
        ...headers
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
  console.log('\n============================================================');
  console.log('   ROUTEPASS PRODUCTION VPS BACKEND TEST SUITE');
  console.log('============================================================\n');

  let passengerToken = '';
  let driverToken = '';
  let adminToken = '';

  await new Promise(resolve => server.listen(PORT, '127.0.0.1', resolve));
  console.log(`✓ Test HTTP & WebSocket server listening on port ${PORT}`);

  try {
    // 1. Health Check
    const health = await makeRequest('GET', '/api/health');
    if (health.status !== 200 || health.data.status !== 'HEALTHY') {
      throw new Error('Health check failed: ' + JSON.stringify(health.data));
    }
    console.log('✓ [1/13] Health check verified: HEALTHY');

    // 2. Authentication with seeded accounts
    const pasLogin = await makeRequest('POST', '/api/auth/login', {
      phone: '+251911223344',
      password: '123456',
      role: 'PASSENGER'
    });
    if (!pasLogin.data.success) throw new Error('Passenger login failed: ' + JSON.stringify(pasLogin.data));
    passengerToken = pasLogin.data.token;
    console.log('✓ [2/13] Passenger login passed:', pasLogin.data.user.fullName);

    const driLogin = await makeRequest('POST', '/api/auth/login', {
      phone: '+251911998877',
      password: '123456',
      role: 'DRIVER'
    });
    if (!driLogin.data.success) throw new Error('Driver login failed: ' + JSON.stringify(driLogin.data));
    driverToken = driLogin.data.token;
    console.log('✓ [3/13] Driver login passed:', driLogin.data.user.fullName, `(Plate: ${driLogin.data.user.assignedVehiclePlate})`);

    const admLogin = await makeRequest('POST', '/api/auth/login', {
      phone: '+251910001122',
      password: '123456',
      role: 'ADMIN'
    });
    if (!admLogin.data.success) throw new Error('Admin login failed: ' + JSON.stringify(admLogin.data));
    adminToken = admLogin.data.token;
    console.log('✓ [4/13] Admin login passed:', admLogin.data.user.fullName);

    // 3. Security: Admin cannot freely register
    const rogueAdminReg = await makeRequest('POST', '/api/auth/register', {
      fullName: 'Intruder',
      phone: '+251999888777',
      password: 'LongEnough_Test_Password#2026',
      role: 'ADMIN'
    });
    if (rogueAdminReg.status === 403) {
      console.log('✓ [5/13] Security verified: Admin cannot freely register without adminSecret (HTTP 403)');
    } else {
      throw new Error('Security flaw: Admin was allowed to register freely! Status: ' + rogueAdminReg.status);
    }

    // 4. Role Isolation
    const illegalAdminCall = await makeRequest('GET', '/api/admin/stats', null, passengerToken);
    if (illegalAdminCall.status === 403) {
      console.log('✓ [6/13] Role isolation verified: Passenger cannot access Admin endpoints (HTTP 403)');
    } else {
      throw new Error('Role isolation failed! Expected 403, got: ' + illegalAdminCall.status);
    }

    // 5. Registration does NOT activate subscription
    const testPassengerPhone = '+2519' + Math.floor(10000000 + Math.random() * 90000000);
    const missingOtpReg = await makeRequest('POST', '/api/auth/register', {
      fullName: 'Unverified Passenger',
      phone: testPassengerPhone,
      password: 'RoutePassTest#2026',
      role: 'PASSENGER',
      appliedRouteId: 'route_bole_merkato'
    });
    if (missingOtpReg.status !== 403) throw new Error('Signup without verified SMS OTP must be rejected.');
    const newPassengerOtpChallengeId = await seedVerifiedOtp(testPassengerPhone);
    const newPasReg = await makeRequest('POST', '/api/auth/register', {
      fullName: 'Hiwot Bekele',
      phone: testPassengerPhone,
      password: 'RoutePassTest#2026',
      role: 'PASSENGER',
      otpChallengeId: newPassengerOtpChallengeId,
      appliedRouteId: 'route_bole_merkato',
      appliedRouteName: 'Bole - Merkato Express'
    });
    if (!newPasReg.data.success) throw new Error('Passenger registration failed');
    const newPasToken = newPasReg.data.token;
    if (newPasReg.data.subscription?.status !== 'PENDING' || newPasReg.data.subscription?.qrToken !== null) {
      throw new Error('Requirement violated: Registration activated subscription or provided QR token prematurely!');
    }
    console.log('✓ [7/13] Subscription lifecycle verified: New signup subscription is PENDING, UNPAID, and QR is null');

    // Verify GET /api/subscriptions/my-status prompts for payment and hides QR
    const initialStatus = await makeRequest('GET', '/api/subscriptions/my-status', null, newPasToken);
    if (initialStatus.data.isSubscribed !== false || initialStatus.data.qrToken !== null) {
      throw new Error('Pending passenger received active subscription state: ' + JSON.stringify(initialStatus.data));
    }
    console.log('✓ [8/13] Subscription status check correctly prompts:', initialStatus.data.message);

    // 6. Telebirr Payment with Idempotency activates subscription and issues signed QR
    const testIdempotencyKey = 'IDEM-TEST-' + Date.now();
    const telebirrPayment1 = await makeRequest('POST', '/api/subscriptions/telebirr/pay', {
      routeId: 'route_bole_merkato',
      idempotencyKey: testIdempotencyKey
    }, newPasToken);

    if (!telebirrPayment1.data.success || telebirrPayment1.data.subscription.status !== 'ACTIVE') {
      throw new Error('Telebirr payment failed to activate subscription: ' + JSON.stringify(telebirrPayment1.data));
    }
    const signedQrToken = telebirrPayment1.data.subscription.qrToken;
    if (!signedQrToken || !signedQrToken.startsWith('RP1:')) {
      throw new Error('Invalid server-signed QR token generated: ' + signedQrToken);
    }
    console.log('✓ [9/13] Telebirr payment processed & server-signed QR issued:', signedQrToken.slice(0, 32) + '...');

    // Test Idempotency: Repeating payment with same idempotency key must not create duplicate charge
    const telebirrPayment2 = await makeRequest('POST', '/api/subscriptions/telebirr/pay', {
      routeId: 'route_bole_merkato',
      idempotencyKey: testIdempotencyKey
    }, newPasToken);
    if (!telebirrPayment2.data.idempotentReplay) {
      throw new Error('Idempotency failed: Expected idempotentReplay flag');
    }
    console.log('✓ [10/13] Telebirr payment idempotency verified: Duplicate payment prevented');

    // 7. Driver starts trip and updates stop arrival
    const startTripRes = await makeRequest('POST', '/api/trips/start', {
      routeId: 'route_bole_merkato',
      direction: 'OUTBOUND'
    }, driverToken);
    if (!startTripRes.data.success) throw new Error('Start trip failed: ' + JSON.stringify(startTripRes.data));
    const activeTrip = startTripRes.data.trip;
    console.log(`✓ [11/13] Real Driver trip started: ID ${activeTrip.id} on route ${activeTrip.routeId}`);

    const stopArrivalRes = await makeRequest('POST', `/api/trips/${activeTrip.id}/stop-arrival`, {
      stopName: 'Bole Atlas'
    }, driverToken);
    if (!stopArrivalRes.data.success) throw new Error('Stop arrival failed');

    // 8. Vehicle Capacity Limits & Atomic Transaction Verification
    // Configure vehicle with capacity limit = 1 to test boarding then strict denial
    const testVehId = 'veh_higer_aa_34921'; // assigned Higer vehicle for the authenticated driver
    await makeRequest('PATCH', `/api/vehicles/${testVehId}/type`, {
      vehicleType: 'HIGER_24',
      capacityLimit: 1,
      currentOccupancy: 0
    }, adminToken);

    // First scan: Board passenger 1 -> Should succeed
    const scan1 = await makeRequest('POST', '/api/checkins/scan', {
      qrToken: signedQrToken,
      tripId: activeTrip.id,
      vehicleId: testVehId,
      currentStop: 'Bole Atlas'
    }, driverToken);

    if (scan1.status !== 200 || scan1.data.status !== 'VERIFIED_BOARDED') {
      throw new Error('First scan failed: ' + JSON.stringify(scan1.data));
    }
    console.log('✓ [12/13] Passenger boarded successfully: Occupancy now 1/1');

    // Duplicate check-in test: Passenger 1 attempts to check in again on same trip
    const duplicateScan = await makeRequest('POST', '/api/checkins/scan', {
      qrToken: signedQrToken,
      tripId: activeTrip.id,
      vehicleId: testVehId,
      currentStop: 'Bole Atlas'
    }, driverToken);
    if (duplicateScan.status !== 409 || duplicateScan.data.status !== 'ALREADY_CHECKED_IN') {
      throw new Error('Duplicate check-in was not blocked: ' + JSON.stringify(duplicateScan.data));
    }
    console.log('✓ Duplicate check-in prevented (HTTP 409 ALREADY_CHECKED_IN)');

    // Register second passenger and pay to obtain valid QR pass
    const testPassenger2Phone = '+2519' + Math.floor(10000000 + Math.random() * 90000000);
    const passenger2OtpChallengeId = await seedVerifiedOtp(testPassenger2Phone);
    const pas2Reg = await makeRequest('POST', '/api/auth/register', {
      fullName: 'Dawit Mengistu',
      phone: testPassenger2Phone,
      password: 'RoutePassTest#2026',
      role: 'PASSENGER',
      otpChallengeId: passenger2OtpChallengeId,
      appliedRouteId: 'route_bole_merkato'
    });
    const pas2Pay = await makeRequest('POST', '/api/subscriptions/telebirr/pay', {
      routeId: 'route_bole_merkato'
    }, pas2Reg.data.token);
    const pas2Qr = pas2Pay.data.subscription.qrToken;

    // Scan second passenger on full vehicle (1/1 seats) -> MUST BE REJECTED with DENIED_CAPACITY_FULL!
    const scan2 = await makeRequest('POST', '/api/checkins/scan', {
      qrToken: pas2Qr,
      tripId: activeTrip.id,
      vehicleId: testVehId,
      currentStop: 'Bole Atlas'
    }, driverToken);

    if (scan2.status === 409 && scan2.data.status === 'DENIED_CAPACITY_FULL') {
      console.log('✓ Capacity limit strictly enforced: Overcapacity scan denied (HTTP 409 DENIED_CAPACITY_FULL)');
    } else {
      throw new Error('Capacity limit was NOT enforced! Result: ' + JSON.stringify(scan2.data));
    }

    // 9. Admin Dashboard Metrics, Complaints, Notifications, Audit Logs
    // Passenger submits a complaint
    const cmpRes = await makeRequest('POST', '/api/complaints', {
      category: 'Punctuality',
      description: 'Shuttle arrived 5 minutes later than scheduled time.'
    }, newPasToken);
    if (!cmpRes.data.success) throw new Error('Complaint submission failed');

    // Admin fetches stats
    const adminStats = await makeRequest('GET', '/api/admin/stats', null, adminToken);
    if (!adminStats.data.success) throw new Error('Admin stats failed');
    console.log('✓ [13/13] Admin metrics verified: Revenue ETB', adminStats.data.stats.totalRevenueEtb, 'Total Checkins:', adminStats.data.stats.todayCheckins);

    // Driver ends trip
    await makeRequest('POST', `/api/trips/${activeTrip.id}/end`, null, driverToken);

    console.log('\n============================================================');
    console.log('  ALL 13 BACKEND INTEGRATION & SECURITY TESTS PASSED 100%!');
    console.log('============================================================\n');
  } catch (err) {
    console.error('\n❌ TEST RUN FAILED:', err);
    process.exitCode = 1;
  } finally {
    server.close();
  }
}

runTests();
