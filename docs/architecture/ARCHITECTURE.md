# Architecture

## Overview

NHRC Grants Platform is a modular institutional research administration system. The initial implementation uses a web client, a REST API, PostgreSQL, Redis and object/file storage abstractions.

## Logical domains

1. Executive and portfolio intelligence
2. Pre-award
3. Award management
4. Finance and accounts
5. Procurement
6. Laboratory grant oversight
7. Research governance
8. Performance, outputs and impact
9. People and organisation
10. Personal work management
11. Administration
12. IT, security, audit and records

## Core principles

- One institutional grant record across the lifecycle
- Role-based and permission-based access
- Separation of duties for approvals
- Immutable material audit events
- Explicit workflow states and approvals
- Finance source-of-truth remains the approved finance process/system
- Ethics status is referenced, not manufactured by the grants system
- Secrets never committed to source control
- Development, UAT and production are separate environments

## Initial technical stack

- Frontend: Next.js + TypeScript
- API: Spring Boot + Kotlin
- Database: PostgreSQL
- Cache/queue support: Redis
- Migrations: Flyway
- Local orchestration: Docker Compose
- API docs: OpenAPI
- Authentication: pluggable OIDC/JWT boundary, local development profile initially

## Module boundary

The API is organised by business domain and exposes explicit service-layer operations. Cross-domain transitions are handled through service methods and auditable workflow actions rather than direct table updates from the client.
