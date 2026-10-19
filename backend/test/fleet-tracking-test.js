'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
process.env.NODE_ENV = 'test';
delete process.env.DATABASE_URL;
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'routepass-fleet-gps-'));
process.env.JWT_SECRET = 'fleet-tracking-tests-authentication-secret';
const { DB } = require('../src/db');
const { signToken } = require('../src/middleware/auth');
const { initIntercitySchema } = require('../src/services/intercity-schema');

test('operator GPS shows genuine scoped fixes, preserves stale/offline ages, and accepts only the assigned active departure', async t => {
  await initIntercitySchema();
  await DB.exec('CREATE TABLE IF NOT EXISTS transport_companies (id TEXT PRIMARY KEY,name TEXT,phone TEXT,representative_name TEXT,status TEXT)');
  for (const id of ['fleet_co1', 'fleet_co2']) await DB.prepare('INSERT INTO transport_companies(id,name,phone,status) VALUES(?,?,?,?)').run(id,id,id,'ACTIVE');
  const tokens = {};
  for (const [id, role, company] of [['fleet_admin','ADMIN',''],['fleet_passenger','PASSENGER',''],['fleet_commute','DRIVER',''],['fleet_c1_driver','DRIVER','fleet_co1'],['fleet_c2_driver','DRIVER','fleet_co2']]) {
    await DB.prepare('INSERT INTO users(id,role,fullName,phone,passwordHash,status,companyId) VALUES(?,?,?,?,?,?,?)').run(id,role,id,id,'unused','ACTIVE',company);
    tokens[id] = signToken({ id, role });
  }
  tokens.fleet_co1 = signToken({ id:'fleet_co1', role:'COMPANY' });
  tokens.fleet_co2 = signToken({ id:'fleet_co2', role:'COMPANY' });
  for (const [id,type,company] of [['fleet_commute_route','DAILY_COMMUTE',''],['fleet_c1_route','INTERCITY','fleet_co1'],['fleet_c2_route','INTERCITY','fleet_co2']]) await DB.prepare('INSERT INTO routes(id,name,nameAm,routeType,companyId) VALUES(?,?,?,?,?)').run(id,id,id,type,company);
  for (const [id,driver,route,company] of [
    ['fleet_live','fleet_commute','fleet_commute_route',''],['fleet_stale','fleet_commute','fleet_commute_route',''],['fleet_offline','fleet_commute','fleet_commute_route',''],['fleet_no_gps','fleet_commute','fleet_commute_route',''],['fleet_bad_gps','fleet_commute','fleet_commute_route',''],['fleet_future_gps','fleet_commute','fleet_commute_route',''],
    ['fleet_c1_extra','fleet_c1_driver','fleet_c1_route','fleet_co1'],['fleet_c1_bus','fleet_c1_driver','fleet_c1_route','fleet_co1'],['fleet_c2_bus','fleet_c2_driver','fleet_c2_route','fleet_co2']
  ]) await DB.prepare('INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,companyId) VALUES(?,?,?,?,?,?,?,?)').run(id,id,'Test vehicle','MINIBUS_14',14,driver,route,company);
  for (const id of ['fleet_live','fleet_stale','fleet_bad_gps','fleet_future_gps']) await DB.prepare('INSERT INTO trips(id,vehicleId,driverId,routeId,status) VALUES(?,?,?,?,?)').run('trip_'+id,id,'fleet_commute','fleet_commute_route','IN_PROGRESS');
  const now = Date.now();
  for (const [id,lat,time] of [['fleet_live',9,new Date(now-1000).toISOString()],['fleet_stale',9.1,new Date(now-300000).toISOString()],['fleet_offline',9.2,new Date(now-2000).toISOString()],['fleet_bad_gps',91,new Date(now-1000).toISOString()],['fleet_future_gps',9,new Date(now+60000).toISOString()]]) await DB.prepare('INSERT INTO vehicle_live_locations(vehicle_id,latitude,longitude,updated_at) VALUES(?,?,?,?)').run(id,lat,38.7,time);
  for (const [id,company,driver,vehicle,route] of [['fleet_d1','fleet_co1','fleet_c1_driver','fleet_c1_bus','fleet_c1_route'],['fleet_d2','fleet_co2','fleet_c2_driver','fleet_c2_bus','fleet_c2_route']]) await DB.prepare('INSERT INTO intercity_departures(id,company_id,driver_id,vehicle_id,route_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)').run(id,company,driver,vehicle,route,'Addis','Hawassa',new Date().toISOString(),9,38.7,14,500,'SCHEDULED',new Date().toISOString());

  const express = require('express');
  const app = express(); app.use(express.json());
  app.use('/api/fleet', require('../src/routes/fleet-tracking'));
  app.use('/api/vehicles', require('../src/routes/vehicles'));
  const server = app.listen(0,'127.0.0.1'); await new Promise(resolve => server.once('listening',resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  async function request(url,principal,body) {
    const response = await fetch(base+url,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(principal?{Authorization:'Bearer '+tokens[principal]}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
    return { status:response.status, data:await response.json(), cache:response.headers.get('cache-control') };
  }
  await t.test('only authenticated operators may inspect the scoped fleet', async () => {
    assert.equal((await request('/api/fleet/tracking')).status,401);
    for (const id of ['fleet_passenger','fleet_commute','fleet_c1_driver']) assert.equal((await request('/api/fleet/tracking',id)).status,403);
    const admin = await request('/api/fleet/tracking','fleet_admin');
    assert.equal(admin.status,200,JSON.stringify(admin.data)); assert.equal(admin.cache,'no-store');
    assert.ok(!admin.data.vehicles.some(v=>v.id.startsWith('fleet_c1_')||v.id.startsWith('fleet_c2_')));
    const company = await request('/api/fleet/tracking','fleet_co1');
    assert.deepEqual(company.data.vehicles.map(v=>v.id).sort(),['fleet_c1_bus','fleet_c1_extra']);
    assert.deepEqual((await request('/api/fleet/tracking','fleet_co2')).data.vehicles.map(v=>v.id),['fleet_c2_bus']);
  });
  await t.test('live, stale, offline and missing GPS remain distinguishable, without legacy seeded coordinates', async () => {
    const vehicles = (await request('/api/fleet/tracking','fleet_admin')).data.vehicles;
    const get = id => vehicles.find(v=>v.id===id);
    assert.equal(get('fleet_live').location.status,'LIVE');
    assert.equal(get('fleet_live').driver.name,'fleet_commute');
    assert.equal(get('fleet_live').route.name,'fleet_commute_route');
    assert.equal(get('fleet_stale').location.status,'STALE');
    assert.equal(get('fleet_stale').location.latitude,9.1);
    assert.ok(get('fleet_stale').location.ageSeconds>=300);
    assert.equal(get('fleet_offline').location.status,'OFFLINE');
    assert.equal(get('fleet_offline').location.latitude,9.2);
    for (const id of ['fleet_no_gps','fleet_bad_gps','fleet_future_gps']) {
      assert.equal(get(id).location.status,'NO_GPS');
      assert.equal(get(id).location.latitude,null); assert.equal(get(id).location.longitude,null);
      assert.equal(get(id).location.hasGpsLocation,false);
    }
  });
  const gps = {latitude:9.03,longitude:38.75,speed:20,currentStop:'Addis'};
  await t.test('intercity GPS starts with confirmed boarding and targets the exact scheduled vehicle', async () => {
    assert.equal((await request('/api/vehicles/my-location','fleet_c1_driver',gps)).status,409);
    await DB.prepare("UPDATE intercity_departures SET status='BOARDING' WHERE id='fleet_d1'").run();
    const upload = await request('/api/vehicles/my-location','fleet_c1_driver',gps);
    assert.equal(upload.status,200,JSON.stringify(upload.data)); assert.equal(upload.data.vehicleId,'fleet_c1_bus');
    assert.equal(await DB.prepare("SELECT vehicle_id FROM vehicle_live_locations WHERE vehicle_id='fleet_c1_extra'").get(),undefined);
    const company = await request('/api/fleet/tracking','fleet_co1');
    const bus = company.data.vehicles.find(v=>v.id==='fleet_c1_bus');
    assert.equal(bus.location.status,'LIVE'); assert.equal(bus.location.latitude,gps.latitude); assert.equal(bus.trip.type,'INTERCITY');
    assert.equal((await request('/api/vehicles/my-location','fleet_c2_driver',gps)).status,409);
  });
  await t.test('bad fixes, reassigned vehicle, and completed journeys cannot publish GPS', async () => {
    for (const bad of [{latitude:null},{longitude:null},{latitude:''},{latitude:91},{longitude:-181},{speed:301}, {latitude:'NaN'}]) assert.equal((await request('/api/vehicles/my-location','fleet_c1_driver',{...gps,...bad})).status,400);
    await DB.prepare("UPDATE vehicles SET assignedRouteId=NULL WHERE id='fleet_c1_bus'").run();
    assert.equal((await request('/api/vehicles/my-location','fleet_c1_driver',gps)).status,409);
    await DB.prepare("UPDATE vehicles SET assignedRouteId='fleet_c1_route' WHERE id='fleet_c1_bus'").run();
    await DB.prepare("UPDATE intercity_departures SET status='COMPLETED' WHERE id='fleet_d1'").run();
    assert.equal((await request('/api/vehicles/my-location','fleet_c1_driver',gps)).status,409);
    const bus = (await request('/api/fleet/tracking','fleet_co1')).data.vehicles.find(v=>v.id==='fleet_c1_bus');
    assert.equal(bus.location.status,'OFFLINE'); assert.equal(bus.location.latitude,gps.latitude); assert.ok(bus.location.lastGpsAt);
  });
});
