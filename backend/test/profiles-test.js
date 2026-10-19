// Isolated end-to-end coverage: authentication, document privacy, file validation and completion.
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
const fs = require('fs');
const os = require('os');
const path = require('path');
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-profiles-test-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'private-uploads');
const assert = require('node:assert/strict');
const express = require('express');
const { DB } = require('../src/db');
const profiles = require('../src/routes/profiles');
const { signToken } = require('../src/middleware/auth');

async function main() {
  await profiles.initProfileSchema();
  await DB.exec(`CREATE TABLE IF NOT EXISTS transport_companies (
    id TEXT PRIMARY KEY, name TEXT, phone TEXT, representative_name TEXT, status TEXT
  )`);
  await DB.prepare("INSERT INTO transport_companies (id,name,phone,representative_name,status) VALUES ('company_test','Test Company','+251900000000','Owner','ACTIVE')").run();
  const addUser = DB.prepare('INSERT INTO users (id,role,fullName,phone,passwordHash,status,companyId) VALUES (?,?,?,?,?,?,?)');
  await addUser.run('other_passenger', 'PASSENGER', 'Other passenger', '+251900000001', 'not-used', 'ACTIVE', '');
  await addUser.run('company_driver', 'DRIVER', 'Company driver', '+251900000002', 'not-used', 'ACTIVE', 'company_test');
  await addUser.run('inactive_user', 'PASSENGER', 'Inactive', '+251900000003', 'not-used', 'INACTIVE', '');
  const tokens = {
    driver: signToken({ id: 'usr_drv_kassahun', role: 'DRIVER' }),
    passenger: signToken({ id: 'usr_pas_alemayehu', role: 'PASSENGER' }),
    outsider: signToken({ id: 'other_passenger', role: 'PASSENGER' }),
    admin: signToken({ id: 'usr_adm_root', role: 'ADMIN' }),
    company: signToken({ id: 'company_test', role: 'COMPANY' }),
    companyDriver: signToken({ id: 'company_driver', role: 'DRIVER' }),
    inactive: signToken({ id: 'inactive_user', role: 'PASSENGER' })
  };
  const app = express();
  app.use(express.json({ limit: '1mb' }));
  app.use('/api/profiles', profiles);
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}/api/profiles`;
  async function request(method, endpoint, token, body, type) {
    const response = await fetch(base + endpoint, { method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(type ? { 'Content-Type': type } : {}) },
      ...(body !== undefined ? { body: typeof body === 'object' && !Buffer.isBuffer(body) ? JSON.stringify(body) : body } : {})
    });
    const content = await response.arrayBuffer();
    return { status: response.status, headers: response.headers,
      json: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(Buffer.from(content).toString()) : null,
      bytes: Buffer.from(content) };
  }
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=', 'base64');
  const pdf = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF');
  try {
    assert.equal((await request('GET', '/me')).status, 401);
    assert.equal((await request('GET', '/me', tokens.inactive)).status, 401);
    assert.equal((await request('GET', '/me', tokens.company)).status, 403);
    const empty = await request('GET', '/me', tokens.passenger);
    assert.deepEqual(empty.json.profile.missing, ['PHOTO', 'ID']);
    assert.equal(empty.json.profile.complete, false);
    console.log('PASS: authentication and required registration fields');

    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.passenger, Buffer.from('pretend-image'), 'image/png')).status, 415);
    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.passenger, pdf, 'application/pdf')).status, 415);
    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.passenger, png, 'image/jpeg')).status, 415);
    assert.equal((await request('PUT', '/me/documents/LICENSE', tokens.passenger, pdf, 'application/pdf')).status, 400);
    assert.equal((await request('PUT', '/me/documents/ID', tokens.passenger, Buffer.alloc(5 * 1024 * 1024 + 1), 'application/pdf')).status, 413);
    console.log('PASS: document types, signatures, role restrictions and 5 MB limit');

    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.passenger, png, 'image/png')).status, 200);
    const completed = await request('PUT', '/me/documents/ID', tokens.passenger, pdf, 'application/pdf');
    assert.equal(completed.json.profile.complete, true);
    assert.deepEqual(completed.json.profile.missing, []);
    assert.equal((await request('GET', '/me/documents/ID', tokens.passenger)).bytes.compare(pdf), 0);
    const selfPhoto = await request('GET', '/usr_pas_alemayehu/photo', tokens.passenger);
    assert.equal(selfPhoto.bytes.compare(png), 0);
    assert.equal(selfPhoto.headers.get('cache-control'), 'private, no-store');
    assert.equal((await request('GET', '/usr_pas_alemayehu/photo', tokens.outsider)).status, 404);
    assert.equal((await request('GET', '/usr_pas_alemayehu/documents/ID', tokens.outsider)).status, 404);
    assert.equal((await request('GET', '/usr_pas_alemayehu/documents/ID', tokens.driver)).status, 404);
    assert.equal((await request('GET', '/usr_pas_alemayehu/documents/ID', tokens.admin)).status, 200);
    assert.equal((await request('GET', '/usr_pas_alemayehu/documents/ID', tokens.company)).status, 404);
    console.log('PASS: complete passenger profile and private identity documents');

    assert.equal((await request('PATCH', '/me', tokens.passenger, { emergencyContactName: 'Change' }, 'application/json')).status, 403);
    assert.equal((await request('PATCH', '/me', tokens.driver, { emergencyContactPhone: 'wrong' }, 'application/json')).status, 400);
    assert.equal((await request('PATCH', '/me', tokens.driver, { role: 'ADMIN' }, 'application/json')).status, 400);
    assert.equal((await request('PATCH', '/me', tokens.driver, { emergencyContactName: 'Family Contact', emergencyContactPhone: '+251911000000', vehicleColor: 'White' }, 'application/json')).status, 200);
    for (const kind of ['LICENSE', 'VEHICLE_REGISTRATION', 'INSURANCE', 'PHOTO']) {
      const upload = await request('PUT', `/me/documents/${kind}`, tokens.driver, kind === 'PHOTO' ? png : pdf, kind === 'PHOTO' ? 'image/png' : 'application/pdf');
      assert.equal(upload.status, 200);
    }
    const driverProfile = await request('GET', '/me', tokens.driver);
    assert.equal(driverProfile.json.profile.complete, true);
    assert.equal(driverProfile.json.profile.emergencyContactPhone, '+251911000000');
    assert.equal(driverProfile.json.profile.vehicleColor, 'White');
    console.log('PASS: driver documents, emergency contact and vehicle color');

    const vehicle = await DB.prepare("SELECT id FROM vehicles WHERE plateNumber = '3-AA-34921'").get();
    await DB.prepare('UPDATE vehicles SET driverId = ? WHERE id = ?').run('usr_drv_kassahun', vehicle.id);
    await DB.prepare(`INSERT INTO subscriptions (id,passengerId,routeId,priceEtb,subscriptionStatus,vehicleId)
      VALUES ('profile_test_subscription','usr_pas_alemayehu','route_bole_merkato',100,'PENDING',?)`).run(vehicle.id);
    assert.equal((await request('GET', '/usr_pas_alemayehu/photo', tokens.driver)).status, 200);
    assert.equal((await request('GET', '/usr_drv_kassahun/photo', tokens.passenger)).status, 200);
    assert.equal((await request('GET', '/usr_drv_kassahun/documents/LICENSE', tokens.passenger)).status, 404);
    await DB.prepare("UPDATE subscriptions SET subscriptionStatus='INACTIVE' WHERE id='profile_test_subscription'").run();
    assert.equal((await request('GET', '/usr_pas_alemayehu/photo', tokens.driver)).status, 200);
    await DB.prepare("UPDATE subscriptions SET vehicleId=NULL WHERE id='profile_test_subscription'").run();
    assert.equal((await request('GET', '/usr_pas_alemayehu/photo', tokens.driver)).status, 404);
    console.log('PASS: assigned photos visible with no identity-document disclosure');

    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.companyDriver, png, 'image/png')).status, 200);
    assert.equal((await request('PUT', '/me/documents/LICENSE', tokens.companyDriver, pdf, 'application/pdf')).status, 200);
    assert.equal((await request('GET', '/company_driver/photo', tokens.admin)).status, 404);
    assert.equal((await request('GET', '/company_driver/documents/LICENSE', tokens.admin)).status, 404);
    assert.equal((await request('GET', '/company_driver/photo', tokens.company)).status, 200);
    assert.equal((await request('GET', '/company_driver/documents/LICENSE', tokens.company)).status, 200);
    console.log('PASS: company ownership and commute administrator isolation');

    const namesBefore = fs.readdirSync(path.join(process.env.UPLOAD_DIR, 'profiles'));
    const oldDocument = await DB.prepare("SELECT storage_name FROM user_profile_documents WHERE user_id='usr_pas_alemayehu' AND kind='PHOTO'").get();
    assert.equal((await request('PUT', '/me/documents/PHOTO', tokens.passenger, png, 'image/png')).status, 200);
    const namesAfter = fs.readdirSync(path.join(process.env.UPLOAD_DIR, 'profiles'));
    assert.equal(namesAfter.length, namesBefore.length);
    assert.equal(namesAfter.includes(oldDocument.storage_name), false);
    assert(namesAfter.every(name => /^[a-f0-9-]+\.(png|jpg|pdf)$/.test(name)));
    console.log('PASS: safe private filenames and replacement cleanup');
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
