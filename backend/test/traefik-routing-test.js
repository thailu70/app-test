'use strict';
// Validate the rendered Compose output, including its escaped redirect capture:
// docker compose -f backend/docker-compose.yml -f backend/docker-compose.traefik.yml config --format json | node backend/test/traefik-routing-test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const config = JSON.parse(fs.readFileSync(0, 'utf8'));
const primary = process.env.ROUTEPASS_DOMAIN;
const legacy = process.env.ROUTEPASS_LEGACY_DOMAIN || primary;
assert.ok(primary, 'Set ROUTEPASS_DOMAIN for rendered routing checks.');
const labels = config.services.api.labels;
function router(name, hostname) {
  const prefix = 'traefik.http.routers.' + name + '.';
  assert.equal(labels[prefix + 'rule'], 'Host(`' + hostname + '`)');
  assert.equal(labels[prefix + 'entrypoints'], 'websecure');
  assert.equal(labels[prefix + 'tls'], 'true');
  assert.equal(labels[prefix + 'tls.certresolver'], 'letsencrypt');
  assert.equal(labels[prefix + 'service'], 'routepass-api');
}
router('routepass-api', primary);
router('routepass-www', 'www.' + primary);
router('routepass-legacy', legacy);
assert.equal(labels['traefik.http.routers.routepass-www.middlewares'], 'routepass-canonical');
assert.equal(labels['traefik.http.routers.routepass-api.middlewares'], undefined, 'The apex must not redirect to itself.');
assert.equal(labels['traefik.http.routers.routepass-legacy.middlewares'], undefined, 'Installed legacy clients retain their original API host.');
const redirect = 'traefik.http.middlewares.routepass-canonical.redirectregex.';
assert.equal(labels[redirect + 'permanent'], 'true');
// Compose versions may re-escape literal dollars when serializing the reusable
// config document. Normalize that serialization before checking Traefik expansion.
const renderedReplacement = labels[redirect + 'replacement'];
assert.ok(['https://' + primary + '${1}', 'https://' + primary + '$${1}'].includes(renderedReplacement), 'Compose must retain exactly one redirect capture.');
const replacement = renderedReplacement.replace(/\$\$/g, '$');
const regex = new RegExp(labels[redirect + 'regex']);
for (const path of ['/', '/join/?role=driver', '/travel/?booking=private-capability&return=%2Fhome']) {
  const source = 'https://www.' + primary + path;
  const canonical = source.replace(regex, (_match, suffix) => replacement.replace('${1}', suffix));
  assert.equal(canonical, 'https://' + primary + path, 'Canonical redirects preserve the full path and query.');
}
assert.equal(labels['traefik.http.services.routepass-api.loadbalancer.server.port'], '3000');
assert.ok(!config.services.api.ports?.length, 'The API remains private behind Traefik.');
assert.ok(!config.services.db.ports?.length, 'PostgreSQL remains private.');
assert.ok(!config.services.nginx || config.services.nginx.profiles?.includes('nginx-proxy'), 'Bundled Nginx remains absent or opt-in.');
console.log('PASS: independent apex/www/legacy TLS routers, canonical redirect capture and private services');
