/* Loaded ONLY by the isolated review company renderer, before any portal code. */
(() => {
  'use strict';
  if (location.origin !== 'https://passo.live' || !location.pathname.startsWith('/review/company/')) throw Error('Review company origin mismatch.');
  const apiBase = '/review/api', originalFetch = window.fetch.bind(window);
  const bridge = window.PassoReviewSession;
  let memoryToken = '';
  // Do not even read local/sessionStorage: Android disables DOM storage for
  // this WebView. The optional native bridge is ephemeral and absent in Live.
  const getToken = () => {
    try { return bridge ? String(bridge.getToken() || '') : memoryToken; } catch (_) { return ''; }
  };
  const setToken = value => {
    if (typeof value !== 'string' || value.length > 8192) return false;
    try { if (bridge) return bridge.setToken(value) === true; memoryToken = value; return true; } catch (_) { return false; }
  };
  const clearToken = () => {
    memoryToken = '';
    try { return bridge ? bridge.clearToken() === true : true; } catch (_) { return false; }
  };
  function apiUrl(input) {
    if (typeof input !== 'string') throw Error('Unsupported demo request.');
    if (/%2f|%5c|%2e/i.test(input.split('?')[0])) throw Error('Demo requests cannot leave the isolated review API.');
    // Existing shared company components use root-relative API paths.
    const candidate = input.startsWith('/api/') ? '/review' + input : input;
    const url = new URL(candidate, location.href);
    if (url.origin !== 'https://passo.live' || url.username || url.password || !url.pathname.startsWith('/review/api/') || /%2f|%5c|%2e/i.test(url.pathname)) throw Error('Demo requests cannot leave the isolated review API.');
    return url.href;
  }
  const reviewFetch = (input, options = {}) => originalFetch(apiUrl(input), {
    ...options, credentials: 'omit', redirect: 'error', mode: 'same-origin', referrerPolicy: 'no-referrer', cache: 'no-store'
  });
  const openDocument = (input, filename = 'PASSO-synthetic-document') => {
    const url = new URL(apiUrl(input));
    const application = /^\/review\/api\/onboarding\/applications\/DRIVER\/[a-zA-Z0-9_-]{1,100}\/documents\/(?:LICENSE|VEHICLE_REGISTRATION|INSURANCE|PHOTO)$/.test(url.pathname);
    const companyTicket = /^\/review\/api\/intercity\/company\/bookings\/[a-zA-Z0-9_-]{1,100}\/ticket\.pdf$/.test(url.pathname);
    const capabilityTicket = /^\/review\/api\/intercity\/tickets\/[a-zA-Z0-9_-]{1,100}\.pdf$/.test(url.pathname) && [...url.searchParams.keys()].join(',') === 'token' && /^[\w-]+\.[\w-]+\.[\w-]+$/.test(url.searchParams.get('token') || '') && (url.searchParams.get('token') || '').length <= 4096;
    if (url.hash || !((application || companyTicket) && !url.search || capabilityTicket) || typeof filename !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/.test(filename) || filename.includes('..')) throw Error('Only a scoped synthetic document or ticket can be opened with a safe filename.');
    try { return bridge && typeof bridge.openDocument === 'function' ? bridge.openDocument(url.pathname + url.search, filename) === true : false; } catch (_) { return false; }
  };
  const exportCsv = csv => {
    if (typeof csv !== 'string' || new TextEncoder().encode(csv).byteLength > 2 * 1024 * 1024) throw Error('The synthetic report is too large to export. Narrow the dates and retry.');
    try { return bridge && typeof bridge.exportCsv === 'function' ? bridge.exportCsv(csv) === true : false; } catch (_) { return false; }
  };
  Object.defineProperty(window, 'fetch', { value: reviewFetch, writable: false, configurable: false });
  Object.defineProperty(window, 'PassoCompanyEnvironment', { value: Object.freeze({ review: true, apiBase, getToken, setToken, clearToken, apiUrl, openDocument, exportCsv }), writable: false });
  document.querySelectorAll('a[href^="/join/"]').forEach(link => { link.removeAttribute('href'); link.textContent = 'Demo uses the supplied company account.'; });
  const register = document.getElementById('register');
  if (register) { register.hidden = true; register.querySelectorAll('input,button').forEach(el => { el.disabled = true; }); }
})();
