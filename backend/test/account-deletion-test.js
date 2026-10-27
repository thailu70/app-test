'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const bcrypt = require('bcryptjs');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = require('node:fs').mkdtempSync(path.join(os.tmpdir(), 'passo-delete-'));
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, 'private-uploads');
process.env.JWT_SECRET = 'isolated-account-deletion-regression-secret';
process.env.ADMIN_REGISTRATION_SECRET = 'isolated-account-deletion-admin-secret';
const { DB } = require('../src/db');
const { runDatabaseMigrations } = require('../src/server');
const { signToken, resolveActivePrincipal } = require('../src/middleware/auth');
const { createAccountDeletionService, isAccountClosed } = require('../src/services/account-deletion');
const { createAccountRouter } = require('../src/routes/account');

test('authenticated account deletion revokes credentials and removes live personal data without breaking historical records', async t => {
  await runDatabaseMigrations();
  const password = 'Fixture account deletion password', hash = await bcrypt.hash(password, 4);
  const service = createAccountDeletionService();
  const express = require('express'), jwt = require('jsonwebtoken');
  const app = express(); app.use(express.json()); app.use('/api/account', createAccountRouter({ service, attemptLimit: 100 }));
  const http = app.listen(0, '127.0.0.1'); await new Promise(resolve => http.once('listening', resolve));
  t.after(() => new Promise(resolve => { http.closeAllConnections(); http.close(resolve); }));
  const base = 'http://127.0.0.1:' + http.address().port;
  async function api(token, method = 'GET', body) {
    const response = await fetch(base + '/api/account/deletion', { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json(), headers: response.headers };
  }
  async function user(id, role = 'PASSENGER', status = 'ACTIVE') {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,email,passwordHash,status,licenseNumber) VALUES(?,?,?,?,?,?,?,?)').run(id, role, 'Private Person ' + id, '+2519' + String(phoneCounter++).padStart(8,'0'), id + '@example.invalid', hash, status, role === 'DRIVER' ? 'PrivateLicence' : '');
    return signToken({ id, role });
  }
  let phoneCounter = 800;
  const passengerToken = await user('delete_passenger');
  const driverToken = await user('delete_driver','DRIVER');
  const adminToken = await user('delete_admin','ADMIN');
  const otherToken = await user('delete_other');
  const route = await DB.prepare('SELECT id FROM routes ORDER BY id LIMIT 1').get();
  await fs.mkdir(path.join(process.env.UPLOAD_DIR,'profiles'), { recursive: true });
  const filename = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png';
  await fs.writeFile(path.join(process.env.UPLOAD_DIR,'profiles',filename), 'private image');
  await DB.prepare('INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,?,?,?,?)').run('delete_passenger','Private Contact','0911111111','White',new Date().toISOString());
  await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)').run('delete_passenger','PHOTO',filename,'image/png',13,new Date().toISOString());
  await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,paymentStatus,subscriptionStatus,qrToken,daysRemaining) VALUES('delete_pass','delete_passenger',?,2500,'PAID','ACTIVE','private-boarding-secret',20)").run(route.id);
  await DB.prepare("INSERT INTO payment_transactions(id,passengerId,referenceNumber,amountEtb,phoneNumber,status) VALUES('delete_payment','delete_passenger','DELETE-RETAIN-REFERENCE',2500,'0911111111','COMPLETED')").run();
  await DB.prepare("INSERT INTO checkin_records(id,tripId,passengerId,passengerName,routeId,stopName) VALUES('delete_checkin','history_trip','delete_passenger','Private passenger',?,'History stop')").run(route.id);
  await DB.prepare("INSERT INTO complaints(id,passengerId,passengerName,category,description) VALUES('delete_complaint','delete_passenger','Private passenger','PAYMENT','Disputed historical payment')").run();

  await t.test('requires a valid own-account token, current password, exact confirmation and excludes administrator closure', async () => {
    assert.equal((await api()).status,401);
    const details = await api(passengerToken); assert.equal(details.status,200); assert.equal(details.body.deletion.eligible,true); assert.match(details.headers.get('cache-control'),/no-store/);
    assert.ok(details.body.deletion.retainedCategories.some(item=>item.includes('Payment')));
    assert.equal((await api(passengerToken,'POST',{password:'wrong password',confirmation:'DELETE'})).status,401);
    assert.equal((await api(passengerToken,'POST',{password,confirmation:'delete'})).status,400);
    assert.equal((await api(otherToken,'POST',{password,confirmation:'DELETE',accountId:'delete_passenger'})).status,400);
    assert.equal((await api(adminToken)).body.deletion.eligible,false);
    assert.equal((await api(adminToken,'POST',{password,confirmation:'DELETE'})).status,403);
    assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get('delete_passenger')).status,'ACTIVE');
  });
  await t.test('closes only the requester, removes private profile/files and keeps referenced payment/travel/support records', async () => {
    const result = await api(passengerToken,'POST',{password,confirmation:'DELETE'});
    assert.equal(result.status,200,JSON.stringify(result.body)); assert.equal(result.body.deletion.status,'COMPLETED_WITH_RETENTION'); assert.equal(result.body.deletion.sessionRevoked,true);
    const closed = await DB.prepare('SELECT * FROM users WHERE id=?').get('delete_passenger');
    assert.equal(closed.status,'SUSPENDED'); assert.equal(closed.fullName,'Deleted account'); assert.equal(closed.email,null); assert.match(closed.phone,/^closed_/); assert.equal(await bcrypt.compare(password,closed.passwordHash),false);
    assert.equal(await DB.prepare('SELECT * FROM user_profiles WHERE user_id=?').get(closed.id),undefined);
    assert.equal(await DB.prepare('SELECT * FROM user_profile_documents WHERE user_id=?').get(closed.id),undefined);
    await assert.rejects(fs.stat(path.join(process.env.UPLOAD_DIR,'profiles',filename)),{code:'ENOENT'});
    const sub = await DB.prepare("SELECT * FROM subscriptions WHERE id='delete_pass'").get(); assert.equal(sub.subscriptionStatus,'CANCELLED'); assert.equal(sub.qrToken,null); assert.equal(sub.daysRemaining,0);
    assert.equal((await DB.prepare("SELECT * FROM payment_transactions WHERE id='delete_payment'").get()).amountEtb,2500);
    assert.equal((await DB.prepare("SELECT * FROM checkin_records WHERE id='delete_checkin'").get()).passengerName,'Deleted account');
    assert.equal((await DB.prepare("SELECT * FROM complaints WHERE id='delete_complaint'").get()).description,'Disputed historical payment');
    assert.equal((await DB.prepare("SELECT status FROM users WHERE id='delete_other'").get()).status,'ACTIVE');
    assert.equal((await api(passengerToken)).status,401); assert.equal(await resolveActivePrincipal(passengerToken),null);
    // The permanent receipt prevents an operator's later status toggle from
    // restoring an old token, even though historical foreign keys remain intact.
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id='delete_passenger'").run();
    assert.equal(await resolveActivePrincipal(passengerToken),null); assert.equal(await isAccountClosed(closed.id,'PASSENGER'),true);
  });
  await t.test('active driver trips and assigned passengers require handover; closure removes live location after handover', async () => {
    await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,status) VALUES('delete_vehicle','DELETE-PLATE','Van','MINIVAN_8',8,'delete_driver','Private driver',?,'IN_SERVICE')").run(route.id);
    await DB.prepare("INSERT INTO trips(id,driverId,vehicleId,routeId,status) VALUES('delete_trip','delete_driver','delete_vehicle',?,'IN_PROGRESS')").run(route.id);
    let result = await api(driverToken,'POST',{password,confirmation:'DELETE'}); assert.equal(result.status,409); assert.equal(result.body.blockers[0].code,'ACTIVE_TRIP');
    assert.equal(await isAccountClosed('delete_driver','DRIVER'),false);
    await DB.prepare("UPDATE trips SET status='COMPLETED' WHERE id='delete_trip'").run();
    await DB.prepare("INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES('delete_late_departure','fixture_company',?,'delete_driver','delete_vehicle','Origin','Destination',?,9,38,8,100,'SCHEDULED',?)").run(route.id,new Date(Date.now()-5*60000).toISOString(),new Date().toISOString());
    result = await api(driverToken,'POST',{password,confirmation:'DELETE'}); assert.equal(result.status,409); assert.ok(result.body.blockers.some(item=>item.code==='COMMITTED_DEPARTURE'),'A scheduled journey five minutes late is still inside the boarding window.');
    await DB.prepare("UPDATE intercity_departures SET status='CANCELLED' WHERE id='delete_late_departure'").run();
    await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,vehicleId,priceEtb,paymentStatus,subscriptionStatus) VALUES('delete_assigned_pass','delete_other',?,'delete_vehicle',2500,'PAID','ACTIVE')").run(route.id);
    const future = new Date(Date.now() + 30 * 86400000).toISOString().slice(0,10);
    await DB.prepare("UPDATE subscriptions SET startDate=?,endDate=?,daysRemaining=30 WHERE id='delete_assigned_pass'").run(new Date().toISOString().slice(0,10),future);
    result = await api(driverToken); assert.equal(result.body.deletion.eligible,false); assert.ok(result.body.deletion.blockers.some(b=>b.code==='ASSIGNED_PASSENGERS'));
    await DB.prepare("UPDATE subscriptions SET vehicleId='' WHERE id='delete_assigned_pass'").run();
    await DB.prepare("UPDATE subscriptions SET vehicleId='delete_vehicle',startDate='2020-01-01',endDate='2020-01-31',daysRemaining=30 WHERE id='delete_assigned_pass'").run();
    assert.equal((await api(driverToken)).body.deletion.eligible,true,'Stale ACTIVE/PAID flags and a stale counter cannot keep an expired historical passenger assignment active.');
    await DB.prepare("INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude) VALUES('delete_vehicle',9.1,38.7)").run();
    result = await api(driverToken,'POST',{password,confirmation:'DELETE'}); assert.equal(result.status,200,JSON.stringify(result.body));
    const vehicle = await DB.prepare("SELECT * FROM vehicles WHERE id='delete_vehicle'").get(); assert.equal(vehicle.driverId,null); assert.equal(vehicle.currentLat,null); assert.equal(vehicle.assignedRouteId,null); assert.equal(vehicle.status,'OFF_DUTY');
    assert.equal(await DB.prepare("SELECT * FROM vehicle_live_locations WHERE vehicle_id='delete_vehicle'").get(),undefined);
    assert.equal((await DB.prepare("SELECT * FROM trips WHERE id='delete_trip'").get()).status,'COMPLETED');
  });
  await t.test('group and cargo commitments require handover and closure cancels unapproved proposals while preserving completed history', async () => {
    await require('../src/services/mobility-schema').initMobilitySchema();
    const token=await user('delete_mobility','DRIVER');
    await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES('delete_mobility_vehicle','DELETE-MOBILITY','Van','MINIVAN_8',8,'delete_mobility','IN_SERVICE')").run();
    const instant=new Date().toISOString(),future=new Date(Date.now()+86400000).toISOString();
    for(const [id,status] of [['delete_group_approved','APPROVED'],['delete_group_pending','PENDING_REVIEW'],['delete_group_history','COMPLETED']])await DB.prepare('INSERT INTO mobility_trips(id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)')
      .run(id,'delete_mobility','delete_mobility_vehicle','Addis Ababa','Hawassa',future,9,38,8,500,status,instant);
    let result=await api(token,'POST',{password,confirmation:'DELETE'});assert.equal(result.status,409);assert.ok(result.body.blockers.some(b=>b.code==='GROUP_TRAVEL_COMMITMENT'));
    assert.equal((await DB.prepare("SELECT status FROM mobility_trips WHERE id='delete_group_pending'").get()).status,'PENDING_REVIEW','Rejected deletion never cancels a proposal');
    await DB.prepare("UPDATE mobility_trips SET status='BOARDING',departure_at='2020-01-01T00:00:00Z' WHERE id='delete_group_approved'").run();
    result=await api(token);assert.equal(result.body.deletion.eligible,false,'Boarding remains a commitment even after its scheduled date');
    await DB.prepare("UPDATE mobility_trips SET status='COMPLETED' WHERE id='delete_group_approved'").run();
    await DB.prepare("INSERT INTO mobility_requests(id,kind,secret_hash,origin,destination,travel_date,vehicle_type,customer_name,customer_phone,status,driver_id,vehicle_id,created_at) VALUES('delete_cargo_request','CARGO','fixture-only','Origin','Destination','2099-01-01','MINIVAN_8','Customer','0912000000','ASSIGNED','delete_mobility','delete_mobility_vehicle',?)").run(instant);
    result=await api(token,'POST',{password,confirmation:'DELETE'});assert.equal(result.status,409);assert.ok(result.body.blockers.some(b=>b.code==='ASSIGNED_TRANSPORT_REQUEST'));
    await DB.prepare("UPDATE mobility_requests SET status='COMPLETED' WHERE id='delete_cargo_request'").run();
    result=await api(token,'POST',{password,confirmation:'DELETE'});assert.equal(result.status,200,JSON.stringify(result.body));
    assert.equal((await DB.prepare("SELECT status FROM mobility_trips WHERE id='delete_group_pending'").get()).status,'CANCELLED');
    assert.equal((await DB.prepare("SELECT status FROM mobility_trips WHERE id='delete_group_history'").get()).status,'COMPLETED');
    assert.equal((await DB.prepare("SELECT status FROM mobility_requests WHERE id='delete_cargo_request'").get()).status,'COMPLETED');
    assert.equal((await DB.prepare("SELECT driverId FROM vehicles WHERE id='delete_mobility_vehicle'").get()).driverId,null);
  });

  await t.test('pending onboarding accounts can close without OTP or operational approval', async () => {
    await user('delete_pending','DRIVER','PENDING');
    const instant = new Date().toISOString();
    await DB.prepare('INSERT INTO onboarding_accounts(account_id,role,normalized_phone,created_at,updated_at) VALUES(?,?,?,?,?)').run('delete_pending','DRIVER','+251912000055',instant,instant);
    const onboardingToken = jwt.sign({ id:'delete_pending',role:'DRIVER',scope:'ONBOARDING' },process.env.JWT_SECRET,{audience:'routepass-onboarding',expiresIn:'2h'});
    assert.equal((await api(onboardingToken)).status,200);
    assert.equal((await api(onboardingToken,'POST',{password,confirmation:'DELETE'})).status,200);
    assert.equal(await DB.prepare("SELECT * FROM onboarding_accounts WHERE account_id='delete_pending'").get(),undefined);
    assert.equal((await api(onboardingToken)).status,401);
  });
  await t.test('company closure protects associated drivers and disables own routes without deleting other accounts', async () => {
    await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES('delete_company','Deletion Fixture Company','0912300099','Private representative',?,'ACTIVE')").run(hash);
    const token = signToken({id:'delete_company',role:'COMPANY'});
    await DB.prepare("UPDATE users SET companyId='delete_company' WHERE id='delete_other'").run();
    assert.equal((await api(token)).body.deletion.eligible,true,'Passenger association does not imply ownership of that account');
    await user('delete_company_driver','DRIVER'); await DB.prepare("UPDATE users SET companyId='delete_company' WHERE id='delete_company_driver'").run();
    assert.equal((await api(token,'POST',{password,confirmation:'DELETE'})).status,409);
    await DB.prepare("UPDATE users SET companyId='' WHERE id='delete_company_driver'").run();
    await DB.prepare("INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES('delete_late_company_departure','delete_company',?,'delete_company_driver','fixture_vehicle','Origin','Destination',?,9,38,8,100,'SCHEDULED',?)").run(route.id,new Date(Date.now()-5*60000).toISOString(),new Date().toISOString());
    const lateCompany = await api(token,'POST',{password,confirmation:'DELETE'}); assert.equal(lateCompany.status,409); assert.ok(lateCompany.body.blockers.some(item=>item.code==='COMPANY_DEPARTURES'));
    await DB.prepare("UPDATE intercity_departures SET status='CANCELLED' WHERE id='delete_late_company_departure'").run();
    await DB.prepare("INSERT INTO routes(id,name,nameAm,companyId,routeType) VALUES('delete_company_route','Private Company Route','Route','delete_company','INTERCITY')").run();
    assert.equal((await api(token,'POST',{password,confirmation:'DELETE'})).status,200);
    const company = await DB.prepare("SELECT * FROM transport_companies WHERE id='delete_company'").get(); assert.equal(company.status,'INACTIVE'); assert.equal(company.phone,null); assert.equal(company.representative_name,'');
    assert.equal((await DB.prepare("SELECT active FROM routes WHERE id='delete_company_route'").get()).active,0);
    assert.equal((await DB.prepare("SELECT status FROM users WHERE id='delete_company_driver'").get()).status,'ACTIVE');
    assert.equal(await resolveActivePrincipal(token),null);
  });
  await t.test('unsafe paths and shared references are never deleted; cleanup failure is accurately queued and retryable', async () => {
    const token = await user('delete_shared');
    const sharedFile = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff.png';
    await fs.writeFile(path.join(process.env.UPLOAD_DIR,'profiles',sharedFile),'shared fixture');
    for (const id of ['delete_shared','delete_other']) await DB.prepare('INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,?,?,?,?)').run(id,'PHOTO',sharedFile,'image/png',12,new Date().toISOString());
    const result = await api(token,'POST',{password,confirmation:'DELETE'}); assert.equal(result.status,200); assert.equal(result.body.deletion.status,'PENDING_CLEANUP');
    assert.equal((await fs.readFile(path.join(process.env.UPLOAD_DIR,'profiles',sharedFile))).toString(),'shared fixture');
    await DB.prepare("DELETE FROM user_profile_documents WHERE user_id='delete_other' AND kind='PHOTO'").run();
    await service.retryPendingFiles();
    assert.equal((await DB.prepare('SELECT status FROM account_deletion_requests WHERE id=?').get(result.body.deletion.id)).status,'COMPLETED_WITH_RETENTION');
    await assert.rejects(fs.stat(path.join(process.env.UPLOAD_DIR,'profiles',sharedFile)),{code:'ENOENT'});
    const outside = path.join(process.env.DATA_DIR,'outside.png'); await fs.writeFile(outside,'must survive');
    await DB.prepare("INSERT INTO account_deletion_files(request_id,folder,storage_name) VALUES(?,'profiles','../../outside.png')").run(result.body.deletion.id);
    await DB.prepare("UPDATE account_deletion_requests SET status='PENDING_CLEANUP' WHERE id=?").run(result.body.deletion.id);
    await service.retryPendingFiles(); assert.equal((await fs.readFile(outside)).toString(),'must survive');
    assert.equal((await DB.prepare('SELECT status FROM account_deletion_requests WHERE id=?').get(result.body.deletion.id)).status,'PENDING_CLEANUP');
  });
});
