# NHRC Grants implementation status

Date: 19 September 2026

Branch: `work/grants-complete-workflows-20260919`

Review: draft PR #3, targeting `develop`.

## Release decision

**Do not merge or deploy this branch as a completed platform.** This change set implements the server-side grant workspaces and records their tests. It does not complete the requested native screens, every button, or every institutional workflow. The stable `develop` branch was not changed.

The approved v0.7 design remains the interface specification. The existing logo, navigation and web files were not replaced. An attempted write of the new interface request helper was blocked by the tool connection, including one retry; no new frontend file from that attempt was saved. The work must not be described as a completed visual or button-level implementation.

The new branch retires the old unrestricted write routes through `LegacyGrantMutationGuard`. The existing interface still uses those routes. Deploying only these server changes alongside that interface would therefore prevent existing writes, including the old opportunity form. This is an explicit release blocker, not a reason to remove the approval safeguards.

## Implemented in source

### Data and profiles

Three additive migrations, V011 to V013, retain existing migrations and records. They add institution profiles, researcher expertise and methods, selected track record, availability, partner and funder information, call and submission types, revision numbers, saved decision evidence, source records and import history. The initial institutional profile deliberately contains only the existing organisation name and short name. Missing legal, eligibility and capacity details are not invented.

The server-owned form catalogue defines 34 record types. These are field definitions and persistence services, not 34 completed screens:

| Area | Record types |
| --- | --- |
| Profiles and pre-award | Institutions, researchers, partners, funders, opportunities, applications |
| Award administration | Contracts, award partners, amendments, deliverables, risks |
| Finance | Receipts, expenditures, commitments, advances, financial reports, forecasts, reconciliations |
| Procurement | Plans, requisitions, suppliers, purchase orders, procurement contracts, assets |
| Laboratory | Laboratory items, maintenance and calibration |
| Governance and performance | Compliance links, technical reports, research outputs, impact evidence, closeout items |
| Organisation and support | Support tickets, organisation units, research themes |

### Application preparation and decisions

SOI, EOI, LOI, concept note, full proposal and fellowship types have distinct baseline narrative requirements. These are internal preparation structures, not a claim that every donor-specific application template has been implemented.

The application service records eligibility checklists, conditions, director decisions, researcher assignment and acceptance, proposal revisions, budget scenarios, controlled document references, assigned reviews, institutional authorisation, external submission evidence, funder outcomes, invited next-stage applications and award setup records.

Budget quantities and amounts use decimal arithmetic. Each line must balance across the funder, NHRC and partner contributions. Required sections drive the readiness value. Reviews and authorised packages are tied to the content revision. Technical administrators do not acquire approval authority merely by being technical administrators. The service checks the acting person's server-side role and record access. Independent approval actions reject the maker or lead researcher where defined.

Saving a document currently records a controlled HTTPS reference and version. It does not upload a binary document, scan it for malware or establish a secure document store.

### Funding discovery

The source clients use the documented official Grants.gov search and opportunity-detail services, and Simpler.Grants.gov search. Simpler requires `SIMPLER_GRANTS_API_KEY` in the server environment. No key belongs in browser code or a repository file.

Discovery is **on demand**. A scheduled worker is not implemented, and the API now reports that accurately. Searches are bounded to 100 calls per run and expose truncation. Calls with the same source and external identifier are refreshed rather than inserted again. Matching the same call across different providers is not yet implemented.

Imported source snapshots do not overwrite staff-curated opportunity details. Unknown amounts remain unknown. A closing date is not converted into an invented closing time. Researcher matches show exact expertise or method phrase overlap, not a funding-success probability. Matching does not assign a researcher, send an email or approve eligibility.

No automatic donor submission is implemented. Submission actions record a submission already made through the appropriate external process.

### Operations and oversight

The services add controlled operational records and actions for finance, procurement, laboratory activity, reporting and closeout, with version checks and audit history. Repeated workflow action references are checked to avoid applying the same action twice. Management summaries are calculated from stored records. Finance totals keep currencies separate and exclude mismatched legacy currencies while reporting their count. No entries are posted to the NHRC accounting system.

