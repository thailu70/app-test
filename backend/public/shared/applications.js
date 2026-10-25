(function (root, factory) {
  const panel = factory();
  if (typeof module === 'object' && module.exports) module.exports = panel;
  if (root) root.PassoApplications = panel;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';
  const esc = value => String(value ?? '').replace(/[&<>"']/g,
    ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);
  const labels = { LICENSE: 'Driving licence', VEHICLE_REGISTRATION: 'Vehicle registration', INSURANCE: 'Insurance',
    PHOTO: 'Profile photo', BUSINESS_LICENSE: 'Business licence', REGISTRATION: 'Company registration', REPRESENTATIVE_ID: 'Representative ID',
    ADMIN_ACCEPTANCE: 'Initial admin acceptance', ROUTE_PREFERENCE: 'Route choice or proposal', MONTHLY_FEE: 'Whole-vehicle monthly fee proposal', PROPOSED_MONTHLY_FEE: 'Whole-vehicle monthly fee proposal' };
  const reviewLabel = state => ({ AWAITING_ACCEPTANCE: 'Awaiting admin acceptance', DRAFT: 'In progress', SUBMITTED: 'Documents submitted', CORRECTIONS_REQUESTED: 'Corrections requested', APPROVED: 'Approved', REJECTED: 'Returned' })[state] || state || 'In progress';
  function markup() {
    return '<section id="applications-panel" class="card section-card applications-panel">' +
      '<div class="section-title"><div><h3>Driver and company applications</h3><p>Accept daily commute route and fee proposals first. Review each applicant’s required details and documents before approving passenger allocation.</p></div>' +
      '<button class="btn btn-secondary" type="button" data-application-refresh>Refresh applications</button></div>' +
      '<p class="application-share">Recruit daily commute drivers: <a href="/join/" target="_blank" rel="noopener">passo.live/join/ ↗</a></p>' +
      '<p data-application-message role="status" aria-live="polite"></p><div data-application-list></div><div data-application-detail hidden></div></section>';
  }
  function mount({ container, api, token = '', isActive = () => true, onReviewed = () => {} }) {
    let disposed = false, revision = 0, selected = null, busy = false, applications = [], nextCursor = null;
    const requests = new Set(), downloads = new Set();
    const find = selector => container.querySelector(selector);
    const active = () => !disposed && isActive();
    const message = (text, error = false) => {
      if (!active()) return;
      find('[data-application-message]').textContent = text;
      find('[data-application-message]').className = error ? 'form-message error' : 'form-message';
    };
    async function call(path, options = {}) {
      const controller = new AbortController(); requests.add(controller);
      try { return await api(path, { ...options, signal: controller.signal }); }
      finally { requests.delete(controller); }
    }
    function renderList(applications) {
      const rows = applications.map(a => '<tr><td><strong>' + esc(a.companyName || a.fullName) + '</strong><br>' + esc(a.phone) +
        '</td><td>' + esc(a.role === 'COMPANY' ? 'Company' : 'Driver') + '</td><td>' + esc(a.status) + '</td><td>' +
        (a.onboarding?.phoneVerified ? 'Verified' : a.onboarding?.acceptanceRequired && !a.onboarding?.acceptedAt ? 'After admin acceptance' : a.onboarding?.phoneVerificationRequiredForAllocation === false ? 'Verification deferred' : a.onboarding?.phoneVerificationRequiredForApproval === false ? 'Optional for approval' : 'Needs verification') + '</td><td>' +
        esc(reviewLabel(a.onboarding?.reviewStatus)) +
        '</td><td><button type="button" class="btn btn-secondary small" data-application-open="' + esc(a.id) +
        '" data-application-role="' + esc(a.role) + '">Review</button></td></tr>').join('');
      find('[data-application-list]').innerHTML = '<div class="table-wrap"><table><thead><tr><th>Applicant</th><th>Type</th><th>Account</th><th>Phone</th><th>Application</th><th>Action</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6">No applications yet. Share the registration link to get started.</td></tr>') + '</tbody></table></div>' +
        (nextCursor ? '<button class="btn btn-secondary" type="button" data-application-more>Load more applications</button>' : '');
    }
    async function load(more = false) {
      if (busy) return;
      const current = ++revision;
      find('[data-application-refresh]').disabled = true; message('Loading applications…');
      try {
        const result = await call('/api/onboarding/applications' + (more && nextCursor ? '?cursor=' + encodeURIComponent(nextCursor) : ''));
        if (!active() || current !== revision) return;
        const rows = Array.isArray(result.applications) ? result.applications : [];
        applications = more ? applications.concat(rows.filter(row => !applications.some(current => current.id === row.id))) : rows;
        nextCursor = result.nextCursor || null;
        renderList(applications);
        message('Applications updated. Website and app registrations share this review queue.');
      } catch (error) { if (active() && current === revision) message('Could not load applications: ' + error.message, true); }
      finally { if (active()) find('[data-application-refresh]').disabled = false; }
    }
    function applicationPath(role, id) {
      return '/api/onboarding/applications/' + encodeURIComponent(role) + '/' + encodeURIComponent(id);
    }
    function renderDetail(account, onboarding) {
      selected = { account, onboarding };
      const docs = Array.isArray(onboarding.documents) ? onboarding.documents : [];
      const missing = Array.isArray(onboarding.reviewMissing) ? onboarding.reviewMissing : Array.isArray(onboarding.missing) ? onboarding.missing : [];
      const otpOptional = onboarding.phoneVerificationRequiredForApproval === false;
      const otpDeferred = otpOptional && onboarding.phoneVerificationRequiredForAllocation === false;
      const profile = onboarding.profile || {};
      const suspended = account.status === 'SUSPENDED';
      const readyForReview = !suspended && (onboarding.reviewReady ?? onboarding.ready) === true && Boolean(onboarding.submittedAt);
      const proposal = onboarding.routePreference || {};
      const fee = Number(proposal.proposedMonthlyFeeEtb);
      const needsAcceptance = onboarding.acceptanceRequired && !onboarding.acceptedAt;
      const canAccept = !suspended && needsAcceptance && ['PENDING','ACTIVE'].includes(account.status) && ['EXISTING','PROPOSED'].includes(proposal.mode) && Number.isFinite(fee) && fee > 0;
      const routeDetails = !onboarding.acceptanceRequired ? '' : '<h4>Daily commute proposal</h4><dl class="application-facts"><div><dt>Route preference</dt><dd>' +
        esc(proposal.mode === 'EXISTING' ? proposal.routeName || 'Selected existing route' : proposal.mode === 'PROPOSED' ? 'Proposed new route' : 'Not provided yet') + '</dd></div>' +
        '<div><dt>Monthly fee for the whole vehicle</dt><dd>' + (Number.isFinite(fee) && fee > 0 ? esc(fee.toLocaleString('en-ET', {minimumFractionDigits:2, maximumFractionDigits:2})) + ' ETB / vehicle / month' : 'Not provided yet') + '</dd></div>' +
        (proposal.feeLocked ? '<div><dt>Calculated monthly fee</dt><dd>' + esc(proposal.passengerCapacity) + ' seats × ' + esc(Number(proposal.passengerMonthlyFareEtb).toLocaleString('en-ET', {minimumFractionDigits:2, maximumFractionDigits:2})) + ' ETB per passenger / month</dd></div>' : '') +
        (proposal.departure ? '<div><dt>Departure</dt><dd>' + esc(proposal.departure) + '</dd></div>' : '') +
        (proposal.destination ? '<div><dt>Destination</dt><dd>' + esc(proposal.destination) + '</dd></div>' : '') +
        '<div><dt>Initial acceptance</dt><dd>' + (onboarding.acceptedAt ? 'Accepted' : esc(onboarding.acceptanceStatus === 'REJECTED' ? 'Returned to applicant' : 'Awaiting admin acceptance')) + '</dd></div></dl>';
      const detail = find('[data-application-detail]'); detail.hidden = false;
      detail.innerHTML = '<div class="application-detail"><div class="section-title"><div><h3>' + esc(account.companyName || account.fullName) +
        '</h3><p>' + esc(account.phone) + ' · ' + esc(account.role) + '</p></div><button type="button" class="btn btn-secondary" data-application-close>Close review</button></div>' +
        '<dl class="application-facts"><div><dt>Phone</dt><dd>' + (onboarding.phoneVerified ? 'Verified by SMS' : otpDeferred ? 'Not yet verified · verification deferred' : otpOptional ? 'Not yet verified · optional for approval' : 'Not yet verified') +
        '</dd></div><div><dt>Account status</dt><dd>' + esc(account.status) + '</dd></div><div><dt>Review status</dt><dd>' + esc(reviewLabel(onboarding.reviewStatus)) +
        '</dd></div><div><dt>Representative / driver</dt><dd>' + esc(account.fullName) + '</dd></div>' +
        (account.licenseNumber ? '<div><dt>Driving licence number</dt><dd>' + esc(account.licenseNumber) + '</dd></div>' : '') +
        (account.assignedVehiclePlate ? '<div><dt>Vehicle plate</dt><dd>' + esc(account.assignedVehiclePlate) + '</dd></div>' : '') +
        ['registrationNumber','address','emergencyContactName','emergencyContactPhone','vehicleColor'].filter(key => profile[key]).map(key =>
          '<div><dt>' + esc(({registrationNumber:'Registration number',address:'Company address',emergencyContactName:'Emergency contact',emergencyContactPhone:'Emergency phone',vehicleColor:'Vehicle colour'})[key]) + '</dt><dd>' + esc(profile[key]) + '</dd></div>').join('') + '</dl>' + routeDetails +
        '<h4>Uploaded documents</h4><div class="application-documents">' + (docs.map(doc => '<button type="button" class="btn btn-secondary" data-application-document="' + esc(doc.kind) + '">' +
          esc(labels[doc.kind] || doc.kind) + ' ↓</button>').join('') || '<p>No documents uploaded.</p>') + '</div>' +
        (needsAcceptance ? '<p class="notice">Review the driver, vehicle, route and whole-vehicle fee proposal. Accepting this application unlocks file uploads and phone verification. Complete documents are required for final approval. The current phone-verification policy is shown above.</p>' : !readyForReview ? '<p class="notice">Cannot approve yet. ' + (missing.length ? 'Missing: ' + missing.map(x => esc(labels[x] || x.replace(/_/g, ' ').toLowerCase())).join(', ') + '.' : 'The applicant must complete and submit their application.') + '</p>' :
          '<p class="notice">' + (otpDeferred ? 'The required application details and documents are complete. OTP is not required for driver approval or passenger allocation at present.' : otpOptional ? 'The required application details and documents are complete. OTP is optional for approval. Phone verification is still required before passenger allocation.' : 'The phone and required documents are complete.') + ' Review the uploaded files before approving.</p>') +
        '<label class="application-note">Review note (optional)<textarea data-application-note maxlength="500" rows="2" placeholder="A short note visible to the applicant"></textarea></label>' +
        '<div class="form-actions">' + (needsAcceptance && !suspended ? '<button type="button" class="btn btn-primary" data-application-decision="ACCEPT"' + (!canAccept ? ' disabled' : '') + '>Accept initial application</button>' : '') +
        (!suspended ? '<button type="button" class="btn btn-primary" data-application-decision="APPROVE"' + (!readyForReview ? ' disabled' : '') + '>Approve completed application</button>' : '') +
        (!suspended && !needsAcceptance ? '<button type="button" class="btn btn-secondary" data-application-decision="CORRECTIONS">Request document / detail corrections</button>' : '') +
        (account.status === 'PENDING' ? '<button type="button" class="btn btn-secondary" data-application-decision="REJECT">Reject initial application</button>' : '') +
        (account.role === 'DRIVER' && account.status === 'ACTIVE' ? '<button type="button" class="btn btn-secondary" data-application-decision="SUSPEND">Suspend driver access</button>' : '') +
        (account.role === 'DRIVER' && suspended ? '<button type="button" class="btn btn-primary" data-application-decision="REACTIVATE">Restore driver access</button>' : '') + '</div></div>';

    }
    async function open(role, id) {
      const current = ++revision; message('Loading application…');
      try {
        const result = await call(applicationPath(role, id));
        if (!active() || current !== revision) return;
        renderDetail(result.account, result.onboarding); message('');
      } catch (error) { if (active() && current === revision) message(error.message, true); }
    }
    async function review(decision) {
      if (!selected || busy) return;
      const note = find('[data-application-note]').value.trim();
      if (decision === 'CORRECTIONS' && !note) { message('Explain which documents or details need correction in the review note.', true); find('[data-application-note]').focus(); return; }
      busy = true;
      const { account } = selected;
      const buttons = container.querySelectorAll('[data-application-decision]'); buttons.forEach(button => { button.disabled = true; });
      try {
        const result = await call(applicationPath(account.role, account.id) + '/review', { method: 'POST', body: JSON.stringify({ decision, note }) });
        if (!active()) return;
        selected = null; find('[data-application-detail]').hidden = true;
        busy = false; await load();
        const outcomes = {
          ACCEPT: 'Initial application accepted. The driver can upload documents for final approval.',
          CORRECTIONS: 'Corrections requested. The accepted route is retained and the applicant can replace documents and resubmit.',
          REJECT: 'Initial application rejected. The applicant can read your note and resubmit.',
          SUSPEND: 'Driver access suspended. The approved application and documents are retained.',
          REACTIVATE: result.account?.status === 'ACTIVE' ? 'Driver access restored.' : 'Driver returned to pending application. They can sign in to complete the remaining review steps.'
        };
        message(outcomes[decision] || (result.onboarding?.phoneVerificationRequiredForAllocation !== false && result.onboarding?.phoneVerificationRequiredForApproval === false && !result.onboarding.phoneVerified ? 'Application approved. Phone verification is still required before passenger allocation.' : 'Application approved. Passenger allocation is now available through the existing operations tools.'));

        onReviewed();
      } catch (error) {
        message(error.message, true);
        if (active() && selected) renderDetail(selected.account, selected.onboarding);
      } finally { busy = false; }
    }
    async function download(kind, button) {
      if (!selected) return;
      const { account } = selected;
      const controller = new AbortController(); requests.add(controller); button.disabled = true;
      try {
        const response = await fetch(applicationPath(account.role, account.id) + '/documents/' + encodeURIComponent(kind), {
          headers: { Authorization: 'Bearer ' + token }, cache: 'no-store', signal: controller.signal
        });
        if (!response.ok) throw new Error('Could not download document. Refresh and try again.');
        const blob = await response.blob(); if (!active()) return;
        const url = URL.createObjectURL(blob); downloads.add(url);
        const link = document.createElement('a'); link.href = url;
        link.download = kind.toLowerCase() + (blob.type === 'application/pdf' ? '.pdf' : blob.type === 'image/png' ? '.png' : '.jpg');
        link.click(); message('Document downloaded for review.');
      } catch (error) { if (error.name !== 'AbortError') message(error.message, true); }
      finally { requests.delete(controller); if (active()) button.disabled = false; }
    }
    function click(event) {
      const refresh = event.target.closest('[data-application-refresh]'); if (refresh) { load(); return; }
      if (event.target.closest('[data-application-more]')) { load(true); return; }
      const button = event.target.closest('[data-application-open]'); if (button) { open(button.dataset.applicationRole, button.dataset.applicationOpen); return; }
      if (event.target.closest('[data-application-close]')) { selected = null; revision++; find('[data-application-detail]').hidden = true; return; }
      const decision = event.target.closest('[data-application-decision]'); if (decision) { review(decision.dataset.applicationDecision); return; }
      const doc = event.target.closest('[data-application-document]'); if (doc) download(doc.dataset.applicationDocument, doc);
    }
    container.addEventListener('click', click); load();
    return () => { disposed = true; revision++; container.removeEventListener('click', click); requests.forEach(request => request.abort()); downloads.forEach(url => URL.revokeObjectURL(url)); };
  }
  return { markup, mount };
});
