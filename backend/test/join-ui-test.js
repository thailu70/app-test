'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const portal = require('../public/join/join');

test('recruitment portal translates every labelled control and ships local visual assets without inline scripts', () => {
  const html=fs.readFileSync(path.join(__dirname,'../public/join/index.html'),'utf8');
  for(const match of html.matchAll(/data-i18n="([^"]+)"/g)) for(const lang of ['en','am','om'])
    assert.notEqual(portal.translate(match[1],lang),match[1],match[1]+' '+lang);
  for(const [key,value] of Object.entries(portal.words))assert.equal(value.length,3,key);
  for(const match of html.matchAll(/(?:src|href)="(\/join\/[^"?]+)(?:\?[^"]*)?"/g))
    assert.ok(fs.existsSync(path.join(__dirname,'../public',match[1])),match[1]);
  assert.equal(/<script(?![^>]*src=)[^>]*>/.test(html),false);
  assert.equal(/\son(?:click|submit|change)=|\sstyle=|https:\/\//.test(html),false,'Local assets, external script and CSS respect the portal CSP.');
  assert.ok(html.includes('mailto:info@passo.live'));
  assert.ok(html.includes('href="/company/"'),'Existing company/invitation access remains available.');
});

test('document restrictions match the actual private upload contract', () => {
  assert.equal(portal.uploadProblem({type:'application/pdf',size:120},'LICENSE'),null);
  assert.equal(portal.uploadProblem({type:'image/jpeg',size:5*1024*1024},'PHOTO'),null);
  assert.equal(portal.uploadProblem({type:'application/pdf',size:120},'PHOTO'),'photoType');
  assert.equal(portal.uploadProblem({type:'text/html',size:120},'LICENSE'),'fileType');
  for(const size of [0,-1,NaN,5*1024*1024+1])assert.equal(portal.uploadProblem({type:'image/png',size},'PHOTO'),'fileSize');
  assert.equal(portal.escape('<img src=x onerror="bad">'), '&lt;img src=x onerror=&quot;bad&quot;&gt;');
});

test('onboarding requests keep tokens on same-origin API, never attach them to login and reject redirects', async () => {
  let request;
  const api=portal.createApiClient({getToken:()=> 'private-onboarding-token',fetchFn:async (url,options)=>{request={url,options};return {ok:true,status:200,json:async()=>({success:true})};}});
  await api('/me');
  assert.equal(request.url,'/api/onboarding/me');
  assert.equal(request.options.headers.Authorization,'Bearer private-onboarding-token');
  assert.equal(request.options.credentials,'omit');assert.equal(request.options.redirect,'error');
  await api('/login',{method:'POST',anonymous:true,body:{role:'DRIVER',phone:'0912345678',password:'not-persisted'}});
  assert.equal(request.options.headers.Authorization,undefined);
  assert.equal(JSON.parse(request.options.body).password,'not-persisted');
  await assert.rejects(api('https://untrusted.example/documents/PHOTO'),/Invalid onboarding API path/);
  await assert.rejects(api('//untrusted.example/me'),/Invalid onboarding API path/);
});

test('expired account clears the matching session, while an old response cannot clear a newer login', async () => {
  let token='old',cleared=0,respond;
  const api=portal.createApiClient({getToken:()=>token,onUnauthorized:()=>cleared++,fetchFn:()=>new Promise(resolve=>{respond=resolve;})});
  const oldRequest=api('/me');token='new';respond({ok:false,status:401,json:async()=>({success:false,error:'Expired'})});
  await assert.rejects(oldRequest,/Expired/);assert.equal(cleared,0);
  const current=api('/me');respond({ok:false,status:401,json:async()=>({success:false,error:'Expired'})});
  await assert.rejects(current,/Expired/);assert.equal(cleared,1);
});

test('public forms send the correct driver ownership fields and cannot submit hidden-company vehicle details', () => {
  const input={fullName:' Applicant ',phone:'0912345678',password:'secret password',companyName:'Company',companyId:'existing-company',serviceType:'INTERCITY',licenseNumber:'LICENCE',assignedVehiclePlate:'HIDDEN',vehicleModel:'HIDDEN',vehicleType:'MINIBUS_14'};
  assert.deepEqual(portal.accountPayload('DRIVER','register',input),{role:'DRIVER',fullName:'Applicant',phone:'0912345678',password:'secret password',serviceType:'INTERCITY',licenseNumber:'LICENCE',companyId:'existing-company'});
  assert.deepEqual(portal.accountPayload('COMPANY','register',input),{role:'COMPANY',fullName:'Applicant',phone:'0912345678',password:'secret password',companyName:'Company'});
  assert.deepEqual(portal.accountPayload('COMPANY','login',input),{role:'COMPANY',phone:'0912345678',password:'secret password'});
});

test('frontend API client completes real pending driver/company onboarding without OTP at signup', async t => {
  const os=require('node:os');
  process.env.NODE_ENV='test';delete process.env.DATABASE_URL;
  process.env.DATA_DIR=fs.mkdtempSync(path.join(os.tmpdir(),'passo-join-contract-'));
  process.env.JWT_SECRET='passo-onboarding-frontend-contract-test-secret';
  const {DB}=require('../src/db');
  const {initOnboardingSchema,createOnboardingService}=require('../src/services/onboarding');
  await initOnboardingSchema();await require('../src/services/master-data').initMasterDataSchema();
  await DB.prepare("INSERT INTO transport_companies(id,name,phone,representative_name,password_hash,status) VALUES('join_existing_company','Existing Transport','0900000099','Existing Representative','unused','ACTIVE')").run();
  let sent=[];
  const service=createOnboardingService({sendSms:async(phone,message)=>{sent.push({phone,code:/\b(\d{6})\b/.exec(message)?.[1]});return {sent:true};}});
  const express=require('express'),app=express();app.use(express.json());
  app.use('/api/onboarding',require('../src/routes/onboarding').createOnboardingRouter({service,rateLimitMax:1000}));
  const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
  t.after(()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);}));
  const base='http://127.0.0.1:'+server.address().port;
  let token='';
  const api=portal.createApiClient({getToken:()=>token,fetchFn:(url,options)=>fetch(base+url,options)});
  const input={fullName:'Join Driver',phone:'0912346701',password:'Local fixture password',serviceType:'INTERCITY',companyId:'join_existing_company',licenseNumber:'JOIN-LICENCE'};
  const registered=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','register',input)});
  assert.equal(registered.account.companyId,'join_existing_company');assert.equal(registered.account.status,'PENDING');assert.equal(registered.onboarding.phoneVerified,false);assert.equal(sent.length,0);
  const login=await api('/login',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','login',input)});token=login.token;
  await assert.rejects(api('/submit',{method:'POST',body:{}}),error=>error.status===428);
  assert.equal((await api('/request-otp',{method:'POST',body:{}})).delivery,'SENT');
  assert.equal((await api('/verify-otp',{method:'POST',body:{code:sent.at(-1).code}})).onboarding.phoneVerified,true);
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=','base64');
  for(const kind of ['LICENSE','VEHICLE_REGISTRATION','INSURANCE','PHOTO'])await api('/documents/'+kind,{method:'PUT',raw:new Blob([png],{type:'image/png'})});
  const profile=await api('/profile',{method:'PATCH',body:{emergencyContactName:'Local Contact',emergencyContactPhone:'0912000070',vehicleColor:'Teal'}});
  assert.equal(profile.onboarding.ready,true);assert.equal(profile.onboarding.reviewStatus,'DRAFT');
  assert.equal((await api('/documents/PHOTO',{blob:true})).type,'image/png');
  assert.equal((await api('/submit',{method:'POST',body:{}})).onboarding.reviewStatus,'SUBMITTED');
  token='';const companyInput={fullName:'Company Representative',companyName:'Join Contract Transport',phone:'0912346702',password:'Local fixture password'};
  const company=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('COMPANY','register',companyInput)});
  token=company.token;assert.equal(company.account.status,'PENDING');assert.deepEqual(company.onboarding.requiredDocuments,['BUSINESS_LICENSE','REGISTRATION','REPRESENTATIVE_ID']);
  const companyLogin=await api('/login',{method:'POST',anonymous:true,body:portal.accountPayload('COMPANY','login',companyInput)});
  assert.equal(companyLogin.account.id,company.account.id);assert.equal(companyLogin.onboarding.phoneVerified,false);
});
