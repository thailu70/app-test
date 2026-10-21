'use strict';

// Public callbacks use operator configuration, never an untrusted Host/forwarded header.
function publicBaseUrl() {
  const value = String(process.env.PUBLIC_BASE_URL || 'https://passo.live').trim();
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw new Error('PUBLIC_BASE_URL must be an HTTPS origin without credentials, a path or query.');
  return url.origin;
}

function httpsCallbackUrl(value) {
  let url;
  try { url = new URL(String(value).trim()); }
  catch (_) { throw new Error('Payment callback must be a valid HTTPS URL.'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash)
    throw new Error('Payment callback must use HTTPS without credentials or a fragment.');
  return url.href;
}

module.exports = { publicBaseUrl, httpsCallbackUrl };
