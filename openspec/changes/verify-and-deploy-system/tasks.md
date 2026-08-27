## 1. Preconditions and ownership gates

- [ ] 1.1 Record the exact baseline commit (`0e53b7efec27f5821056bd546b8a245144414fdb`) and confirm the dedicated worktree/branch; do not reinterpret the missing `main0e53b7e` ref as a different commit.
- [ ] 1.2 Index the existing T04–T12 and Redis-sibling planning artifacts by owner/path, then collect proof of P0 merge, rebase onto the selected baseline, and spec sync; record T12 optional cuts (statistics → notifications → cache). Existing but unmerged or unsynced planning artifacts do not satisfy the evidence gate; keep the change Draft when any proof is missing.
- [ ] 1.3 Publish an ownership map for allowed T13 paths (integration/E2E tests, JMeter, `deploy/**`, deployment/performance/runbook/demo docs) and a stop-and-request template for T01–T12 defects or shared-file changes.
- [ ] 1.4 Select and document the approved headless CLI/browser runner available in the apply environment; if unavailable, mark E2E execution blocked rather than substituting unapproved tooling silently.

## 2. Integration and headless E2E evidence

- [ ] 2.1 Create an isolated test profile that injects generated credentials, points at disposable MySQL/Redis services, and defaults to non-public endpoints.
- [ ] 2.2 Implement API integration coverage for register/login, password non-disclosure, resource/category/slot browsing, direct and pending booking creation, approval/rejection, cancellation, check-in, no-show/violation, terminal slot release for `REJECTED`/`CANCELLED`/`NO_SHOW`, refresh/persistence, and idempotency; cover `COMPLETED` release only when an approved optional automatic-completion feature is enabled, never as a P1 prerequisite.
- [ ] 2.3 Implement boundary coverage for missing/malformed/expired credentials, student-to-admin 403, cross-owner read/cancel, invalid transitions, missing resources, and same-slot 409 conflicts; assert canonical envelopes and no unauthorized mutation, classifying HTTP 409/code `43000` `SYSTEM_BUSY` by message/category as a separate error.
- [ ] 2.4 Implement headless browser flows for both direct and approval paths, including route refresh after each state change; capture screenshots and network traces with Authorization, cookies, passwords, and PII redacted.
- [ ] 2.5 Link each E2E artifact to a requirement and record failures as owner change requests without editing business/frontend/common source in T13.

## 3. JMeter concurrency experiment

- [ ] 3.1 Add parameterized JMeter plans for the isolated vulnerable baseline, unique-index-only round, and unique-index-plus-Redisson round using clean data scopes.
- [ ] 3.2 Configure the same-slot case for 100 concurrent requests and a distinct-resource/date case for lock-granularity comparison; ensure the baseline cannot reach public or production endpoints.
- [ ] 3.3 Add result extraction for success, HTTP 409 business conflicts, HTTP 409/code `43000` `SYSTEM_BUSY` errors (classified by message/category), 500/connection/data errors, average/P95/P99 latency, thread/ramp settings, environment versions, and final booking/slot row counts.
- [ ] 3.4 Produce a three-round report where exactly one success and 99 business conflicts is asserted only for each healthy-Redis, valid-seed, same-slot protected round; label baseline duplicates as historical vulnerability evidence, and count `SYSTEM_BUSY` separately as an error.

## 4. Container and Nginx deployment plan

- [ ] 4.1 Add the JDK 17 backend Dockerfile and frontend build-plus-Nginx Dockerfile with pinned non-floating base references, digest recording, non-root settings where practical, and no secret material.
- [ ] 4.2 Add Compose services for Nginx/API/MySQL 8/Redis with private DB/Redis networks, no MySQL/Redis host ports, public 80/443 only, named volumes, healthchecks, restart policies, dependency ordering, resource limits, and bounded logging.
- [ ] 4.3 Add Nginx SPA fallback and `/api` proxy configuration with security headers, request-body limits, upstream timeouts, internal-address hiding, and certificate/key mounts without committed keys.
- [ ] 4.4 Add a non-secret `.env.example` documenting required runtime variables, image pin/digest update procedure, certificate renewal steps, and the distinction between local compose validation and external deployment acceptance.

## 5. Migration, backup, restore, and recovery runbooks

- [ ] 5.1 Add an empty-database verification script/runbook that applies V001–V005 to two fresh MySQL 8 databases, asserts exactly twelve tables/required indexes/no seed, and proves identical definitions without editing migrations.
- [ ] 5.2 Add a consistent MySQL backup/restore runbook with isolated restore target, schema/booking/slot checks, checksum or row evidence, and explicit operator-recorded RPO/RTO assumptions.
- [ ] 5.3 Add a volume restart/recreate procedure proving MySQL persistence and health-gated recovery for API/MySQL/Redis; capture logs and compose config output.
- [ ] 5.4 Add Redis-failure behavior checks for both consumers: T07 booking-lock outage MUST fail closed with HTTP 409/code `43000` `SYSTEM_BUSY` and MUST NOT use DB-only fallback; T12 availability/cache outage MAY fall back to MySQL. Prove database uniqueness and authorization remain correct and record message/category, latency, and recovery evidence.
- [ ] 5.5 Add conservative image/config rollback and recovery steps that preserve volumes and treat committed migrations as forward-only unless an owner-approved recovery plan exists.

## 6. Demo orchestration and evidence

- [ ] 6.1 Request or reference a T01-owned seed change or define an ephemeral runtime fixture; keep the empty-migration lane seed-free and stop if ownership is not approved.
- [ ] 6.2 Generate deterministic non-PII admin/student/resource/booking/violation data with strong runtime-injected passwords; redact credentials from logs and evidence.
- [ ] 6.3 Add setup/teardown orchestration scoped to the fixture-owned database/volume and a demo script covering the required lifecycle and authorization steps.
- [ ] 6.4 Produce a redacted evidence bundle mapping screenshots/network traces to acceptance requirements and mark the demo gate Draft when setup, owner approval, or evidence is incomplete.

## 7. Local verification gates

- [ ] 7.1 Run `cd booking-api && mvn verify` on JDK 17 and preserve the actual output.
- [ ] 7.2 Run a clean frontend install/build using the repository-pinned lockfile and preserve the actual output; do not modify package manifests in T13.
- [ ] 7.3 Run strict OpenSpec change validation, main-spec validation after any future sync, and `git diff --check`; record unexecuted checks explicitly.
- [ ] 7.4 Run compose config, image/dependency/artifact scan, secret scan, health smoke, empty migration, backup/restore, restart persistence, Redis failure, and local headless E2E checks; attach raw evidence and classify failures.
- [ ] 7.5 Review changed paths against ownership and verify no `.env`, key, token, password, PII, build output, migration edit, or public DB/Redis port is present.

## 8. External acceptance gate

- [ ] 8.1 Obtain explicit user authorization for the target host/domain, DNS ownership, TLS certificate/renewal mechanism, and any required credentials before attempting public acceptance.
- [ ] 8.2 If authorization and infrastructure are provided, run the documented deployment, HTTPS, public smoke, rollback, and monitoring checks without automatic spend/provisioning; otherwise mark these tasks not run/blocked.
- [ ] 8.3 Keep the PR/change Draft until T04–T12 gates, all required local evidence, spec sync, and any authorized external checks are complete; never claim public URL, domain, or certificate completion from local evidence alone.