## Verification evidence

The existing GitHub `validate` workflow passed for application commit `1569b96605e0a782d2ef262806b059ee7d9f7da2`, tested as PR merge commit `090c958b6eb6ca314328c337f4ca8fb1f059b425` against `develop` commit `cb7d4633456f16f4f3e607fc6931362cb6318199`.

Run: `35451582952`; job: `105919560040`.

Observed successful steps were API compilation and unit tests, API and existing web image builds, a clean PostgreSQL startup with migrations, service readiness and the existing basic endpoint checks. The job completed on 19 September 2026 at approximately 15:26 UTC.

The change set contains 24 new unit tests: 18 grant-rule/catalogue tests and six source-parser/response-limit tests. The tests cover protected fields, required values, optional amounts, decimal budgets, ORCID checks, evidence links, time-zone requirements, readiness, stage and role rules, schema references, phrase matching and bounded source parsing. These tests do not make live funding-source requests.

The existing workflow's endpoint checks cover the earlier public/status, dashboard, opportunities, applications, awards, finance-summary and organisation-role routes. They do not establish that every new workbench route or every full workflow works end to end. The existing web build passed without a new interface implementation. The final currency-summary and truthful discovery-mode corrections were committed after the run above; use the checks on the current PR head for their latest build result.

Build output also reported two web dependency advisories, one moderate and one high. The advisory identifiers and remedies still require investigation; a successful build is not security clearance. Do not apply a forced dependency upgrade without reviewing compatibility and testing it.

## Required before release

1. Connect all native forms and contextual actions to the new services while reproducing v0.7 page-specific layouts. Replace fixed readiness values and checklist ticks with saved state. Add browser tests for navigation, errors, save/reload persistence and role-specific actions.
2. Exercise every new record service against PostgreSQL, then complete full SOI/EOI/proposal and award journeys with independent users. Test stale edits, repeated actions, access denial, return-and-resubmit paths, failed imports and transaction rollback. Validate an upgrade from a copy of the existing v010 database as well as a clean start.
3. Complete outstanding business paths, particularly partner and supplier due diligence, funder approval of amendments, signed versus internally approved agreements, invited-stage deadline editing, and finance access to award-partner references. Review cumulative purchase orders against a requisition, cross-record currencies, field lengths and award-closeout concurrency before approving finance/procurement use.
4. Provision and test the required independent business roles, including finance approver and governance reviewer. Confirm the institution's actual approval and delegation rules. Development identity selection is for isolated demonstration use only; it is not production authentication.
5. Validate live funding-source responses from the deployment environment. Confirm external credentials through approved server configuration, add other required funders using documented permitted interfaces, and implement cross-provider duplicate review and scheduled discovery only when their policies and tests are complete.
6. Complete document storage, authorised downloads, upload scanning and retention, along with outbound email delivery controls, where required. Current evidence links and in-app notifications do not replace these capabilities.
7. Complete administration, access reviews, delegations, templates, security operations, backup/restore verification, dependency remediation and deployment review. Those areas are not completed by the new grant catalogue.

## Service entry points for the next implementation step

All new services are under `/api/workbench`.

- Identity and form definitions: `GET /identity`, `GET /catalogue`, `GET /lookups/{key}`.
- Records: `GET/POST /records/{key}`, `GET/PATCH /records/{key}/{id}`, `POST /records/{key}/{id}/actions`, `GET /records/{key}/{id}/history`.
- Application detail: `GET /applications/{id}`. Separate routes save narrative, budget lines, selected budget scenario and document versions; `/applications/{id}/actions` controls stage decisions.
- Discovery: `GET /sources`, `POST /sources/search`, source snapshots/details and researcher matches under `/opportunities/{id}`.
- Oversight: `GET /summary`, `/finance`, `/personal`, `/awards` and `/awards/{id}`. Award actions have their own controlled route.

A record save uses the catalogue's `values` and the current `version`. A workflow decision additionally includes an action code, unique `requestId`, note and any required evidence or decision values. The server does not accept a form-supplied acting-user identifier as authority.
