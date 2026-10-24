'use strict';

// Temporary account-onboarding policy requested by the operator. Phone numbers
// remain mandatory and phoneVerified remains a record of actual OTP ownership.
// Payment ownership proofs, password recovery and other OTP purposes do not use
// this policy. Transport COMPANY account verification remains mandatory.
function accountPhonePolicy(account, role = account?.role) {
  const normalizedRole = String(role || '').toUpperCase();
  const required = !['PASSENGER', 'DRIVER'].includes(normalizedRole);
  return {
    phoneVerificationRequiredForRegistration: required,
    phoneVerificationRequiredForApproval: required,
    phoneVerificationRequiredForAllocation: required
  };
}

module.exports = { accountPhonePolicy };
