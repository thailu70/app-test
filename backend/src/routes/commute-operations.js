const express = require('express');
const { DB } = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { commuteRoute } = require('../services/commute-scope');
const { getGlobalGpsRadius } = require('../services/master-data');
const router = express.Router();
router.use(authenticate, requireRole('ADMIN'));
router.get('/routes/:id/departure-settings', async (req,res) => {
  try {
    const route = await DB.prepare(`SELECT * FROM routes WHERE id=? AND ${commuteRoute('')}`).get(req.params.id);
    if (!route) return res.status(404).json({success:false,error:'Daily commute route not found.'});
    const settings = await DB.prepare('SELECT * FROM route_departure_settings WHERE route_id=?').get(route.id);
    const global = await getGlobalGpsRadius();
    const configured = settings || {route_id:route.id,latitude:null,longitude:null,radius_m:300};
    const effective = global.overrideEnabled ? global.radiusMeters : configured.radius_m;
    res.json({success:true,settings:{...configured,radius_m:effective,configuredRadiusMeters:configured.radius_m,
      effectiveRadiusMeters:effective,globalRadiusOverride:global.overrideEnabled},route});
  } catch(e) { res.status(500).json({success:false,error:'Could not load departure settings.'}); }
});
router.put('/routes/:id/departure-settings', async (req,res) => {
  try {
    const route = await DB.prepare(`SELECT id FROM routes WHERE id=? AND ${commuteRoute('')}`).get(req.params.id);
    if (!route) return res.status(404).json({success:false,error:'Daily commute route not found.'});
    const { latitude, longitude, radiusMeters } = req.body;
    if (![latitude,longitude,radiusMeters].every(v=>typeof v==='number' && Number.isFinite(v)) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || radiusMeters < 25 || radiusMeters > 5000) return res.status(400).json({success:false,error:'Use valid latitude/longitude and a radius from 25 to 5000 metres.'});
    await DB.transaction(async tx => {
      await tx.prepare('INSERT INTO route_departure_settings(route_id,latitude,longitude,radius_m,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(route_id) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,radius_m=excluded.radius_m,updated_at=CURRENT_TIMESTAMP').run(route.id,latitude,longitude,radiusMeters);
      await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run('COMMUTE_DEPARTURE_SETTINGS',req.user.id,'ADMIN',JSON.stringify({routeId:route.id,latitude,longitude,radiusMeters}));
    });
    res.json({success:true,message:'Departure area saved. Arrival requires GPS in this radius and within 30 minutes of departure.'});
  } catch(e) { console.error('[Departure settings]',e);res.status(500).json({success:false,error:'Could not save departure settings.'}); }
});
router.get('/reports', async(req,res)=>{
  try {
    const period=req.query.period||'daily',sort=req.query.sort||'date',order=req.query.order||'asc';
    if(!['daily','weekly','monthly'].includes(period)||!['date','trips','boardings','revenue','passengers'].includes(sort)||!['asc','desc'].includes(order))return res.status(400).json({success:false,error:'Invalid report filters.'});
    const days=period==='monthly'?365:period==='weekly'?84:30;
    const from=new Date(Date.now()-days*86400000).toISOString();
    const dailyExpression=column=>DB.isPostgres?`TO_CHAR(CAST(${column} AS TIMESTAMP) + INTERVAL '3 hours', 'YYYY-MM-DD')`:`DATE(${column}, '+3 hours')`;
    const queries=[
      [`SELECT ${dailyExpression('t.startTime')} AS day,COUNT(*) AS value FROM trips t JOIN routes r ON r.id=t.routeId WHERE ${commuteRoute('r')} AND t.startTime>=? GROUP BY day`,'trips'],
      [`SELECT ${dailyExpression('c.timestamp')} AS day,COUNT(*) AS value FROM checkin_records c JOIN routes r ON r.id=c.routeId WHERE ${commuteRoute('r')} AND c.status='BOARDED' AND c.timestamp>=? GROUP BY day`,'boardings'],
      [`SELECT ${dailyExpression('p.date')} AS day,COALESCE(SUM(p.amountEtb),0) AS value FROM payment_transactions p JOIN users u ON u.id=p.passengerId WHERE COALESCE(u.companyId,'')='' AND p.provider NOT IN ('ADMIN_TEST','TEST_AUTO','Telebirr-TestSandbox') AND p.status IN ('COMPLETED','VERIFIED') AND p.date>=? GROUP BY day`,'revenue'],
      [`SELECT ${dailyExpression('u.createdAt')} AS day,COUNT(*) AS value FROM users u WHERE u.role='PASSENGER' AND COALESCE(u.companyId,'')='' AND u.createdAt>=? GROUP BY day`,'passengers']
    ];
    const results=await Promise.all(queries.map(async([sql,key])=>({key,rows:await DB.prepare(sql).all(from)})));
    const buckets=new Map();
    const bucket=day=>{if(period==='monthly')return day.slice(0,7);if(period==='weekly'){const d=new Date(day+'T00:00:00Z');d.setUTCDate(d.getUTCDate()-((d.getUTCDay()+6)%7));return d.toISOString().slice(0,10);}return day;};
    for(let i=days;i>=0;i--){const day=new Date(Date.now()-i*86400000+3*3600000).toISOString().slice(0,10),key=bucket(day);if(!buckets.has(key))buckets.set(key,{date:key,trips:0,boardings:0,revenue:0,passengers:0});}
    for(const result of results)for(const row of result.rows){const key=bucket(String(row.day).slice(0,10));if(!buckets.has(key))buckets.set(key,{date:key,trips:0,boardings:0,revenue:0,passengers:0});buckets.get(key)[result.key]+=Number(row.value);}
    const rows=[...buckets.values()].sort((a,b)=>(typeof a[sort]==='string'?a[sort].localeCompare(b[sort]):a[sort]-b[sort])*(order==='asc'?1:-1));
    res.json({success:true,period,timezone:'Africa/Addis_Ababa',rows,totals:rows.reduce((s,r)=>{for(const k of ['trips','boardings','revenue','passengers'])s[k]+=r[k];return s;},{trips:0,boardings:0,revenue:0,passengers:0})});
  }catch(e){console.error('[Reports]',e);res.status(500).json({success:false,error:'Could not load performance report.'});}
});
module.exports=router;
