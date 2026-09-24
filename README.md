# NHRC Grants Platform

Institutional grant-management platform for Navrongo Health Research Centre.

The implementation follows the approved v0.7 institutional operations design and covers the complete funding lifecycle from opportunity discovery through award closeout and impact, with dedicated Finance & Accounts, Procurement, Laboratory, Research Governance, Administration, IT & Security and protected technical administration capabilities.

## Local development

1. Copy `.env.example` to `.env` and set local-only secrets.
2. Start Docker Desktop.
3. Run `docker compose build` then `docker compose up -d`.
4. Web: http://localhost:3000
5. API status: http://localhost:8080/api/public/status
6. Health: http://localhost:8080/actuator/health

PostgreSQL uses host port 5436 by default to avoid collisions with other NHRC development stacks. Secrets, local storage, keys and backups are excluded from source control.

## Development branch

Active implementation is maintained on `develop` and promoted to `main` through reviewed pull requests after build, migration and functional validation.

See `docs/architecture/ARCHITECTURE.md` and `docs/architecture/FUNCTIONAL_IMPLEMENTATION.md`.


## Automated opportunity discovery

The Opportunity Intelligence module includes a controlled external discovery pipeline:

1. fetch from approved official sources;
2. normalize source-specific records to the NHRC opportunity model;
3. reject closed/expired or invalid-dated records;
4. deduplicate by source identifier and cross-source fingerprint;
5. retain source evidence and discovery-run metrics;
6. match opportunities to NHRC research themes and researcher expertise;
7. place external discoveries in a human review queue;
8. require a named NHRC reviewer to accept a discovered opportunity before eligibility review, EOI, or application registration.

Configured source adapters currently include:
- Grants.gov public search and fetchOpportunity APIs;
- UKRI Funding Finder RSS;
- EU Funding & Tenders Portal search.

Discovery is scheduled by default at 06:15 UTC daily and can also be run per source or for all enabled sources from Opportunity Intelligence. Source search terms, batch sizes, enable/disable state, and schedule state are governed from the platform. The source endpoint itself is not editable from the business UI.

Environment controls:
- `OPPORTUNITY_DISCOVERY_ENABLED=true|false`
- `OPPORTUNITY_DISCOVERY_CRON=0 15 6 * * *`
- `OPPORTUNITY_DISCOVERY_ZONE=UTC`

The scheduler uses Redis leases to prevent overlapping runs across multiple API instances. External source failures are isolated per source and recorded in the integration registry rather than aborting the other source adapters.
