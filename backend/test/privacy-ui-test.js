'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const code = fs.readFileSync(path.join(__dirname,'../public/delete-account/delete-account.js'),'utf8');
function fixture(fetchFn) {
  const nodes = new Map(), windowListeners = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id,{ value:'',textContent:'',hidden:false,disabled:false,listeners:new Map(),classList:{toggle(){}},addEventListener(name,fn){this.listeners.set(name,fn);},replaceChildren(...children){this.children=children;},reset(){for(const key of ['password','reauth','confirmation'])node(key).value='';} });
    return nodes.get(id);
  }
  vm.runInNewContext(code,{document:{getElementById:node,createElement:()=>({textContent:''})},window:{addEventListener:(name,fn)=>windowListeners.set(name,fn)},fetch:fetchFn});
  node('role').value='PASSENGER';node('phone').value='0912345678';node('password').value='not-saved-password';
  return { node, event:(id,name='submit')=>node(id).listeners.get(name)({preventDefault(){}}), pagehide:()=>windowListeners.get('pagehide')() };
}
const response = (data,status=200)=>({ok:status<400,status,json:async()=>data});
const details = eligible=>({success:true,deletion:{eligible,blockers:eligible?[]:[{code:'ACTIVE_TRIP',message:'Finish your active trip first.'}],consequences:['Closes account'],retainedCategories:['Financial history']}});
test('public privacy and deletion pages expose accurate operator/contact/retention and same-origin assets',()=>{
  const privacy=fs.readFileSync(path.join(__dirname,'../public/privacy/index.html'),'utf8'), deletion=fs.readFileSync(path.join(__dirname,'../public/delete-account/index.html'),'utf8');
  for(const html of [privacy,deletion]){assert.match(html,/Passo Mobility, Ethiopia/);assert.match(html,/thailu70@gmail\.com/);assert.match(html,/info@passo\.live/);assert.doesNotMatch(html,/<script(?![^>]*src=)|\son(?:click|submit|change)=|\sstyle=/);}
  assert.match(privacy,/Historical payment\/refund records/);assert.match(privacy,/Guest intercity tickets are separate/);assert.match(deletion,/pattern="DELETE"/);
  assert.doesNotMatch(code,/localStorage|sessionStorage|indexedDB/);
});
test('web deletion requires a separate confirmation, clears password after login and signs out after closure',async()=>{
  const requests=[];
  const f=fixture(async(url,options)=>{requests.push({url,options});return response(url.endsWith('/login')?{success:true,token:'memory-only-token'}:options.method==='POST'?{success:true,deletion:{id:'delete_receipt',status:'COMPLETED_WITH_RETENTION',message:'Account closed'}}:details(true));});
  await f.event('login-form');assert.equal(requests.length,2);assert.equal(requests[0].options.headers.Authorization,undefined);assert.equal(requests[1].options.headers.Authorization,'Bearer memory-only-token');assert.equal(requests[0].options.redirect,'error');
  assert.equal(f.node('password').value,'');assert.equal(f.node('review').hidden,false);assert.equal(f.node('sign-in').hidden,true);assert.equal(f.node('delete-form').hidden,false);
  f.node('confirmation').value='wrong';await f.event('delete-form');assert.equal(requests.length,2);
  f.node('confirmation').value='DELETE';f.node('reauth').value='not-saved-password';await f.event('delete-form');assert.equal(requests.length,3);assert.deepEqual(JSON.parse(requests[2].options.body),{password:'not-saved-password',confirmation:'DELETE'});
  assert.equal(f.node('reauth').value,'');assert.equal(f.node('receipt').hidden,false);assert.match(f.node('receipt-id').textContent,/delete_receipt/);
});
test('handover blocker hides destructive controls and cancel does not call closure',async()=>{
  const requests=[];
  const f=fixture(async(url,options)=>{requests.push({url,options});return response(url.endsWith('/login')?{success:true,token:'token'}:details(false));});
  await f.event('login-form');assert.equal(f.node('delete-form').hidden,true);assert.equal(f.node('blockers').hidden,false);assert.match(f.node('blockers').textContent,/active trip/);
  await f.event('cancel-button','click');assert.equal(requests.length,2);assert.equal(f.node('sign-in').hidden,false);
});
test('leaving the page invalidates a late login response instead of restoring its session',async()=>{
  let finish;
  const f=fixture(()=>new Promise(resolve=>{finish=resolve;}));
  const login=f.event('login-form');f.pagehide();finish(response({success:true,token:'late-token'}));await login;
  assert.equal(f.node('password').value,'');assert.notEqual(f.node('sign-in').hidden,true);
});
