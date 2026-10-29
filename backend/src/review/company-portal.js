'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { getReviewRuntime } = require('./isolation');
// Exact aliases reuse company components without exposing /admin, /shared,
// directory listings, join/signup or arbitrary files in the review stack.
const ASSETS = Object.freeze({
  '/company/': 'company/index.html', '/company/index.html': 'company/index.html',
  '/company/intercity.html': 'company/intercity.html',
  '/company/company.js': 'company/company.js', '/company/intercity.js': 'company/intercity.js',
  '/company/company.css': 'company/company.css', '/company/admin.css': 'admin/admin.css',
  '/company/applications.js': 'shared/applications.js', '/company/applications.css': 'shared/applications.css',
  '/company/fleet-map.js': 'shared/fleet-map.js', '/company/fleet-map.css': 'shared/fleet-map.css',
  '/company/review-session.js': 'company/review-session.js', '/company/review.css': 'company/review.css'
});
const CSP = "default-src 'none'; script-src https://passo.live/review/company/; style-src https://passo.live/review/company/ 'unsafe-inline'; img-src https://passo.live/review/api/ data: blob:; font-src https://passo.live/review/company/; connect-src https://passo.live/review/api/; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'";
function renderCompanyHtml(source) {
  return source
    .replace('<head>', '<head><script src="/review/company/review-session.js" defer></script><link rel="stylesheet" href="/review/company/review.css">')
    .replace(/(?:href|src)="\/(?:company\/|admin\/admin\.css|shared\/(?:applications\.(?:js|css)|fleet-map\.(?:js|css)))/g, value => value.replace(/"\//, '"/review/').replace('/review/admin/admin.css','/review/company/admin.css').replace('/review/shared/','/review/company/'))
    .replace(/<a href="\/join\/[^\"]*">[^<]*<\/a>/g, '<span>Sign in with the supplied synthetic company owner account.</span>')
    .replace(/<a href="\/travel\/"[^>]*>[^<]*<\/a>/g, '<span class="muted">Demo departures are visible in the native travel screen.</span>')
    .replace('Register an invited company →','Company workspace →')
    .replace('Issue a confirmed boarding ticket after your company has collected the payment. Cash collection is recorded separately from online Telebirr payments.','Practice issuing a synthetic ticket. No real money is collected and this ticket is not valid for real travel.')
    .replace('Payment has been collected','Simulate collection (no real money)')
    .replace('Issue paid ticket & PDF','Issue synthetic ticket & PDF')
    .replace('Settled ticket sales and boarding. Cash collected and verified online payments are included.','Synthetic ticket activity only. Amounts are demonstration values; no real cash or online payment is collected.')
    .replaceAll('Revenue · ETB','Synthetic amount · ETB').replaceAll('Revenue ETB','Synthetic amount ETB')
    .replace('<body>', '<body><aside class="review-banner" role="status"><strong>SYNTHETIC DEMO COMPANY</strong>Isolated sample accounts and journeys. No real payment, SMS or transport. Changes affect this demo only.</aside>');
}
function createCompanyPortal(config) {
  if (config !== getReviewRuntime()) throw Error('Review company portal requires the isolated runtime.');
  return async (req, res) => {
    if (!Object.hasOwn(ASSETS, req.path) || !['GET','HEAD'].includes(req.method)) return res.status(404).json({success:false,error:'Not available.'});
    let source = await fs.readFile(path.join(__dirname,'../../public',ASSETS[req.path]),'utf8');
    if (req.path.endsWith('.html') || req.path==='/company/') source=renderCompanyHtml(source);
    if (req.path==='/company/fleet-map.css') source=source.replace(/^@import[^;]+;\s*/,'');
    if (req.path==='/company/applications.js') source=source.replace(/<p class="application-share">[^\n]*<\/p>/,'<p class="application-share">Synthetic company drivers only. No recruitment messages are sent.</p>');
    res.set('Content-Security-Policy',CSP);res.set('X-Content-Type-Options','nosniff');res.set('Cross-Origin-Resource-Policy','same-origin');
    res.type(req.path.endsWith('.js')?'application/javascript':req.path.endsWith('.css')?'text/css':'text/html').send(source);
  };
}
module.exports={createCompanyPortal,renderCompanyHtml,ASSETS,CSP};
