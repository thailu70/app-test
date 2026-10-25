'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
process.env.NODE_ENV='test';delete process.env.DATABASE_URL;
process.env.DATA_DIR=fs.mkdtempSync(path.join(os.tmpdir(),'passo-credential-race-'));
process.env.JWT_SECRET='isolated-credential-reset-race-test-secret';
const { DB }=require('../src/db');
const bcrypt=require('bcryptjs');
const { initAccountRecoverySchema,createAccountRecoveryService }=require('../src/services/account-recovery');
const { verifyToken }=require('../src/middleware/auth');
test('a password reset cannot upgrade an in-flight old-password proof to a fresh session',async t=>{
  await require('../src/services/onboarding').initOnboardingSchema();
  await require('../src/services/account-deletion').initAccountDeletionSchema();
  await initAccountRecoverySchema();
  const express=require('express'),app=express();app.use(express.json());
  app.use('/api/auth',require('../src/routes/auth'));
  app.use('/api/onboarding',require('../src/routes/onboarding'));
  app.use('/api/company',require('../src/routes/company-portal'));
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
  t.after(()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);}));
  const base='http://127.0.0.1:'+server.address().port;
  const call=async(endpoint,body,token)=>{const response=await fetch(base+endpoint,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,data:await response.json()};};
  for(const [role,id,phone,loginPath,protectedPath] of [
    ['PASSENGER','race_passenger','+251912345670','/api/auth/login','/api/auth/me'],
    ['DRIVER','race_driver','+251912345671','/api/onboarding/login','/api/onboarding/me'],
    ['COMPANY','race_company','+251912345672','/api/company/login','/api/company/me']
  ])await t.test(role+' login carries the version bound to its checked password hash',async()=>{
    const oldPassword='Old race password '+role,newPassword='New race password '+role,hash=await bcrypt.hash(oldPassword,4);
    if(role==='COMPANY')await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES(?,?,?,?,?,'ACTIVE')").run(id,'Race Company',phone,'Fixture',hash);
    else await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?)').run(id,role,'Race Fixture',phone,hash,role==='DRIVER'?'PENDING':'ACTIVE');
    if(role==='DRIVER')await DB.prepare("INSERT INTO onboarding_accounts(account_id,role,normalized_phone,review_status,service_type,acceptance_status,created_at,updated_at) VALUES(?,'DRIVER',?,'AWAITING_ACCEPTANCE','DAILY_COMMUTE','PENDING',?,?)").run(id,phone,new Date().toISOString(),new Date().toISOString());
    let sms;
    const recovery=createAccountRecoveryService({sendSms:async(_phone,message)=>{sms=message;return {sent:true};}});
    await recovery.request({role,phone});await recovery.waitForDeliveries();
    const code=/\b(\d{6})\b/.exec(sms)[1];
    let release,entered;
    const paused=new Promise(resolve=>release=resolve),atPasswordCheck=new Promise(resolve=>entered=resolve),originalCompare=bcrypt.compare;
    bcrypt.compare=async(...args)=>{const result=await originalCompare(...args);if(args[0]===oldPassword){entered();await paused;}return result;};
    try{
      const pending=call(loginPath,{role,phone,password:oldPassword});
      await atPasswordCheck;
      await recovery.reset({role,phone,code,newPassword});
      release();
      const staleLogin=await pending;
      if(staleLogin.status===200){assert.equal(verifyToken(staleLogin.data.token).sessionVersion,0,'A password proof must never acquire a version created by a later reset');assert.equal((await call(protectedPath,null,staleLogin.data.token)).status,401);}
      else assert.equal(staleLogin.status,401);
      const fresh=await call(loginPath,{role,phone,password:newPassword});
      assert.equal(fresh.status,200,JSON.stringify(fresh.data));assert.equal(verifyToken(fresh.data.token).sessionVersion,1);
      assert.equal((await call(protectedPath,null,fresh.data.token)).status,200);
    }finally{release();bcrypt.compare=originalCompare;}
  });
});
