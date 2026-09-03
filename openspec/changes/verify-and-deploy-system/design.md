## Context

The proposal defines the motivation and observable contracts. The repository is a Spring Boot 3.5/JDK 17 backend, Vue/Vite frontend, MySQL 8 schema with V001–V005, Redis/Redisson support, and Nginx-facing deployment model. The selected planning baseline is the existing `0e53b7efec27f5821056bd546b8a245144414fdb` commit (the requested `main0e53b7e` ref does not exist). T04–T12 and Redis-sibling planning artifacts exist, but merge/rebase/spec-sync evidence is not present on this baseline; unmerged or unsynced planning cannot satisfy the evidence gate, so implementation and external acceptance are gated.

## Goals / Non-Goals

**Goals:**

- Make every P0 integration, concurrency, deployment, recovery, and demo gate executable and auditable with redacted artifacts.
- Keep T13 ownership limited to integration/E2E tests, JMeter, `deploy/**`, and deployment/performance/runbook/demo documentation.
- Separate clean local/container evidence from external public deployment acceptance.
- Preserve the frozen API, authorization, state, slot, migration, dependency, and security contracts.

**Non-Goals:**

- No business or frontend feature implementation, common contract edits, Maven/npm/lockfile changes, SQL migration edits, or production seed insertion.
- No automatic cloud provisioning, DNS changes, certificate purchase, public deployment, or spend.
- No weakening of unique constraints, authentication, validation, error classification, or acceptance thresholds.

## Decisions

### 1. One change, five capability deltas, one evidence index

The change keeps one PR/change but separates contracts into five new capability paths so integration, load, runtime, recovery, and demo obligations can be validated independently. A future apply should maintain a single evidence index linking commands, raw outputs, screenshots, and owner change requests to each requirement.

Alternative considered: one large deployment spec. Rejected because it obscures which gate is local, external, or owned by another task.

### 2. Test harness is disposable and headless

Integration tests run against isolated MySQL/Redis services and generated runtime credentials. E2E uses a headless CLI/browser driver with network capture and screenshot redaction. The harness must reset only its own fixture scope and must never target a public or production endpoint by default.

Alternative considered: manual browser-only acceptance. Rejected because it cannot provide repeatable refresh, network, authorization, or persistence evidence.

### 3. Concurrency evidence is a three-round experiment

JMeter plans share request data and environment capture while varying only the concurrency-control version. The vulnerable baseline is isolated and historical; each healthy-Redis, valid-seed, same-slot protected round may claim one success and 99 business conflicts. `HTTP 409` with code `43000` and message/category `SYSTEM_BUSY` is a separate system error, not a business conflict. Distinct resource/date scenarios run separately to detect an accidental global lock. The report stores raw JTL/log output and a database snapshot rather than relying on a summary claim.

Alternative considered: a single final-load run. Rejected because it cannot demonstrate why the unique index is the correctness boundary or whether Redisson changes conflict behavior.

### 4. Standing authorization is local-only and worktree-scoped
The user has granted standing permission to execute local verification tools in
`D:\Projects\project1_campus\target\worktrees\verify-and-deploy-system`:
Docker/Docker Compose, Maven, npm, JMeter, headless Chrome, MySQL/Redis, and
migration/backup/restore/restart/database checks. This is an execution permission,
not evidence of success. Every runtime URL remains restricted to
`127.0.0.1`, `localhost`, or `::1`. External hosts, public IPs, DNS, TLS/certificates,
public URLs, public deployment, and externally exposed 443 remain outside the
permission and require separate authorization.

### 5. Compose keeps stateful services private

The runtime has Nginx as the only host-published service. API, MySQL, and Redis communicate on private networks; named volumes hold MySQL/Redis state; healthchecks and dependency conditions gate startup. Image tags are pinned and accompanied by a digest refresh procedure. Resource/log limits and non-root execution are explicit runtime settings.

Alternative considered: publish MySQL/Redis for convenience. Rejected because it violates the project security contract and would make local evidence unsafe to reuse.

### 6. Nginx is the single edge contract

Nginx serves the built SPA with history fallback and proxies `/api` to the API service. The configuration owns headers, body limits, upstream timeouts, and certificate/key mounts. Certificate renewal is a runbook operation requiring an authorized target; no key is committed.

Alternative considered: expose API and static server separately. Rejected because it complicates same-origin E2E evidence and increases public attack surface.

### 7. Fresh schema and demo data are separate lanes

The empty-database gate runs V001–V005 only and asserts twelve tables/no seed. Demo orchestration consumes a separately approved T01 seed change or an ephemeral fixture, with generated strong credentials and no PII. If T01 does not provide that source, T13 opens a change request and marks the demo gate blocked.

Alternative considered: append demo inserts to V005. Rejected because it changes the frozen migration contract and makes fresh-schema verification non-deterministic.

### 8. Recovery is evidence-first and rollback is conservative

The runbook records backup command, restore target, checksums/row evidence, elapsed time, assumed RPO/RTO, volume restart behavior, and image/config rollback. It treats committed migrations as forward-only unless an owner-approved recovery procedure exists; rollback restores a prior image/config or backup rather than deleting schema history.

## Risks / Trade-offs

- [T04–T12 or Redis-sibling artifacts are not merged/spec-synced] → Keep the change Draft, list the exact missing merge/rebase/spec-sync proof, and do not start implementation acceptance; planning artifacts alone do not satisfy the gate.
- [External target or TLS credentials are unavailable] → Complete only local gates; mark public deployment/domain/certificate checks not run and request explicit authorization later.
- [T07 booking-lock Redis outage] → Fail closed with HTTP 409, code `43000`, message/category `SYSTEM_BUSY`; do not use a DB-only fallback. [T12 availability/cache Redis outage] → Permit MySQL fallback and record its latency/consistency evidence. Count `SYSTEM_BUSY` separately from business conflicts.
- [Headless screenshots or network traces leak secrets] → Generate disposable credentials, redact Authorization/cookie/password fields, scan artifacts before publication, and fail the evidence gate on leakage.
- [Pinned image becomes unavailable or vulnerable] → Record digest/source metadata and a controlled refresh task; never silently switch to floating tags.
- [Demo fixture collides with another run] → Use isolated database/volume and namespace, deterministic teardown, and owner-scoped cleanup only.

## Migration Plan

Planning creates only this change and its artifacts on the dedicated worktree. After explicit review and a separate apply request, implementation should proceed in this order: verify T04–T12 gates; add isolated integration/E2E and JMeter assets; add deploy files and runbooks; run local validation; request owner changes for defects; then run external acceptance only when the user supplies an authorized target and TLS path. No migration file is altered.

Rollback after apply is file-scoped: revert T13 test/deploy/docs artifacts or select the prior pinned image/config, restore the last verified backup, and rerun health plus smoke gates. Do not delete volumes or rewrite V001–V005 as an automatic rollback step.

## Open Questions

- Which headless CLI/browser runner is already approved in the eventual apply environment?
- Which user-authorized external target, DNS owner, and certificate renewal mechanism should be used for the optional public gate?
- Which T01-owned seed change or fixture contract will provide the demo dataset?
