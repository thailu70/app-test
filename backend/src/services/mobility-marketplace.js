'use strict';
const crypto = require('crypto');
const { publicBaseUrl } = require('./public-url');
const ACTIVE = ['ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION'];
const schemas = new WeakMap();
async function initMarketplaceSchema(db) {
  if (!schemas.has(db)) schemas.set(db, Promise.resolve().then(()=>db.exec(`
    CREATE TABLE IF NOT EXISTS mobility_market_settings (id TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mobility_request_market (
      request_id TEXT PRIMARY KEY REFERENCES mobility_requests(id), estimated_weight_kg NUMERIC,travel_time TEXT,
      route_distance_km NUMERIC, selected_quote_id TEXT,commission_percent NUMERIC NOT NULL DEFAULT 0,
      commission_etb NUMERIC NOT NULL DEFAULT 0, settlement TEXT NOT NULL DEFAULT 'NONE',
      completed_at TEXT, confirmed_at TEXT, dispute_note TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS mobility_quotes (
      id TEXT PRIMARY KEY,request_id TEXT NOT NULL REFERENCES mobility_requests(id),driver_id TEXT NOT NULL REFERENCES users(id),
      vehicle_id TEXT NOT NULL REFERENCES vehicles(id),price_etb NUMERIC NOT NULL CHECK(price_etb>0),note TEXT NOT NULL DEFAULT '',
      eta_minutes INTEGER,status TEXT NOT NULL DEFAULT 'OPEN',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
      UNIQUE(request_id,driver_id)
    );
    CREATE TABLE IF NOT EXISTS mobility_wallets (driver_id TEXT PRIMARY KEY REFERENCES users(id),balance_cents INTEGER NOT NULL DEFAULT 0 CHECK(balance_cents>=0),held_cents INTEGER NOT NULL DEFAULT 0 CHECK(held_cents>=0 AND held_cents<=balance_cents));
    CREATE TABLE IF NOT EXISTS mobility_credit_ledger (id TEXT PRIMARY KEY,driver_id TEXT NOT NULL REFERENCES users(id),event_key TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,amount_cents INTEGER NOT NULL,request_id TEXT,reference TEXT,note TEXT NOT NULL DEFAULT '',actor_id TEXT,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mobility_job_locations (request_id TEXT PRIMARY KEY REFERENCES mobility_requests(id),driver_id TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_meters REAL NOT NULL,recorded_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mobility_reviews (id TEXT PRIMARY KEY,request_id TEXT NOT NULL UNIQUE REFERENCES mobility_requests(id),driver_id TEXT NOT NULL REFERENCES users(id),rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5),comment TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'PENDING',moderation_note TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS mobility_review_reports (id TEXT PRIMARY KEY,review_id TEXT NOT NULL REFERENCES mobility_reviews(id),actor_id TEXT NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,UNIQUE(review_id,actor_id));
    CREATE INDEX IF NOT EXISTS mobility_quote_request ON mobility_quotes(request_id,status);
  `)).catch(error => { schemas.delete(db); throw error; }));
  return schemas.get(db);
}
const uid = prefix => prefix + crypto.randomUUID();
const rounded = value => Math.round(Number(value) * 100) / 100;
const km = (a,b,c,d) => { const r=v=>v*Math.PI/180; const h=Math.sin(r(c-a)/2)**2+Math.cos(r(a))*Math.cos(r(c))*Math.sin(r(d-b)/2)**2;return 6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h)); };
function createMarketplace({ db, now, fail, money, text, coordinates, vehicleDriver, serviceType, getRequest, requestView, sendSms }) {
  const instant=()=>new Date(now()).toISOString(), lock=tx=>tx.isPostgres?' FOR UPDATE':'';
  const defaults={cargoCommissionPercent:0,groupCommissionPercent:0,cargoRateEtbPerKgKm:0,minimumCargoFareEtb:0,notificationRadiusKm:30};
  async function settings() { const row=await db.prepare("SELECT value FROM mobility_market_settings WHERE id='pricing'").get();return {settings:{...defaults,...(row?JSON.parse(row.value):{})}}; }
  async function updateSettings(actor,input) {
    const current=(await settings()).settings;
    for(const key of Object.keys(defaults)) if(input[key]!=null) {const value=Number(input[key]);if(!Number.isFinite(value)||value<0||value>(key.includes('Percent')?100:key==='notificationRadiusKm'?500:100000))fail(400,'Enter valid marketplace pricing settings.');current[key]=value;}
    await db.transaction(async tx=>{await tx.prepare("INSERT INTO mobility_market_settings(id,value) VALUES('pricing',?) ON CONFLICT(id) DO UPDATE SET value=excluded.value").run(JSON.stringify(current));await audit(tx,actor,'MARKETPLACE_PRICING_UPDATED','pricing');});
    return {settings:current};
  }
  async function walletRow(driverId,tx=db,locked=false) {
    await tx.prepare('INSERT INTO mobility_wallets(driver_id) VALUES(?) ON CONFLICT(driver_id) DO NOTHING').run(driverId);
    return tx.prepare('SELECT * FROM mobility_wallets WHERE driver_id=?'+(locked?lock(tx):'')).get(driverId);
  }
  const walletView=row=>({balanceEtb:Number(row.balance_cents)/100,heldEtb:Number(row.held_cents)/100,availableEtb:(Number(row.balance_cents)-Number(row.held_cents))/100});
  async function credits(driverId) {
    if(!driverId) return {wallet:null,ledger:[]};
    const driver=await db.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER'").get(driverId);if(!driver)fail(404,'Driver unavailable.');
    return {wallet:walletView(await walletRow(driverId)),ledger:(await db.prepare('SELECT * FROM mobility_credit_ledger WHERE driver_id=? ORDER BY created_at DESC LIMIT 100').all(driverId)).map(r=>({id:r.id,kind:r.kind,amountEtb:Number(r.amount_cents)/100,reference:r.reference,note:r.note,createdAt:r.created_at}))};
  }
  async function topup(actor,input) {
    const amount=Math.round(money(input.amountEtb)*100),reference=text(input.reference,'verified payment reference',120),note=text(input.note,'credit note',500,true);
    await db.transaction(async tx=>{
      if(!await tx.prepare("SELECT id FROM users WHERE id=? AND role='DRIVER'").get(input.driverId))fail(404,'Driver unavailable.');
      await walletRow(input.driverId,tx,true);
      const previous=await tx.prepare('SELECT * FROM mobility_credit_ledger WHERE event_key=?').get('topup:'+reference);
      if(previous){if(previous.driver_id!==input.driverId||Number(previous.amount_cents)!==amount)fail(409,'This payment reference was already used.');return;}
      await tx.prepare('UPDATE mobility_wallets SET balance_cents=balance_cents+? WHERE driver_id=?').run(amount,input.driverId);
      await tx.prepare('INSERT INTO mobility_credit_ledger(id,driver_id,event_key,kind,amount_cents,reference,note,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)').run(uid('mcl_'),input.driverId,'topup:'+reference,'TOPUP',amount,reference,note,actor,instant());
      await audit(tx,actor,'DRIVER_CREDIT_TOPUP',input.driverId);
    });return credits(input.driverId);
  }
  async function enrich(row) {
    const meta=await db.prepare('SELECT * FROM mobility_request_market WHERE request_id=?').get(row.id);
    const count=await db.prepare("SELECT COUNT(*) AS count FROM mobility_quotes WHERE request_id=? AND status='OPEN'").get(row.id);
    const review=await db.prepare('SELECT rating,comment,status FROM mobility_reviews WHERE request_id=?').get(row.id);
    return {estimatedWeightKg:meta?.estimated_weight_kg==null?null:Number(meta.estimated_weight_kg),travelTime:meta?.travel_time||'',routeDistanceKm:meta?.route_distance_km==null?null:Number(meta.route_distance_km),commissionEtb:Number(meta?.commission_etb||0),settlement:meta?.settlement||'NONE',completedAt:meta?.completed_at||null,confirmedAt:meta?.confirmed_at||null,disputeNote:meta?.dispute_note||'',quotesCount:Number(count.count),review:review?{rating:Number(review.rating),comment:review.comment,status:review.status}:null,paymentMode:'DIRECT_TO_DRIVER',paymentVerified:false};
  }
  async function createMeta(key,input,tx=db) {
    let weight=null,distance=null,time=null;
    if(input.estimatedWeightKg!=null&&input.estimatedWeightKg!==''){weight=Number(input.estimatedWeightKg);if(!Number.isFinite(weight)||weight<=0||weight>100000)fail(400,'Enter estimated cargo weight between 0 and 100,000 kg.');}
    if(input.travelTime){if(typeof input.travelTime!=='string'||!/^([01]\d|2[0-3]):[0-5]\d$/.test(input.travelTime))fail(400,'Choose a valid pickup time.');time=input.travelTime;}
    if(input.originLatitude!=null&&input.destinationLatitude!=null)try {const result=await require('./mobility-geo').getGeoService().route(input);if(Number(result.distanceKm)>0)distance=Number(result.distanceKm);}catch(_error){/* Quotes remain available when road routing is unavailable. */}
    await tx.prepare('INSERT INTO mobility_request_market(request_id,estimated_weight_kg,travel_time,route_distance_km,updated_at) VALUES(?,?,?,?,?)').run(key,weight,time,distance,instant());
  }
  async function busy(driverId,tx=db,except='') {
    const row=await tx.prepare("SELECT id FROM mobility_requests WHERE driver_id=? AND status IN ('ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION') AND id<>? LIMIT 1").get(driverId,except);
    const trip=await tx.prepare("SELECT id FROM mobility_trips WHERE driver_id=? AND status='BOARDING' LIMIT 1").get(driverId);return Boolean(row||trip);
  }
  async function ratings(driverId,tx=db) {const r=await tx.prepare("SELECT AVG(rating) AS average,COUNT(*) AS count FROM mobility_reviews WHERE driver_id=? AND status='VISIBLE'").get(driverId);return {ratingAverage:r.average==null?null:rounded(r.average),ratingCount:Number(r.count)};}
  async function quoteView(q,tx=db) {
    const driver=await tx.prepare('SELECT fullName FROM users WHERE id=?').get(q.driver_id),vehicle=await tx.prepare('SELECT * FROM vehicles WHERE id=?').get(q.vehicle_id);
    return {id:q.id,driverId:q.driver_id,driverName:driver?.fullName||'',vehicleId:q.vehicle_id,vehicleType:vehicle?.vehicleType||'',vehicleModel:vehicle?.model||'',vehiclePlate:vehicle?.plateNumber||'',priceEtb:Number(q.price_etb),note:q.note,etaMinutes:q.eta_minutes==null?null:Number(q.eta_minutes),status:q.status,createdAt:q.created_at,...await ratings(q.driver_id,tx)};
  }
  async function opportunities(driverId,query={}) {
    const type=await serviceType(driverId);if(!['GROUP_TRAVEL','CARGO'].includes(type))fail(403,'This marketplace is for approved group travel and cargo drivers.');
    const wallet=walletView(await walletRow(driverId));if(await busy(driverId))return {requests:[],wallet,busy:true,notifications:[]};
    const fleet=await db.prepare("SELECT * FROM vehicles WHERE driverId=? AND status IN ('AVAILABLE','IN_SERVICE')").all(driverId);
    const valid=[];for(const v of fleet)try{await vehicleDriver(v.id,driverId,type==='CARGO'?'CARGO':'GROUP');valid.push(v);}catch(error){if(!error.status)throw error;}
    if(!valid.length)return {requests:[],wallet,busy:false,notifications:[]};
    let driverLocation=null;
    if(query.latitude!=null&&query.longitude!=null){const [latitude,longitude]=coordinates(query.latitude,query.longitude);driverLocation={latitude,longitude};}
    if(!driverLocation)for(const v of valid){const point=await db.prepare('SELECT * FROM vehicle_live_locations WHERE vehicle_id=?').get(v.id);if(point&&now()-Date.parse(point.updated_at)<15*60000){driverLocation=point;break;}}
    const cfg=(await settings()).settings,today=new Date(now()+3*3600000).toISOString().slice(0,10);
    const rows=await db.prepare("SELECT * FROM mobility_requests WHERE kind=? AND status='REQUESTED' AND travel_date>=? ORDER BY travel_date,created_at DESC LIMIT 100").all(type==='CARGO'?'CARGO':'GROUP',today),requests=[];
    for(const r of rows) {
      if(!valid.some(v=>v.vehicleType===r.vehicle_type&&(r.kind!=='GROUP'||Number(v.capacityLimit)>=Number(r.people))))continue;
      const extra=await enrich(r),my=await db.prepare('SELECT * FROM mobility_quotes WHERE request_id=? AND driver_id=?').get(r.id,driverId);
      const proximity=driverLocation&&r.origin_latitude!=null?rounded(km(Number(driverLocation.latitude),Number(driverLocation.longitude),Number(r.origin_latitude),Number(r.origin_longitude))):null;
      requests.push({id:r.id,kind:r.kind,origin:r.origin,destination:r.destination,travelDate:r.travel_date,vehicleType:r.vehicle_type,people:r.people,details:r.details,...extra,photoUrl:r.photo_storage?'/api/mobility/requests/'+encodeURIComponent(r.id)+'/photo':null,pickupDistanceKm:proximity,nearby:proximity!=null&&proximity<=cfg.notificationRadiusKm,estimatedCargoPriceEtb:r.kind==='CARGO'&&extra.estimatedWeightKg&&extra.routeDistanceKm&&cfg.cargoRateEtbPerKgKm>0?rounded(Math.max(cfg.minimumCargoFareEtb,extra.estimatedWeightKg*extra.routeDistanceKm*cfg.cargoRateEtbPerKgKm)):null,myQuote:my?await quoteView(my):null});
    }
    requests.sort((a,b)=>(a.pickupDistanceKm??Infinity)-(b.pickupDistanceKm??Infinity));
    return {requests,wallet,busy:false,notifications:requests.filter(r=>r.nearby&&!r.myQuote).map(r=>({id:r.id,title:r.kind==='CARGO'?'Nearby cargo request':'Nearby group travel request',origin:r.origin,destination:r.destination,travelDate:r.travelDate}))};
  }
  async function quote(driverId,key,input) {
    const price=money(input.priceEtb),note=text(input.note,'quote details',1000,true),eta=input.etaMinutes==null?null:Number(input.etaMinutes);
    if(eta!=null&&(!Number.isInteger(eta)||eta<1||eta>10080))fail(400,'Enter a valid pickup estimate in minutes.');
    const result=await db.transaction(async tx=>{
      const before=await getRequest(key,null,tx,true);await vehicleDriver(input.vehicleId,driverId,before.kind,tx,true);
      const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+lock(tx)).get(key);if(r.status!=='REQUESTED'||await busy(driverId,tx))fail(409,'This request or driver is no longer available.');
      const {vehicle}=await vehicleDriver(input.vehicleId,driverId,r.kind,tx);if(vehicle.vehicleType!==r.vehicle_type||r.kind==='GROUP'&&Number(vehicle.capacityLimit)<Number(r.people))fail(400,'Use a matching vehicle with enough capacity.');
      const old=await tx.prepare('SELECT * FROM mobility_quotes WHERE request_id=? AND driver_id=?').get(key,driverId),qid=old?.id||uid('mqt_');
      await tx.prepare('INSERT INTO mobility_quotes(id,request_id,driver_id,vehicle_id,price_etb,note,eta_minutes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(request_id,driver_id) DO UPDATE SET vehicle_id=excluded.vehicle_id,price_etb=excluded.price_etb,note=excluded.note,eta_minutes=excluded.eta_minutes,updated_at=excluded.updated_at').run(qid,key,driverId,input.vehicleId,price,note,eta,instant(),instant());
      return tx.prepare('SELECT * FROM mobility_quotes WHERE id=?').get(qid);
    });return {quote:await quoteView(result)};
  }
  async function quotes(key,capability){await getRequest(key,capability);const rows=await db.prepare("SELECT * FROM mobility_quotes WHERE request_id=? AND status IN ('OPEN','SELECTED') ORDER BY price_etb,created_at").all(key);return {quotes:await Promise.all(rows.map(r=>quoteView(r)))};}
  async function ledger(tx,driverId,key,kind,cents,requestId){await tx.prepare('INSERT INTO mobility_credit_ledger(id,driver_id,event_key,kind,amount_cents,request_id,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING').run(uid('mcl_'),driverId,key,kind,cents,requestId,instant());}
  async function queue(tx,r,event,message,phone=r.customer_phone){const fingerprint=[r.driver_id,r.vehicle_id,r.quote_etb==null?'':Number(r.quote_etb)].join(':');await tx.prepare('INSERT INTO mobility_sms_outbox(id,event_key,request_id,assignment_key,phone,message,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING').run(uid('rsms_'),'market:'+r.id+':'+event+':'+phone,r.id,fingerprint,phone,message,instant(),instant());}
  async function select(key,capability,input) {
    await getRequest(key,capability);const cfg=(await settings()).settings;
    await db.transaction(async tx=>{
      let q=await tx.prepare('SELECT * FROM mobility_quotes WHERE id=? AND request_id=?').get(input.quoteId,key);if(!q)fail(404,'Quote unavailable.');
      await vehicleDriver(q.vehicle_id,q.driver_id,(await getRequest(key,capability,tx)).kind,tx,true);
      const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+lock(tx)).get(key);
      q=await tx.prepare('SELECT * FROM mobility_quotes WHERE id=? AND request_id=?').get(input.quoteId,key);
      const m=await tx.prepare('SELECT * FROM mobility_request_market WHERE request_id=?').get(key);
      if(m?.selected_quote_id===q.id&&r.status!=='REQUESTED')return;
      if(r.status!=='REQUESTED'||q.status!=='OPEN'||await busy(q.driver_id,tx,key))fail(409,'This driver or request is no longer available.');
      const percent=r.kind==='CARGO'?cfg.cargoCommissionPercent:cfg.groupCommissionPercent,cents=Math.round(Number(q.price_etb)*percent);
      const w=await walletRow(q.driver_id,tx,true);if(Number(w.balance_cents)-Number(w.held_cents)<cents)fail(409,'This driver needs to add Passo credit before this quote can be selected.','DRIVER_CREDIT_REQUIRED');
      await tx.prepare('UPDATE mobility_wallets SET held_cents=held_cents+? WHERE driver_id=?').run(cents,q.driver_id);
      await ledger(tx,q.driver_id,'hold:'+key,'HOLD',cents,key);
      await tx.prepare("UPDATE mobility_quotes SET status=CASE WHEN id=? THEN 'SELECTED' ELSE 'CLOSED' END WHERE request_id=?").run(q.id,key);
      await tx.prepare("UPDATE mobility_requests SET status='ASSIGNED',driver_id=?,vehicle_id=?,quote_etb=?,assigned_at=? WHERE id=?").run(q.driver_id,q.vehicle_id,q.price_etb,instant(),key);
      await tx.prepare("INSERT INTO mobility_request_market(request_id,selected_quote_id,commission_percent,commission_etb,settlement,updated_at) VALUES(?,?,?,?,'HELD',?) ON CONFLICT(request_id) DO UPDATE SET selected_quote_id=excluded.selected_quote_id,commission_percent=excluded.commission_percent,commission_etb=excluded.commission_etb,settlement='HELD',updated_at=excluded.updated_at").run(key,q.id,percent,cents/100,instant());
      const current=await getRequest(key,null,tx,true),driver=await tx.prepare('SELECT fullName,phone FROM users WHERE id=?').get(q.driver_id),vehicle=await tx.prepare('SELECT plateNumber FROM vehicles WHERE id=?').get(q.vehicle_id);
      const link=publicBaseUrl()+'/api/mobility/track#id='+encodeURIComponent(key)+'&secret='+encodeURIComponent(capability);
      await queue(tx,current,'selected',`PASSO booking confirmed. ${r.origin} to ${r.destination}. Driver ${driver.fullName}, ${driver.phone}. Vehicle ${vehicle.plateNumber}. Agreed ETB ${q.price_etb}. Track privately: ${link}`);
      await queue(tx,current,'selected',`PASSO customer selected your quote: ${r.origin} to ${r.destination}, ${r.travel_date}. Customer ${r.customer_name}, ${r.customer_phone}. ETB ${q.price_etb}. Open your driver dashboard.`,driver.phone);
    });return {request:await requestView(await getRequest(key,capability))};
  }
  async function owned(driverId,key,tx=db,locked=false){const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+(locked?lock(tx):'')).get(key);if(!r||r.driver_id!==driverId)fail(404,'Assigned request unavailable.');return r;}
  async function bidderPhoto(driverId,row){if(row.kind!=='CARGO'||row.status!=='REQUESTED'||await busy(driverId))return false;const fleet=await db.prepare('SELECT * FROM vehicles WHERE driverId=?').all(driverId);for(const v of fleet){if(v.vehicleType!==row.vehicle_type)continue;try{await vehicleDriver(v.id,driverId,'CARGO');return true;}catch(error){if(!error.status)throw error;}}return false;}
  async function progress(driverId,key,input) {
    const transitions={ARRIVED_PICKUP:['ASSIGNED','AT_PICKUP'],LOADED:['AT_PICKUP','LOADED'],START_TRIP:['LOADED','IN_TRANSIT'],ARRIVED_DESTINATION:['IN_TRANSIT','AT_DESTINATION'],COMPLETE:['AT_DESTINATION','AWAITING_CONFIRMATION']},step=transitions[input.action];if(!step)fail(400,'Choose a valid trip action.');
    await db.transaction(async tx=>{
      const r=await owned(driverId,key,tx,true);if(r.status===step[1])return;if(r.status!==step[0])fail(409,'Complete the preceding trip step first.');
      await tx.prepare('UPDATE mobility_requests SET status=? WHERE id=?').run(step[1],key);
      if(input.action==='COMPLETE')await tx.prepare('UPDATE mobility_request_market SET completed_at=?,updated_at=? WHERE request_id=?').run(instant(),instant(),key);
      const messages={ARRIVED_PICKUP:'Your driver has arrived at pickup.',LOADED:r.kind==='CARGO'?'Your cargo has been loaded.':'Your group has boarded.',START_TRIP:'Your journey has started.',ARRIVED_DESTINATION:'Your driver has reached the destination.',COMPLETE:'Your driver marked the service complete. Please open your private Passo booking to confirm completion or report a problem.'};
      await queue(tx,r,input.action,'PASSO '+messages[input.action]+' '+r.origin+' to '+r.destination+'.');
    });return {request:await requestView(await owned(driverId,key))};
  }
  async function updateLocation(driverId,key,input) {
    const [latitude,longitude]=coordinates(input.latitude,input.longitude),accuracy=Number(input.accuracyMeters),timestamp=Date.parse(input.timestamp);
    if(!Number.isFinite(accuracy)||accuracy<=0||accuracy>1000||!Number.isFinite(timestamp)||now()-timestamp>120000||timestamp-now()>30000)fail(400,'Use a fresh device location.');
    await db.transaction(async tx=>{const r=await owned(driverId,key,tx,true);if(!ACTIVE.includes(r.status))fail(409,'Location sharing is only available during your assigned service.');
      const old=await tx.prepare('SELECT recorded_at FROM mobility_job_locations WHERE request_id=?').get(key);if(old&&Date.parse(old.recorded_at)>timestamp)return;
      await tx.prepare('INSERT INTO mobility_job_locations(request_id,driver_id,latitude,longitude,accuracy_meters,recorded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,accuracy_meters=excluded.accuracy_meters,recorded_at=excluded.recorded_at').run(key,driverId,latitude,longitude,accuracy,new Date(timestamp).toISOString());});return {updated:true};
  }
  async function location(key,capability){const r=await getRequest(key,capability);if(!ACTIVE.includes(r.status))return {location:null,trackingActive:false};const p=await db.prepare('SELECT * FROM mobility_job_locations WHERE request_id=? AND driver_id=?').get(key,r.driver_id);return {trackingActive:true,location:p?{latitude:Number(p.latitude),longitude:Number(p.longitude),accuracyMeters:Number(p.accuracy_meters),timestamp:p.recorded_at,stale:now()-Date.parse(p.recorded_at)>120000}:null};}
  async function capture(tx,r){const m=await tx.prepare('SELECT * FROM mobility_request_market WHERE request_id=?').get(r.id);if(!m||m.settlement!=='HELD')return;const cents=Math.round(Number(m.commission_etb)*100);await walletRow(r.driver_id,tx,true);await tx.prepare('UPDATE mobility_wallets SET balance_cents=balance_cents-?,held_cents=held_cents-? WHERE driver_id=?').run(cents,cents,r.driver_id);await ledger(tx,r.driver_id,'capture:'+r.id,'COMMISSION',-cents,r.id);await tx.prepare("UPDATE mobility_request_market SET settlement='CAPTURED',confirmed_at=?,updated_at=? WHERE request_id=?").run(instant(),instant(),r.id);}
  async function confirm(key,capability,input){if(!['CONFIRM','DISPUTE'].includes(input.action))fail(400,'Confirm completion or report a problem.');await getRequest(key,capability);await db.transaction(async tx=>{const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+lock(tx)).get(key);if(input.action==='CONFIRM'&&r.status==='COMPLETED')return;if(r.status!=='AWAITING_CONFIRMATION')fail(409,'Wait until the driver completes your service.');if(input.action==='DISPUTE'){await tx.prepare("UPDATE mobility_requests SET status='DISPUTED' WHERE id=?").run(key);await tx.prepare('UPDATE mobility_request_market SET dispute_note=?,updated_at=? WHERE request_id=?').run(text(input.note,'problem description',1000),instant(),key);}else{await capture(tx,r);await tx.prepare("UPDATE mobility_requests SET status='COMPLETED' WHERE id=?").run(key);}});return {request:await requestView(await getRequest(key,capability))};}
  async function cancel(key,capability){await getRequest(key,capability);await db.transaction(async tx=>{const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+lock(tx)).get(key);if(r.status==='CANCELLED')return;if(!['REQUESTED','ASSIGNED'].includes(r.status))fail(409,'Contact support to cancel a service already in progress.');await release(tx,r);await tx.prepare("UPDATE mobility_requests SET status='CANCELLED' WHERE id=?").run(key);});return {request:await requestView(await getRequest(key,capability))};}
  async function release(tx,r){const m=await tx.prepare('SELECT * FROM mobility_request_market WHERE request_id=?').get(r.id);if(m?.settlement!=='HELD')return;const cents=Math.round(Number(m.commission_etb)*100);await walletRow(r.driver_id,tx,true);await tx.prepare('UPDATE mobility_wallets SET held_cents=held_cents-? WHERE driver_id=?').run(cents,r.driver_id);await ledger(tx,r.driver_id,'release:'+r.id,'RELEASE',cents,r.id);await tx.prepare("UPDATE mobility_request_market SET settlement='RELEASED',updated_at=? WHERE request_id=?").run(instant(),r.id);}
  async function review(key,capability,input){const r=await getRequest(key,capability);if(r.status!=='COMPLETED'||!r.driver_id)fail(409,'Reviews are available after a completed service.');const rating=Number(input.rating);if(!Number.isInteger(rating)||rating<1||rating>5)fail(400,'Choose a rating from one to five.');const comment=text(input.comment,'review comment',1000,true);const existing=await db.prepare('SELECT id FROM mobility_reviews WHERE request_id=?').get(key);if(existing)fail(409,'This booking already has a review.');const rid=uid('mrv_');await db.prepare('INSERT INTO mobility_reviews(id,request_id,driver_id,rating,comment,created_at) VALUES(?,?,?,?,?,?)').run(rid,key,r.driver_id,rating,comment,instant());return {review:{id:rid,rating,comment,status:'PENDING'}};}
  async function reviews(driverId){const rows=await db.prepare("SELECT id,rating,comment,created_at FROM mobility_reviews WHERE driver_id=? AND status='VISIBLE' ORDER BY created_at DESC LIMIT 50").all(driverId);return {...await ratings(driverId),reviews:rows.map(r=>({id:r.id,rating:Number(r.rating),comment:r.comment,createdAt:r.created_at,customerName:'Verified customer'}))};}
  async function adminReviews(){return {reviews:await db.prepare('SELECT * FROM mobility_reviews ORDER BY created_at DESC LIMIT 200').all()};}
  async function moderate(actor,key,input){if(!['VISIBLE','HIDDEN'].includes(input.status))fail(400,'Choose a valid review status.');const result=await db.prepare('UPDATE mobility_reviews SET status=?,moderation_note=? WHERE id=?').run(input.status,text(input.reason,'moderation reason',500),key);await audit(db,actor,'MARKETPLACE_REVIEW_'+input.status,key);return {updated:true};}
  async function reportReview(actor,key,input){if(!await db.prepare("SELECT id FROM mobility_reviews WHERE id=? AND status='VISIBLE'").get(key))fail(404,'Review unavailable.');await db.prepare('INSERT INTO mobility_review_reports(id,review_id,actor_id,reason,created_at) VALUES(?,?,?,?,?) ON CONFLICT(review_id,actor_id) DO NOTHING').run(uid('mrr_'),key,actor,text(input.reason,'report reason',500),instant());return {reported:true};}
  async function resolve(actor,key,input){if(!['COMPLETE','CANCEL'].includes(input.action))fail(400,'Complete or cancel the disputed service.');await db.transaction(async tx=>{const r=await tx.prepare('SELECT * FROM mobility_requests WHERE id=?'+lock(tx)).get(key);if(!r||!['DISPUTED','AWAITING_CONFIRMATION'].includes(r.status))fail(409,'Select a disputed or unconfirmed service.');if(input.action==='COMPLETE')await capture(tx,r);else await release(tx,r);await tx.prepare('UPDATE mobility_requests SET status=?,review_note=? WHERE id=?').run(input.action==='COMPLETE'?'COMPLETED':'CANCELLED',text(input.note,'resolution note',1000),key);await audit(tx,actor,'MARKETPLACE_RESOLVED',key);});return {request:await requestView(await getRequest(key,null,db,true))};}
  async function audit(tx,actor,action,resource){await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run(action,actor,'ADMIN',resource);}
  return {init:()=>initMarketplaceSchema(db),settings,updateSettings,credits,topup,enrich,createMeta,opportunities,quote,quotes,select,progress,updateLocation,location,confirm,cancel,review,reviews,adminReviews,moderate,reportReview,resolve,capture,release,bidderPhoto};
}
module.exports={createMarketplace,initMarketplaceSchema};
