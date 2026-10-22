# Privacy and account deletion

Operator confirmed by the owner: **Passo Mobility, Ethiopia**. Privacy contact:
**thailu70@gmail.com**. Service support: **info@passo.live**.

Public policy: `/privacy/`. Public account closure and guest-data request path:
`/delete-account/`. These pages are served with no-store and a same-origin CSP.
The web deletion screen keeps its short-lived login token in memory only. It
clears the password field after login and requires the password again before
closing the account. There is no automatic browser credential persistence.

## API contract

`GET /api/account/deletion` accepts a normal account Bearer token or an existing
onboarding-scoped DRIVER/COMPANY token. The account and role always come from the
verified token and the current database row. Response:

```json
{"success":true,"deletion":{"eligible":true,"blockers":[],"consequences":["..."],"retainedCategories":["..."],"privacyUrl":"https://passo.live/privacy/","requestUrl":"https://passo.live/delete-account/","supportEmail":"thailu70@gmail.com"}}
```

`POST /api/account/deletion` requires the same Bearer token and exactly
`{"password":"current password","confirmation":"DELETE"}`. It is rate limited,
compares the current bcrypt password, locks the account and relevant vehicle
rows, and rechecks account state and safety blockers in the transaction. It does
not accept another account ID. Successful response is HTTP 200:

```json
{"success":true,"deletion":{"id":"delete_...","status":"COMPLETED_WITH_RETENTION","sessionRevoked":true,"retainedCategories":["..."],"message":"..."}}
```

`PENDING_CLEANUP` means access and live profile records have been removed but one
or more private files still need physical cleanup. This is reported accurately,
and the persisted queue retries on server startup. A support operator may run
`createAccountDeletionService().retryPendingFiles()` through the private runtime
to retry without restarting. Invalid basenames, symlinks and files referenced by
another live document record are not deleted automatically; investigate those
cases instead of widening filesystem permissions or deleting directories.

## Closure and retained information

Users become SUSPENDED; company accounts become INACTIVE. Passwords are replaced
with random unrecoverable values. Sign-in phone/email, live profile/emergency
contacts and uploaded documents are removed. Onboarding records and verification
challenges are removed. Passenger subscriptions become CANCELLED and their QR
tokens are invalidated. Check-in and complaint display names become `Deleted
account`. Driver assignments and live GPS are removed. A company's own routes
become inactive after a safe handover. No other account is deleted.

A deletion receipt permanently blocks old tokens even if an operator mistakenly
reactivates a tombstone. Normal authentication consults this receipt. The
profile upload/update paths also recheck closure after their transaction lock;
the existing onboarding service already locks and checks the current account.

Foreign-key account IDs remain as non-login tombstones. Existing financial
records, historical trips/boarding, complaint content, security audits and
backup copies can contain retained information required for accounting,
fraud/safety investigation or dispute resolution. Closure does **not** issue a
refund. Historical payment references must remain available to reconcile late
provider callbacks or resolve unused paid travel. Do not promise that every copy
of every personal datum has been erased. The policy states these categories
and purposes without inventing a legal retention duration.

Guest intercity tickets are not app accounts and are not erased merely because
their phone matches an account. This avoids accidentally deleting another
person's booking or financial record. The public page provides the privacy
email path for proportionate ownership verification and individual review.

## Operational handover

Automatic closure is blocked for an active driver trip, a scheduled/boarding
intercity departure, or assigned active paid passengers. Companies must release
active/pending drivers and vehicle assignments and resolve upcoming journeys.
Administrator accounts require a handover and cannot use self-service deletion.
The account remains unchanged when blocked. The response explains the blocker
and the public policy offers the privacy team contact to arrange closure.

## Backup restore requirement

Preserve the `account_deletion_requests` receipt table when recovering financial
or operational data. If restoring a snapshot older than deletion, reapply newer
deletion receipts and rerun affected closures/cleanup **before restoring public
access**. A backup restore must not recreate a login or profile that the person
already deleted. The existing backup process does not yet automate this merge;
it is an operator requirement and must be included in the recovery drill.

## Verification

- `node --test test/account-deletion-test.js`: isolated SQLite HTTP regression;
  reauthentication, role isolation, retention, QR invalidation, active-trip and
  passenger handover, pending onboarding/company closure, permanent revocation,
  file cleanup retry, shared-file protection and path traversal prevention.
- `node test/account-deletion-postgres-test.js`: disposable PostgreSQL CI fixture
  after the existing schema/smoke setup; checks enum constraints, row locking,
  retained foreign keys and company/driver closure. Never run against production.
- Public policy/deletion UI must be visually checked at mobile and desktop sizes
  and connected to the native Privacy & account screen before release.

This implements an engineering data-handling policy. Store declarations and
business/legal retention obligations must still be reviewed by the operator;
publishing this page does not certify Google Play approval or legal compliance.
