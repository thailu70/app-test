'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'test-only-notification-key-'.repeat(3);
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-push-'));
if (process.env.MOBILITY_TEST_POSTGRES === 'true') {
  if (process.env.CI !== 'true' || !process.env.DATABASE_URL) throw new Error('Disposable CI PostgreSQL configuration is required.');
} else delete process.env.DATABASE_URL;
const { DB } = require('../src/db');
const { initMobilitySchema } = require('../src/services/mobility-schema');
const { initMarketplaceSchema } = require('../src/services/mobility-marketplace');
const { createMobilityPushService, createFcmTransport } = require('../src/services/mobility-push');
const { MobilityError } = require('../src/services/mobility');

test('push tokens stay private; nearby eligibility, busy suppression, dedup and guest updates are enforced', async () => {
  await initMobilitySchema(DB); await initMarketplaceSchema(DB);
  for (const driver of ['push-driver1','push-driver2']) await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,'DRIVER','Fixture driver',?,'unused','ACTIVE')").run(driver, driver === 'push-driver1' ? '+251900000011' : '+251900000012');
  await DB.prepare("INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES('push-vehicle','PUSH-FIXTURE','Fixture','MINIVAN_8',8,'push-driver1','AVAILABLE')").run();
  let clock = Date.parse('2026-11-01T08:00:00Z');
  const at = () => new Date(clock).toISOString();
  await DB.prepare("INSERT INTO mobility_requests(id,kind,secret_hash,origin,destination,travel_date,people,vehicle_type,customer_name,customer_phone,status,created_at) VALUES('push-request','GROUP','fixture','Fixture pickup','Fixture destination','2026-11-02',4,'MINIVAN_8','Private person','+251900000099','REQUESTED',?)").run(at());
  let busy = true, eligible = true, sends = [], invalidate = false, deliveryFailure = false;
  const fake = {
    init: async () => {},
    request: async (id, secret) => { if (id !== 'push-request' || secret !== 'private-request-capability') throw new MobilityError(404,'Unavailable'); return { request: { id } }; },
    marketplace: { opportunities: async driver => ({ busy, requests: busy ? [] : [{ id: 'push-request', kind: 'GROUP', nearby: driver === 'push-driver1', myQuote: null }] }) }
  };
  const push = createMobilityPushService({ db: DB, service: fake, now: () => clock, env: { JWT_SECRET: process.env.JWT_SECRET },
    driverIsEligible: async () => eligible,
    provider: { send: async (token, notification, data) => { sends.push({ token, notification, data }); return invalidate ? { sent: false, invalidToken: true } : { sent: !deliveryFailure }; } } });
  const driverToken = 'driver-device-fixture-token-123456789';
  const guestToken = 'guest-device-fixture-token-123456789';
  await push.registerDriver('push-driver1', { token: driverToken, latitude: 9, longitude: 38.7 });
  await push.registerDriver('push-driver2', { token: 'far-device-fixture-token-123456789', latitude: 12, longitude: 38 });
  await assert.rejects(push.registerDriver('usr_pas_alemayehu', { token: driverToken }), error => error.status === 403);
  await assert.rejects(push.registerRequest('push-request', 'wrong', { token: guestToken }), error => error.status === 404);
  await push.registerRequest('push-request', 'private-request-capability', { token: guestToken });
  const saved = await DB.prepare('SELECT * FROM mobility_push_tokens').all();
  assert.equal(JSON.stringify(saved).includes(driverToken), false); assert.equal(JSON.stringify(saved).includes(guestToken), false);
  await push.flush(); assert.equal(sends.length, 0, 'Busy driver receives no request notification.');
  assert.equal(Number((await DB.prepare('SELECT COUNT(*) AS count FROM mobility_push_outbox').get()).count), 0);
  busy = false; eligible = false; await push.flush(); assert.equal(sends.length, 0, 'Unapproved driver receives no alerts.');
  eligible = true; await push.flush(); assert.equal(sends.length, 1); assert.equal(sends[0].data.eventType, 'NEW_REQUEST');
  assert.equal(sends[0].token, driverToken); assert.deepEqual(Object.keys(sends[0].data).sort(), ['eventType','kind','requestId','scope']);
  assert.equal(JSON.stringify(sends).includes('Private person'), false); assert.equal(JSON.stringify(sends).includes('private-request-capability'), false);
  await push.flush(); assert.equal(sends.length, 1, 'Repeated poll does not resend the same request.');
  await DB.prepare("INSERT INTO mobility_quotes(id,request_id,driver_id,vehicle_id,price_etb,created_at,updated_at) VALUES('push-quote','push-request','push-driver1','push-vehicle',1000,?,?)").run(at(), at());
  await push.flush(); assert.equal(sends.at(-1).data.eventType, 'QUOTES'); assert.equal(sends.at(-1).token, guestToken);
  busy = true;
  await DB.prepare("UPDATE mobility_requests SET status='ASSIGNED',driver_id='push-driver1',assigned_at=? WHERE id='push-request'").run(at());
  await push.flush(); assert.ok(sends.some(x => x.data.eventType === 'SELECTED' && x.token === driverToken));
  assert.ok(sends.some(x => x.data.eventType === 'STATUS_ASSIGNED' && x.token === guestToken));
  const afterAssignment = sends.length; await push.flush(); assert.equal(sends.length, afterAssignment);
  await DB.prepare("UPDATE mobility_requests SET status='AT_DESTINATION' WHERE id='push-request'").run();
  invalidate = true; await push.flush();
  assert.ok(!await DB.prepare("SELECT id FROM mobility_push_tokens WHERE scope='REQUEST'").get(), 'Unregistered FCM device is revoked.');
  await assert.rejects(push.unregisterRequest('push-request','wrong',{token:guestToken}),error=>error.status===404);
  await push.unregisterDriver('push-driver1',{token:driverToken});
  assert.ok(!await DB.prepare("SELECT id FROM mobility_push_tokens WHERE owner_id='push-driver1'").get());
  invalidate=false;deliveryFailure=true;
  await push.registerRequest('push-request','private-request-capability',{token:'retry-fixture-device-token-123456789'});
  await DB.prepare("UPDATE mobility_requests SET status='AWAITING_CONFIRMATION' WHERE id='push-request'").run();
  await push.flush();const firstAttempt=sends.length;
  await push.flush();assert.equal(sends.length,firstAttempt,'Transient delivery failure does not spin or retry immediately.');
  clock+=59000;await push.flush();assert.equal(sends.length,firstAttempt);
  clock+=1000;deliveryFailure=false;await push.flush();assert.equal(sends.length,firstAttempt+1);
  assert.equal(sends.at(-1).data.eventType,'STATUS_AWAITING_CONFIRMATION');
  deliveryFailure=true;
  await DB.prepare("UPDATE mobility_requests SET status='COMPLETED' WHERE id='push-request'").run();
  await push.flush();const beforeExpiry=sends.length;clock+=13*3600000;await push.flush();
  assert.equal(sends.length,beforeExpiry,'Undelivered notifications expire after twelve hours.');
  assert.ok((await DB.prepare("SELECT id FROM mobility_push_outbox WHERE event_type='STATUS_COMPLETED' AND status='EXPIRED'").get()));
  const pagingTokens=[1,2,3].map(n=>'pagination-fixture-device-token-123456789-'+n),pagedSends=[];
  const paged=createMobilityPushService({db:DB,service:fake,now:()=>clock,env:{JWT_SECRET:process.env.JWT_SECRET},customerBatchSize:1,driverIsEligible:async()=>false,
    provider:{send:async(token,_notification,data)=>{pagedSends.push({token,event:data.eventType});return {sent:true};}}});
  for(const token of pagingTokens)await paged.registerRequest('push-request','private-request-capability',{token});
  await DB.prepare("UPDATE mobility_requests SET status='CANCELLED' WHERE id='push-request'").run();
  for(let i=0;i<8;i++)await paged.flush();
  assert.deepEqual(pagedSends.filter(x=>pagingTokens.includes(x.token)).map(x=>x.token).sort(),pagingTokens.slice().sort(),'Stable keyset paging reaches every older customer token without starvation or duplicate delivery.');
  assert.deepEqual(createMobilityPushService({service:fake,env:{JWT_SECRET:process.env.JWT_SECRET}}).health(),{enabled:false,reason:'NOT_CONFIGURED'});
  const review = createMobilityPushService({service:fake,env:{JWT_SECRET:process.env.JWT_SECRET,REVIEW_ENVIRONMENT:'true'},provider:{send:async()=>{throw new Error('Must not contact provider');}}});
  assert.deepEqual(await review.flush(),{sent:0,enabled:false,reason:'REVIEW_DISABLED'});
  const express = require('express');
  const { createMobilityRouter } = require('../src/routes/mobility');
  const app = express(); app.use(express.json()); app.use('/api/mobility',createMobilityRouter({service:fake,pushService:push}));
  const server = app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve));
  try {
    const base='http://127.0.0.1:'+server.address().port+'/api/mobility';
    const post=(url,headers={})=>fetch(base+url,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify({token:guestToken})});
    assert.equal((await post('/driver/push-token')).status,401,'Driver endpoint requires authentication.');
    assert.equal((await post('/requests/push-request/push-token',{'X-Request-Secret':'wrong'})).status,404,'Private request capability is required.');
    assert.equal((await post('/requests/push-request/push-token',{'X-Request-Secret':'private-request-capability'})).status,200);
  } finally { await new Promise(resolve=>server.close(resolve)); }
});

