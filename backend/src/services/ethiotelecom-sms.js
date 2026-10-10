const crypto = require('crypto');

/**
 * Minimal Ethio Telecom SMS adapter.
 *
 * The exact endpoint and authorization header are issued for the RoutePass
 * enterprise account. This adapter keeps those provider-specific values in
 * server environment variables and never logs credentials or OTP content.
 */
async function sendEthioTelecomSms({ phone, message }) {
  const apiUrl = (process.env.ETHIOTELECOM_SMS_API_URL || '').trim();
  const apiToken = (process.env.ETHIOTELECOM_SMS_API_TOKEN || '').trim();
  if (!apiUrl || !apiToken) {
    const error = new Error('Ethio Telecom SMS is not configured on this server.');
    error.code = 'SMS_NOT_CONFIGURED';
    throw error;
  }

  const authHeader = (process.env.ETHIOTELECOM_SMS_AUTH_HEADER || 'Authorization').trim();
  const authScheme = process.env.ETHIOTELECOM_SMS_AUTH_SCHEME === undefined
    ? 'Bearer'
    : String(process.env.ETHIOTELECOM_SMS_AUTH_SCHEME);
  const sender = (process.env.ETHIOTELECOM_SMS_SENDER_ID || '').trim();
  const payloadMode = (process.env.ETHIOTELECOM_SMS_PAYLOAD_MODE || 'to-message').trim();

  let body;
  if (payloadMode === 'ethiotelecom-smn') {
    // For accounts provisioned with the Ethio Telecom developer portal's
    // documented SMS/SMN operation (POST .../notifications/sms).
    body = { endpoint: `sms:${phone}`, message };
  } else if (payloadMode === 'to-message') {
    // Use only when Ethio Telecom's enterprise integration team has issued
    // an endpoint that accepts this conventional JSON contract.
    body = { to: phone, message, ...(sender ? { sender } : {}) };
  } else {
    const error = new Error('Unsupported Ethio Telecom SMS payload mode.');
    error.code = 'SMS_CONFIGURATION_INVALID';
    throw error;
  }

  const headerValue = authScheme ? `${authScheme} ${apiToken}` : apiToken;
  let response;
  try {
    response = await fetch(apiUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [authHeader]: headerValue
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10000)
    });
  } catch (_) {
    const error = new Error('Ethio Telecom SMS service could not be reached.');
    error.code = 'SMS_PROVIDER_UNAVAILABLE';
    throw error;
  }

  if (!response.ok) {
    // Do not echo provider response text: provider diagnostics may include IDs
    // or sensitive metadata and the client must not see them.
    const error = new Error('Ethio Telecom SMS service rejected the request.');
    error.code = 'SMS_PROVIDER_REJECTED';
    throw error;
  }

  return { accepted: true };
}

function otpDigest(phone, purpose, code) {
  const key = (process.env.OTP_HASH_SECRET || process.env.JWT_SECRET || '').trim();
  if (!key) {
    const error = new Error('OTP_HASH_SECRET must be configured.');
    error.code = 'OTP_SECRET_NOT_CONFIGURED';
    throw error;
  }
  return crypto.createHmac('sha256', key).update(`${phone}:${purpose}:${code}`).digest('hex');
}

module.exports = { sendEthioTelecomSms, otpDigest };
