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
  assert.ok(html.includes('href="/" aria-label="Passo home"'));
  assert.ok(html.includes('href="/privacy/"'));assert.ok(html.includes('href="/delete-account/"'));
  assert.doesNotMatch(html,/href="\/(?:company|travel)\/|data-role=|data-service=|data-company-profile|name="companyId"|name="companyName"/,'The recruitment page only presents daily commute driver registration.');
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

test('public forms always send daily commute driver ownership fields despite stale company/intercity input', () => {
  const input={fullName:' Applicant ',phone:'0912345678',password:'secret password',companyName:'Company',companyId:'existing-company',serviceType:'INTERCITY',licenseNumber:'LICENCE',assignedVehiclePlate:'HIDDEN',vehicleModel:'HIDDEN',vehicleType:'MINIBUS_14'};
  for(const role of ['DRIVER','COMPANY','PASSENGER']){
    const actual=portal.accountPayload(role,'register',input);assert.equal(actual.role,'DRIVER');assert.equal(actual.serviceType,'DAILY_COMMUTE');assert.equal(actual.assignedVehiclePlate,'HIDDEN');assert.equal(actual.companyId,undefined);assert.equal(actual.companyName,undefined);
    assert.deepEqual(portal.accountPayload(role,'login',input),{role:'DRIVER',phone:'0912345678',password:'secret password'});
  }
  assert.equal(portal.isDailyDriverAccount({role:'DRIVER',companyId:''}),true);
  for(const account of [{role:'COMPANY'},{role:'DRIVER',companyId:'company'},{role:'PASSENGER'},null])assert.equal(portal.isDailyDriverAccount(account),false);
  const daily={...input,serviceType:'DAILY_COMMUTE',routeChoice:'EXISTING',appliedRouteId:'untrusted-old-id',proposedDeparture:' Bole ',proposedDestination:' CMC ',proposedMonthlyFeeEtb:'45000.25'};
  const proposed=portal.accountPayload('DRIVER','register',daily);
  assert.equal(proposed.routeChoice,undefined);assert.equal(proposed.appliedRouteId,undefined);assert.equal(proposed.proposedDeparture,'Bole');assert.equal(proposed.proposedDestination,'CMC');assert.equal(proposed.proposedMonthlyFeeEtb,45000.25);assert.equal(proposed.proposedMonthlyFeeBasis,'PER_VEHICLE');
  const fixed=portal.accountPayload('DRIVER','register',daily,{price:{mode:'FIXED',amountEtb:35000}});
  assert.equal(fixed.proposedMonthlyFeeEtb,undefined,'A set price is recomputed by the backend, never posted as a manual proposal.');
  const html=fs.readFileSync(path.join(__dirname,'../public/join/index.html'),'utf8');
  assert.equal(html.includes('PER_PASSENGER'),false,'The user requested a whole-vehicle monthly fee, with no unit selector.');
  assert.equal(html.includes('name="proposedMonthlyFeeBasis"'),false);
  assert.equal(html.includes('name="routeChoice"'),false);assert.equal(html.includes('name="appliedRouteId"'),false);
  assert.equal(html.includes('<select id="serviceType"'),false);
  assert.ok(html.includes('name="serviceType" type="hidden" value="DAILY_COMMUTE"'));
  assert.doesNotMatch(html,/data-service=|data-role=|INTERCITY|Transport company/);
});

test('daily-driver controls cannot be switched to a different role or service and login disables registration fields', () => {
  const html=fs.readFileSync(path.join(__dirname,'../public/join/index.html'),'utf8').split('<form id="accountForm"')[1].split('</form>')[0];
  const controls=[...html.matchAll(/<(?:input|select)\b[^>]*name="([^"]+)"/g)].map(match=>({name:match[1],required:true,disabled:false}));
  const control=name=>controls.find(control=>control.name===name);
  controls.push({name:'companyId',required:true,disabled:false},{name:'companyName',required:true,disabled:false});
  portal.applyRegistrationFields(controls,'DRIVER','register','DAILY_COMMUTE');
  for(const name of ['proposedDeparture','proposedDestination','proposedMonthlyFeeEtb','vehicleType'])assert.equal(control(name).required,true,name);
  for(const name of ['companyId','companyName'])assert.equal(control(name).disabled,true,name);
  for(const [role,service] of [['DRIVER','INTERCITY'],['COMPANY','INTERCITY']]){
    portal.applyRegistrationFields(controls,role,'register',service);
    for(const name of ['companyId','companyName'])assert.equal(control(name).disabled,true,name);
    for(const name of ['proposedDeparture','proposedDestination','proposedMonthlyFeeEtb','vehicleType'])assert.equal(control(name).required,true,name);
  }
  portal.applyRegistrationFields(controls,'DRIVER','login','DAILY_COMMUTE');
  assert.deepEqual(controls.filter(item=>!item.disabled).map(item=>item.name).sort(),['password','phone']);
});
test('the homepage sign-in link opens driver login while URL role/service values cannot select another registration flow',()=>{
  for(const query of ['?view=login','?view=login&role=COMPANY&serviceType=INTERCITY'])assert.equal(portal.initialMode(query),'login');
  for(const query of ['', '?role=company','?view=register&serviceType=INTERCITY','?view=LOGIN','?view=https://example.invalid'])assert.equal(portal.initialMode(query),'register');
  assert.equal(portal.accountPayload('COMPANY',portal.initialMode('?view=login&role=company'),{phone:'0912345678',password:'local-fixture'}).role,'DRIVER');
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

test('driver review can proceed without OTP only when server review readiness permits it', () => {
  const accepted={acceptanceRequired:true,canVerifyAndUpload:true,reviewStatus:'DRAFT',phoneVerified:false,
    phoneVerificationRequiredForApproval:false,phoneVerificationRequiredForAllocation:false,ready:false,reviewReady:true};
  assert.equal(portal.onboardingAccess(accepted).canSubmit,true);
  assert.equal(portal.onboardingAccess({...accepted,ready:true,reviewReady:false}).canSubmit,false);
  assert.equal(portal.onboardingAccess({...accepted,reviewReady:undefined}).canSubmit,false,'Missing review readiness cannot bypass other requirements.');
  assert.equal(portal.onboardingAccess({...accepted,canVerifyAndUpload:false}).canSubmit,false,'Initial acceptance is still mandatory.');
  assert.equal(portal.onboardingAccess({...accepted,reviewStatus:'APPROVED'}).canSubmit,false);
  for(const language of ['en','am','om']){
    assert.notEqual(portal.translate('phoneDeferred',language),'phoneDeferred');
    assert.notEqual(portal.translate('approvalPhoneDeferred',language),'approvalPhoneDeferred');
  }
  assert.match(portal.translate('phoneDeferred','en'),/not required right now/);
  assert.match(portal.translate('allocationNote','en'),/documents.*administrator approval.*optional for now/);
});

const fixedQuote = amount => ({success:true,routeMatch:{status:'EXACT',routeId:'route-test',routeName:'Bole to Merkato'},price:{mode:'FIXED',amountEtb:amount,currency:'ETB',basis:'PER_VEHICLE',source:'ROUTE_PASSENGER_FARE_X_CAPACITY',passengerCapacity:14,passengerMonthlyFareEtb:amount/14}});
const manualQuote = status => ({success:true,routeMatch:{status,routeId:null,routeName:null},price:{mode:'PROPOSED',amountEtb:null,currency:'ETB',basis:'PER_VEHICLE',source:null,passengerCapacity:14,passengerMonthlyFareEtb:null}});

test('quote controller locks server prices, leaves unknown prices editable and blocks errors until retry', async () => {
  let result=fixedQuote(35000),failed=false,calls=0,lastRequest;
  const controller=portal.createCommuteQuoteController({fetchFn:async(url,options)=>{calls++;lastRequest={url,options};if(failed)throw new Error('offline');return {ok:true,json:async()=>result};}});
  const values={departure:'Bole',destination:'Merkato',vehicleType:'MINIBUS_14'};
  await controller.update({departure:'Bole',destination:'',vehicleType:'MINIBUS_14'},{immediate:true});assert.equal(calls,0);assert.equal(portal.quoteAccess(controller.getState()).canSubmit,false);
  await controller.update(values,{immediate:true});assert.deepEqual(portal.quoteAccess(controller.getState()),{canSubmit:true,readOnly:true,fixedAmount:35000});
  assert.ok(lastRequest.url.startsWith('/api/onboarding/commute-quote?'));assert.equal(lastRequest.options.method,'GET');assert.equal(lastRequest.options.credentials,'omit');assert.equal(lastRequest.options.redirect,'error');assert.equal(lastRequest.options.headers,undefined,'Public quotes carry no account token.');
  result=manualQuote('NONE');await controller.update({...values,departure:'New place'},{immediate:true});assert.deepEqual(portal.quoteAccess(controller.getState()),{canSubmit:true,readOnly:false,fixedAmount:null});
  result=manualQuote('AMBIGUOUS');await controller.update({...values,departure:'Shared point'},{immediate:true});assert.equal(controller.getState().quote.routeMatch.status,'AMBIGUOUS');assert.equal(portal.quoteAccess(controller.getState()).readOnly,false);
  failed=true;await controller.update({...values,vehicleType:'MINIVAN_8'},{immediate:true});assert.equal(controller.getState().status,'ERROR');assert.equal(portal.quoteAccess(controller.getState()).canSubmit,false);
  failed=false;result=fixedQuote(35000);await controller.update({...values,vehicleType:'MINIVAN_8'},{immediate:true,force:true});assert.equal(controller.getState().status,'READY');controller.dispose();
});

test('changing endpoints or vehicle type immediately discards old quotes and ignores late responses', async () => {
  const requests=[];
  const controller=portal.createCommuteQuoteController({fetchFn:(url,options)=>new Promise(resolve=>requests.push({url,options,resolve}))});
  const original={departure:'Bole',destination:'Merkato',vehicleType:'MINIBUS_14'};
  const old=controller.update(original,{immediate:true});assert.equal(controller.getState().status,'LOADING');
  const changed=controller.update({...original,vehicleType:'MINIVAN_8'},{immediate:true});assert.equal(requests[0].options.signal.aborted,true);assert.equal(controller.getState().quote,null);assert.equal(portal.quoteAccess(controller.getState()).canSubmit,false);
  requests[1].resolve({ok:true,json:async()=>fixedQuote(20000)});await changed;
  requests[0].resolve({ok:true,json:async()=>fixedQuote(35000)});await old;
  assert.equal(controller.getState().quote.price.amountEtb,20000);assert.equal(controller.getState().context.vehicleType,'MINIVAN_8');
  await controller.update({...original,destination:''},{immediate:true});assert.equal(controller.getState().status,'INCOMPLETE');assert.equal(controller.getState().quote,null);controller.dispose();
});

test('typing is debounced and malformed fixed prices never become an editable fallback', async () => {
  const timers=new Map();let nextId=0,calls=0;
  const controller=portal.createCommuteQuoteController({setTimer:fn=>{const id=++nextId;timers.set(id,fn);return id;},clearTimer:id=>timers.delete(id),fetchFn:async()=>{calls++;return {ok:true,json:async()=>({...fixedQuote(35000),routeMatch:{status:'NONE'}})};}});
  controller.update({departure:'Bo',destination:'Merkato',vehicleType:'MINIBUS_14'});
  controller.update({departure:'Bole',destination:'Merkato',vehicleType:'MINIBUS_14'});
  assert.equal(timers.size,1);assert.equal(calls,0);assert.equal(controller.getState().status,'LOADING');
  [...timers.values()][0]();await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls,1);assert.equal(controller.getState().status,'ERROR');assert.equal(portal.quoteAccess(controller.getState()).canSubmit,false);controller.dispose();
});

test('frontend API client completes real daily driver onboarding with pricing and administrator acceptance before OTP/uploads', async t => {
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
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=','base64');
  await DB.prepare("INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('join_review_admin','ADMIN','Local Reviewer','0900000088','unused','ACTIVE')").run();
  const admin=require('../src/middleware/auth').signToken({id:'join_review_admin',role:'ADMIN'});
  const oldFare=(await DB.prepare("SELECT basePriceEtb FROM routes WHERE id='route_bole_merkato'").get()).basePriceEtb;
  const dailyInput={fullName:'Daily Route Applicant',phone:'0912346703',password:'Local fixture password',serviceType:'DAILY_COMMUTE',licenseNumber:'DAILY-LICENCE',assignedVehiclePlate:'JOIN-DAILY-TEST',vehicleModel:'Minibus',vehicleType:'MINIBUS_14',proposedDeparture:'Bole Medhanialem',proposedDestination:'Merkato Bus Terminal',proposedMonthlyFeeEtb:'1'};
  const quoteController=portal.createCommuteQuoteController({fetchFn:(url,options)=>fetch(base+url,options)});
  await quoteController.update({departure:dailyInput.proposedDeparture,destination:dailyInput.proposedDestination,vehicleType:dailyInput.vehicleType},{immediate:true});
  assert.equal(quoteController.getState().status,'READY');assert.equal(quoteController.getState().quote.price.amountEtb,35000);
  const suggestions=await fetch(base+'/api/onboarding/commute-suggestions?field=departure&q=Bole');assert.ok((await suggestions.json()).places.some(place=>place.label==='Bole Medhanialem'));
  token='';const daily=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','register',dailyInput,quoteController.getState().quote)});token=daily.token;
  assert.equal(daily.onboarding.reviewStatus,'AWAITING_ACCEPTANCE');assert.equal(daily.onboarding.routePreference.routeId,'route_bole_merkato');assert.equal(daily.onboarding.routePreference.proposedMonthlyFeeEtb,35000);assert.equal(daily.onboarding.routePreference.feeLocked,true);assert.equal(daily.onboarding.routePreference.proposedMonthlyFeeBasis,'PER_VEHICLE');
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
  await DB.prepare("UPDATE routes SET basePriceEtb=2600 WHERE id='route_bole_merkato'").run();
  await quoteController.update({departure:dailyInput.proposedDeparture,destination:dailyInput.proposedDestination,vehicleType:dailyInput.vehicleType},{immediate:true,force:true});assert.equal(quoteController.getState().quote.price.amountEtb,36400);
  const refreshed=await api('/profile',{method:'PATCH',body:portal.routePreferencePayload(dailyInput,quoteController.getState().quote)});assert.equal(refreshed.onboarding.routePreference.proposedMonthlyFeeEtb,36400);assert.equal(refreshed.onboarding.acceptanceStatus,'PENDING','Explicit route/price refresh reopens required acceptance after a fare change.');
  await quoteController.update({departure:'Garment',destination:'Bole',vehicleType:'MINIBUS_14'},{immediate:true});assert.equal(quoteController.getState().quote.price.mode,'PROPOSED');
  token='';const proposedDaily=await api('/register',{method:'POST',anonymous:true,body:portal.accountPayload('DRIVER','register',{...dailyInput,phone:'0912346704',assignedVehiclePlate:'JOIN-PROPOSED-TEST',proposedDeparture:'Garment',proposedDestination:'Bole',proposedMonthlyFeeEtb:'45000.25'},quoteController.getState().quote)});
  assert.equal(proposedDaily.onboarding.routePreference.mode,'PROPOSED');assert.equal(proposedDaily.onboarding.routePreference.departure,'Garment');assert.equal(proposedDaily.onboarding.routePreference.destination,'Bole');assert.equal(proposedDaily.onboarding.canVerifyAndUpload,false);
  quoteController.dispose();
});


test('password recovery uses a separate same-origin client without application credentials', async () => {
  let sent;
  const api=portal.createRecoveryClient({fetchFn:async(url,options)=>{sent={url,options};return {ok:true,json:async()=>({success:true,message:'If eligible, a code will be sent.'})};}});
  await api('/request',{role:'DRIVER',phone:'0912345678'});
  assert.equal(sent.url,'/api/account-recovery/request');
  assert.equal(sent.options.credentials,'omit');assert.equal(sent.options.redirect,'error');
  assert.equal(sent.options.headers.Authorization,undefined);
  await api('/reset',{role:'DRIVER',phone:'0912345678',code:'123456',newPassword:'Recovery fixture password'});
  assert.equal(sent.url,'/api/account-recovery/reset');
  assert.equal(JSON.parse(sent.options.body).newPassword,'Recovery fixture password');
  await assert.rejects(api('https://another.example/reset',{}),/Invalid recovery API path/);
  await assert.rejects(api('//another.example/reset',{}),/Invalid recovery API path/);
});
