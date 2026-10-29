'use strict';
const { getReviewRuntime } = require('./isolation');
function prefixReviewUrls(value) {
  if(value instanceof Date) return value.toISOString();
  if(Buffer.isBuffer(value)) return value;
  if (typeof value === 'string') {
    if (value.startsWith('/api/')) return '/review' + value;
    if (value.startsWith('https://passo.live/api/')) return value.replace('https://passo.live/api/','https://passo.live/review/api/');
    return value;
  }
  if (Array.isArray(value)) return value.map(prefixReviewUrls);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key,item])=>[key,prefixReviewUrls(item)]));
  return value;
}
function validFix(location,now=Date.now()) {
  if(!location || typeof location.latitude!=='number' || !Number.isFinite(location.latitude) || Math.abs(location.latitude)>90 || typeof location.longitude!=='number' || !Number.isFinite(location.longitude) || Math.abs(location.longitude)>180 || typeof location.accuracyMeters!=='number' || !Number.isFinite(location.accuracyMeters) || location.accuracyMeters<0 || location.accuracyMeters>100) return false;
  const at=typeof location.timestamp==='number'?location.timestamp:Date.parse(location.timestamp);
  return Number.isFinite(at) && now-at<=120000 && at-now<=10000;
}
function createReviewMiddleware(config, { db = require('../db').DB, fixtures, now = Date.now } = {}) {
  if(config!==getReviewRuntime()) throw Error('No validated review runtime.');
  const {resolveActivePrincipal}=require('../middleware/auth');
  const companyPortal=require('./company-portal').createCompanyPortal(config);
  // Fixtures are assigned after schema migrations, before the listener opens.
  const currentFixtures=()=>fixtures || require('./state').fixtures;
  return function reviewBoundary(req,res,next) {
    res.set('X-Passo-Environment','REVIEW');res.set('Cache-Control','private, no-store');res.set('Referrer-Policy','no-referrer');
    const original=res.json.bind(res);res.json=value=>original(prefixReviewUrls(value));
    void (async()=>{
      // Express routes are case-insensitive and allow a trailing slash. Policy
      // matching must use those same equivalences; preserve req.path itself
      // so case-sensitive record IDs and downstream routing stay unchanged.
      const policyPath=req.path.toLowerCase().replace(/\/+$/,'')||'/';
      if(!policyPath.startsWith('/api/')) return companyPortal(req,res);
      if(/^\/api\/(?:admin(?:\/|$)|mobility\/admin(?:\/|$))/.test(policyPath) || /\/(?:seed|reset)(?:\/|$)/.test(policyPath)) return res.status(404).json({success:false,error:'Not available.'});
      if(/^\/api\/onboarding\/applications(?:\/|$)/.test(policyPath)) {
        const principal=await resolveActivePrincipal(String(req.get('Authorization')||'').replace(/^Bearer /,''));
        if(principal?.role!=='COMPANY' || !await currentFixtures()?.owned('company',principal.id)) return res.status(404).json({success:false,error:'Not available.'});
        // The existing company-scoped reviewer guards still validate every
        // application/document/action, so independent applicants stay private.
      }
      if(policyPath==='/api/companies/register' || (policyPath==='/api/onboarding/register' && String(req.body?.role||'').toUpperCase()==='COMPANY')) return res.status(503).json({success:false,error:'Use the supplied synthetic company owner credentials. Company signup and real verification are unavailable in demo.'});
      if(req.method==='GET' && policyPath==='/api/review/info') return res.json({success:true,environment:'REVIEW',synthetic:true,paymentsEnabled:false,smsEnabled:false});
      if(/\/(?:checkout|test-payment|payments\/webhook|telebirr|request-otp|verify-otp)(?:\/|$)/.test(policyPath) || /^\/api\/account-recovery(?:\/|$)/.test(policyPath)) return res.status(503).json({success:false,code:'REVIEW_EXTERNAL_SERVICES_DISABLED',error:'Synthetic demo: real payments, SMS and phone verification are disabled. Use the supplied paid sample tickets and reusable demo credentials.'});
      if(policyPath==='/api/auth/register' && String(req.body?.role||'').toUpperCase()==='ADMIN') return res.status(404).json({success:false,error:'Not available.'});
      const samples=currentFixtures();
      if(req.method==='GET' && policyPath==='/api/review/resources') {
        const principal=await resolveActivePrincipal(String(req.get('Authorization')||'').replace(/^Bearer /,''));
        const result=samples && await samples.resources(principal);
        return result?res.json(result):res.status(401).json({success:false,error:'Sign in with the supplied synthetic account to open demo samples.'});
      }
      const daily=req.method==='POST' && ['/api/trips/departure-check','/api/trips/start'].includes(policyPath);
      const group=req.method==='POST' && /^\/api\/mobility\/driver\/trips\/[^/]+\/(boarding|board)$/.test(policyPath);
      const intercity=req.method==='POST' && /^\/api\/intercity\/driver\/departures\/[^/]+\/arrival$/.test(policyPath);
      if(samples && (daily||group||intercity)) {
        const fix=daily||intercity ? req.body?.location || req.body : req.body;
        if(validFix(fix,now())) {
          const principal=await resolveActivePrincipal(String(req.get('Authorization')||'').replace(/^Bearer /,''));
          if(principal?.role==='DRIVER' && await samples.owned('user',principal.id)) {
            // Move ONLY an owned synthetic route to this fresh physical fix.
            // The original auth/assignment/time/GPS/payment/boarding guards run
            // afterwards unchanged. No fabricated GPS fix is sent by the app.
            const soon=new Date(now()+5*60000).toISOString();
            if(daily) {
              const vehicle=await db.prepare('SELECT * FROM vehicles WHERE id=? AND driverId=?').get(req.body.vehicleId,principal.id);
              if(vehicle && await samples.owned('vehicle',vehicle.id) && await samples.owned('route',vehicle.assignedRouteId) && (!req.body.routeId || req.body.routeId===vehicle.assignedRouteId)) {
                const clock=new Date(now()+3*3600000).toISOString().slice(11,16),inbound=req.body.direction==='INBOUND';
                const stop=await db.prepare('SELECT id FROM route_stops WHERE routeId=? ORDER BY stopOrder '+(inbound?'DESC':'ASC')+' LIMIT 1').get(vehicle.assignedRouteId);
                if(stop && await samples.owned('stop',stop.id)) await db.transaction(async tx=>{
                  await tx.prepare('UPDATE routes SET morningDeparture=?,eveningDeparture=? WHERE id=?').run(clock,clock,vehicle.assignedRouteId);
                  await tx.prepare('UPDATE route_stops SET latitude=?,longitude=? WHERE id=?').run(fix.latitude,fix.longitude,stop.id);
                  if(!inbound) await tx.prepare('INSERT INTO route_departure_settings(route_id,latitude,longitude,radius_m) VALUES(?,?,?,300) ON CONFLICT(route_id) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude').run(vehicle.assignedRouteId,fix.latitude,fix.longitude);
                });
              }
            } else {
              const id=decodeURIComponent(req.path.split('/')[5]),table=group?'mobility_trips':'intercity_departures',kind=group?'group-trip':'departure';
              if(await samples.owned(kind,id)) await db.prepare('UPDATE '+table+' SET latitude=?,longitude=?,departure_at=? WHERE id=? AND driver_id=? AND status IN (?,?)').run(fix.latitude,fix.longitude,soon,id,principal.id,group?'APPROVED':'SCHEDULED','BOARDING');
            }
          }
        }
      }
      next();
    })().catch(next);
  };
}
module.exports={createReviewMiddleware,prefixReviewUrls,validFix};
