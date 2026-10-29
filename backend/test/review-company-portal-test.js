'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const {renderCompanyHtml,ASSETS,CSP}=require('../src/review/company-portal');
const root=path.join(__dirname,'../public');
test('review company pages reuse only an exact asset allowlist with a restrictive network policy',()=>{
  for(const page of ['index','intercity']) {
    const source=fs.readFileSync(path.join(root,'company',page+'.html'),'utf8'),html=renderCompanyHtml(source);
    assert.ok(html.includes('SYNTHETIC DEMO COMPANY'));assert.ok(html.includes('No real payment, SMS or transport'));
    const resources=[...html.matchAll(/(?:href|src)="([^"]+)"/g)].map(match=>match[1]);
    for(const resource of resources) {
      assert.ok(resource.startsWith('/review/company/'),resource);
      const url=new URL(resource,'https://passo.live');assert.ok(ASSETS[url.pathname.replace(/^\/review/,'')],resource);
    }
    assert.ok(html.indexOf('/review/company/review-session.js')<html.indexOf('/review/company/'+(page==='index'?'applications.js':'intercity.js')));
    assert.equal(source.includes('SYNTHETIC DEMO COMPANY'),false,'Production source remains unlabelled.');
  }
  assert.ok(CSP.includes('connect-src https://passo.live/review/api/;'));
  for(const policy of ["form-action 'none'","frame-src 'none'","worker-src 'none'","base-uri 'none'"])assert.ok(CSP.includes(policy));
  assert.equal(Object.keys(ASSETS).some(key=>!key.startsWith('/company/')),false);
});
const adapter=fs.readFileSync(path.join(root,'company/review-session.js'),'utf8');
function browser({bridge,pathname='/review/company/'}={}) {
  const calls=[],window={fetch:async(...args)=>{calls.push(args);return {ok:true};},...(bridge?{PassoReviewSession:bridge}:{})};
  const context={window,location:{origin:'https://passo.live',pathname,href:'https://passo.live'+pathname},URL,TextEncoder,document:{querySelectorAll:()=>[],getElementById:()=>null}};
  for(const key of ['sessionStorage','localStorage','indexedDB'])Object.defineProperty(context,key,{get(){throw Error('Storage access forbidden');}});
  vm.runInNewContext(adapter,context);return {environment:window.PassoCompanyEnvironment,window,calls};
}
test('demo company sessions never access DOM storage and carry only ephemeral isolated bridge tokens',()=>{
  let token='',closed=false;const bridge={getToken:()=>closed?'':token,setToken:value=>{if(closed)return false;token=value;return true;},clearToken:()=>{token='';return !closed;}};
  const first=browser({bridge});assert.equal(first.environment.getToken(),'');assert.equal(first.environment.setToken('demo-only-token'),true);
  const second=browser({bridge,pathname:'/review/company/intercity.html'});assert.equal(second.environment.getToken(),'demo-only-token');
  closed=true;token='';assert.equal(first.environment.getToken(),'');assert.equal(second.environment.setToken('late-token'),false);
  const normalBrowser=browser();assert.equal(normalBrowser.environment.setToken('memory-only'),true);assert.equal(normalBrowser.environment.getToken(),'memory-only');
  assert.equal(browser().environment.getToken(),'','A normal browser new document requires sign-in again; nothing persisted.');
  assert.throws(()=>browser({pathname:'/company/'}),/origin mismatch/);
});
test('every review company fetch is scoped, cookie-free and rejects redirects and cross-environment requests',async()=>{
  const {window,calls}=browser();
  await window.fetch('/api/companies/me',{credentials:'include',redirect:'follow',headers:{Authorization:'Bearer demo-token'}});
  assert.equal(calls[0][0],'https://passo.live/review/api/companies/me');assert.equal(calls[0][1].credentials,'omit');assert.equal(calls[0][1].redirect,'error');
  for(const url of ['https://example.com/review/api/users','https://passo.live/api/users','/review/company/company.js','/review/api/../../api/users','/review/api/%2e%2e/api/users','https://user@passo.live/review/api/users','/review/api/%2fapi/users'])assert.throws(()=>window.fetch(url),/cannot leave/);
  assert.equal(calls.length,1);assert.throws(()=>window.fetch({url:'/review/api/users'}),/Unsupported/);
});
test('document and CSV bridges admit only bounded synthetic exports and never accept arbitrary files or origins',()=>{
  const calls=[],bridge={getToken:()=>'',openDocument:(value,filename)=>{calls.push({value,filename});return true;},exportCsv:value=>{calls.push(value);return true;}};
  const {environment}=browser({bridge});
  assert.equal(environment.openDocument('/api/onboarding/applications/DRIVER/rv_demo_driver/documents/LICENSE'),true);
  assert.equal(calls[0].value,'/review/api/onboarding/applications/DRIVER/rv_demo_driver/documents/LICENSE');assert.equal(calls[0].filename,'PASSO-synthetic-document');
  for(const url of ['/api/onboarding/applications/COMPANY/review_company/documents/REGISTRATION','/api/onboarding/applications/DRIVER/rv_demo/documents/LICENSE?token=secret','/api/onboarding/applications/DRIVER/rv_demo/documents/UNKNOWN','/api/admin/files','https://passo.live/api/onboarding/applications/DRIVER/rv_demo/documents/PHOTO'])assert.throws(()=>environment.openDocument(url));
  assert.equal(environment.openDocument('/review/api/intercity/tickets/rv_demo.pdf?token=header.payload.signature','PASSO-synthetic-ticket.pdf'),true);
  assert.equal(environment.openDocument('/review/api/intercity/company/bookings/rv_demo/ticket.pdf','PASSO-synthetic-ticket.pdf'),true);
  for(const filename of ['../../escape.pdf','bad/file.pdf','bad\\file.pdf','bad\nfile.pdf','x'.repeat(81)])assert.throws(()=>environment.openDocument('/api/onboarding/applications/DRIVER/rv_demo/documents/PHOTO',filename));
  assert.throws(()=>environment.openDocument('/review/api/intercity/tickets/rv_demo.pdf?token=header.payload.signature&other=1'));
  assert.equal(environment.exportCsv('environment,name\nSYNTHETIC DEMO,Demo Adult'),true);
  assert.throws(()=>environment.exportCsv('x'.repeat(2*1024*1024+1)),/too large/);
  assert.throws(()=>environment.exportCsv('ሀ'.repeat(1024*1024)),/too large/,'The limit uses UTF-8 bytes, not character count.');
  assert.equal(calls.length,4);assert.equal(browser().environment.openDocument('/api/onboarding/applications/DRIVER/rv_demo/documents/PHOTO'),false);
});