test('a busy driver alert yields the delivery batch to a customer booking update', async () => {
  await initMobilitySchema(DB); await initMarketplaceSchema(DB);
  let clock=Date.parse('2026-11-05T08:00:00Z'),busy=false,failDelivery=true;
  for(const id of ['push-blocked-request','push-ready-request'])await DB.prepare("INSERT INTO mobility_requests(id,kind,secret_hash,origin,destination,travel_date,people,vehicle_type,customer_name,customer_phone,status,created_at) VALUES(?,'GROUP','fixture','Fixture pickup','Fixture destination','2026-11-06',4,'MINIVAN_8','Private fixture','+251900000088','REQUESTED',?)").run(id,new Date(clock).toISOString());
  const sent=[];
  const fake={init:async()=>{},request:async(id,secret)=>{if(id!=='push-ready-request'||secret!=='private-ready-capability')throw new MobilityError(404,'Unavailable');return {request:{id}};},
    marketplace:{opportunities:async()=>({busy,requests:busy?[]:[{id:'push-blocked-request',kind:'GROUP',nearby:true,myQuote:null}]})}};
  const push=createMobilityPushService({db:DB,service:fake,now:()=>clock,env:{JWT_SECRET:process.env.JWT_SECRET},deliveryBatchSize:1,driverIsEligible:async(id)=>id==='push-driver1',
    provider:{send:async(token,_notification,data)=>{if(failDelivery)return {sent:false};sent.push({token,event:data.eventType});return {sent:true};}}});
  await push.registerDriver('push-driver1',{token:'busy-fixture-device-token-123456789',latitude:9,longitude:38.7});
  await push.flush();
  assert.equal(Number((await DB.prepare("SELECT attempts FROM mobility_push_outbox WHERE request_id='push-blocked-request'").get()).attempts),1);
  clock+=60000;busy=true;failDelivery=false;
  await push.registerRequest('push-ready-request','private-ready-capability',{token:'ready-fixture-device-token-123456789'});
  await DB.prepare("UPDATE mobility_requests SET status='ASSIGNED' WHERE id='push-ready-request'").run();
  await push.flush();
  assert.equal(sent.some(x=>x.event==='NEW_REQUEST'),false,'Busy driver still receives no request alert.');
  await push.flush();
  assert.ok(sent.some(x=>x.token==='ready-fixture-device-token-123456789'&&x.event==='STATUS_ASSIGNED'),'A blocked older alert cannot starve a customer update.');
});

