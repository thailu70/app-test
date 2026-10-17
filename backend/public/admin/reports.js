(() => {
  const esc = v => String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function markup(){return `<section class="card section-card" id="performance-report"><h3>Performance over time</h3><div class="inline-actions"><label>Period<select id="report-period"><option value="daily">Daily · 30 days</option><option value="weekly">Weekly · 12 weeks</option><option value="monthly">Monthly · 12 months</option></select></label><label>Sort report<select id="report-sort"><option value="date">Date</option><option value="trips">Trips</option><option value="boardings">Boardings</option><option value="revenue">Revenue</option><option value="passengers">New passengers</option></select></label><label>Order<select id="report-order"><option value="asc">Ascending</option><option value="desc">Descending</option></select></label></div><p id="report-status" role="status"></p><div id="report-graphs" class="report-graphs"></div><div id="report-table" class="table-wrap"></div></section>`;}
  async function load(api){
    const root=document.querySelector('#performance-report');if(!root)return;
    const period=root.querySelector('#report-period'),sort=root.querySelector('#report-sort'),order=root.querySelector('#report-order');
    for(const control of [period,sort,order])control.onchange=()=>load(api);
    root.querySelector('#report-status').textContent='Loading performance…';
    try{
      const result=await api('/api/admin/reports?'+new URLSearchParams({period:period.value,sort:sort.value,order:order.value}));
      if(!root.isConnected)return;
      const rows=result.rows||[], chronological=[...rows].sort((a,b)=>a.date.localeCompare(b.date));
      root.querySelector('#report-status').textContent='Addis Ababa time · Actual recorded events · Grants excluded from revenue';
      root.querySelector('#report-graphs').innerHTML=['trips','boardings','revenue','passengers'].map(key=>`<div><h4>${esc(key==='passengers'?'New passengers':key==='revenue'?'Revenue (ETB)':key)}</h4><strong>${Number(result.totals[key]||0).toLocaleString()}</strong><canvas data-metric="${key}" height="150" role="img" aria-label="${key} performance; values are in the report table below"></canvas></div>`).join('');
      for(const canvas of root.querySelectorAll('canvas')){
        const width=canvas.clientWidth||300;canvas.width=width*2;canvas.height=300;const c=canvas.getContext('2d');c.scale(2,2);const key=canvas.dataset.metric,max=Math.max(1,...chronological.map(r=>Number(r[key]))),gap=width/Math.max(1,chronological.length);c.fillStyle='#087f68';
        chronological.forEach((r,i)=>{const h=Number(r[key])/max*110;c.fillRect(i*gap+1,130-h,Math.max(1,gap-2),h);});
        c.fillStyle='#52635e';c.font='11px system-ui';if(chronological.length){c.fillText(chronological[0].date,0,148);c.textAlign='right';c.fillText(chronological.at(-1).date,width,148);}
      }
      root.querySelector('#report-table').innerHTML='<table><thead><tr><th>Period</th><th>Trips</th><th>Boardings</th><th>Revenue (ETB)</th><th>New passengers</th></tr></thead><tbody>'+rows.map(r=>'<tr>'+['date','trips','boardings','revenue','passengers'].map(k=>'<td>'+esc(r[k])+'</td>').join('')+'</tr>').join('')+'</tbody></table>';
    }catch(e){if(root.isConnected)root.querySelector('#report-status').textContent=e.message;}
  }
  window.RoutepassReports={markup,load};
})();
