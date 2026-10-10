const crypto = require('crypto');

function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

async function sendEthioTelecomSms(phone, message) {
  const url = process.env.ETHIO_TELECOM_SMS_API_URL;
  const token = process.env.ETHIO_TELECOM_SMS_API_TOKEN;
  const sender = process.env.ETHIO_TELECOM_SMS_SENDER_ID;
  if (!url || !token || !sender) {
    const err = new Error('Ethio Telecom SMS is not configured.');
    err.code = 'SMS_NOT_CONFIGURED';
    throw err;
  }
  // Use the approved Ethio Telecom gateway endpoint and payload contract supplied to the operator.
  // Credentials stay on the server and are never returned to the client or logged.
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ to: phone, sender, message }),
    signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) {
    const err = new Error(`SMS provider returned HTTP ${response.status}`);
    err.code = 'SMS_PROVIDER_ERROR';
    throw err;
  }
}

module.exports = { sha256, sendEthioTelecomSms };
