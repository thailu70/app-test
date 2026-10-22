'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let token = '', generation = 0;
  function status(message, error = false) { $('status').textContent = message; $('status').classList.toggle('error', error); }
  function list(id, items) { $(id).replaceChildren(...items.map(text => { const li = document.createElement('li'); li.textContent = text; return li; })); }
  async function api(url, method = 'GET', body, bearer = token) {
    const response = await fetch(url, { method, credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const result = await response.json();
    if (!response.ok || !result.success) { const error = new Error(result.error || 'This request could not be completed.'); error.status = response.status; throw error; }
    return result;
  }
  function signOut() { generation++; token = ''; $('login-form').reset(); $('delete-form').reset(); $('review').hidden = true; $('sign-in').hidden = false; }
  $('cancel-button').addEventListener('click', () => { signOut(); status('Signed out. Your account has not been changed.'); });
  $('login-form').addEventListener('submit', async event => {
    event.preventDefault(); $('login-button').disabled = true; status('Checking your account…');
    const current = ++generation;
    const input = { role: $('role').value, phone: $('phone').value.trim(), password: $('password').value };
    try {
      let result;
      try { result = await api(input.role === 'COMPANY' ? '/api/companies/login' : '/api/auth/login', 'POST', input, ''); }
      catch (error) {
        if (!['DRIVER','COMPANY'].includes(input.role) || ![401,403].includes(error.status)) throw error;
        result = await api('/api/onboarding/login', 'POST', input, '');
      }
      if (current !== generation) return;
      token = result.token; $('password').value = '';
      const { deletion } = await api('/api/account/deletion');
      if (current !== generation) return;
      list('consequences', deletion.consequences); list('retained', deletion.retainedCategories);
      $('blockers').textContent = deletion.blockers.map(item => item.message).join(' ');
      $('blockers').hidden = deletion.eligible; $('delete-form').hidden = !deletion.eligible;
      $('review').hidden = false; $('sign-in').hidden = true;
      status(deletion.eligible ? 'Review these details before confirming.' : 'A transport handover is needed. Contact the privacy team to request closure.');
    } catch (error) { token = ''; $('password').value = ''; status(error.message, true); }
    finally { input.password = ''; $('login-button').disabled = false; }
  });
  $('delete-form').addEventListener('submit', async event => {
    event.preventDefault(); if ($('confirmation').value !== 'DELETE') return;
    $('delete-button').disabled = true; status('Closing your account…');
    try {
      const { deletion } = await api('/api/account/deletion', 'POST', { password: $('reauth').value, confirmation: 'DELETE' });
      token = ''; $('delete-form').reset(); $('review').hidden = true; $('receipt').hidden = false;
      $('receipt-message').textContent = deletion.message;
      $('receipt-id').textContent = 'Receipt: ' + deletion.id + ' · ' + deletion.status;
      status('Signed out. Account access has been revoked.');
    } catch (error) { $('reauth').value = ''; status(error.message, true); }
    finally { $('delete-button').disabled = false; }
  });
  window.addEventListener('pagehide', () => { generation++; token = ''; $('password').value = ''; $('reauth').value = ''; });
})();
