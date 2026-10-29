'use strict';
const { getReviewRuntime } = require('../review/isolation');
const SYNTHETIC_WARNING = 'Synthetic demo - no real payment; not valid for real travel.';
function ticketPresentation(booking) {
  // This is presentation only. Neither a method string nor a review marker
  // grants payment, admission, a signed ticket or a production entitlement.
  const synthetic = Boolean(getReviewRuntime()) || booking?.payment_method === 'SYNTHETIC_REVIEW';
  return {
    synthetic,
    warning: synthetic ? SYNTHETIC_WARNING : '',
    heading: synthetic ? 'INTERCITY - SYNTHETIC DEMO' : 'INTERCITY - CONFIRMED TICKET',
    payment: synthetic ? 'Synthetic demo - no real payment'
      : booking?.payment_method === 'TEST_AUTO' ? 'Authorized test - no money collected'
      : booking?.payment_method === 'CASH_COLLECTED' ? 'Cash collected by company' : 'Telebirr verified',
    boardingInstruction: synthetic ? 'Use this QR only in the synthetic demo. Not valid for real travel.'
      : 'Present this QR code to your assigned driver for boarding.'
  };
}
module.exports = { ticketPresentation, SYNTHETIC_WARNING };
