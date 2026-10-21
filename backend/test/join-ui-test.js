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
  const daily={...input,serviceType:'DAILY_COMMUTE',routeChoice:'EXISTING',appliedRouteId:'route-real',proposedDeparture:'hidden departure',proposedDestination:'hidden destination',proposedMonthlyFeeEtb:'45000.25'};
  assert.deepEqual(portal.accountPayload('DRIVER','register',daily),{role:'DRIVER',fullName:'Applicant',phone:'0912345678',password:'secret password',serviceType:'DAILY_COMMUTE',licenseNumber:'LICENCE',assignedVehiclePlate:'HIDDEN',vehicleModel:'HIDDEN',vehicleType:'MINIBUS_14',routeChoice:'EXISTING',appliedRouteId:'route-real',proposedMonthlyFeeEtb:45000.25,proposedMonthlyFeeBasis:'PER_VEHICLE'});
  const proposed=portal.accountPayload('DRIVER','register',{...daily,routeChoice:'PROPOSED',proposedDeparture:' Bole ',proposedDestination:' CMC '});
  assert.equal(proposed.appliedRouteId,undefined);assert.equal(proposed.proposedDeparture,'Bole');assert.equal(proposed.proposedDestination,'CMC');
  const html=fs.readFileSync(path.join(__dirname,'../public/join/index.html'),'utf8');
  assert.equal(html.includes('PER_PASSENGER'),false,'The user requested a whole-vehicle monthly fee, with no unit selector.');
  assert.equal(html.includes('name="proposedMonthlyFeeBasis"'),false);
});

test('route choice disables every irrelevant required control, including switching to company or sign-in', () => {
  const html=fs.readFileSync(path.join(__dirname,'../public/join/index.html'),'utf8').split('<form id="accountForm"')[1].split('</form>')[0];
  const controls=[...html.matchAll(/<(?:input|select)\b[^>]*name="([^"]+)"/g)].map(match=>({name:match[1],required:true,disabled:false}));
  const control=name=>controls.find(control=>control.name===name);
  portal.applyRegistrationFields(controls,'DRIVER','register','DAILY_COMMUTE','EXISTING');
  for(const name of ['appliedRouteId','routeChoice','proposedMonthlyFeeEtb'])assert.equal(control(name).required,true,name);
  for(const name of ['companyId','companyName','proposedDeparture','proposedDestination'])assert.equal(control(name).disabled,true,name);
  portal.applyRegistrationFields(controls,'DRIVER','register','DAILY_COMMUTE','PROPOSED');
  assert.equal(control('appliedRouteId').disabled,true);assert.equal(control('appliedRouteId').required,false);
  for(const name of ['proposedDeparture','proposedDestination'])assert.equal(control(name).required,true);
  portal.applyRegistrationFields(controls,'DRIVER','register','INTERCITY','PROPOSED');
  assert.equal(control('companyId').required,true);for(const name of ['routeChoice','proposedDeparture','proposedDestination','proposedMonthlyFeeEtb','vehicleType'])assert.equal(control(name).disabled,true,name);
  portal.applyRegistrationFields(controls,'COMPANY','register','DAILY_COMMUTE','EXISTING');
  assert.equal(control('companyName').required,true);assert.equal(control('appliedRouteId').disabled,true);
  portal.applyRegistrationFields(controls,'DRIVER','login','DAILY_COMMUTE','EXISTING');
  assert.deepEqual(controls.filter(item=>!item.disabled).map(item=>item.name).sort(),['password','phone']);
});

