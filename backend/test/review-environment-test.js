'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'passo-review-isolation-'));
process.env.NODE_ENV='test';process.env.DATA_DIR=path.join(temporary,'data');process.env.UPLOAD_DIR=path.join(temporary,'uploads');
const postgres=process.env.REVIEW_TEST_POSTGRES==='true';
if(postgres && (process.env.CI!=='true' || !/^postgres:\/\/passo_review:[^@]+@127\.0\.0\.1:5432\/passo_review$/.test(process.env.DATABASE_URL||''))) throw Error('Only the separate disposable CI review database is allowed.');
if(!postgres)delete process.env.DATABASE_URL;
const isolation=require('../src/review/isolation');
const secret=()=>crypto.randomBytes(32).toString('hex');
const environment={NODE_ENV:'production',REVIEW_ENVIRONMENT:'true',PUBLIC_BASE_URL:'https://passo.live/review',REVIEW_SMS_MODE:'DISABLED',PAYMENT_MODE:'PRODUCTION',PAYMENT_TESTS_ENABLED:'false',ALLOW_MANUAL_TEST_RECHARGE:'false',ALLOW_ADMIN_PASS_ACTIVATION:'false',DATABASE_URL:'postgres://passo_review:'+secret()+'@review-db:5432/passo_review',JWT_SECRET:secret(),QR_SIGNING_KEY:secret(),ADMIN_REGISTRATION_SECRET:secret(),REVIEW_INSTANCE_ID:secret(),REVIEW_FIXTURE_SECRET:secret(),REVIEW_DEMO_PASSWORD:secret(),DATA_DIR:'/app/review-data',UPLOAD_DIR:'/app/review-uploads'};
test('isolated synthetic review runtime never adopts production data or enables live operations',async t=>{
  await t.test('configuration rejects production hosts, secrets, origins and payment/SMS bypasses',()=>{
    for(const invalid of [{REVIEW_ENVIRONMENT:'false'},{PUBLIC_BASE_URL:'https://passo.live'},{DATABASE_URL:'postgres://passo_review:'+secret()+'@db:5432/routepass_db'},{TELEBIRR_PRIVATE_KEY:'not-a-real-key'},{AFRO_SMS_API_TOKEN:'not-a-real-token'},{PAYMENT_TESTS_ENABLED:'true'},{REVIEW_SMS_MODE:'ENABLED'},{JWT_SECRET:environment.QR_SIGNING_KEY},{DATA_DIR:'/app/data'},{NODE_ENV:'development'}]) assert.throws(()=>isolation.validateReviewEnvironment({...environment,...invalid}));
    assert.throws(()=>isolation.activateReviewRuntime({publicBaseUrl:'https://passo.live/review'}));
  });
  const config=isolation.validateReviewEnvironment({...environment,DATA_DIR:process.env.DATA_DIR,UPLOAD_DIR:process.env.UPLOAD_DIR},{test:true});
  isolation.activateReviewRuntime(config);
  for(const key of ['JWT_SECRET','QR_SIGNING_KEY','ADMIN_REGISTRATION_SECRET','PUBLIC_BASE_URL','PAYMENT_MODE','PAYMENT_TESTS_ENABLED','ALLOW_MANUAL_TEST_RECHARGE','ALLOW_ADMIN_PASS_ACTIVATION','REVIEW_ENVIRONMENT']) process.env[key]=environment[key];
  const {DB}=require('../src/db');
  await t.test('database marker refuses a DB containing even seeded non-review accounts',async()=>{
    if(postgres)await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('unowned_ci_account','PASSENGER','Unowned CI account','+251900001111','unused','ACTIVE')").run();
    await assert.rejects(isolation.assertReviewDatabase(DB,config),/containing customer\/account/);
    // These are disposable test databases only, never a VPS or a reused CI DB.
    await DB.exec('DELETE FROM vehicles; DELETE FROM users; DELETE FROM route_stops; DELETE FROM routes; DELETE FROM notifications;');
    await isolation.assertReviewDatabase(DB,config);
    const wrong={...config,marker:'different'};await assert.rejects(isolation.assertReviewDatabase(DB,wrong),/validated alternate/);
  });
  const api=require('../src/server');await api.runDatabaseMigrations();
  const fixtures=require('../src/review/fixtures').createReviewFixtures(DB,config);require('../src/review/state').fixtures=fixtures;
  let accounts=await fixtures.refresh();
  const server=api.server;server.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
  t.after(()=>new Promise(resolve=>{api.resourcePressure.stop();server.closeAllConnections();server.close(resolve);}));
  const base='http://127.0.0.1:'+server.address().port;
  const request=async(endpoint,{method='GET',body,token,headers={}}={})=>{
    const response=await fetch(base+endpoint,{method,headers:{...headers,...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
    const bytes=Buffer.from(await response.arrayBuffer());let data;try{data=JSON.parse(bytes);}catch(_){}
    return {status:response.status,data,headers:response.headers,bytes};
  };
  const login=async(phone,role)=>{const r=await request('/api/auth/login',{method:'POST',body:{phone,role,password:config.password}});assert.equal(r.status,200,JSON.stringify(r.data));return r.data.token;};
  const passenger=await login('0900000101','PASSENGER'),daily=await login('0900000102','DRIVER'),group=await login('0900000103','DRIVER'),cargo=await login('0900000104','DRIVER'),companyDriver=await login('0900000105','DRIVER');
  await t.test('all supported driver scopes sign in; pending/accepted remain genuine application states',async()=>{
    assert.equal((await request('/api/trips/assignments',{token:daily})).status,200);
    for(const token of [group,cargo])assert.equal((await request('/api/mobility/driver/dashboard',{token})).status,200);
    assert.equal((await request('/api/intercity/driver/departures',{token:companyDriver})).status,200);
    for(const phone of ['0900000106','0900000107']) {
      const r=await request('/api/onboarding/login',{method:'POST',body:{phone,password:config.password,role:'DRIVER'}});assert.equal(r.status,200,JSON.stringify(r.data));
      assert.equal(r.data.onboarding.acceptanceStatus,phone==='0900000106'?'PENDING':'ACCEPTED');
      assert.equal((await request('/api/auth/login',{method:'POST',body:{phone,password:config.password,role:'DRIVER'}})).status,403);
    }
  });
  let samples;
  await t.test('review manifest is authenticated, private and returns only seeded synthetic capabilities',async()=>{
    const publicInfo=await request('/api/review/info');assert.equal(publicInfo.status,200);assert.equal(publicInfo.data.smsEnabled,false);assert.equal(publicInfo.data.paymentsEnabled,false);
    assert.equal((await request('/api/review/resources')).status,401);
    const r=await request('/api/review/resources',{token:passenger});assert.equal(r.status,200);samples=r.data;
    const wrongKey=require('jsonwebtoken').sign({id:accounts.passenger,role:'PASSENGER'},'a-different-production-like-key-never-actually-production');
    assert.equal((await request('/api/review/resources',{token:wrongKey})).status,401);
    assert.equal(r.headers.get('x-passo-environment'),'REVIEW');assert.equal(JSON.stringify(r.data).includes(config.password),false);
    const paid=await request('/api/mobility/bookings/'+samples.groupBooking.id,{headers:{'X-Booking-Secret':samples.groupBooking.bookingSecret}});
    assert.equal(paid.status,200,JSON.stringify(paid.data));assert.equal(paid.data.booking.paymentStatus,'PAID');
    assert.ok(paid.data.booking.travelers.every(p=>p.boardingPassUrl.startsWith('https://passo.live/review/api/')));
    assert.equal((await request('/api/intercity/bookings/'+samples.intercityBooking.id,{headers:{'X-Booking-Secret':samples.intercityBooking.bookingSecret}})).status,200);
    assert.equal((await request('/api/mobility/bookings/'+samples.groupBooking.id,{headers:{'X-Booking-Secret':'wrong'}})).status,404);
  });
  await t.test('sample creation is idempotent and never overwrites an unowned record',async()=>{
    const before=Number((await DB.prepare('SELECT COUNT(*) AS count FROM users').get()).count);
    await fixtures.refresh();assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM users').get()).count),before);
    await DB.prepare("UPDATE review_fixture_owners SET marker='tampered' WHERE entity_type='route' AND entity_id='review_daily_route'").run();
    await assert.rejects(fixtures.refresh(),/collision/);
    await DB.prepare("UPDATE review_fixture_owners SET marker=? WHERE entity_type='route' AND entity_id='review_daily_route'").run(config.marker);
  });
  await t.test('external sample PDF and HTML boarding passes visibly identify synthetic travel and no real payment',async()=>{
    const paid=await request('/api/mobility/bookings/'+samples.groupBooking.id,{headers:{'X-Booking-Secret':samples.groupBooking.bookingSecret}});
    const url=new URL(paid.data.booking.travelers[0].boardingPassUrl);
    const html=await request(url.pathname.replace(/^\/review/,'')+url.search);
    assert.equal(html.status,200);assert.match(html.headers.get('content-type'),/text\/html/);
    const markup=html.bytes.toString('utf8');
    assert.match(markup,/<strong>SYNTHETIC DEMO<\/strong>/);assert.match(markup,/no real payment; not valid for real travel/);
    const pdf=await request('/api/intercity/bookings/'+samples.intercityBooking.id+'/ticket.pdf',{headers:{'X-Booking-Secret':samples.intercityBooking.bookingSecret}});
    assert.equal(pdf.status,200);assert.match(pdf.headers.get('content-type'),/application\/pdf/);
    assert.ok(pdf.bytes.subarray(0,5).equals(Buffer.from('%PDF-')));
    const metadata=pdf.bytes.toString('latin1');
    assert.match(metadata,/Passo synthetic demo ticket/);assert.match(metadata,/Synthetic demo - no real payment; not valid for real travel\./);
    assert.equal(require('../src/services/ticket-presentation').ticketPresentation().synthetic,true);
    // Optional local visual QA artifacts contain only this disposable test's
    // synthetic data and keys, never production records or provider secrets.
    if(process.env.REVIEW_ARTIFACT_OUTPUT_DIR) {
      const output=path.resolve(process.env.REVIEW_ARTIFACT_OUTPUT_DIR);fs.mkdirSync(output,{recursive:true});
      fs.writeFileSync(path.join(output,'synthetic-intercity-ticket.pdf'),pdf.bytes);
      fs.writeFileSync(path.join(output,'synthetic-group-ticket.html'),html.bytes);
    }
  });
  await t.test('no admin pages, seed/reset endpoints, payment activation or SMS delivery exist publicly',async()=>{
    for(const endpoint of ['/admin/','/company/','/join/','/api/admin/users','/api/mobility/admin/trips','/api/review/reset','/api/review/seed']) assert.equal((await request(endpoint,{token:daily})).status,404);
    for(const endpoint of ['/api/subscriptions/telebirr/create-order','/api/mobility/bookings/'+samples.groupBooking.id+'/checkout','/api/auth/request-otp'])assert.equal((await request(endpoint,{method:'POST',body:{},token:passenger})).status,503);
    let fetches=0;const original=global.fetch;global.fetch=async()=>{fetches++;throw Error('Unexpected network');};
    try{assert.equal((await require('../src/services/afro-sms').sendSms('+251900000101','Synthetic')).sent,false);assert.equal(fetches,0);}finally{global.fetch=original;}
  });
  await t.test('group and company drivers can board their paid synthetic travelers outside Ethiopia, never an unpaid or foreign ticket',async()=>{
    const location={latitude:-33.86,longitude:151.2,accuracyMeters:5,timestamp:new Date().toISOString()};
    const paid=await request('/api/mobility/bookings/'+samples.groupBooking.id,{headers:{'X-Booking-Secret':samples.groupBooking.bookingSecret}}),trip=paid.data.booking.tripId,ticket=paid.data.booking.travelers[0].ticketNumber;
    const open=await request('/api/mobility/driver/trips/'+trip+'/boarding',{method:'POST',token:group,body:location});assert.equal(open.status,200,JSON.stringify(open.data));
    assert.equal((await request('/api/mobility/driver/trips/'+trip+'/board',{method:'POST',token:cargo,body:{...location,ticketNumber:ticket}})).status,404);
    assert.equal((await request('/api/mobility/driver/trips/'+trip+'/board',{method:'POST',token:group,body:{...location,ticketNumber:ticket}})).status,200);
    assert.equal((await request('/api/mobility/driver/trips/'+trip+'/board',{method:'POST',token:group,body:{...location,ticketNumber:ticket}})).status,409);
    const intercity=await request('/api/intercity/bookings/'+samples.intercityBooking.id,{headers:{'X-Booking-Secret':samples.intercityBooking.bookingSecret}}),departure=intercity.data.booking.departureId;
    const arrival=await request('/api/intercity/driver/departures/'+departure+'/arrival',{method:'POST',token:companyDriver,body:{location}});assert.equal(arrival.status,200,JSON.stringify(arrival.data));
    assert.equal((await request('/api/intercity/driver/departures/'+departure+'/board',{method:'POST',token:companyDriver,body:{qrToken:intercity.data.booking.qrToken}})).status,200);
  });
  await t.test('fresh foreign-location fix relocates only an owned demo route; standard stale/precision guards still reject',async()=>{
    const assignments=await request('/api/trips/assignments',{token:daily}),v=assignments.data.vehicles[0];
    const location={latitude:51.5,longitude:-0.12,accuracyMeters:4,timestamp:Date.now()};
    const input={routeId:v.assignedRouteId,vehicleId:v.id,direction:'OUTBOUND',location};
    assert.equal((await request('/api/trips/departure-check',{method:'POST',token:daily,body:input})).data.validation.allowed,true);
    const stale=await request('/api/trips/start',{method:'POST',token:daily,body:{...input,location:{...location,timestamp:Date.now()-150000}}});assert.equal(stale.status,403);
    const started=await request('/api/trips/start',{method:'POST',token:daily,body:input});assert.equal(started.status,200,JSON.stringify(started.data));
    const foreign=await request('/api/trips/start',{method:'POST',token:cargo,body:input});assert.notEqual(foreign.status,200);
    const sub=await DB.prepare('SELECT * FROM subscriptions WHERE passengerId=?').get(accounts.passenger);
    assert.equal((await request('/api/checkins/scan',{method:'POST',token:daily,body:{qrToken:sub.qrToken,tripId:started.data.trip.id,vehicleId:v.id,currentStop:'Demo origin'}})).status,200);
    assert.equal((await request('/api/trips/'+started.data.trip.id+'/end',{method:'POST',token:daily,body:{}})).status,200);
  });
  await t.test('a disposable deletion stays revoked and receives a fresh replacement ID without deleting its receipt',async()=>{
    const old=accounts.disposable,oldToken=await login('0900000108','PASSENGER');
    const service=require('../src/services/account-deletion').createAccountDeletionService();
    await service.erase(old,'PASSENGER',{password:config.password,confirmation:'DELETE'});
    accounts=await fixtures.refresh();assert.notEqual(accounts.disposable,old);
    assert.equal(await require('../src/services/account-deletion').isAccountClosed(old,'PASSENGER'),true);
    assert.equal((await request('/api/auth/me',{token:oldToken})).status,401);
    assert.ok(await login('0900000108','PASSENGER'));
  });
});
