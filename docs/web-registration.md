# Driver and company web registration

Public entry: `https://passo.live/join/`. Direct links accept `?role=driver` or `?role=company`; add `&view=login` to open sign-in.

Applicants can create an account without SMS verification or a company invitation. Registration creates a **PENDING** driver/company and a dedicated onboarding record. A password login opens only their application workspace. It does not give access to passenger records, allocations, trip operations or the company console.

Daily commute drivers choose an active commute route or propose distinct departure and destination places. They must propose a monthly fee in ETB **for the whole vehicle**, up to two decimal places. This is an application proposal; it never changes the route's published passenger fare or payment records automatically.

Daily commute onboarding has two administrative stages:

1. Registration immediately sends the route and whole-vehicle fee proposal for initial admin review, without OTP or uploads.
2. **Accept application** records the administrator's acceptance and unlocks phone verification, profile completion and file uploads. The account remains PENDING.
3. The driver verifies their phone, uploads documents, completes details and submits for final document review.
4. **Approve completed application** activates operations after every required step is complete. Passenger allocation remains locked until then.

Existing pending web drivers supply their route and fee proposal in their workspace before initial acceptance. Already approved, complete applications retain access when this additive migration is applied. Rejected initial applications may be corrected and resubmitted without OTP or documents.

Company and intercity driver applicants retain their existing sequence:

1. Verify their current phone by SMS.
2. Upload all required documents and complete profile details.
3. Submit their application.
4. Receive approval: a platform administrator reviews independent drivers and companies; an approved company reviews only its own drivers.

Driver documents: driving licence, vehicle registration, insurance and profile photo. Emergency contact name/phone and vehicle colour complete the driver profile. Company documents: business licence, company registration and representative ID. Files are private JPEG/PNG/PDF uploads, maximum 5 MB each; photos require an image.

Daily commute drivers register their own vehicle. Intercity drivers choose an approved company; its administrator adds and assigns company vehicles using the existing operations tools. The current vehicle-type master data supplies signup choices.

## Authentication and review

The `/api/onboarding` router issues short-lived JWTs with `scope=ONBOARDING` and a dedicated audience. General API authentication rejects these tokens. Applicant access remains separate from operational login after approval. Existing native OTP registration and invitation-based company registration remain available.

Endpoints:

| Method | Path under `/api/onboarding` | Access |
| --- | --- | --- |
| POST | `/register`, `/login` | Public, independently rate limited |
| GET | `/me` | Own onboarding session |
| POST | `/request-otp`, `/verify-otp` | Own onboarding session, throttled |
| PATCH | `/profile` | Own onboarding session |
| PUT / GET | `/documents/:kind` | Own onboarding session |
| POST | `/submit` | Complete application only |
| GET | `/applications` | Administrator/company, scoped, paginated |
| GET | `/applications/:role/:id` | Authorized reviewer |
| GET | `/applications/:role/:id/documents/:kind` | Authorized reviewer, private download |
| POST | `/applications/:role/:id/review` | Authorized reviewer, `{decision: ACCEPT, APPROVE or REJECT, note?}`; ACCEPT is administrator-only for daily commute |

Administrators use **Applications** in the admin portal. Company reviewers use **Drivers → Web applications**. The existing driver approval/assignment APIs also enforce the onboarding requirements, preventing an older UI or direct request from bypassing the checklist.

SMS verification is bound to the account and current normalized phone, expires after ten minutes and permits at most five incorrect attempts per challenge. Resends have a 60-second cooldown, an hourly account cap and an IP limit. Failed SMS delivery never verifies a phone. Production responses never contain codes.

Changing the daily commute route, proposed fee or phone revokes initial acceptance and final approval. First-time document/profile completion after acceptance preserves that initial acceptance. Replacing reviewed files clears final approval and requires document review again. Suspended, inactive and deleted accounts cannot use an older onboarding session to restore themselves. A phone intentionally freed by the existing driver-deletion flow can create a fresh pending account; verification and documents are never inherited.

Existing approved accounts without an onboarding record retain their established workflows. This change does not retroactively enroll or deactivate them.

## Deployment and checks

`initializeFeatureSchemas` creates the additive onboarding tables and extends the company status constraint to permit PENDING. Uploads use the existing persistent private upload volume. No environment secrets or payment settings change.

Regression coverage: `onboarding-test.js`, `onboarding-allocation-test.js`, `join-ui-test.js`, `applications-ui-test.js`, and PostgreSQL smoke tests. Use mocked SMS and isolated test databases; do not create production applicants for automated testing.

The page offers English, Amharic and Afaan Oromo, with local fonts/artwork. Support links use `info@passo.live`.