test('administrator acceptance hides and disables verification/uploads before acceptance without changing other roles', () => {
  const sections=Array.from({length:4},()=>({hidden:false,controls:[{disabled:false},{disabled:false}],querySelectorAll(){return this.controls;}}));
  const waiting={acceptanceRequired:true,acceptanceStatus:'PENDING',canVerifyAndUpload:false,ready:true,reviewStatus:'AWAITING_ACCEPTANCE'};
  assert.deepEqual(portal.onboardingAccess(waiting),{locked:true,canSubmit:false});
  portal.applyOnboardingAccess(sections,waiting);
  assert.ok(sections.every(section=>section.hidden&&section.controls.every(control=>control.disabled)),'OTP/profile/upload/final-review controls are inaccessible while waiting.');
  assert.equal(portal.onboardingAccess({...waiting,canVerifyAndUpload:undefined}).locked,true,'A partial response cannot unlock protected steps.');
  assert.deepEqual(portal.onboardingAccess({...waiting,acceptanceStatus:'ACCEPTED',canVerifyAndUpload:true,reviewStatus:'DRAFT'}),{locked:false,canSubmit:true});
  for(const status of ['SUBMITTED','APPROVED'])assert.equal(portal.onboardingAccess({acceptanceRequired:false,ready:true,reviewStatus:status}).canSubmit,false);
  assert.equal(portal.onboardingAccess({acceptanceRequired:false,canVerifyAndUpload:true,reviewStatus:'DRAFT'}).locked,false,'Company/intercity onboarding retains existing verification flow.');
  for(const value of ['0',-1,Infinity,'NaN',1000000.01,'1.001',''])assert.equal(portal.validMonthlyFee(value),false,String(value));
  for(const value of [0.01,'45000.25',1000000])assert.equal(portal.validMonthlyFee(value),true,String(value));
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
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('join_review_admin','ADMIN','Local Reviewer','0900000088','unused','ACTIVE')").run();
  const admin=require('../src/middleware/auth').signToken({id:'join_review_admin',role:'ADMIN'});
  const oldFare=(await DB.prepare("SELECT basePriceEtb FROM routes WHERE id='route_bole_merkato'").get()).basePriceEtb;
  const dailyInput={fullName:'Daily Route Applicant',phone:'0912346703',password:'Local fixture password',serviceType:'DAILY_COMMUTE',licenseNumber:'DAILY-LICENCE',assignedVehiclePlate:'JOIN-DAILY-TEST',vehicleModel:'Minibus',vehicleType:'MINIBUS_14',routeChoice:'EXISTING',appliedRouteId:'route_bole_merkato',proposedMonthlyFeeEtb:'45000.25'};
  token='';const daily=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','register',dailyInput)});token=daily.token;
  assert.equal(daily.onboarding.reviewStatus,'AWAITING_ACCEPTANCE');assert.equal(daily.onboarding.routePreference.routeId,'route_bole_merkato');assert.equal(daily.onboarding.routePreference.proposedMonthlyFeeEtb,45000.25);assert.equal(daily.onboarding.routePreference.proposedMonthlyFeeBasis,'PER_VEHICLE');
  assert.equal(portal.onboardingAccess(daily.onboarding).locked,true);
  await assert.rejects(api('/request-otp',{method:'POST',body:{}}),error=>error.status===428);
  await assert.rejects(api('/documents/PHOTO',{method:'PUT',raw:new Blob([png],{type:'image/png'})}),error=>error.status===428);
  assert.ok((await api('/login',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','login',dailyInput)})).token,'Waiting drivers can sign in without requesting OTP.');
  const accepted=await fetch(base+'/api/onboarding/applications/DRIVER/'+daily.account.id+'/review',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:JSON.stringify({decision:'ACCEPT'})});
  assert.equal(accepted.status,200,await accepted.text());
  const unlocked=await api('/me');assert.equal(portal.onboardingAccess(unlocked.onboarding).locked,false);assert.equal(unlocked.onboarding.reviewStatus,'DRAFT');
  await api('/request-otp',{method:'POST',body:{}});await api('/verify-otp',{method:'POST',body:{code:sent.at(-1).code}});
  for(const kind of unlocked.onboarding.requiredDocuments)await api('/documents/'+kind,{method:'PUT',raw:new Blob([png],{type:'image/png'})});
  await api('/profile',{method:'PATCH',body:{emergencyContactName:'Local Contact',emergencyContactPhone:'0912000070',vehicleColor:'Teal'}});
  assert.equal((await api('/submit',{method:'POST',body:{}})).onboarding.reviewStatus,'SUBMITTED');
  assert.equal((await DB.prepare("SELECT basePriceEtb FROM routes WHERE id='route_bole_merkato'").get()).basePriceEtb,oldFare,'The proposal never changes published route fare.');
  token='';const proposedDaily=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','register',{...dailyInput,phone:'0912346704',assignedVehiclePlate:'JOIN-PROPOSED-TEST',routeChoice:'PROPOSED',proposedDeparture:'Garment',proposedDestination:'Bole'})});
  assert.equal(proposedDaily.onboarding.routePreference.mode,'PROPOSED');assert.equal(proposedDaily.onboarding.routePreference.departure,'Garment');assert.equal(proposedDaily.onboarding.routePreference.destination,'Bole');assert.equal(proposedDaily.onboarding.canVerifyAndUpload,false);
});
