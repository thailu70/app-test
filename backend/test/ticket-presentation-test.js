'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {ticketPresentation,SYNTHETIC_WARNING}=require('../src/services/ticket-presentation');
test('real ticket presentation retains existing provider/cash/test wording',()=>{
  for(const [method,label] of [['TELEBIRR','Telebirr verified'],['CASH_COLLECTED','Cash collected by company'],['TEST_AUTO','Authorized test - no money collected']]) {
    const result=ticketPresentation({payment_method:method});
    assert.equal(result.synthetic,false);assert.equal(result.warning,'');assert.equal(result.heading,'INTERCITY - CONFIRMED TICKET');
    assert.equal(result.payment,label);assert.equal(result.boardingInstruction,'Present this QR code to your assigned driver for boarding.');
  }
});
test('a synthetic payment marker never claims real provider verification or real travel validity',()=>{
  const result=ticketPresentation({payment_method:'SYNTHETIC_REVIEW'});
  assert.equal(result.synthetic,true);assert.equal(result.warning,SYNTHETIC_WARNING);
  assert.match(result.heading,/SYNTHETIC DEMO/);assert.match(result.payment,/no real payment/);
  assert.match(result.boardingInstruction,/Not valid for real travel/);assert.doesNotMatch(result.payment,/Telebirr verified/);
});
