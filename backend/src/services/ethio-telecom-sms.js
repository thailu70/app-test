const crypto = require('crypto');

function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function config() {
  const token = process.env.AFROMESSAGE_API_TOKEN;
  const identifierId = process.env.AFROMESSAGE_IDENTIFIER_ID;
  const senderName = process.env.AFROMESSAGE_SENDER_NAME;
  if (!token || !identifierId || !senderName) {
    const err = new Error('AfroMessage SMS configuration is incomplete.');
    err.code = 'SMS_NOT_CONFIGURED';
    throw err;
  }
  return { token, identifierId, senderName };
}

async function afroMessageGet(path, params) {
  const { token } = config();
  const url = new URL(`https://api.afromessage.com/api/${path}`);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value));
  }
  const response = await fetch(url, {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(12000)
  });
  let payload;
  try { payload = await response.json(); } catch (_) { payload = null; }
  if (!response.ok || !payload || payload.acknowledge !== 'success') {
    const err = new Error(`AfroMessage API request failed (HTTP ${response.status}).`);
    err.code = response.status === 401 || response.status === 403 ? 'SMS_PROVIDER_AUTH_ERROR' : 'SMS_PROVIDER_ERROR';
    throw err;
  }
  return payload;
}

async function requestAfroMessageOtp(phone) {
  const { identifierId, senderName } = config();
  const payload = await afroMessageGet('challenge', {
    from: identifierId,
    sender: senderName,
    to: phone,
    pr: 'Your RoutePass verification code is',
    ps: '. It expires in 5 minutes. Do not share it.',
    ttl: 300,
    len: 6,
    t: 0
  });
  const data = payload.data || payload.result || payload;
  const verificationId = data.verification_id || data.verificationId ||
    data.verificationID || data.vc || data.id;
  if (!verificationId || (typeof verificationId !== 'string' && typeof verificationId !== 'number')) {
    const err = new Error('AfroMessage challenge response did not include a verification ID.');
    err.code = 'SMS_PROVIDER_RESPONSE_ERROR';
    throw err;
  }
  return String(verificationId);
}

async function verifyAfroMessageOtp(phone, verificationId, code) {
  if (!verificationId) return false;
  const payload = await afroMessageGet('verify', { to: phone, vc: verificationId, code });
  return payload.acknowledge === 'success';
}

module.exports = { sha256, requestAfroMessageOtp, verifyAfroMessageOtp };
