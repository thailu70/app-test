'use strict';
const crypto = require('node:crypto');
const TEST_API='https://developerportal.ethiotelebirr.et:38443/apiaccess/payment/gateway';
const PROD_API='https://superapp.ethiomobilemoney.et:38443/apiaccess/payment/gateway';
const TEST_WEB='https://developerportal.ethiotelebirr.et:38443/payment/web/paygate?';
const PROD_WEB='https://superapp.ethiomobilemoney.et:38443/payment/web/paygate?';
const SUCCESS=new Set(['PAY_SUCCESS','SUCCESS','PAID','COMPLETED']);
const FAILURE=new Set(['PAY_FAILED','FAILED','PAY_CANCEL','CANCELLED','CANCEL']);
const EXCLUDED=new Set(['sign','sign_type','header','refund_info','openType','raw_request','biz_content','wallet_reference_data']);
let tokenCache=null;
function config(){
 const env=String(process.env.TELEBIRR_ENVIRONMENT||'test').trim().toLowerCase();
 const live=['production','prod','live'].includes(env), test=['test','sandbox','development','dev'].includes(env);
 return {environment:live?'production':test?'test':'invalid',apiUrl:live?PROD_API:TEST_API,webUrl:live?PROD_WEB:TEST_WEB,
  fabricAppId:String(process.env.TELEBIRR_FABRIC_APP_ID||'').trim(),appSecret:String(process.env.TELEBIRR_APP_SECRET||'').trim(),
  merchantAppId:String(process.env.TELEBIRR_MERCHANT_APP_ID||'').trim(),merchantCode:String(process.env.TELEBIRR_MERCHANT_CODE||'').trim(),
  privateKey:String(process.env.TELEBIRR_PRIVATE_KEY||'').trim(),publicKey:String(process.env.TELEBIRR_PUBLIC_KEY||'').trim(),
  notifyUrl:String(process.env.TELEBIRR_NOTIFY_URL||'https://routepass.duckdns.org/api/subscriptions/telebirr/webhook').trim(),
  redirectUrl:String(process.env.TELEBIRR_REDIRECT_URL||'https://routepass.duckdns.org/api/subscriptions/telebirr/return').trim()};
}
function missingConfiguration(){
 const c=config(),missing=[];
 if(c.environment==='invalid')missing.push('TELEBIRR_ENVIRONMENT must be test or production');
 if(!c.fabricAppId)missing.push('TELEBIRR_FABRIC_APP_ID');
 if(!c.appSecret)missing.push('TELEBIRR_APP_SECRET');
 if(!c.merchantAppId)missing.push('TELEBIRR_MERCHANT_APP_ID');
 if(!/^\d{6}$/.test(c.merchantCode))missing.push('TELEBIRR_MERCHANT_CODE (six digits)');
 if(!c.privateKey)missing.push('TELEBIRR_PRIVATE_KEY');
 if(!/^https:\/\//i.test(c.notifyUrl))missing.push('TELEBIRR_NOTIFY_URL (HTTPS required)');
 if(!/^https:\/\//i.test(c.redirectUrl))missing.push('TELEBIRR_REDIRECT_URL (HTTPS required)');
 return missing;
}
function keyPem(source,kind){
 const raw=String(source||'').trim().replace(/\\n/g,'\n');
 if(!raw)throw Error('key not configured');
 if(raw.includes('-----BEGIN'))return raw;
 const body=raw.replace(/\s+/g,'');
 if(!/^[A-Za-z0-9+\/]+={0,2}$/.test(body))throw Error('invalid key format');
 for(const label of (kind==='private'?['PRIVATE KEY','RSA PRIVATE KEY']:['PUBLIC KEY','RSA PUBLIC KEY'])){
  const pem='-----BEGIN '+label+'-----\n'+(body.match(/.{1,64}/g)||[body]).join('\n')+'\n-----END '+label+'-----\n';
  try{if(kind==='private')crypto.createPrivateKey(pem);else crypto.createPublicKey(pem);return pem;}catch(_){}
 }
 throw Error('invalid key encoding');
}
function canonical(fields){
 const map=new Map();
 for(const [key,value] of Object.entries(fields||{})){
  if(EXCLUDED.has(key)||value===undefined||value===null||typeof value==='object')continue;
  const signedKey=key==='transId'&&!Object.prototype.hasOwnProperty.call(fields,'trans_id')?'trans_id':key;
  map.set(signedKey,String(value));
 }
 const biz=fields&&fields.biz_content;
 if(biz&&typeof biz==='object'&&!Array.isArray(biz))for(const [key,value] of Object.entries(biz)){
  if(!EXCLUDED.has(key)&&value!==undefined&&value!==null&&typeof value!=='object')map.set(key,String(value));
 }
 return [...map.entries()].sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:0).map(([k,v])=>k+'='+v).join('&');
}
function sign(fields){
 return crypto.sign('sha256',Buffer.from(canonical(fields),'utf8'),{key:keyPem(config().privateKey,'private'),padding:crypto.constants.RSA_PKCS1_PSS_PADDING,saltLength:32}).toString('base64');
}
function nonce(){const alphabet='0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';let value='';for(let i=0;i<32;i++)value+=alphabet[crypto.randomInt(0,alphabet.length)];return value;}
function merchantOrderId(){return 'RP'+Date.now().toString(10)+crypto.randomBytes(8).toString('hex').toUpperCase();}
async function post(url,body,headers,operation){
 let response;try{response=await fetch(url,{method:'POST',headers:Object.assign({'Content-Type':'application/json'},headers||{}),body:JSON.stringify(body),signal:AbortSignal.timeout(20000)});}catch(_){throw Error('Telebirr '+operation+' gateway connection failed');}
 let data;try{data=await response.json();}catch(_){throw Error('Telebirr '+operation+' returned invalid JSON');}
 if(!response.ok)throw Error('Telebirr '+operation+' failed with HTTP '+response.status);
 if(data&&data.code!==undefined&&!['0','00000'].includes(String(data.code)))throw Error('Telebirr '+operation+' rejected with code '+String(data.code));
 if(!data||typeof data!=='object'||Array.isArray(data))throw Error('Telebirr '+operation+' returned invalid data');
 return data;
}
function expiryMs(value){
 if(typeof value==='number'&&Number.isFinite(value)&&value>0)return value<1e12?value*1000:value;
 if(typeof value==='string'&&value.trim()){
  const v=value.trim();if(/^\d+$/.test(v)){const n=Number(v);return n<1e12?n*1000:n;}
  const parsed=Date.parse(v);if(Number.isFinite(parsed))return parsed;
 }return null;
}
async function fabricToken(){
 const c=config();if(tokenCache&&Date.now()<tokenCache.until)return tokenCache.value;
 const data=await post(c.apiUrl+'/payment/v1/token',{appSecret:c.appSecret},{'X-APP-Key':c.fabricAppId},'fabric-token');
 if(typeof data.token!=='string'||!data.token)throw Error('Telebirr did not return a fabric token');
 const expiry=expiryMs(data.expirationDate);tokenCache={value:data.token,until:expiry?expiry-60000:Date.now()+240000};return tokenCache.value;
}
function envelope(method,biz){
 const request={timestamp:String(Math.floor(Date.now()/1000)),nonce_str:nonce(),method,version:'1.0',biz_content:biz};
 request.sign=sign(request);request.sign_type='SHA256WithRSA';return request;
}
async function createCheckout(orderId,amount,title){
 const c=config(),total=Number(amount);
 if(!Number.isFinite(total)||total<=0||!/^[A-Za-z0-9]+$/.test(orderId))throw Error('invalid checkout order or amount');
 const token=await fabricToken();
 const request=envelope('payment.preorder',{notify_url:c.notifyUrl,appid:c.merchantAppId,merch_code:c.merchantCode,merch_order_id:orderId,
  trade_type:'Checkout',title:String(title||'RoutePass monthly pass').replace(/[^A-Za-z0-9 _.-]/g,'').slice(0,64)||'RoutePass pass',
  total_amount:total.toFixed(2),trans_currency:'ETB',timeout_express:'120m',redirect_url:c.redirectUrl});
 const response=await post(c.apiUrl+'/payment/v1/merchant/preOrder',request,{'X-APP-Key':c.fabricAppId,Authorization:token},'create-order');
 const prepayId=response.biz_content&&response.biz_content.prepay_id;
 if(typeof prepayId!=='string'||!prepayId)throw Error('Telebirr did not return prepay_id');
 const fields={appid:c.merchantAppId,merch_code:c.merchantCode,nonce_str:nonce(),prepay_id:prepayId,timestamp:String(Math.floor(Date.now()/1000))};
 const params=new URLSearchParams({...fields,sign:sign(fields),sign_type:'SHA256WithRSA',version:'1.0',trade_type:'Checkout'});
 return {prepayId,checkoutUrl:c.webUrl+params.toString()};
}
async function queryOrderStatus(orderId,prepayId){
 const c=config(),token=await fabricToken(),biz={appid:c.merchantAppId,merch_code:c.merchantCode};
 if(prepayId)biz.prepay_id=prepayId;if(orderId)biz.merch_order_id=orderId;
 const request=envelope('payment.queryorder',biz);
 const data=await post(c.apiUrl+'/payment/v1/merchant/queryOrder',request,{'X-APP-Key':c.fabricAppId,Authorization:token},'query-order');
 const b=data.biz_content&&typeof data.biz_content==='object'?data.biz_content:{};
 const status=String(b.trade_status||b.order_status||b.tradeStatus||b.orderStatus||'').trim().toUpperCase();
 return {paid:SUCCESS.has(status),failed:FAILURE.has(status),status,
  amount:String(b.total_amount||b.totalAmount||''),currency:String(b.trans_currency||b.transCurrency||''),
  merchantOrderId:String(b.merch_order_id||b.merchOrderId||''),paymentOrderId:String(b.payment_order_id||b.paymentOrderId||''),
  transactionId:String(b.trans_id||b.transId||''),raw:data};
}
function parseNotification(input){
 let value=input;
 if(typeof value==='string')value=JSON.parse(value);
 if(!value||typeof value!=='object'||Array.isArray(value))throw Error('invalid notification');
 if(value.data&&typeof value.data==='object'&&!Array.isArray(value.data)&&value.data.merch_order_id)value=value.data;
 else if(typeof value.data==='string'){
  const inner=JSON.parse(value.data);
  if(inner&&typeof inner==='object'&&!Array.isArray(inner)&&inner.merch_order_id)value=inner;
 }
 return value;
}
function verifyNotification(notification){
 const c=config();if(!c.publicKey||!notification.sign||!notification.sign_type)return false;
 try{return crypto.verify('sha256',Buffer.from(canonical(notification),'utf8'),{key:crypto.createPublicKey(keyPem(c.publicKey,'public')),padding:crypto.constants.RSA_PKCS1_PSS_PADDING,saltLength:32},Buffer.from(String(notification.sign).replace(/ /g,'+'),'base64'));}catch(_){return false;}
}
module.exports={config,missingConfiguration,merchantOrderId,canonicalString:canonical,createCheckout,queryOrderStatus,parseNotification,verifyNotification,resetTokenCacheForTests(){tokenCache=null;}};
