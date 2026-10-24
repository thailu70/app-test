'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test'; delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-payment-safety-'));
process.env.JWT_SECRET = 'isolated-payment-safety-secret-not-production';
process.env.QR_SIGNING_KEY = 'isolated-payment-safety-qr-not-production';
process.env.PAYMENT_MODE = 'PRODUCTION';
delete process.env.PAYMENT_TESTS_ENABLED;
delete process.env.PAYMENT_TEST_PASSENGER_ID;
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const telebirr = require('../src/services/telebirr');
const sms = require('../src/services/afro-sms');
const { isTestPaymentPhone, paymentTestsEnabled, initPaymentTestSchema, createPaymentTestService, grantCommuterTestPass } = require('../src/services/payment-test');

test('payment tests require the sole authorized phone, guest ownership proof and replay-safe checkout', async t => {
  await require('../src/services/intercity-schema').initIntercitySchema();
  await require('../src/services/profile-schema').initProfileSchema();
  await initPaymentTestSchema();
  await DB.exec('CREATE TABLE IF NOT EXISTS transport_companies(id TEXT PRIMARY KEY,name TEXT,phone TEXT,representative_name TEXT,status TEXT)');
  await DB.prepare("INSERT INTO transport_companies(id,name,status) VALUES('safe_company','Test transport','ACTIVE')").run();
  const accounts = [
    ['safe_test','PASSENGER','+251912469923',''], ['safe_regular','PASSENGER','0911000001',''], ['safe_old','PASSENGER','09000000',''],
    ['safe_unassigned','PASSENGER','0911000002',''], ['safe_expired','PASSENGER','0911000003',''], ['safe_admin','ADMIN','0911000004',''],
    ['safe_driver_a','DRIVER','0911000005',''], ['safe_driver_b','DRIVER','0911000006',''], ['safe_company_driver','DRIVER','0911000007','safe_company']
  ];
  const tokens = {};
  for (const [id,role,phone,company] of accounts) { await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,?,?,?,?,?,?)').run(id, role, 'Private ' + id, phone, 'unused', 'ACTIVE', company); tokens[id] = signToken({ id, role, phone }); }
  tokens.safe_company=signToken({id:'safe_company',role:'COMPANY'});
  await DB.prepare("INSERT INTO routes(id,name,nameAm,routeType,companyId,origin,destination) VALUES('safe_intercity','Intercity','Intercity','INTERCITY','safe_company','Addis','Hawassa')").run();
  await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,companyId) VALUES('safe_intercity_bus','SAFE-IC','Bus','MINIBUS_14',14,'safe_company_driver','Private company driver','safe_intercity','safe_company')").run();
  for (const suffix of ['a','b']) {
    await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,driverName,assignedRouteId,currentLat,currentLng) VALUES(?,?,?,?,?,?,?,?,?,?)').run('safe_vehicle_' + suffix, 'SAFE-' + suffix, 'Minivan', 'MINIVAN_8', 8, 'safe_driver_' + suffix, 'Private driver ' + suffix, 'route_bole_merkato', 55, 44);
    await DB.prepare('INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude,updated_at) VALUES(?,?,?,?)').run('safe_vehicle_' + suffix, suffix === 'a' ? 9.1 : 9.2, 38.7, new Date(Date.now() - 1000).toISOString());
  }
  const now = new Date().toISOString().slice(0, 10), future = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10);
  for (const [id,passenger,end] of [['safe_paid','safe_regular',future],['safe_expired_pass','safe_expired','2020-01-01']]) await DB.prepare("INSERT INTO subscriptions(id,passengerId,routeId,vehicleId,pickupStopId,destinationStopId,startDate,endDate,priceEtb,paymentStatus,subscriptionStatus,daysRemaining) VALUES(?,?,'route_bole_merkato','safe_vehicle_a','stop_atlas','stop_merkato',?,?,2500,'PAID','ACTIVE',30)").run(id, passenger, now, end);
  const originals = { missingConfiguration: telebirr.missingConfiguration, config: telebirr.config, createCheckout: telebirr.createCheckout, queryOrderStatus: telebirr.queryOrderStatus, checkoutUrlForPrepay: telebirr.checkoutUrlForPrepay, sendSms: sms.sendSms };
  let sent = [], smsWorks = true;
  sms.sendSms = async (phone, message) => { if (/test payment code/.test(message)) sent.push({ phone, code: /\b\d{6}\b/.exec(message)?.[0] }); return { sent: smsWorks }; };
  telebirr.missingConfiguration = () => ['mock merchant not configured']; telebirr.config = () => ({ environment: 'production', publicKey: 'fixture' });
  t.after(() => { for (const key of Object.keys(originals)) if (key !== 'sendSms') telebirr[key] = originals[key]; sms.sendSms = originals.sendSms; });
  const express = require('express'), app = express(); app.use(express.json());
  app.use('/api/intercity', require('../src/routes/intercity'));
  app.use('/api/subscriptions/telebirr', require('../src/routes/telebirr'));
  app.use('/api/sync', require('../src/routes/sync'));
  app.use('/api/admin', require('../src/routes/commute-operations'));
  app.use('/api/admin', require('../src/routes/admin'));
  const server = app.listen(0,'127.0.0.1'); await new Promise(resolve => server.once('listening',resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function api(endpoint, body = {}, token, secret) { const response = await fetch(base + endpoint, { method: 'POST', headers: { 'Content-Type':'application/json', ...(token ? { Authorization:'Bearer ' + tokens[token] } : {}), ...(secret ? { 'X-Booking-Secret':secret } : {}) }, body:JSON.stringify(body) }); return { status:response.status, body:await response.json(), cache:response.headers.get('cache-control') }; }
  async function get(endpoint, token) { const response=await fetch(base+endpoint,{headers:{Authorization:'Bearer '+tokens[token]}});return {status:response.status,body:await response.json()}; }
  let sequence = 0;
  async function hold() {
    const id = 'safe_departure_' + ++sequence;
    await DB.prepare('INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)')
      .run(id,'safe_company','safe_intercity','safe_company_driver','safe_intercity_bus','Addis','Hawassa',new Date(Date.now()+600000).toISOString(),9,38,14,450,new Date().toISOString());
    const result = await api('/api/intercity/departures/' + id + '/hold', { seatNumber:1 }); assert.equal(result.status,201,JSON.stringify(result.body)); return result.body;
  }
  const pathFor = (held, suffix) => '/api/intercity/bookings/' + held.booking.id + suffix;
  async function allowSmsAgain() { await DB.prepare('UPDATE payment_test_phone_limits SET requested_at=?').run(new Date(Date.now()-61000).toISOString()); }
  async function proof(held) {
    await allowSmsAgain(); const requested = await api(pathFor(held,'/test-payment/request-otp'),{passengerPhone:'0912469923'},null,held.bookingSecret);
    assert.equal(requested.status,200,JSON.stringify(requested.body)); assert.equal(requested.body.delivery,'SENT');
    const verified = await api(pathFor(held,'/test-payment/verify-otp'),{passengerPhone:'+251912469923',code:sent.at(-1).code},null,held.bookingSecret);
    assert.equal(verified.status,200,JSON.stringify(verified.body)); return verified.body.testPaymentVerificationToken;
  }
  const payer = { passengerName:'Test rider', passengerPhone:'0912469923' };

  await t.test('free payments fail closed by default without SMS, verification, pass or ticket activation', async () => {
    assert.equal(paymentTestsEnabled(), false);
    process.env.PAYMENT_TEST_PASSENGER_ID = 'safe_test';
    const held = await hold(), sentBefore = sent.length;
    const commuter = await api('/api/subscriptions/telebirr/pay', { routeId:'route_bole_merkato', idempotencyKey:'disabled-test-payment-key', PAYMENT_TESTS_ENABLED:true, paymentTestsEnabled:true }, 'safe_test');
    assert.equal(commuter.status,403); assert.match(commuter.body.error,/free test payments.*disabled/i);
    for (const suffix of ['/test-payment/request-otp','/test-payment/verify-otp','/checkout']) {
      const result = await api(pathFor(held,suffix), {...payer,code:'123456',testPaymentVerificationToken:'a'.repeat(64),paymentTestsEnabled:true}, null, held.bookingSecret);
      assert.equal(result.status,403); assert.match(result.body.error,/free test payments.*disabled/i);
    }
    assert.equal(sent.length,sentBefore);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM payment_test_challenges').get()).count,0);
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM payment_transactions WHERE provider='TEST_AUTO'").get()).count,0);
    assert.equal((await DB.prepare('SELECT payment_status FROM intercity_bookings WHERE id=?').get(held.booking.id)).payment_status,'UNPAID');
    assert.equal(await DB.prepare("SELECT id FROM subscriptions WHERE passengerId='safe_test'").get(),undefined);
    for (const flag of ['', 'false', '0', '1', 'yes']) { process.env.PAYMENT_TESTS_ENABLED=flag; assert.equal(paymentTestsEnabled(),false,flag); }
    // Only this isolated fixture opts in; no NODE_ENV-based bypass exists.
    process.env.PAYMENT_TESTS_ENABLED='true';
    assert.equal(paymentTestsEnabled(),true);
  });

  await t.test('future commuter tests need the configured account identity as well as its current authorized phone', async () => {
    delete process.env.PAYMENT_TEST_PASSENGER_ID;
    const request = { routeId:'route_bole_merkato', idempotencyKey:'unbound-test-payment-key', allowedPassengerId:'safe_test', PAYMENT_TEST_PASSENGER_ID:'safe_test' };
    assert.equal((await api('/api/subscriptions/telebirr/pay',request,'safe_test')).status,403);
    process.env.PAYMENT_TEST_PASSENGER_ID='different-account';
    assert.equal((await api('/api/subscriptions/telebirr/pay',request,'safe_test')).status,403);
    process.env.PAYMENT_TEST_PASSENGER_ID='safe_test';
    await DB.prepare("UPDATE users SET phone='0911000088' WHERE id='safe_test'").run();
    await DB.prepare("UPDATE users SET phone='0912469923' WHERE id='safe_regular'").run();
    try {
      assert.equal((await api('/api/subscriptions/telebirr/pay',request,'safe_regular')).status,403,'Claiming the staff phone does not inherit the operator-bound account identity');
      assert.equal((await api('/api/subscriptions/telebirr/pay',request,'safe_test')).status,503,'The bound account with a changed phone follows ordinary provider checkout, not a free grant');
      const route=await DB.prepare("SELECT * FROM routes WHERE id='route_bole_merkato'").get();
      await assert.rejects(() => grantCommuterTestPass({passengerId:'safe_test',route,key:'direct-wrong-phone',signedQr:()=> 'unused'}, DB, {allowedPassengerId:'safe_test',isEnabled:()=>true}), error=>error.status===403);
      assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM payment_transactions WHERE provider='TEST_AUTO'").get()).count,0);
    } finally {
      await DB.prepare("UPDATE users SET phone='0911000001' WHERE id='safe_regular'").run();
      await DB.prepare("UPDATE users SET phone='+251912469923' WHERE id='safe_test'").run();
    }
  });

  await t.test('only canonical variants of the allowed current account phone can test; replay never extends twice', async () => {
    for (const value of ['0912469923','+251912469923','251912469923','912469923',' +251 (912) 469-923 ']) assert.equal(isTestPaymentPhone(value),true,value);
    for (const value of ['09000000','0912469924','+2519124699230','hello','']) assert.equal(isTestPaymentPhone(value),false,value);
    for (const id of ['safe_regular','safe_old']) {
      const result = await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',phone:'0912469923',idempotencyKey:'blocked-'+id},id);
      assert.equal(result.status,503); assert.equal(result.body.paymentMode,undefined);
    }
    const calls = await Promise.all(Array.from({length:8},()=>api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',phone:'0911000001',idempotencyKey:'one-test-payment-key'},'safe_test')));
    assert.ok(calls.every(result=>result.status===200),JSON.stringify(calls.map(result=>result.body)));
    assert.equal(calls.filter(result=>!result.body.idempotentReplay).length,1);
    const before = await DB.prepare("SELECT endDate,qrToken FROM subscriptions WHERE passengerId='safe_test'").get();
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM payment_transactions WHERE passengerId='safe_test'").get()).count,1);
    assert.equal((await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',idempotencyKey:'one-test-payment-key'},'safe_test')).status,200);
    assert.deepEqual(await DB.prepare("SELECT endDate,qrToken FROM subscriptions WHERE passengerId='safe_test'").get(),before);
    assert.equal((await api('/api/subscriptions/telebirr/pay',{routeId:'route_megenagna_torhailoch',idempotencyKey:'one-test-payment-key'},'safe_test')).status,409);
    await DB.prepare("UPDATE users SET phone='0911000088' WHERE id='safe_test'").run();
    assert.equal((await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',phone:'0912469923',idempotencyKey:'stale-jwt-phone-key'},'safe_test')).status,503,'The old JWT phone claim cannot retain a test privilege');
    await DB.prepare("UPDATE users SET phone='+251912469923' WHERE id='safe_test'").run();
  });

  await t.test('guest test requires booking capability and purpose-specific SMS ownership, never an entered phone alone', async () => {
    const first = await hold(), second = await hold();
    assert.equal((await api(pathFor(first,'/checkout'),payer,null,first.bookingSecret)).status,428);
    assert.equal((await api(pathFor(first,'/checkout'),{...payer,testPaymentVerificationToken:'a'.repeat(64)},null,first.bookingSecret)).status,428,'A LOGIN or REGISTRATION proof has no payment context');
    assert.equal((await api(pathFor(first,'/test-payment/request-otp'),payer,null,'wrong')).status,404);
    assert.equal((await api(pathFor(first,'/test-payment/request-otp'),{passengerPhone:'09000000'},null,first.bookingSecret)).status,400);
    const token = await proof(first); assert.equal(sent.at(-1).phone,'+251912469923');
    assert.equal((await api(pathFor(second,'/checkout'),{...payer,testPaymentVerificationToken:token},null,second.bookingSecret)).status,428,'The proof cannot pay another booking');
    const paid = await api(pathFor(first,'/checkout'),{...payer,passengerPhone:'+251912469923',testPaymentVerificationToken:token},null,first.bookingSecret);
    assert.equal(paid.status,200,JSON.stringify(paid.body)); assert.equal(paid.body.booking.paymentMethod,'TEST_AUTO');
    assert.equal((await api(pathFor(first,'/checkout'),payer,null,first.bookingSecret)).status,200,'The same paid booking is idempotent');
    assert.equal((await DB.prepare('SELECT proof_hash,consumed_at FROM payment_test_challenges WHERE booking_id=?').get(first.booking.id)).proof_hash,null);
    assert.equal((await DB.prepare('SELECT payment_status FROM intercity_bookings WHERE id=?').get(second.booking.id)).payment_status,'UNPAID');
  });

  await t.test('OTP failures, amount changes, delivery failure and expiry cannot authorize test payment', async () => {
    const held = await hold(); await allowSmsAgain();
    assert.equal((await api(pathFor(held,'/test-payment/request-otp'),payer,null,held.bookingSecret)).status,200);
    const valid = sent.at(-1).code, wrong = valid==='111111'?'222222':'111111';
    const attempts = await Promise.all(Array.from({length:7},()=>api(pathFor(held,'/test-payment/verify-otp'),{...payer,code:wrong},null,held.bookingSecret)));
    assert.ok(attempts.every(result=>result.status===400));
    assert.equal((await DB.prepare('SELECT attempts FROM payment_test_challenges WHERE booking_id=?').get(held.booking.id)).attempts,5);
    assert.equal((await api(pathFor(held,'/test-payment/verify-otp'),{...payer,code:valid},null,held.bookingSecret)).status,400);
    const token = await proof(held);
    await DB.prepare('UPDATE intercity_bookings SET amount_etb=451 WHERE id=?').run(held.booking.id);
    assert.equal((await api(pathFor(held,'/checkout'),{...payer,testPaymentVerificationToken:token},null,held.bookingSecret)).status,428);
    await DB.prepare('UPDATE intercity_bookings SET amount_etb=450 WHERE id=?').run(held.booking.id);
    await DB.prepare('UPDATE payment_test_challenges SET proof_expires_at=? WHERE booking_id=?').run(new Date(Date.now()-1).toISOString(),held.booking.id);
    assert.equal((await api(pathFor(held,'/checkout'),{...payer,testPaymentVerificationToken:token},null,held.bookingSecret)).status,428);
    const failure = await hold(); await allowSmsAgain(); smsWorks=false;
    assert.equal((await api(pathFor(failure,'/test-payment/request-otp'),payer,null,failure.bookingSecret)).status,503);
    assert.equal((await api(pathFor(failure,'/test-payment/verify-otp'),{...payer,code:sent.at(-1).code},null,failure.bookingSecret)).status,400); smsWorks=true;
  });

  await t.test('disabling free mode blocks an already issued proof without consuming it or altering a paid ticket', async () => {
    const held=await hold(), booking=await DB.prepare('SELECT * FROM intercity_bookings WHERE id=?').get(held.booking.id);
    const service=createPaymentTestService({sendSms:(...args)=>sms.sendSms(...args)});
    await allowSmsAgain(); await service.requestOtp(booking,payer.passengerPhone);
    const token=(await service.verifyOtp(booking,payer.passengerPhone,sent.at(-1).code)).testPaymentVerificationToken;
    const challenge=await DB.prepare('SELECT * FROM payment_test_challenges WHERE booking_id=?').get(held.booking.id);
    process.env.PAYMENT_TESTS_ENABLED='false';
    try {
      const sentBefore=sent.length;
      await assert.rejects(()=>service.requestOtp(booking,payer.passengerPhone),error=>error.status===403);
      await assert.rejects(()=>service.verifyOtp(booking,payer.passengerPhone,sent.at(-1).code),error=>error.status===403);
      await assert.rejects(()=>DB.transaction(tx=>service.consume(tx,booking,payer.passengerPhone,token)),error=>error.status===403);
      assert.equal(sent.length,sentBefore);
      assert.deepEqual(await DB.prepare('SELECT * FROM payment_test_challenges WHERE booking_id=?').get(held.booking.id),challenge);
      assert.equal((await DB.prepare('SELECT payment_status FROM intercity_bookings WHERE id=?').get(held.booking.id)).payment_status,'UNPAID');
      const pass=await DB.prepare("SELECT paymentStatus,qrToken,endDate FROM subscriptions WHERE passengerId='safe_test'").get();
      assert.equal(pass.paymentStatus,'PAID'); assert.ok(pass.qrToken);
      assert.equal((await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',idempotencyKey:'disabled-later-test-key'},'safe_test')).status,403);
      assert.deepEqual(await DB.prepare("SELECT paymentStatus,qrToken,endDate FROM subscriptions WHERE passengerId='safe_test'").get(),pass);
    } finally { process.env.PAYMENT_TESTS_ENABLED='true'; }
  });

  await t.test('safe provider initialization failure retries the same order; ambiguous submission reconciles before any new order', async () => {
    telebirr.missingConfiguration=()=>[];
    const held = await hold(), calls=[];
    telebirr.createCheckout=async order=>{calls.push(order);if(calls.length===1)throw Object.assign(Error('Mock token service unavailable before preorder'),{checkoutRetrySafe:true});return {prepayId:'safe-prepay',checkoutUrl:'https://payments.example/safe'};};
    const ordinary={passengerName:'Ordinary rider',passengerPhone:'0911556677'};
    const first=await api(pathFor(held,'/checkout'),ordinary,null,held.bookingSecret);assert.equal(first.status,502);assert.equal(first.body.code,'CHECKOUT_RETRY_SAFE');
    const retry=await api(pathFor(held,'/checkout'),ordinary,null,held.bookingSecret);assert.equal(retry.status,200);assert.equal(retry.body.checkoutUrl,'https://payments.example/safe');assert.equal(calls.length,2);assert.equal(calls[0],calls[1]);
    const uncertain=await hold();let submissions=0;
    telebirr.createCheckout=async()=>{submissions++;throw Error('Mock response timeout after preorder submission');};
    assert.equal((await api(pathFor(uncertain,'/checkout'),ordinary,null,uncertain.bookingSecret)).status,502);
    const saved=await DB.prepare('SELECT order_id FROM intercity_bookings WHERE id=?').get(uncertain.booking.id);
    telebirr.queryOrderStatus=async()=>{throw Error('Mock gateway temporarily unavailable');};
    const pending=await api(pathFor(uncertain,'/checkout'),ordinary,null,uncertain.bookingSecret);assert.equal(pending.status,503);assert.equal(pending.body.code,'PAYMENT_CONFIRMATION_PENDING');assert.equal(submissions,1);
    assert.equal((await DB.prepare('SELECT order_id FROM intercity_bookings WHERE id=?').get(uncertain.booking.id)).order_id,saved.order_id);
    telebirr.queryOrderStatus=async()=>({paid:true,failed:false,merchantOrderId:saved.order_id,currency:'ETB',amount:'450.00',transactionId:'provider-confirmed'});
    const paid=await api(pathFor(uncertain,'/checkout'),ordinary,null,uncertain.bookingSecret);assert.equal(paid.status,200);assert.equal(paid.body.paid,true);assert.equal(paid.body.booking.paymentMethod,'TELEBIRR');assert.equal(submissions,1);
  });

  await t.test('pending existing preorder recovers its own checkout URL without making a second provider order', async () => {
    const held=await hold(), ordinary={passengerName:'Recovering rider',passengerPhone:'0911556688'};let submissions=0;
    telebirr.createCheckout=async()=>{submissions++;throw Error('Mock missing preorder response');};
    assert.equal((await api(pathFor(held,'/checkout'),ordinary,null,held.bookingSecret)).status,502);
    const saved=await DB.prepare('SELECT order_id FROM intercity_bookings WHERE id=?').get(held.booking.id);
    telebirr.queryOrderStatus=async()=>({paid:false,failed:false,merchantOrderId:saved.order_id,currency:'ETB',amount:'450.00',prepayId:'existing-prepay',status:'PENDING'});
    telebirr.checkoutUrlForPrepay=id=>{assert.equal(id,'existing-prepay');return 'https://payments.example/recovered';};
    const recovered=await api(pathFor(held,'/checkout'),ordinary,null,held.bookingSecret);assert.equal(recovered.status,200);assert.equal(recovered.body.checkoutUrl,'https://payments.example/recovered');assert.equal(submissions,1);
    assert.equal((await DB.prepare('SELECT payment_status FROM intercity_bookings WHERE id=?').get(held.booking.id)).payment_status,'UNPAID');
  });

  await t.test('sync exposes genuine GPS only to its driver, scoped admin or exact active paid assigned passenger', async () => {
    const sync=async id=>{const response=await api('/api/sync/pull',{},id);assert.equal(response.status,200,JSON.stringify(response.body));assert.equal(response.cache,'private, no-store');return response.body.data.vehicles;};
    for (const suffix of ['a','b']) await DB.prepare("INSERT INTO trips(id,vehicleId,driverId,routeId,status) VALUES(?,?,?,'route_bole_merkato','IN_PROGRESS')").run('safe_sync_trip_'+suffix,'safe_vehicle_'+suffix,'safe_driver_'+suffix);
    for(const id of ['safe_unassigned','safe_expired','safe_test'])for(const vehicle of await sync(id)){assert.equal(vehicle.currentLat,null);assert.equal(vehicle.currentLng,null);assert.equal(vehicle.driverName,null);assert.equal(vehicle.driverId,null);}
    const paid=await sync('safe_regular');assert.equal(paid.find(v=>v.id==='safe_vehicle_a').currentLat,9.1);assert.equal(paid.find(v=>v.id==='safe_vehicle_b').currentLat,null,'A different vehicle on the same route is private');
    const driver=await sync('safe_driver_a');assert.equal(driver.find(v=>v.id==='safe_vehicle_a').driverId,'safe_driver_a');assert.equal(driver.find(v=>v.id==='safe_vehicle_b').driverName,null);
    const admin=await sync('safe_admin');assert.equal(admin.find(v=>v.id==='safe_vehicle_b').currentLat,9.2);assert.ok(!admin.some(v=>v.id==='safe_intercity_bus'));
    const companyDriver=await sync('safe_company_driver');assert.deepEqual(companyDriver.map(v=>v.id),['safe_intercity_bus']);assert.equal(companyDriver[0].currentLat,null,'Seeded currentLat/currentLng never become genuine GPS');
    const currentPoint = async actor => (await sync(actor)).find(v=>v.id==='safe_vehicle_a').currentLat;
    await DB.prepare("UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id='safe_vehicle_a'").run(new Date(Date.now()-121000).toISOString());
    for (const actor of ['safe_regular','safe_driver_a','safe_admin']) assert.equal(await currentPoint(actor),null,'Stale GPS must not be projected as a current point for '+actor);
    await DB.prepare("UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id='safe_vehicle_a'").run(new Date(Date.now()+60000).toISOString());
    assert.equal(await currentPoint('safe_admin'),null,'A future GPS timestamp is invalid');
    await DB.prepare("UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id='safe_vehicle_a'").run(new Date().toISOString());
    await DB.prepare("UPDATE trips SET status='COMPLETED' WHERE id='safe_sync_trip_a'").run();
    for (const actor of ['safe_regular','safe_driver_a','safe_admin']) assert.equal(await currentPoint(actor),null,'A completed trip cannot publish its last GPS as current');
    await DB.prepare("UPDATE trips SET status='IN_PROGRESS',driverId='safe_driver_b' WHERE id='safe_sync_trip_a'").run();
    assert.equal(await currentPoint('safe_admin'),null,'A trip for a previous/different driver is not the current assignment');
    await DB.prepare("UPDATE trips SET driverId='safe_driver_a',routeId='route_megenagna_torhailoch' WHERE id='safe_sync_trip_a'").run();
    assert.equal(await currentPoint('safe_admin'),null,'An old route trip does not authorize GPS on a reassigned route');
    await DB.prepare("UPDATE trips SET routeId='route_bole_merkato' WHERE id='safe_sync_trip_a'").run();
    await DB.prepare("UPDATE vehicles SET driverId=NULL WHERE id='safe_vehicle_a'").run();
    assert.equal(await currentPoint('safe_admin'),null,'Unassigned vehicles have no current driver location');
    await DB.prepare("UPDATE vehicles SET driverId='safe_driver_a' WHERE id='safe_vehicle_a'").run();
    assert.equal(await currentPoint('safe_regular'),9.1,'Fresh GPS returns for the current active trip only');
    await DB.prepare("INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude,updated_at) VALUES('safe_intercity_bus',9.3,38.7,?)").run(new Date().toISOString());
    assert.equal((await sync('safe_company_driver'))[0].currentLat,null,'Intercity GPS requires a BOARDING departure');
    await DB.prepare("INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES('safe_sync_departure','safe_company','safe_intercity','safe_company_driver','safe_intercity_bus','Addis','Hawassa',?,9,38,14,450,'BOARDING',?)").run(new Date().toISOString(),new Date().toISOString());
    assert.equal((await sync('safe_company_driver'))[0].currentLat,9.3);
    await DB.prepare("UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id='safe_intercity_bus'").run(new Date(Date.now()-121000).toISOString());
    assert.equal((await sync('safe_company_driver'))[0].currentLat,null,'Intercity GPS is also freshness limited');
    await DB.prepare("UPDATE vehicle_live_locations SET updated_at=? WHERE vehicle_id='safe_intercity_bus'").run(new Date().toISOString());
    await DB.prepare("UPDATE vehicles SET assignedRouteId=NULL WHERE id='safe_intercity_bus'").run();
    assert.equal((await sync('safe_company_driver'))[0].currentLat,null,'A stale intercity route assignment cannot expose current GPS');
    await DB.prepare("UPDATE vehicles SET assignedRouteId='safe_intercity' WHERE id='safe_intercity_bus'").run();
    await DB.prepare("UPDATE intercity_departures SET status='COMPLETED' WHERE id='safe_sync_departure'").run();
    assert.equal((await sync('safe_company_driver'))[0].currentLat,null,'Intercity completion immediately suppresses current GPS');
    await DB.prepare("UPDATE vehicles SET driverId='safe_unassigned' WHERE id='safe_vehicle_b'").run();
    assert.equal((await sync('safe_unassigned')).find(v=>v.id==='safe_vehicle_b').currentLat,null,'A passenger never gains driver-owner privileges through a stale vehicle foreign key');
    await DB.prepare("UPDATE vehicles SET driverId='safe_driver_b' WHERE id='safe_vehicle_b'").run();
    await DB.prepare("UPDATE users SET companyId='safe_company' WHERE id IN ('safe_admin','safe_unassigned')").run();
    for(const id of ['safe_admin','safe_unassigned'])assert.ok(!(await sync(id)).some(v=>v.id==='safe_intercity_bus'),'Legacy account metadata cannot cross the admin/passenger commute boundary');
    await DB.prepare("UPDATE users SET companyId='' WHERE id IN ('safe_admin','safe_unassigned')").run();
  });

  await t.test('financial reports exclude no-money test activity while retaining operational booking and boarding counts', async () => {
    const beforeStats=(await get('/api/admin/stats','safe_admin')).body.stats.totalRevenueEtb;
    const beforeReports={};for(const period of ['daily','weekly','monthly'])beforeReports[period]=(await get('/api/admin/reports?period='+period,'safe_admin')).body.totals.revenue;
    for(const [id,amount,provider,status] of [['real_verified',100,'Telebirr','VERIFIED'],['real_completed',50,'Cash','COMPLETED'],['fake_auto',900,'TEST_AUTO','VERIFIED'],['fake_admin',800,'ADMIN_TEST','COMPLETED'],['fake_sandbox',700,'Telebirr-TestSandbox','COMPLETED']])
      await DB.prepare('INSERT INTO payment_transactions(id,passengerId,referenceNumber,amountEtb,provider,status) VALUES(?,?,?,?,?,?)').run(id,'safe_regular','reference-'+id,amount,provider,status);
    assert.equal((await get('/api/admin/stats','safe_admin')).body.stats.totalRevenueEtb-beforeStats,150);
    for(const period of ['daily','weekly','monthly'])assert.equal((await get('/api/admin/reports?period='+period,'safe_admin')).body.totals.revenue-beforeReports[period],150,period);
    const before=(await get('/api/intercity/company/performance?period=daily','safe_company')).body.total;
    for(const [method,amount,status] of [['CASH_COLLECTED',200,'BOARDED'],['TEST_AUTO',400,'BOARDED'],['TELEBIRR',100,'CONFIRMED']]){
      const held=await hold();await DB.prepare("UPDATE intercity_bookings SET payment_status='PAID',payment_method=?,amount_etb=?,status=? WHERE id=?").run(method,amount,status,held.booking.id);
    }
    for(const period of ['daily','weekly','monthly']){
      const result=await get('/api/intercity/company/performance?period='+period,'safe_company');assert.equal(result.status,200,JSON.stringify(result.body));
      assert.equal(result.body.total.revenueEtb-before.revenueEtb,300);assert.equal(result.body.total.bookings-before.bookings,3);assert.equal(result.body.total.boarded-before.boarded,2);
    }
    assert.equal((await DB.prepare("SELECT COUNT(*) AS count FROM payment_transactions WHERE id IN ('fake_auto','fake_admin','fake_sandbox')").get()).count,3,'Test financial records remain visible for audit');
  });

  await t.test('uncertain commuter preorder remains reconcilable instead of being falsely marked failed', async () => {
    telebirr.createCheckout=async()=>{throw Error('Mock lost commuter preorder response');};
    const started=await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',idempotencyKey:'commuter-uncertain-key'},'safe_regular');
    assert.equal(started.status,502);assert.equal(started.body.code,'PAYMENT_CONFIRMATION_PENDING');
    const order=await DB.prepare("SELECT * FROM telebirr_payment_orders WHERE idempotency_key='commuter-uncertain-key'").get();assert.equal(order.status,'PENDING');
    const retried=await api('/api/subscriptions/telebirr/pay',{routeId:'route_bole_merkato',idempotencyKey:'commuter-uncertain-key'},'safe_regular');assert.equal(retried.status,503);assert.equal(retried.body.merchantOrderId,order.merchant_order_id);
    telebirr.queryOrderStatus=async()=>({paid:true,failed:false,merchantOrderId:order.merchant_order_id,currency:'ETB',amount:String(order.amount_etb),transactionId:'confirmed-commuter'});
    const status=await get('/api/subscriptions/telebirr/status/'+order.merchant_order_id,'safe_regular');assert.equal(status.status,200);assert.equal(status.body.status,'PAID');
    assert.equal((await DB.prepare('SELECT status FROM telebirr_payment_orders WHERE merchant_order_id=?').get(order.merchant_order_id)).status,'PAID');
  });

  await t.test('real provider adapter distinguishes pre-submission failure from a possibly accepted preorder', async () => {
    const originalFetch=global.fetch,originalKey=process.env.TELEBIRR_PRIVATE_KEY;
    const {privateKey}=require('node:crypto').generateKeyPairSync('rsa',{modulusLength:2048});
    process.env.TELEBIRR_PRIVATE_KEY=privateKey.export({format:'pem',type:'pkcs8'});
    try {
      telebirr.resetTokenCacheForTests();let calls=0;
      global.fetch=async()=>{calls++;throw Error('mock token network failure');};
      await assert.rejects(()=>originals.createCheckout('RPSAFE12345678',10,'Fixture'),error=>error.checkoutRetrySafe===true);
      assert.equal(calls,1,'A token failure never sent a merchant preorder');
      telebirr.resetTokenCacheForTests();calls=0;
      global.fetch=async()=>{calls++;if(calls===1)return {ok:true,status:200,json:async()=>({token:'isolated-fabric-token',expirationDate:Date.now()+3600000})};throw Error('mock preorder timeout');};
      await assert.rejects(()=>originals.createCheckout('RPUNCERTAIN12345678',10,'Fixture'),error=>error.checkoutRetrySafe!==true);
      assert.equal(calls,2,'After preorder submission the adapter cannot promise that no order exists');
    } finally {global.fetch=originalFetch;telebirr.resetTokenCacheForTests();if(originalKey===undefined)delete process.env.TELEBIRR_PRIVATE_KEY;else process.env.TELEBIRR_PRIVATE_KEY=originalKey;}
  });
});
