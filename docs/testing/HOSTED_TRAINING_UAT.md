# NHRC eGMS hosted training - 16 October 2026

URL: https://egms.134-209-21-8.sslip.io

## Deployment

- Existing user-approved DigitalOcean host; fresh, isolated eGMS training PostgreSQL database and Redis/mail-capture volumes. The local grant database, uploads and invitation-encryption key were not exported.
- HTTPS certificate issued by Let's Encrypt, valid through 13 January 2027, with a scoped renewal hook. Dedicated eGMS hostname configuration and a private ingress network; existing VA template checksum unchanged, existing VA HTTPS returned 200.
- API, web, database, Redis and mail capture have memory limits. Databases/API/capture are not publicly exposed as separate ports. The HTTPS app requires authenticated sessions, CSRF and assigned roles.
- A private, single-use setup invitation was created for the named platform owner. It is not committed or included in test logs. Strong random credentials for synthetic training roles are stored only on the server with restricted permissions.
- Recovered verified legacy schema dependencies before immutable V013 using a scoped Flyway callback. Full fresh-schema preflight and actual server startup through V022 succeeded. Existing migration checksums and version history preserved.

## Validation

- Final API: 64 tests passed; no failures/errors. Action-state unit checks: 22 passed.
- Both production builds passed. Same-origin API proxy and hosted HTTPS status returned the training environment and version 0.4.5.
- Hosted authenticated role/register results are recorded in HOSTED_ROLE_ACCEPTANCE.json. Synthetic welcome/setup/replay results are recorded in HOSTED_ONBOARDING_ACCEPTANCE.json when completed.
- Superadmin can read all 53 configured operational definitions/registers; business approval authority remains tied to assigned business roles.
- Local lifecycle notification evidence: 33 captured messages, correct intended recipients, permitted exact-record links, manual advance reminder, duplicate suppression and actual automatic reminder delivery. See EMAIL_WORKFLOW_UAT.md.
- Brevo configuration tests reject non-administrators, invalid keys and enabling an untested connection. No secret is returned in configuration responses. Actual Brevo sending remains pending the owner's secure key entry, sender verification and connection-email receipt.

## Limits

This run used authenticated HTTP, running PostgreSQL migrations and SMTP capture. No new browser visual/usability pass was performed in this run. Prior local browser reports describe their own earlier scope.

The full 4B local AI model was not installed on this shared 4 GB server; the assistant therefore reports model unavailability here. The deterministic intelligence dashboards and grant workflows do not depend on that model. The existing local deployment retains its configured model. No additional paid resource was provisioned.

The free sslip.io hostname depends on the server retaining its IP and that external DNS service. This is a training deployment, not a claim of institutional production acceptance. The administrator must configure real users, email delivery and institutional operational policies before live use.
