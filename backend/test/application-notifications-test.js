'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV='test'; delete process.env.DATABASE_URL;
process.env.DATA_DIR=fs.mkdtempSync(path.join(os.tmpdir(),'passo-application-outbox-test-'));
const { DB }=require('../src/db');
const { initApplicationNotificationSchema, createApplicationNotificationService }=require('../src/services/application-notifications');
test('application notifications are durable, account-specific and delivered after decisions commit',async t=>{
  await initApplicationNotificationSchema();
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('outbox_driver','DRIVER','Outbox Fixture','0912345620','unused','PENDING')").run();
  let clock=Date.now(), available=true; const deliveries=[];
  const service=createApplicationNotificationService({now:()=>clock,sendSms:async(phone,message)=>{deliveries.push({phone,message,status:(await DB.prepare("SELECT status FROM users WHERE id='outbox_driver'").get()).status});return {sent:available};}});
  const queue=(decision,eventKey)=>DB.transaction(tx=>service.queue(tx,{accountId:'outbox_driver',role:'DRIVER',decision,eventKey}));
  await t.test('rollback produces no SMS and repeated event keys are idempotent',async()=>{
    await assert.rejects(()=>DB.transaction(async tx=>{await service.queue(tx,{accountId:'outbox_driver',role:'DRIVER',decision:'ACCEPT',eventKey:'rolled-back'});throw Error('Rollback fixture');}),/Rollback/);
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM application_notification_outbox').get()).count,0);
    const id=await queue('ACCEPT','accepted-event');assert.equal(await queue('ACCEPT','accepted-event'),id);
    assert.equal(deliveries.length,0,'Queue does not deliver before its transaction commits');
    assert.equal((await DB.prepare('SELECT COUNT(*) AS count FROM application_notification_outbox').get()).count,1);
  });
  await t.test('delivery sees the committed decision and never broadcasts private application state',async()=>{
    await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id='outbox_driver'").run();
    assert.deepEqual(await service.flush(),{sent:1,failed:0});
    assert.equal(deliveries[0].status,'ACTIVE');assert.equal(deliveries[0].phone,'+251912345620');
    assert.match(deliveries[0].message,/https:\/\/passo\.live\/join\/\?view=login/);assert.match(deliveries[0].message,/info@passo\.live/);
    assert.doesNotMatch(deliveries[0].message,/Outbox Fixture/);
    assert.deepEqual(await service.flush(),{sent:0,failed:0});assert.equal(deliveries.length,1);
  });
  await t.test('provider failure retains a durable retry without changing application status',async()=>{
    available=false;await queue('CORRECTIONS','correction-event');
    assert.deepEqual(await service.flush(),{sent:0,failed:1});
    const row=await DB.prepare("SELECT * FROM application_notification_outbox WHERE event_key='correction-event'").get();
    assert.equal(row.status,'PENDING');assert.equal(row.attempts,1);assert.equal(row.last_error,'SMS_DELIVERY_FAILED');
    assert.equal((await DB.prepare("SELECT status FROM users WHERE id='outbox_driver'").get()).status,'ACTIVE');
    assert.deepEqual(await service.flush(),{sent:0,failed:0});clock+=60001;available=true;
    assert.deepEqual(await service.flush(),{sent:1,failed:0});
  });
  await t.test('a newer decision supersedes an older undelivered one and changed phone cancels delivery',async()=>{
    await queue('REJECT','old-event');await queue('APPROVE','new-event');
    assert.equal((await DB.prepare("SELECT status FROM application_notification_outbox WHERE event_key='old-event'").get()).status,'SUPERSEDED');
    await DB.prepare("UPDATE users SET phone='0912345621' WHERE id='outbox_driver'").run();
    const before=deliveries.length;await service.flush();assert.equal(deliveries.length,before);
    assert.equal((await DB.prepare("SELECT status FROM application_notification_outbox WHERE event_key='new-event'").get()).status,'CANCELLED');
  });
  await t.test('default test transport cannot send real SMS',async()=>{
    const testService=createApplicationNotificationService({now:()=>clock});await queue('RESTORE','test-transport-event');
    assert.deepEqual(await testService.flush(),{sent:0,failed:1});
    assert.equal((await DB.prepare("SELECT status FROM application_notification_outbox WHERE event_key='test-transport-event'").get()).status,'PENDING');
  });
});
