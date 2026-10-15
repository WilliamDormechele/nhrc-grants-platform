# NHRC eGMS 0.4.5 - notifications and training-host preparation

Local verification completed 16 October 2026 (Europe/London). Local UAT: http://localhost:3000.

## Changes

- Application/review/assignment links select their application; advance, report, deliverable and other operational links open the exact record. Approval links filter the parent application's decisions. Missing application links no longer silently select another application.
- Returned/rejected decisions include recorded reasons and notify the assigned investigator, named reviewer/authority and appropriate Grants officers. Partner advances include Finance Officer, Finance Approver, Post-award oversight and the parent award investigator, subject to configured notification policies.
- Outstanding and partly retired partner advances have retirement reminder tasks. Returned review reminders route to the investigator for revision. Currently rejected applications, retired advances and completed tasks are excluded from the corresponding reminder queues.
- Reminder kinds resolve to actual record tables for email rendering. Queued mail is cancelled if the recipient/role/email policy becomes ineligible; reminders are cancelled if the task completes or responsibility changes before delivery.
- Browser requests use the site's own address through a private API proxy, enabling a future hosted deployment. Portal email URLs and allowed origins are configurable. Authentication, record permissions and CSRF remain enforced.
- Progress toasts disappear after 8 seconds, success/information after 6.5 seconds, and errors after 12 seconds. No toast close button. Hiding progress does not cancel work or remove duplicate-action protection; detailed form errors remain available in the form.
- Assistant panel/button/action label: NHRC eGMS Assistant / Ask NHRC eGMS Assistant.

## Evidence

- 60 API unit/regression tests passed; zero failures/errors. Five new exact-record routing regressions cover application decisions, reviews, advances, handovers and missing records.
- Transactional PostgreSQL trigger tests passed for returns, rejections, reviewer/investigator recipients, partner finance oversight, completed/rejected task suppression and inactive/unrelated recipient exclusions. All probe domain changes rolled back.
- 33 lifecycle emails delivered to local SMTP capture. Reserved UAT recipient addresses verified against each message, and recipient-owned inboxes verified using authenticated sessions. Permitted secondary URLs identify the corresponding record. Restricted workspace links/details were withheld; personal notification links remain available.
- A manual partner advance reminder reached capture; a duplicate was suppressed. Researcher reminder triggering returned 403; a retired-advance reminder returned 409. Another user's notification focus did not disclose their message.
- Real scheduler (not a directly invoked service method) generated and delivered reminder 27f55aaf-3911-4e3b-9336-dfe159ed30bf for synthetic deliverable 3a76f462-f1ee-412f-8e0c-def756171256 to its named Post-award owner. The capture included the exact deliverable link. The test-only task was cancelled afterward.
- All 13 configured role accounts signed in and loaded their permitted catalog and own inbox through http://localhost:3000. Anonymous records returned 401; missing-CSRF mutation returned 403; restricted researcher advance access returned 403.
- API health UP and web HTTP 200. Both production builds passed, including web type checks. Configured Gmail mode restored after capture testing.

Evidence files: EMAIL_WORKFLOW_ACCEPTANCE.json, EMAIL_SAME_ORIGIN_ACCEPTANCE.json, email-routing-tests.sql, email-final-api-tests/*.xml and build/verification logs in this directory.

## Limits and hosting status

Email delivery was verified through local SMTP capture, not receipt in an external Gmail/user mailbox. The active UAT accounts currently use reserved test addresses; administrators must enter users' actual addresses before real delivery. Existing Gmail configuration was preserved, with no credentials or mail vault copied into source control.

These checks used the running database, authenticated HTTP interfaces and SMTP capture. No new browser visual/timing acceptance was performed in this run; do not confuse the production build and HTTP checks with browser usability evidence.

The code upload to the existing GitHub develop branch is separate from internet hosting. No DigitalOcean deployment has occurred: SSH access is still denied, and server capacity, domain/TLS, mail connectivity and isolation from existing ODK services remain to be verified. A temporary Cloudflare tunnel was blocked by automatic approval review and was not started. DigitalOcean was subsequently selected by the user.

A database backup was retained locally at .local/backups/pre-email-20261015.dump. No grant database, uploads, encrypted Gmail configuration, SSH private key or local UAT password file is included in the source upload.
