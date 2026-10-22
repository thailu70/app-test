'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'),path = require('node:path'),vm = require('node:vm');

test('web guest test payment waits for SMS proof and clears it when the phone changes',async()=>{
  const nodes=new Map(),requests=[];
  function node(selector){if(!nodes.has(selector))nodes.set(selector,{value:'',disabled:false,textContent:'',className:'',innerHTML:'',classList:{add(){},remove(){},toggle(){}},events:{},addEventListener(name,fn){this.events[name]=fn;},querySelectorAll(){return [];},querySelector(){return node('button');}});return nodes.get(selector);}
  const booking={id:'ui-booking',seatNumber:1,amountEtb:450,status:'HOLD',paymentStatus:'UNPAID',passengerName:'Fixture Rider',passengerPhone:'0912469923',departure:{origin:'Addis',destination:'Hawassa',departureAt:new Date().toISOString()}};
  const session=new Map([['passo_booking_ui-booking','private-booking-secret']]);
  const context={document:{querySelector:node,body:{append(){}},createElement:()=>({click(){},remove(){}})},window:{scrollTo(){}},location:{search:'?booking=ui-booking',assign(){}},history:{replaceState(){}},sessionStorage:{getItem:key=>session.get(key),setItem:(key,value)=>session.set(key,value)},URLSearchParams,
    FormData:class{constructor(form){this.form=form;}*[Symbol.iterator](){if(this.form===node('#details')){yield ['passengerName',node('#details [name=passengerName]').value];yield ['passengerPhone',node('#details [name=passengerPhone]').value];}}},
    fetch:async(url,options={})=>{requests.push({url,options});let body;
      if(url.includes('/departures?'))body={departures:[]};else if(url.endsWith('/request-otp'))body={delivery:'SENT'};
      else if(url.endsWith('/verify-otp'))body={testPaymentVerificationToken:'a'.repeat(64),expiresInSeconds:300};
      else if(url.endsWith('/checkout'))body={paid:true};else body={booking};
      return {ok:true,status:200,json:async()=>body};}
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../public/travel/travel.js'),'utf8'),context);
  const flush=()=>new Promise(resolve=>setImmediate(resolve));await flush();await flush();
  const submit=()=>node('#details').onsubmit({preventDefault(){},target:node('#details')});
  submit();await flush();assert.ok(!requests.some(request=>request.url.endsWith('/checkout')),'Entering the test phone alone never checks out');
  assert.match(node('#message').textContent,/Verify ownership/);
  await node('#testSendCode').onclick({target:node('#testSendCode')});node('#testPaymentCode').value='123456';
  await node('#testVerifyCode').onclick({target:node('#testVerifyCode')});submit();await flush();await flush();
  const checkout=requests.find(request=>request.url.endsWith('/checkout'));assert.ok(checkout);
  assert.equal(JSON.parse(checkout.options.body).testPaymentVerificationToken,'a'.repeat(64));
  assert.equal(checkout.options.headers['X-Booking-Secret'],'private-booking-secret');
  node('#details [name=passengerPhone]').value='0911000001';node('#details [name=passengerPhone]').events.input();
  assert.equal(node('#testPaymentVerification').disabled,true);
  node('#details [name=passengerPhone]').value='0912469923';node('#details [name=passengerPhone]').events.input();
  const count=requests.filter(request=>request.url.endsWith('/checkout')).length;submit();await flush();
  assert.equal(requests.filter(request=>request.url.endsWith('/checkout')).length,count,'A different phone or completed booking clears the proof');
});
