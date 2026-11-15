'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const bcrypt = require('bcryptjs');
process.env.NODE_ENV = 'test';
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-delete-rides-'));
if (process.env.MOBILITY_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL is required.');
} else delete process.env.DATABASE_URL;
const { DB } = require('../src/db');
const { createAccountDeletionService, isAccountClosed } = require('../src/services/account-deletion');
const { initRidesSchema } = require('../src/services/rides-schema');
const { initPushSchema } = require('../src/services/mobility-push');

test('ride account closure preserves commitments and removes live credentials without financial side effects', async t => {
  await require('../src/services/master-data').initMasterDataSchema();
  await require('../src/services/onboarding').initOnboardingSchema();
  const password = 'Isolated ride deletion password', hash = await bcrypt.hash(password, 4);
  const deletion = createAccountDeletionService();
  const instant = new Date().toISOString();
  let serial = 0;
  async function user(role = 'PASSENGER') {
    const id = 'ride_delete_user_' + ++serial;
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,email,passwordHash,status) VALUES(?,?,?,?,?,?,?)')
      .run(id, role, 'Private fixture ' + id, '+2519009' + String(serial).padStart(5,'0'), id+'@example.invalid', hash, 'ACTIVE');
    return id;
  }
  async function vehicle(driver) {
    const id = 'ride_delete_vehicle_' + ++serial;
    await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES(?,?,?,'MINIVAN_8',8,?,'IN_SERVICE')").run(id, id, 'Fixture vehicle', driver);
    return id;
  }
  async function taxi(passenger, driver, car, status) {
    const id = 'ride_delete_taxi_' + ++serial;
    await DB.prepare("INSERT INTO ride_jobs(id,passenger_id,driver_id,vehicle_id,status,source,tier,route_json,tariff_json,estimate_json,pin_hash,pin_cipher,idempotency_owner,idempotency_key,created_at,last_latitude,last_longitude,last_accuracy,last_point_at) VALUES(?,?,?,?,?,'APP','ECONOMY',?,'{}','{}',?,?,?,?,?,9,38,5,?)")
      .run(id, passenger, driver, car, status, JSON.stringify({origin:'Recorded pickup',destination:'Recorded dropoff'}), 'a'.repeat(64), 'fixture-encrypted-pin', passenger, id, instant, instant);
    return id;
  }
  async function shared(driver, car, status, owner = null) {
    const id = 'ride_delete_shared_' + ++serial;
    await DB.prepare("INSERT INTO ride_shared_trips(id,initiator_role,passenger_id,driver_id,vehicle_id,route_json,estimate_json,departure_at,seats,fare_cents,status,idempotency_owner,idempotency_key,created_at) VALUES(?,?,?,?,?,'{}','{}',?,8,15000,?,?,?,?)")
      .run(id, owner ? 'PASSENGER' : 'DRIVER', owner, driver, car, new Date(Date.now()+86400000).toISOString(), status, owner || driver, id, instant);
    return id;
  }
  async function booking(trip, passenger, status = 'CONFIRMED') {
    const id = 'ride_delete_booking_' + ++serial;
    await DB.prepare("INSERT INTO ride_shared_bookings(id,trip_id,passenger_id,seats_json,fare_cents,total_cents,status,pin_hash,pin_cipher,idempotency_key,created_at) VALUES(?,?,?,'[1]',15000,15000,?,?,?, ?,?)")
      .run(id, trip, passenger, status, 'b'.repeat(64), 'fixture-encrypted-booking-pin', id, instant);
    return id;
  }
  async function token(owner, scope) {
    const id = 'ride_delete_push_' + ++serial;
    await DB.prepare("INSERT INTO mobility_push_tokens(id,token_hash,token_encrypted,scope,owner_id,registered_at,updated_at) VALUES(?,?,'fixture-encrypted-token',?,?,?,?)").run(id,id,scope,owner,instant,instant);
    await DB.prepare("INSERT INTO mobility_push_outbox(id,event_key,token_id,request_id,event_type,kind,scope,next_attempt_at,expires_at,created_at) VALUES(?,?,?,'fixture-reference','STATUS_COMPLETED','TAXI',?,?,?,?)")
      .run('outbox_'+id,'event_'+id,id,scope==='RIDE_PASSENGER'?'PASSENGER':'DRIVER',instant,new Date(Date.now()+3600000).toISOString(),instant);
    return id;
  }
  const closed = (id, role) => deletion.erase(id, role, {password,confirmation:'DELETE'});
  const hasBlocker = async (id, role, code) => assert.ok((await deletion.inspect(id,role)).blockers.some(b=>b.code===code), code);

  await t.test('older development databases without ride or push tables still support closure', async () => {
    const id = await user();
    assert.equal((await deletion.inspect(id,'PASSENGER')).eligible,true);
    await closed(id,'PASSENGER'); assert.equal(await isAccountClosed(id,'PASSENGER'),true);
  });

  await initRidesSchema(); await initPushSchema();
  const driver = await user('DRIVER'), passenger = await user(), car = await vehicle(driver);

  await t.test('pending and active taxi rides block closure of their passenger and assigned driver', async () => {
    const id = await taxi(passenger,null,null,'REQUESTED');
    await hasBlocker(passenger,'PASSENGER','TAXI_RIDE_COMMITMENT');
    await assert.rejects(closed(passenger,'PASSENGER'),e=>e.status===409);
    for (const status of ['ACCEPTED','AT_PICKUP','IN_PROGRESS']) {
      await DB.prepare('UPDATE ride_jobs SET status=?,driver_id=?,vehicle_id=? WHERE id=?').run(status,driver,car,id);
      await hasBlocker(passenger,'PASSENGER','TAXI_RIDE_COMMITMENT');
      await hasBlocker(driver,'DRIVER','TAXI_RIDE_COMMITMENT');
    }
    assert.equal(await isAccountClosed(passenger,'PASSENGER'),false);
    await DB.prepare("UPDATE ride_jobs SET status='CANCELLED' WHERE id=?").run(id);
  });

  await t.test('shared requests, future seats and boarding commitments require explicit resolution', async () => {
    const pending = await shared(null,null,'REQUESTED',passenger);
    await hasBlocker(passenger,'PASSENGER','SHARED_RIDE_COMMITMENT');
    await DB.prepare("UPDATE ride_shared_trips SET status='CANCELLED' WHERE id=?").run(pending);
    const offered = await shared(driver,car,'OPEN');
    const reserved = await booking(offered,passenger);
    for (const status of ['OPEN','BOARDING','IN_PROGRESS']) {
      await DB.prepare('UPDATE ride_shared_trips SET status=? WHERE id=?').run(status,offered);
      await hasBlocker(driver,'DRIVER','SHARED_RIDE_COMMITMENT');
      await hasBlocker(passenger,'PASSENGER','SHARED_SEAT_COMMITMENT');
    }
    await DB.prepare("UPDATE ride_shared_trips SET status='COMPLETED' WHERE id=?").run(offered);
    await DB.prepare("UPDATE ride_shared_bookings SET status='COMPLETED' WHERE id=?").run(reserved);
  });

  await t.test('reserved driver credit blocks closure without capturing, releasing or forfeiting money', async () => {
    await DB.prepare('INSERT INTO mobility_wallets(driver_id,balance_cents,held_cents) VALUES(?,10000,1000)').run(driver);
    await hasBlocker(driver,'DRIVER','RESERVED_DRIVER_CREDIT');
    await assert.rejects(closed(driver,'DRIVER'),e=>e.status===409);
    assert.equal(Number((await DB.prepare('SELECT held_cents FROM mobility_wallets WHERE driver_id=?').get(driver)).held_cents),1000);
    await DB.prepare('UPDATE mobility_wallets SET held_cents=0 WHERE driver_id=?').run(driver);
  });

  await t.test('a new taxi commitment appearing after preflight is rechecked under the closure transaction', async () => {
    const original = DB.transaction;
    let id;
    DB.transaction = async function (fn) {
      DB.transaction = original;
      id = await taxi(passenger,driver,car,'ACCEPTED');
      return original.call(DB,fn);
    };
    try { await assert.rejects(closed(driver,'DRIVER'),e=>e.status===409); }
    finally { DB.transaction = original; }
    assert.equal(await isAccountClosed(driver,'DRIVER'),false);
    await DB.prepare("UPDATE ride_jobs SET status='CANCELLED' WHERE id=?").run(id);
  });

  await t.test('terminal journey receipts remain while live locations, notification credentials and PINs are removed', async () => {
    const terminal = await taxi(passenger,driver,car,'COMPLETED');
    await DB.prepare("INSERT INTO ride_samples(ride_id,sequence,latitude,longitude,accuracy_meters,recorded_at,accepted,reason,distance_m) VALUES(?,1,9,38,5,?,1,'DISTANCE',100)").run(terminal,instant);
    const trip = await shared(driver,car,'COMPLETED');
    const seat = await booking(trip,passenger,'COMPLETED');
    await DB.prepare('INSERT INTO ride_shared_locations(trip_id,driver_id,latitude,longitude,accuracy_meters,recorded_at) VALUES(?,?,9,38,5,?)').run(trip,driver,instant);
    await DB.prepare('INSERT INTO ride_availability(driver_id,vehicle_id,online,latitude,longitude,accuracy_meters,recorded_at,updated_at) VALUES(?,?,1,9,38,5,?,?)').run(driver,car,instant,instant);
    await DB.prepare('INSERT INTO ride_driver_eligibility(driver_id,approved,updated_at,actor_id) VALUES(?,1,?,?)').run(driver,instant,'fixture-admin');
    await DB.prepare("INSERT INTO ride_vehicle_eligibility(vehicle_id,driver_id,approved,tier,updated_at,actor_id) VALUES(?,?,1,'ECONOMY',?,'fixture-admin')").run(car,driver,instant);
    await DB.prepare('INSERT INTO ride_pin_attempts(ride_id,actor_id,attempts) VALUES(?,?,1),(?,?,2)').run(terminal,driver,seat,driver);
    const driverToken = await token(driver,'RIDE_DRIVER'), legacyToken = await token(driver,'DRIVER'), passengerToken = await token(passenger,'RIDE_PASSENGER');
    const outsider = await user(), otherToken = await token(outsider,'RIDE_PASSENGER');
    const disclosure = await deletion.inspect(driver,'DRIVER');
    assert.ok(disclosure.retainedCategories.some(x=>x.includes('taxi meter')));
    await closed(driver,'DRIVER');
    for (const id of [driverToken,legacyToken]) {
      assert.equal(await DB.prepare('SELECT id FROM mobility_push_tokens WHERE id=?').get(id),undefined);
      assert.equal(await DB.prepare('SELECT id FROM mobility_push_outbox WHERE token_id=?').get(id),undefined);
    }
    assert.ok(await DB.prepare('SELECT id FROM mobility_push_tokens WHERE id=?').get(passengerToken));
    assert.equal(await DB.prepare('SELECT driver_id FROM ride_availability WHERE driver_id=?').get(driver),undefined);
    assert.equal(await DB.prepare('SELECT trip_id FROM ride_shared_locations WHERE trip_id=?').get(trip),undefined);
    assert.equal(await DB.prepare('SELECT driver_id FROM ride_driver_eligibility WHERE driver_id=?').get(driver),undefined);
    assert.equal(await DB.prepare('SELECT vehicle_id FROM ride_vehicle_eligibility WHERE driver_id=?').get(driver),undefined);
    const history = await DB.prepare('SELECT * FROM ride_jobs WHERE id=?').get(terminal);
    assert.equal(history.status,'COMPLETED'); assert.equal(history.pin_cipher,null); assert.equal(history.last_latitude,null);
    assert.match(history.route_json,/Recorded pickup/);
    assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM ride_samples WHERE ride_id=?').get(terminal)).count),1,'Metering evidence remains under the disclosed retention category.');
    assert.equal((await DB.prepare('SELECT pin_cipher FROM ride_shared_bookings WHERE id=?').get(seat)).pin_cipher,'');
    assert.equal(Number((await DB.prepare('SELECT balance_cents FROM mobility_wallets WHERE driver_id=?').get(driver)).balance_cents),10000,'Account closure cannot forfeit available verified credit.');
    await closed(passenger,'PASSENGER');
    assert.equal(await DB.prepare('SELECT id FROM mobility_push_tokens WHERE id=?').get(passengerToken),undefined);
    assert.equal(await DB.prepare('SELECT id FROM mobility_push_outbox WHERE token_id=?').get(passengerToken),undefined);
    assert.ok(await DB.prepare('SELECT id FROM mobility_push_tokens WHERE id=?').get(otherToken),'Other accounts keep their notification credentials.');
    assert.equal((await DB.prepare('SELECT status FROM users WHERE id=?').get(outsider)).status,'ACTIVE');
  });
});
