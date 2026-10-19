(()=>{const $=s=>document.querySelector(s),key='passo_company_token';let token=sessionStorage.getItem(key)||'',company={},mapCleanup=null,currentView='overview';
const esc=v=>String(v??'—').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function api(p,o={}){
  const controller=new AbortController();let timedOut=false;
  const timeout=setTimeout(()=>{timedOut=true;controller.abort();},12000);
  try{
    const h={'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})};
    const r=await fetch('/api'+p,{...o,headers:h,signal:controller.signal,cache:'no-store'});
    const b=await r.json().catch(e=>{if(e.name==='AbortError')throw e;return {};});
    if(timedOut)throw Error('The request timed out. Please retry.');
    if(r.status===401&&token&&!p.endsWith('/login')){logout('Your session expired. Please sign in again.');throw Error('Your session expired. Please sign in again.');}
    if(!r.ok)throw Error(b.error||('Could not load data (HTTP '+r.status+'). Please retry.'));
    return b;
  }catch(e){if(timedOut||e.name==='AbortError')throw Error('The request timed out. Please retry.');throw e;}
  finally{clearTimeout(timeout);}
}
function msg(id,text,error=false){const el=$(id);el.textContent=text;el.className=error?'message error':'message'}
async function auth(path,form){try{const b=await api(path,{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(form)))});token=b.token;company=b.user||{};sessionStorage.setItem(key,token);$('#login').classList.add('hidden');$('#dash').classList.remove('hidden');showView(currentView);await load()}catch(e){msg('#loginMsg',e.message,true)}}
$('#signin').onsubmit=e=>{e.preventDefault();auth('/companies/login',e.target)};$('#register').onsubmit=e=>{e.preventDefault();auth('/companies/register',e.target)};
let drivers=[],vehicles=[],routes=[],assignments=[],bookings=[],roster=[];
const resources={
  profile:{path:'/companies/me'},summary:{path:'/companies/operations/summary'},
  fleet:{path:'/companies/operations/fleet'},drivers:{path:'/companies/operations/drivers'},
  routes:{path:'/companies/operations/routes'},bookings:{path:'/companies/operations/bookings'}
};
function feedback(id,names){
  const el=$(id),failed=names.filter(n=>resources[n].error);
  el.innerHTML=failed.length?'<div>'+failed.map(n=>esc(n.charAt(0).toUpperCase()+n.slice(1))+': '+esc(resources[n].error)+(resources[n].data?' Last loaded data is still shown.':'')).join('<br>')+'</div><button class="retry" data-retry="'+failed.join(',')+'">Retry</button>':'';
}
function preserveOptions(id,html){const el=$(id),value=el.value;el.innerHTML=html;if(Array.from(el.options||[]).some(o=>o.value===value))el.value=value;}
async function load(only){
  const requestedToken=token;if(!requestedToken)return;
  $('#refresh').disabled=true;$('#portalStatus').textContent='Updating your operations…';
  try{
    const names=Array.isArray(only)?only:Object.keys(resources);
    await Promise.allSettled(names.map(async name=>{
      const item=resources[name];
      try{const data=await api(item.path);if(token!==requestedToken)return;item.data=data;item.error='';}
      catch(e){if(token===requestedToken)item.error=e.message;}
    }));
    if(token!==requestedToken)return;
    const me=resources.profile.data||{},s=resources.summary.data||{},f=resources.fleet.data||{},d=resources.drivers.data||{},rr=resources.routes.data||{},b=resources.bookings.data||{};
    company=me.company||company;$('#companyName').textContent=company.name||company.companyName||'Company';
    drivers=d.drivers||f.drivers||drivers;vehicles=f.vehicles||vehicles;routes=rr.routes||routes;assignments=rr.routes||assignments;bookings=b.bookings||bookings;roster=b.roster||roster;
    feedback('#overviewStatus',Object.keys(resources));feedback('#driversStatus',['drivers']);feedback('#fleetStatus',['fleet']);feedback('#routesStatus',['routes']);feedback('#assignmentsStatus',['routes','drivers','fleet']);feedback('#bookingsStatus',['bookings']);
    const errors=Object.keys(resources).filter(n=>resources[n].error);
    $('#portalStatus').textContent=errors.length?'Some data could not load. Open a section to retry, or use Refresh data.':'Updated '+new Date().toLocaleTimeString();
$('#pendingCount').textContent=drivers.filter(x=>x.status==='PENDING').length;$('#driversCount').textContent=drivers.filter(x=>x.status==='ACTIVE').length;$('#vehiclesCount').textContent=vehicles.length;$('#assignmentsCount').textContent=s.summary?.activeAssignments??'—';$('#bookingCount').textContent=bookings.length;
$('#driverRows').innerHTML=drivers.map(d=>'<tr><td>'+esc(d.fullName)+'</td><td>'+esc(d.phone)+' <button class="small" data-edit-driver-phone="'+esc(d.id)+'">Edit</button></td><td>'+esc(d.licenseNumber)+'</td><td><span class="pill">'+esc(d.status)+'</span></td><td><button class="small" data-toggle-driver="'+esc(d.id)+'" data-driver-status="'+(d.status==='ACTIVE'?'SUSPENDED':'ACTIVE')+'">'+(d.status==='ACTIVE'?'Deactivate':'Reactivate')+'</button> <button class="small danger" data-delete-driver="'+esc(d.id)+'">Delete</button>'+(d.status==='PENDING'?'<br><button class="small" data-approve="'+esc(d.id)+'">Approve</button><button class="small danger" data-reject="'+esc(d.id)+'">Reject</button>':'')+'</td></tr>').join('')||'<tr><td colspan="5">No company driver applications yet.</td></tr>';
$('#fleetRows').innerHTML=vehicles.map(v=>'<tr><td>'+esc(v.plateNumber)+'</td><td>'+esc(v.model)+'</td><td>'+esc(v.vehicleType)+' / '+esc(v.capacityLimit)+'</td><td>'+esc(drivers.find(d=>d.id===v.driverId)?.fullName||v.driverName||'Unassigned')+'</td><td>'+esc(v.routeName||'Unassigned')+'</td><td>'+esc(v.status)+'</td><td>'+esc(v.lastGpsAt||'No GPS report')+'</td></tr>').join('')||'<tr><td colspan="7">No vehicles registered yet.</td></tr>';
$('#routeRows').innerHTML=routes.map(r=>'<tr><td>'+esc(r.name)+'<br><span class="muted">'+esc(r.nameAm||'')+'</span><br><span class="pill">'+esc(r.routeType||r.route_type||'DAILY_COMMUTE')+'</span></td><td>'+esc(r.origin||'—')+' → '+esc(r.destination||'—')+'</td><td>'+esc((r.routeType==='INTERCITY'||r.route_type==='INTERCITY')?(r.departureTime||r.departure_time||r.morningDeparture):(r.morningDeparture+' / '+r.eveningDeparture))+'</td><td>'+esc(r.distanceKm)+' km</td><td>'+esc(r.basePriceEtb)+' ETB</td><td>'+esc(r.active?'Active':'Inactive')+'</td><td><button class="small secondary" data-edit-route="'+esc(r.id)+'">Edit</button></td></tr>').join('')||'<tr><td colspan="7">No company routes created yet.</td></tr>';
preserveOptions('#assignRoute','<option value="">Select company route</option>'+routes.filter(r=>r.active).map(r=>'<option value="'+esc(r.id)+'">'+esc(r.name)+'</option>').join(''));
preserveOptions('#assignDriver','<option value="">Approved company driver</option>'+drivers.filter(d=>d.status==='ACTIVE').map(d=>'<option value="'+esc(d.id)+'">'+esc(d.fullName)+' · '+esc(d.phone)+'</option>').join(''));
preserveOptions('#assignVehicle','<option value="">Company vehicle</option>'+vehicles.map(v=>'<option value="'+esc(v.id)+'">'+esc(v.plateNumber)+' · '+esc(v.model)+'</option>').join(''));
$('#assignmentRows').innerHTML=assignments.map(a=>'<tr><td>'+esc(a.name)+'</td><td>'+esc(drivers.find(d=>d.id===a.driver_id)?.fullName||a.driver_id||'Unassigned')+'</td><td>'+esc(vehicles.find(v=>v.id===a.vehicle_id)?.plateNumber||a.vehicle_id||'Unassigned')+'</td><td>'+esc(a.assignment_status||'Route created')+'</td><td>'+(a.assignment_status==='ACTIVE'&&a.assignment_id?'<button class="small danger" data-unassign="'+esc(a.assignment_id)+'">Unassign</button>':'—')+'</td></tr>').join('')||'<tr><td colspan="5">No route assignments yet.</td></tr>';
$('#bookingRows').innerHTML=bookings.map(b=>'<tr><td>'+esc(b.passenger_name)+'</td><td>'+esc(b.passenger_phone)+'</td><td>'+esc(b.route_name)+'</td><td>'+esc(b.pickup_stop_id)+' → '+esc(b.destination_stop_id)+'</td><td>'+esc(b.subscription_status)+'</td><td>'+esc(b.payment_status)+'</td><td>'+esc(b.days_remaining)+'</td><td>'+esc(b.vehicle_plate||'Not assigned')+' '+esc(b.vehicle_model||'')+'</td></tr>').join('')||'<tr><td colspan="8">No bookings on assigned company routes.</td></tr>';
$('#rosterRows').innerHTML=roster.map(r=>'<tr><td>'+esc(r.passenger_name)+'</td><td>'+esc(routes.find(x=>x.id===r.route_id)?.name||r.route_id)+'</td><td>'+esc(r.stop_name)+'</td><td>'+esc(r.status)+'</td><td>'+esc(r.trip_id)+'</td><td>'+esc(r.timestamp)+'</td></tr>').join('')||'<tr><td colspan="6">No passenger boarding records yet.</td></tr>';
}catch(e){$('#portalStatus').textContent='Could not refresh operations: '+e.message}finally{$('#refresh').disabled=false}}
$('#driverRows').onclick=async e=>{
 const id=e.target.dataset.approve||e.target.dataset.reject;
 if(id){try{await api('/companies/operations/drivers/'+encodeURIComponent(id)+'/approval',{method:'PATCH',body:JSON.stringify({action:e.target.dataset.approve?'APPROVE':'REJECT'})});await load()}catch(x){msg('#driverMsg',x.message,true)}return;}
 const phoneId=e.target.dataset.editDriverPhone;
 if(phoneId){const driver=drivers.find(d=>String(d.id)===String(phoneId)),phone=window.prompt('New driver phone number',driver?.phone||'');if(phone===null)return;try{await api('/companies/operations/drivers/'+encodeURIComponent(phoneId)+'/contact',{method:'PATCH',body:JSON.stringify({phone:phone.trim()})});msg('#driverMsg','Driver phone number updated.');await load()}catch(x){msg('#driverMsg',x.message,true)}return;}
 const toggleId=e.target.dataset.toggleDriver;
 if(toggleId){const status=e.target.dataset.driverStatus;if(status==='SUSPENDED'&&!window.confirm('Deactivate this driver? They will not be able to sign in.'))return;try{await api('/companies/operations/drivers/'+encodeURIComponent(toggleId)+'/approval',{method:'PATCH',body:JSON.stringify({action:status==='ACTIVE'?'APPROVE':'REJECT'})});msg('#driverMsg',status==='ACTIVE'?'Driver reactivated.':'Driver deactivated.');await load()}catch(x){msg('#driverMsg',x.message,true)}return;}
 const deleteId=e.target.dataset.deleteDriver;
 if(deleteId){if(!window.confirm('Delete this driver account? This removes login access and active assignments.'))return;try{await api('/companies/operations/drivers/'+encodeURIComponent(deleteId),{method:'DELETE'});msg('#driverMsg','Driver account deleted.');await load()}catch(x){msg('#driverMsg',x.message,true)}}
};
$('#vehicleForm').onsubmit=async e=>{e.preventDefault();try{const b=await api('/companies/operations/vehicles',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(e.target)))});msg('#vehicleMsg','Vehicle registered: '+b.vehicle.plateNumber);e.target.reset();await load()}catch(x){msg('#vehicleMsg',x.message,true)}};
function syncRouteType(){const type=$('#routeType').value,intercity=type==='INTERCITY';$('#singleDepartureWrap').classList.toggle('hidden',!intercity);$('#morningDepartureWrap').classList.toggle('hidden',intercity);$('#eveningDepartureWrap').classList.toggle('hidden',intercity);$('#departureTime').required=intercity;$('#morningDeparture').required=!intercity;$('#eveningDeparture').required=!intercity}$('#routeType').onchange=syncRouteType;syncRouteType();
$('#routeForm').onsubmit=async e=>{e.preventDefault();const data=Object.fromEntries(new FormData(e.target)),id=data.routeId;delete data.routeId;if(data.routeType==='INTERCITY'){data.morningDeparture=data.departureTime;data.eveningDeparture=data.departureTime}else{data.departureTime=data.morningDeparture}try{await api('/companies/operations/routes'+(id?'/'+encodeURIComponent(id):''),{method:id?'PUT':'POST',body:JSON.stringify(data)});msg('#routeMsg',id?'Route updated.':'Route created.');e.target.reset();$('#routeForm [name=routeId]').value='';$('#routeType').value='INTERCITY';$('#departureTime').value='08:00';$('#morningDeparture').value='06:30';$('#eveningDeparture').value='17:30';syncRouteType();$('#routeSave').textContent='Create route';await load()}catch(x){msg('#routeMsg',x.message,true)}};
$('#routeRows').onclick=e=>{const id=e.target.dataset.editRoute;if(!id)return;const r=routes.find(x=>x.id===id);if(!r)return;$('#routeType').value=r.routeType||r.route_type||'DAILY_COMMUTE';for(const k of ['name','nameAm','description','origin','destination','distanceKm','basePriceEtb'])$('#routeForm [name='+k+']').value=r[k]??'';$('#routeForm [name=departureTime]').value=r.departureTime||r.departure_time||r.morningDeparture||'08:00';$('#routeForm [name=morningDeparture]').value=r.morningDeparture||'06:30';$('#routeForm [name=eveningDeparture]').value=r.eveningDeparture||'17:30';$('#routeForm [name=routeId]').value=id;syncRouteType();$('#routeSave').textContent='Save route changes';showView('routes')};
$('#routeCancel').onclick=()=>{$('#routeForm').reset();$('#routeForm [name=routeId]').value='';$('#routeType').value='INTERCITY';$('#departureTime').value='08:00';$('#morningDeparture').value='06:30';$('#eveningDeparture').value='17:30';syncRouteType();$('#routeSave').textContent='Create route'};
$('#assignForm').onsubmit=async e=>{e.preventDefault();try{await api('/companies/operations/assignments',{method:'POST',body:JSON.stringify({routeId:$('#assignRoute').value,driverId:$('#assignDriver').value,vehicleId:$('#assignVehicle').value})});msg('#assignMsg','Driver, vehicle and route assigned successfully.');await load()}catch(x){msg('#assignMsg',x.message,true)}};
$('#assignmentRows').onclick=async e=>{const id=e.target.dataset.unassign;if(!id)return;try{await api('/companies/operations/assignments/'+encodeURIComponent(id),{method:'DELETE'});msg('#assignMsg','Assignment removed. The vehicle remains in your company fleet.');await load()}catch(x){msg('#assignMsg',x.message,true)}};
const views={
  overview:['overviewSection','Overview','Your company at a glance'],
  drivers:['driverSection','Drivers','Driver approvals'],
  vehicles:['vehicleSection','Vehicles','Your vehicle fleet'],
  routes:['routeSection','Manage routes','Company routes'],
  assignments:['assignSection','Assignments','Drivers, vehicles and routes'],
  bookings:['bookingSection','Bookings','Passenger bookings'],
  gps:['gpsSection','Driver GPS','Track your drivers']
};
function showView(name){
  currentView=views[name]?name:'overview';
  for(const [view,[id]] of Object.entries(views))$('#'+id).classList.toggle('hidden',view!==currentView);
  document.querySelectorAll('[data-view]').forEach(el=>{el.classList.toggle('active',el.dataset.view===currentView);el.setAttribute('aria-current',el.dataset.view===currentView?'page':'false');});
  $('#page-title').textContent=views[currentView][1];$('#view-title').textContent=views[currentView][2];
  if(mapCleanup){mapCleanup();mapCleanup=null;}
  if(currentView==='gps'&&token&&typeof window!=='undefined'&&window.RoutepassFleetMap)mapCleanup=window.RoutepassFleetMap.mount({container:$('#companyFleetMap'),apiUrl:'/api/fleet/tracking',token});
}
document.querySelectorAll('[data-view],[data-open-view]').forEach(el=>el.onclick=()=>{const name=el.dataset.view||el.dataset.openView;showView(name);if(typeof history!=='undefined')history.replaceState(null,'','#'+name);});
for(const id of ['overviewStatus','driversStatus','fleetStatus','routesStatus','assignmentsStatus','bookingsStatus'])$('#'+id).onclick=e=>{const retry=e.target.closest?.('[data-retry]');if(retry)load(retry.dataset.retry.split(','));};
function logout(reason){
  token='';sessionStorage.removeItem(key);if(mapCleanup){mapCleanup();mapCleanup=null;}
  Object.values(resources).forEach(r=>{delete r.data;delete r.error;});
  drivers=[];vehicles=[];routes=[];assignments=[];bookings=[];roster=[];company={};
  for(const id of ['driverRows','fleetRows','routeRows','assignmentRows','bookingRows','rosterRows','overviewStatus','driversStatus','fleetStatus','routesStatus','assignmentsStatus','bookingsStatus'])$('#'+id).innerHTML='';
  for(const id of ['pendingCount','driversCount','vehiclesCount','assignmentsCount','bookingCount'])$('#'+id).textContent='—';
  $('#companyName').textContent='Company';$('#portalStatus').textContent='';
  $('#dash').classList.add('hidden');$('#login').classList.remove('hidden');if(reason)msg('#loginMsg',reason,true);
}
$('#logout').onclick=()=>logout();$('#mobileLogout').onclick=()=>logout();$('#refresh').onclick=()=>load();
if(typeof location!=='undefined')currentView=location.hash.slice(1)||'overview';
if(typeof window!=='undefined')window.addEventListener('hashchange',()=>showView(location.hash.slice(1)));
if(token){$('#login').classList.add('hidden');$('#dash').classList.remove('hidden');showView(currentView);load();}
})();
