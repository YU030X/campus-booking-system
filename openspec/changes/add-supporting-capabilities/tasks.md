## 0. Preconditions and gates

- [ ] 0.1 Reconfirm the worktree is `D:/Projects/project1_campus/target/worktrees/add-supporting-capabilities`, branch `codex/add-supporting-capabilities`, and base `0e53b7e`; record `git status --short` before any apply work.
- [ ] 0.2 Obtain and review auditable T06/T07/T09/T10 and `add-redis-concurrency-foundation` artifacts/owner handoffs. Their sibling planning artifacts exist but are unmerged/unreviewed; record committed descendant and review evidence before proceeding, and do not treat uncommitted planning as satisfying the merged/reviewed gate.
- [ ] 0.3 Obtain shared-config and frontend single-writer handoffs for four independent default-false flags and notification/statistics route/API entries. T12 must not edit frozen routes/contracts; P1 entries are appended only by the corresponding owner after accepting the handoff. Do not edit config, router, HTTP, contracts, pom, SQL, common, or deploy files.

## 1. Observable operation log (highest implementation priority)

- [ ] 1.1 Define the `log/**` annotation, approved action-key registry, context projection, and exact docs/11 field mapping; document owner handoff points without changing owner packages.
- [ ] 1.2 Implement bounded recursive redaction/truncation for parameters and error messages; cover passwords, complete JWTs, DB/Redis credentials, full phones, nested values, and oversized bodies.
- [ ] 1.3 Implement the allowlisted AOP outcome/cost/IP/user capture and failure-isolated persistence; ensure log failures never alter the primary response/transaction and do not recurse.
- [ ] 1.4 Add unit/integration tests for approved vs unapproved pointcuts, success/error fields, redaction, bounded output, and simulated DB/serialization failure isolation.
- [ ] 1.5 Verify operation-log tests and `mvn verify` when backend code exists; record exact output and run `git diff --check`.

## 2. Optional availability cache

- [ ] 2.1 After Gate A/B/C, define the `cache/**` port for exact availability key construction and deterministic `ttlSeconds = 300 + (uint32(SHA-256(key)[0..3]) mod 601)` expiry (final TTL always 5–15 minutes), hit/miss/fallback outcomes, and post-commit invalidation requests.
- [ ] 2.2 Implement the enabled Cache Aside read path and disabled-flag no-Redis path; Redis/cache failure may only fall back the availability read to its database calculation and must not alter T07's fail-closed booking Redis lock or cut it when cache scope is reduced.
- [ ] 2.3 Implement after-commit invalidation hooks consumed through owner ports/events for booking create/cancel/reject/no-show and resource status/rule/closure mutations; prove rollback causes no invalidation.
- [ ] 2.4 Add real Redis integration tests for hit, miss, deterministic TTL algorithm and inclusive 300..900-second bounds, outage/write failure availability-database fallback (with T07 lock fail-closed behavior unaffected), invalidation after commit for booking create/cancel/reject/no-show and resource status/rule/closure changes, no invalidation after rollback, and optional single-flight failure safety. Do not claim an embedded/mock Redis test as real Redis evidence.
- [ ] 2.5 Verify cache tests, relevant backend build, and `git diff --check`; capture before/after latency or hit/miss evidence without claiming unrun measurements.

## 3. Optional in-app notifications

- [ ] 3.1 After the event and shared-route handoffs, define exact entity/DTO/VO boundaries under `notification/**` and agreed P1 frontend notification directories; keep Long IDs stringified and fields within 100/1000/30 limits.
- [ ] 3.2 Implement authenticated current-user `GET /api/v1/notifications` with pageNumber/pageSize bounds and createdAt/id descending ordering.
- [ ] 3.3 Implement owner-only idempotent `POST /api/v1/notifications/{id}/read`; verify foreign/missing IDs do not leak data or mutate state.
- [ ] 3.4 Implement post-commit event consumption with no SQL/schema change and no database unique key: lock the recipient user row with `SELECT ... FOR UPDATE` (or equivalent serialization) in the same transaction as the identity check and insert; compare `bizId=NULL` as equal only to NULL. Prove rollback emits nothing, sequential retries insert at most one row, and two concurrent first deliveries behind a barrier insert exactly one row. Do not add email/SMS.
- [ ] 3.5 Add API/security/integration tests for 401/403/404, pagination/order, field boundaries, ownership, repeat-read idempotency, post-commit/rollback, NULL-bizId identity, and a two-consumer concurrent double-delivery test asserting final count=1.
- [ ] 3.6 Add only agreed P1 frontend API/views/components/tests; route/HTTP/contracts changes remain handoffs. Run `npm run build` if UI files are added and record exact output.

## 4. Optional administrator statistics (lowest priority)

- [ ] 4.1 After the availability/rules and shared-route handoffs, define exact `ResourceUsageAggregate` and `BookingStatusAggregate` DTOs and the no-PII response envelopes under `statistics/**`.
- [ ] 4.2 Implement ADMIN-only resources/bookings GET routes with independent flag, inclusive `fromDate`/`toDate`, Asia/Shanghai parsing, reversed/invalid/>366-day rejection, and deterministic grouping order. Freeze `usageRate` as occupied minutes divided by the sum of each day's `resource_time_rule` schedulable minutes (closure days contribute zero), summed across the range; return null when the denominator is zero and use the frozen booking/slot occupancy semantics.
- [ ] 4.3 Implement MySQL 8 aggregate queries using existing indexes only; do not edit `sql/**`. If plans are slow, create an index-request handoff and stop rather than adding a migration.
- [ ] 4.4 Add MySQL 8 integration tests, SQL `EXPLAIN` evidence, DTO/no-PII assertions, and 401/403/security tests. Record real commands/results.
- [ ] 4.5 Add only agreed P1 frontend statistics API/views/tests; run `npm run build` when UI is added and run `git diff --check`.

## 5. Final acceptance and handoff

- [ ] 5.1 Run the relevant backend suite and `mvn verify`; run frontend build only when T12 UI exists; preserve separate evidence for real Redis and MySQL 8/EXPLAIN checks.
- [ ] 5.2 Run `openspec validate add-supporting-capabilities --type change --strict --no-interactive` and `git diff --check`; fix artifact issues without editing implementation files.
- [ ] 5.3 Verify each feature can independently remain disabled/default-false and that cutting statistics, then notifications, then cache leaves P0 booking correctness and T07 tests unchanged.
- [ ] 5.4 Prepare owner handoff notes, risks, rollback (flag off), unrun checks, and spec-sync instructions; do not sync/archive/commit/push in this planning-only workflow.