test('FCM HTTP v1 signs a scoped assertion, caches access and identifies revoked tokens', async () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const file = path.join(process.env.DATA_DIR, 'synthetic-service-account.json');
  fs.writeFileSync(file, JSON.stringify({type:'service_account',project_id:'passo-fixture-project',client_email:'fixture@passo-fixture-project.iam.gserviceaccount.com',private_key:privateKey.export({format:'pem',type:'pkcs8'})}),{mode:0o600});
  let authorizations=0, messages=0;
  const provider = createFcmTransport({accountFile:file,fetchImpl:async(url,options)=>{
    if(url==='https://oauth2.googleapis.com/token'){
      authorizations++; const assertion=options.body.get('assertion'),segments=assertion.split('.');
      assert.equal(crypto.verify('RSA-SHA256',Buffer.from(segments[0]+'.'+segments[1]),publicKey,Buffer.from(segments[2],'base64url')),true);
      const claims=JSON.parse(Buffer.from(segments[1],'base64url'));assert.equal(claims.scope,'https://www.googleapis.com/auth/firebase.messaging');assert.equal(claims.aud,'https://oauth2.googleapis.com/token');
      return {ok:true,json:async()=>({access_token:'synthetic-short-lived-access',expires_in:3600})};
    }
    assert.equal(url,'https://fcm.googleapis.com/v1/projects/passo-fixture-project/messages:send');
    assert.equal(options.headers.Authorization,'Bearer synthetic-short-lived-access');
    const payload=JSON.parse(options.body);assert.equal(payload.message.token,'synthetic-registration-token');assert.equal(payload.message.android.priority,'high');assert.equal(payload.message.notification,undefined);assert.equal(payload.message.android.notification,undefined);
    messages++;return messages===1?{ok:true,json:async()=>({name:'projects/fixture/messages/1'})}:{ok:false,status:404,json:async()=>({error:{details:[{'@type':'type.googleapis.com/google.firebase.fcm.v1.FcmError',errorCode:'UNREGISTERED'}]}})};
  }});
  assert.deepEqual(await provider.send('synthetic-registration-token',{title:'Update',body:'Open Passo'},{eventType:'QUOTES'}),{sent:true});
  assert.equal((await provider.send('synthetic-registration-token',{title:'Update',body:'Open Passo'},{eventType:'QUOTES'})).invalidToken,true);
  assert.equal(authorizations,1);assert.equal(messages,2);
});
