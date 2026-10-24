'use strict';

// No environment or client request implicitly enables a free payment path.
const paymentTestsEnabled = () => String(process.env.PAYMENT_TESTS_ENABLED || '').trim().toLowerCase() === 'true';

module.exports = { paymentTestsEnabled };
