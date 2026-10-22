'use strict';
const { test }=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const home=require('../public/home/home');
const html=fs.readFileSync(path.join(__dirname,'../public/home/index.html'),'utf8');
test('the selected website ships real local assets, translated copy and working existing-service destinations',()=>{
  for(const match of html.matchAll(/data-i18n="([^"]+)"/g))for(const language of ['en','am','om'])assert.notEqual(home.translate(match[1],language),match[1],match[1]+' '+language);
  for(const [key,values]of Object.entries(home.words))assert.equal(values.length,3,key);
  for(const match of html.matchAll(/(?:src|href)="(\/(?:home|join)\/[^"?#]+)(?:[?][^"]*)?"/g))if(!match[1].endsWith('/'))assert.equal(fs.existsSync(path.join(__dirname,'../public',match[1])),true,match[1]);
  for(const destination of ['#services','#drive','#about','#download','/travel/','/join/','/company/','/admin/','/privacy/','/delete-account/','mailto:info@passo.live'])assert.ok(html.includes('href="'+destination+'"'),destination);
  const downloadVersion=/data-apk-version="([\d.]+)"/.exec(html)?.[1];assert.match(downloadVersion,/^\d+\.\d+\.\d+$/);assert.ok(html.includes('href="/company/downloads/routepass-'+downloadVersion+'.apk"'));assert.match(html,/Android APK · test build/);assert.doesNotMatch(html,/play\.google\.com/);
  assert.match(fs.readFileSync(path.join(__dirname,'../public/home/home.css'),'utf8'),/font-family:Outfit;font-weight:100 900/);
  assert.match(home.translate('verifyBody','en'),/After acceptance.*verify.*upload.*final approval/);
  assert.doesNotMatch(home.translate('registerBody','en'),/upload|OTP|verif/i);
  assert.doesNotMatch(html,/<svg|<script(?![^>]*src=)|\son(?:click|submit|change)=|\sstyle=/);
  assert.deepEqual([...html.matchAll(/(?:src|href)="(https?:\/\/[^\"]+)"/g)].map(match=>match[1]),['https://passo.live/'],'Only the canonical URL may be external; runtime assets stay same origin.');
  assert.match(fs.readFileSync(path.join(__dirname,'../public/home/assets/icons/LICENSE.txt'),'utf8'),/MIT License/);
});
function fixture(){
  const listeners=new Map(),buttons=['en','am','om'].map(language=>({dataset:{language},attrs:new Map(),events:new Map(),setAttribute(key,value){this.attrs.set(key,value);},addEventListener(name,fn){this.events.set(name,fn);}}));
  const textNodes=['heroFirst','whereNext','verifyBody'].map(key=>({dataset:{i18n:key},textContent:''}));
  const links=[{events:new Map(),addEventListener(name,fn){this.events.set(name,fn);}}];
  const image={src:''};
  const menu={attrs:new Map(),events:new Map(),focused:false,setAttribute(key,value){this.attrs.set(key,value);},addEventListener(name,fn){this.events.set(name,fn);},querySelector(){return image;},contains(target){return target===this;},focus(){this.focused=true;}};
  const navigation={open:false,classList:{toggle(_key,value){navigation.open=value;}},querySelectorAll(){return links;},contains(target){return target===this;}};
  const storage=new Map(),document={documentElement:{lang:'en'},getElementById:id=>id==='menu-button'?menu:navigation,querySelectorAll:selector=>selector==='[data-i18n]'?textNodes:buttons,addEventListener:(name,fn)=>listeners.set(name,fn)};
  let widthChange;
  home.mount({document,localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)},matchMedia:()=>({addEventListener:(_name,fn)=>{widthChange=fn;}})});
  return {buttons,textNodes,links,menu,navigation,document,listeners,image,storage,widthChange:()=>widthChange()};
}
test('mobile navigation, language selection and escape/outside dismissal are interactive',()=>{
  const f=fixture();assert.equal(f.menu.attrs.get('aria-expanded'),'false');
  f.menu.events.get('click')();assert.equal(f.navigation.open,true);assert.match(f.image.src,/\/x\.svg$/);
  f.buttons[1].events.get('click')();assert.equal(f.document.documentElement.lang,'am');assert.equal(f.buttons[1].attrs.get('aria-pressed'),'true');assert.equal(f.textNodes[0].textContent,home.translate('heroFirst','am'));
  assert.equal(f.storage.get('passo.home.language'),'am');
  f.listeners.get('keydown')({key:'Escape'});assert.equal(f.navigation.open,false);assert.equal(f.menu.focused,true);
  f.menu.events.get('click')();f.links[0].events.get('click')();assert.equal(f.navigation.open,false);
  f.menu.events.get('click')();f.listeners.get('click')({target:{}});assert.equal(f.navigation.open,false);
  f.menu.events.get('click')();f.widthChange();assert.equal(f.navigation.open,false);
});
