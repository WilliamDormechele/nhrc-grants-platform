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
