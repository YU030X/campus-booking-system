## Why

T13 is the final integration boundary for the campus booking system, but the repository currently has no reproducible, owner-scoped evidence that the frozen P0 contracts survive a clean build, empty-database migration, concurrent booking load, container restart, backup/restore, and headless browser flow. This change establishes that evidence and a safe deployment/runbook plan now, while keeping implementation and external infrastructure approval outside the planning phase.

## What Changes

- Add owner-scoped integration and headless E2E test plans covering registration/login, resource and slot discovery, direct and pending bookings, approval/rejection, cancellation, check-in, no-show/violation behavior, explicit terminal release for `REJECTED`/`CANCELLED`/`NO_SHOW`, optional `COMPLETED` only when an approved automatic-completion feature is enabled, refresh/persistence, and 401/403/409 ownership boundaries.
- Add a three-round JMeter plan and evidence format: isolated vulnerable baseline, database unique-index protection, and unique-index plus Redisson; in each healthy-Redis, valid-seed, same-slot protected round require exactly one success and 99 business conflicts, while `HTTP 409`/code `43000` `SYSTEM_BUSY` responses are separate errors; verify different resource/date requests are not globally serialized.
- Add deployment artifacts and runbook plans for pinned JDK 17 backend and frontend-build/Nginx images, private MySQL 8/Redis networks, public 80/443 only, healthchecks, restart ordering, resource/log limits, SPA fallback, `/api` proxy, security headers, request limits, timeouts, certificate mounts/renewal, rollback, and persistence.
- Add empty-database migration, Redis-failure, health, dependency/artifact/secret-scan, compose-config, and restart-persistence verification gates; never edit V001–V005 or weaken existing contracts.
- Define a demo orchestration contract that uses a T01-owned seed change request or runtime test fixture, with no plaintext weak passwords, PII, committed secrets, or migration edits.
- Record explicit local gates versus external gates. Without user-provided infrastructure target and authorization, TLS material, or credentials, the change remains Draft/gated and must not claim public deployment, domain, or certificate completion.
- Document discovered defects as requests to the owning T04–T12/T01 change rather than changing business, frontend, Maven, npm, SQL, or common source in T13.

## Capabilities

### New Capabilities

- `integration-e2e-verification`: reproducible API integration, headless browser, authorization, lifecycle, persistence, and evidence requirements.
- `concurrency-performance-verification`: JMeter scenarios, three-round comparison, metrics, conflict classification, and lock-granularity evidence.
- `deployment-runtime`: container images, Compose topology, Nginx edge behavior, health/restart/resource/security controls, and external-gate rules.
- `data-recovery-runbook`: backup/restore, migration-on-empty-DB, volume persistence, rollback, RPO/RTO assumptions, and recovery evidence.
- `demo-orchestration`: safe fixture/seed ownership, deterministic demo setup, cleanup, and screenshot/network evidence.

### Modified Capabilities

- None. T13 verifies and operationalizes existing frozen contracts; any required business/API/schema change must be raised as a separate owner change request.

## Impact

- Planned write scope is limited to `openspec/changes/verify-and-deploy-system/**`, `deploy/**`, integration/E2E test ownership paths, JMeter assets, and deployment/performance/runbook/demo documentation after apply approval.
- Business implementation, frontend feature source, `pom.xml`, `package.json`/lockfiles, `sql/` migrations, and shared/common contracts are non-goals and remain owned by T01–T12.
- The change introduces no runtime dependency or public infrastructure by itself. Public acceptance is an explicit external gate requiring a user-authorized target and TLS/credential provision; otherwise only local/container evidence can be marked complete.
- At planning time the T04–T12 and Redis-sibling planning artifacts exist, but their merge/rebase/spec-sync evidence is not present on the selected baseline; unmerged or unsynced planning artifacts do not satisfy the P0 evidence gate, so the future PR must remain Draft until independently proven.
