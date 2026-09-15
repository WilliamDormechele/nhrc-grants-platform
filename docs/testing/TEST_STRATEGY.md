# Test strategy

Every production tranche must pass the following gates before promotion to main:

1. Kotlin compilation and unit tests through the API Docker build.
2. Next.js production compilation through the web Docker build.
3. Flyway validation and forward migration against PostgreSQL 16.
4. Container startup and health validation for PostgreSQL, Redis, API and web.
5. API smoke checks for public status, actuator health, dashboard summary and representative domain endpoints.
6. Workflow checks for sequential pre-award transitions and award conversion safeguards.
7. Search/filter checks for opportunities, applications, awards, procurement and administrative registers.
8. Permission and audit regression tests before production authentication is enabled.

Local secrets are never committed. UAT must use fictional or approved sanitised records.
