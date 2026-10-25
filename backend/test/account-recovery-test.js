'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'passo-recovery-test-'));
process.env.JWT_SECRET = 'isolated-password-recovery-test-secret';
const { DB } = require('../src/db');
const bcrypt = require('bcryptjs');
const { initAccountRecoverySchema, createAccountRecoveryService, REQUEST_MESSAGE } = require('../src/services/account-recovery');
const { getSessionVersion, sessionIsCurrent } = require('../src/services/account-sessions');
test('password recovery requires delivered account-bound ownership proof without changing onboarding approval', async t => {
  await require('../src/services/onboarding').initOnboardingSchema();
  await require('../src/services/account-deletion').initAccountDeletionSchema();
  await initAccountRecoverySchema();
  const oldHash = await bcrypt.hash('Old password fixture', 4);
  for (const [id, role, phone, status] of [['recovery_driver','DRIVER','+251912345610','PENDING'],['recovery_passenger','PASSENGER','0912345611','ACTIVE'],['recovery_suspended','DRIVER','0912345612','SUSPENDED'],['recovery_closed','DRIVER','0912345613','PENDING']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)').run(id, role, 'Recovery Fixture', phone, oldHash, status);
  }
  await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,review_status,service_type,acceptance_status,created_at,updated_at) VALUES('recovery_driver','DRIVER','+251912345610','AWAITING_ACCEPTANCE','DAILY_COMMUTE','PENDING',?,?)")
    .run(new Date().toISOString(), new Date().toISOString());
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES('recovery_company','Recovery Company','+251912345614','Fixture Representative',?,'PENDING')").run(oldHash);
  await DB.prepare("INSERT INTO account_deletion_requests(id,account_id,role,status,requested_at) VALUES('closed_receipt','recovery_closed','DRIVER','COMPLETED',?)").run(new Date().toISOString());
  let clock = Date.now(), smsWorking = true;
  const messages = [];
  const service = createAccountRecoveryService({ now: () => clock, sendSms: async (phone, message) => { messages.push({ phone, message }); return { sent: smsWorking }; } });
  const request = async (role, phone) => { const result = await service.request({ role, phone }); await service.waitForDeliveries(); return result; };
  const codeFor = phone => /\b(\d{6})\b/.exec(messages.findLast(item => item.phone === phone)?.message || '')?.[1];
  const reset = (role, phone, code, newPassword = 'New password fixture') => service.reset({ role, phone, code, newPassword });
  const invalid = error => error.status === 400 && /invalid, expired or already used/.test(error.message);
  await t.test('unknown, invalid, closed and existing requests return identical non-enumerating payloads', async () => {
    const known = await request('DRIVER', '0912345610');
    for (const input of [{ role:'DRIVER',phone:'0912345999' },{ role:'ADMIN',phone:'0912345610' },{ role:'DRIVER',phone:'invalid' },{ role:'DRIVER',phone:'0912345613' }]) assert.deepEqual(await service.request(input), known);
    assert.equal(known.message, REQUEST_MESSAGE); assert.equal(messages.length, 1);
    const challenge = await DB.prepare("SELECT * FROM account_recovery_challenges WHERE account_id='recovery_driver'").get();
    assert.notEqual(challenge.code_hash, codeFor('+251912345610')); assert.equal(challenge.code_hash.length, 64);
    assert.equal(challenge.attempts, 0); assert.ok(challenge.sent_at); assert.equal(challenge.consumed_at, null);
    await assert.rejects(() => reset('PASSENGER','0912345610',codeFor('+251912345610')), invalid);
  });
  await t.test('wrong codes consume a maximum of five attempts and registration OTP deferral does not bypass recovery', async () => {
    const real = codeFor('+251912345610'), wrong = real === '000000' ? '999999' : '000000';
    for (let attempt=0;attempt<5;attempt++) await assert.rejects(() => reset('DRIVER','0912345610',wrong), invalid);
    await assert.rejects(() => reset('DRIVER','0912345610',real), invalid);
    assert.equal((await DB.prepare("SELECT attempts FROM account_recovery_challenges WHERE account_id='recovery_driver'").get()).attempts, 5);
    assert.equal((await DB.prepare("SELECT passwordHash FROM users WHERE id='recovery_driver'").get()).passwordHash, oldHash);
  });
  await t.test('resend cooldown and hourly limit apply per normalized account even across phone spellings', async () => {
    const before = messages.length;
    await request('DRIVER', '+251912345610'); assert.equal(messages.length, before);
    for (let attempt=0;attempt<4;attempt++) { clock+=61000; await request('DRIVER','0912345610'); }
    assert.equal(messages.length, before+4);
    clock+=61000; await request('DRIVER','0912345610'); assert.equal(messages.length, before+4);
  });
  await t.test('successful reset consumes proof, revokes sessions, and preserves driver approval/documents/phone verification state', async () => {
    clock+=3600001; await request('DRIVER','0912345610');
    const code = codeFor('+251912345610');
    const previousOnboarding = await DB.prepare("SELECT * FROM onboarding_accounts WHERE account_id='recovery_driver'").get();
    assert.equal(await sessionIsCurrent({ id:'recovery_driver',role:'DRIVER' }), true);
    await assert.rejects(() => reset('DRIVER','0912345610',code,'short'), error => error.status===400 && /10 characters/.test(error.message));
    await assert.rejects(() => reset('DRIVER','0912345610',code,'ሀ'.repeat(25)), error => error.status===400 && /72 bytes/.test(error.message));
    assert.equal((await reset('DRIVER','0912345610',code)).success, true);
    const account = await DB.prepare("SELECT * FROM users WHERE id='recovery_driver'").get();
    assert.equal(await bcrypt.compare('New password fixture', account.passwordHash), true); assert.equal(account.status,'PENDING');
    assert.deepEqual(await DB.prepare("SELECT * FROM onboarding_accounts WHERE account_id='recovery_driver'").get(), previousOnboarding);
    assert.equal(await getSessionVersion(account.id,'DRIVER'),1);
    assert.equal(await sessionIsCurrent({id:account.id,role:'DRIVER'}),false);
    assert.equal(await sessionIsCurrent({id:account.id,role:'DRIVER',sessionVersion:0}),false);
    assert.equal(await sessionIsCurrent({id:account.id,role:'DRIVER',sessionVersion:1}),true);
    await assert.rejects(() => reset('DRIVER','0912345610',code), invalid);
  });
  await t.test('expiry and failed SMS delivery cannot authorize password changes', async () => {
    await request('PASSENGER','0912345611'); const expired = codeFor('+251912345611');
    clock+=10*60000+1; await assert.rejects(() => reset('PASSENGER','0912345611',expired),invalid);
    smsWorking=false; await request('PASSENGER','0912345611');
    await assert.rejects(() => reset('PASSENGER','0912345611',codeFor('+251912345611')),invalid);
    assert.equal((await DB.prepare("SELECT passwordHash FROM users WHERE id='recovery_passenger'").get()).passwordHash,oldHash);
    smsWorking=true;
  });
  await t.test('company and suspended driver passwords can recover without activating either account', async () => {
    for (const [role,phone,id,status] of [['COMPANY','0912345614','recovery_company','PENDING'],['DRIVER','0912345612','recovery_suspended','SUSPENDED']]) {
      await request(role,phone); await reset(role,phone,codeFor('+251'+phone.slice(1)));
      const table=role==='COMPANY'?'transport_companies':'users';
      const row=await DB.prepare(`SELECT * FROM ${table} WHERE id=?`).get(id); assert.equal(row.status,status);
      assert.equal(await bcrypt.compare('New password fixture',row.passwordHash||row.password_hash),true);
    }
  });
  await t.test('real HTTP recovery router keeps request responses generic and limits reset attempts', async () => {
    const express=require('express'), app=express(); app.use(express.json());
    app.use('/api/account-recovery',require('../src/routes/account-recovery').createAccountRecoveryRouter({service,requestLimit:2,resetLimit:2}));
    const server=app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve));
    try {
      const base='http://127.0.0.1:'+server.address().port+'/api/account-recovery';
      for(let index=0;index<3;index++){const response=await fetch(base+'/request',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({role:'DRIVER',phone:'0912345999'})});assert.equal(response.status,200);assert.equal((await response.json()).message,REQUEST_MESSAGE);}
      for(let index=0;index<3;index++){const response=await fetch(base+'/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({role:'DRIVER',phone:'0912345999',code:'000000',newPassword:'Valid fixture password'})});assert.equal(response.status,index===2?429:400);}
    } finally { server.closeAllConnections(); await new Promise(resolve=>server.close(resolve)); }
  });
  await t.test('password reset revokes normal, onboarding and account-management tokens while fresh logins work', async () => {
    const express=require('express'),jwt=require('jsonwebtoken'),app=express();app.use(express.json());
    const {signToken,JWT_SECRET}=require('../src/middleware/auth');
    app.use('/api/auth',require('../src/routes/auth'));
    app.use('/api/onboarding',require('../src/routes/onboarding'));
    app.use('/api/account',require('../src/routes/account'));
    const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
    const base='http://127.0.0.1:'+server.address().port;
    const call=async(endpoint,token,body)=>{const response=await fetch(base+endpoint,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,data:await response.json()};};
    try {
      const passengerToken=signToken({id:'recovery_passenger',role:'PASSENGER'});
      const driverToken=jwt.sign({id:'recovery_driver',role:'DRIVER',scope:'ONBOARDING',sessionVersion:await getSessionVersion('recovery_driver','DRIVER')},JWT_SECRET,{audience:'routepass-onboarding',expiresIn:'2h'});
      assert.equal((await call('/api/auth/me',passengerToken)).status,200);
      assert.equal((await call('/api/onboarding/me',driverToken)).status,200);
      clock+=61000;
      await request('PASSENGER','0912345611');await reset('PASSENGER','0912345611',codeFor('+251912345611'),'Recovered passenger password');
      await request('DRIVER','0912345610');await reset('DRIVER','0912345610',codeFor('+251912345610'),'Recovered driver password');
      assert.equal((await call('/api/auth/me',passengerToken)).status,401);
      assert.equal((await call('/api/account/deletion',passengerToken)).status,401);
      assert.equal((await call('/api/onboarding/me',driverToken)).status,401);
      const passengerLogin=await call('/api/auth/login',null,{role:'PASSENGER',phone:'0912345611',password:'Recovered passenger password'});
      assert.equal(passengerLogin.status,200);assert.equal((await call('/api/auth/me',passengerLogin.data.token)).status,200);
      const driverLogin=await call('/api/onboarding/login',null,{role:'DRIVER',phone:'0912345610',password:'Recovered driver password'});
      assert.equal(driverLogin.status,200);assert.equal((await call('/api/onboarding/me',driverLogin.data.token)).status,200);
    } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
  });
});
