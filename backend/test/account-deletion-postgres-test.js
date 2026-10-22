'use strict';
// Run against the disposable PostgreSQL CI database after init-db.sql and the
// main smoke test. This never targets the live VPS or sends messages/payments.
const assert = require('node:assert/strict');
const bcrypt = require('bcryptjs');
if (process.env.NODE_ENV !== 'test' || !process.env.DATABASE_URL) throw new Error('A disposable test PostgreSQL database is required.');
const { DB } = require('../src/db');
const { runDatabaseMigrations } = require('../src/server');
const { createAccountDeletionService } = require('../src/services/account-deletion');
const { signToken, resolveActivePrincipal } = require('../src/middleware/auth');
(async () => {
  await runDatabaseMigrations();
  const password = 'PostgreSQL privacy fixture password', hash = await bcrypt.hash(password,4);
  const service = createAccountDeletionService();
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('ci_delete_passenger','PASSENGER','Private CI passenger','+251912000088',?,'ACTIVE'),('ci_delete_driver','DRIVER','Private CI driver','+251912000089',?,'ACTIVE')").run(hash,hash);
  await DB.prepare("INSERT INTO routes(id,name,nameAm) VALUES('ci_delete_route','CI deletion route','Route')").run();
  await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,status) VALUES('ci_delete_vehicle','CI-DELETE-VEH','Van','MINIVAN_8',8,'ci_delete_driver','Private CI driver','ci_delete_route','OFF_DUTY')").run();
  await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,priceEtb,paymentStatus,subscriptionStatus,vehicleId,qrToken,daysRemaining) VALUES('ci_delete_subscription','ci_delete_passenger','ci_delete_route',2500,'PAID','ACTIVE','ci_delete_vehicle','private-token',12)").run();
  await DB.prepare("INSERT INTO payment_transactions(id,passengerId,referenceNumber,amountEtb,status) VALUES('ci_delete_payment','ci_delete_passenger','CI-DELETE-KEEP',2500,'COMPLETED')").run();
  const token = signToken({id:'ci_delete_passenger',role:'PASSENGER'});
  assert.equal((await service.inspect('ci_delete_passenger','PASSENGER')).eligible,true);
  const result = await service.erase('ci_delete_passenger','PASSENGER',{password,confirmation:'DELETE'});
  assert.equal(result.status,'COMPLETED_WITH_RETENTION');
  assert.equal((await DB.prepare("SELECT status FROM users WHERE id='ci_delete_passenger'").get()).status,'SUSPENDED');
  assert.equal((await DB.prepare("SELECT subscriptionStatus FROM subscriptions WHERE id='ci_delete_subscription'").get()).subscriptionStatus,'CANCELLED');
  assert.equal(Number((await DB.prepare("SELECT amountEtb FROM payment_transactions WHERE id='ci_delete_payment'").get()).amountEtb),2500);
  assert.equal(await resolveActivePrincipal(token),null);
  await DB.prepare("INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude) VALUES('ci_delete_vehicle',9,38)").run();
  assert.equal((await service.erase('ci_delete_driver','DRIVER',{password,confirmation:'DELETE'})).status,'COMPLETED_WITH_RETENTION');
  const vehicle = await DB.prepare("SELECT driverId,currentLat FROM vehicles WHERE id='ci_delete_vehicle'").get();
  assert.equal(vehicle.driverId,null); assert.equal(vehicle.currentLat,null);
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES('ci_delete_company','CI Deletion Company','+251912000090','Private representative',?,'ACTIVE')").run(hash);
  const company = await service.erase('ci_delete_company','COMPANY',{password,confirmation:'DELETE'}); assert.equal(company.status,'COMPLETED_WITH_RETENTION');
  assert.equal((await DB.prepare("SELECT status FROM transport_companies WHERE id='ci_delete_company'").get()).status,'INACTIVE');
  console.log('PostgreSQL account deletion passed: enum constraints, row locks, retained foreign keys, session revocation and company/driver closure.');
  process.exit(0);
})().catch(error => { console.error(error); process.exit(1); });
