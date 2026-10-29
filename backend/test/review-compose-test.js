'use strict';
// Feed only the resolved *review* Compose configuration on stdin.
const assert=require('node:assert/strict');
let input='';process.stdin.setEncoding('utf8');process.stdin.on('data',x=>input+=x);process.stdin.on('end',()=>{
  const c=JSON.parse(input),api=c.services['review-api'],db=c.services['review-db'],proxy=c.services['review-proxy'];
  assert.deepEqual(Object.keys(c.services).sort(),['review-api','review-db','review-proxy']);
  for(const service of [api,db]) {
    assert.deepEqual(Object.keys(service.networks),['review_private']);
    assert.equal(service.ports,undefined);
    assert.ok(service.mem_limit<=384*1024*1024);assert.ok(Number(service.cpus)<=0.5);
    assert.ok(!(service.volumes||[]).some(v=>/routepass_(postgres|uploads)|docker\.sock|\.env$/.test(v.source)));
  }
  assert.equal(c.networks.review_private.internal,true);
  assert.equal(c.networks.public_proxy.name,'routepass_internal_net');assert.ok(Object.keys(proxy.networks).includes('public_proxy'));
  assert.equal(api.environment.REVIEW_ENVIRONMENT,'true');assert.equal(api.environment.PAYMENT_TESTS_ENABLED,'false');
  assert.equal(api.environment.PUBLIC_BASE_URL,'https://passo.live/review');
  for(const key of Object.keys(api.environment))assert.equal(/^(TELEBIRR_|AFRO_SMS_)/.test(key),false);
  assert.match(proxy.labels['traefik.http.routers.passo-review.rule'],/PathPrefix\(`\/review\/`\)/);
  assert.equal(proxy.labels['traefik.http.middlewares.passo-review-strip.stripprefix.prefixes'],'/review');
  assert.ok(api.command.includes('src/review/server.js'));assert.equal(api.read_only,true);
  console.log('Review Compose passed: private DB/API, independent volumes, no provider/production credentials, explicit prefix, bounded resources.');
});
