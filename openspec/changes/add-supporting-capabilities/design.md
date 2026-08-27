## Context

T12 is a P1 wave-4 change on top of the frozen foundation, identity, resource, availability, booking, approval, and check-in contracts. T06/T07/T09/T10 and Redis-concurrency sibling artifacts exist as planning files, but are not merged or reviewed into this base, so implementation is gated on committed owner handoffs rather than inferred APIs or uncommitted planning. The planning work lives on `codex/add-supporting-capabilities` in `D:/Projects/project1_campus/target/worktrees/add-supporting-capabilities` and must not modify implementation code in this turn.

## Goals / Non-Goals

**Goals:**

- Provide four independently switchable, testable P1 capabilities with safe `false` defaults and explicit owner handoffs.
- Preserve MySQL booking correctness, authorization, frozen DDL/API/domain contracts, and the strict implementation/cut order.
- Make cache, logging, notifications, and statistics degradations observable and reversible.

**Non-Goals:**

- No email/SMS/push, notification admin query, complex dashboard, outbox table, schema migration, Redis lock redesign, booking-success decision, or P0 state-machine change. Cutting the optional cache must never cut or weaken T07's booking Redis lock.
- No edits to pom/config/sql/common/booking/resource/availability/user/router/http/deploy; any required cross-module change is a handoff.

## Decisions

1. **Feature flags and cut order.** Four independent logical flags (`operation-log`, `cache`, `notifications`, `statistics`) are read through a shared-config contract supplied by its owner; each defaults to `false` and T12 does not edit configuration. Work and acceptance proceed in the order operation log, cache, notifications, statistics. If delivery is cut, remove statistics, then notifications, then cache; never remove the P0 MySQL correctness path or silently drop required log safety behavior.
2. **Ownership fence.** Backend production and tests are limited to `cache/**`, `notification/**`, `log/**`, and `statistics/**`; frontend work is limited to the agreed P1 notification/statistics views/components/API modules and their tests. Every cross-module call is a port/event handoff to T03/T06/T07/T09/T10 or the shared owners.
3. **Cache adapter.** Implement a small cache port owned by `cache/**`. The availability owner calls it only for the exact resource/date GET. Each cache write uses the deterministic bounded-jitter algorithm `ttlSeconds = 300 + (uint32(SHA-256(key)[0..3]) mod 601)`, so the final TTL is always 300..900 seconds (5..15 minutes inclusive). Redis/cache failure is an availability-only miss: calculate from the availability database and do not alter T07's Redis booking-lock path or its fail-closed lock-busy/outage behavior. Invalidation is registered through after-commit synchronization/events for booking create/cancel/reject/no-show and resource status/rules/closures. Optional single-flight is local to the package and may never block fallback.
4. **Operation-log aspect.** Use an annotation plus a narrow allowlist of action keys. The aspect captures method outcome and request context, sanitizes a bounded parameter projection, and submits a best-effort write using the exact docs/11 fields. Log persistence errors are swallowed/diagnosed outside the business transaction and must not recursively trigger the aspect. No query controller is planned.
5. **Notification lifecycle.** The notification package owns entity/mapper/service/controller and a post-commit event consumer. Producers publish only after their transaction commits. With no database unique key and no SQL change, creation must lock the recipient user row with `SELECT ... FOR UPDATE` (or an equivalent database serialization primitive) in the same transaction as the identity check and insert. For deterministic business events, recipient + type + bizId is the idempotency identity; `bizId = NULL` participates in identity comparison and matches only another NULL (`biz_id = :bizId OR (biz_id IS NULL AND :bizId IS NULL)`). GET is current-user scoped and ordered by createdAt/id descending; read acknowledgement is owner-only and idempotent.
6. **Statistics read model.** The statistics package exposes only the two ADMIN GET routes and exact aggregate DTOs in its delta spec. For each date in the inclusive Asia/Shanghai range, the usage-rate denominator is the sum of minutes made schedulable by that day's `resource_time_rule`; a closure day contributes zero. Denominators are summed across the range, and `usageRate` is null when the sum is zero. The numerator is occupied minutes under the frozen booking/slot semantics. Queries use existing booking/resource indexes and bounded inclusive date ranges (maximum 366 days). MySQL 8 `EXPLAIN` is a required acceptance artifact; a slow plan becomes an index-request handoff, never a T12 SQL migration.
7. **Frontend handoff.** Add only P1 API/view modules for notifications and statistics. Frozen routes/contracts and existing `contracts.js`, `router/index.js`, and `http.js` remain single-writer files; P1 entries may be appended only by the corresponding owner after that owner accepts the handoff. If no route handoff is accepted, frontend artifacts remain gated and no unreachable page is claimed as delivered.

## Risks / Trade-offs

- [Owner artifacts are only planning/unmerged] → Stop at the precondition gate; do not treat uncommitted planning as merged/reviewed, guess T06/T07/T09/T10 ports, or edit their packages.
- [Redis outage or stale cache] → Fall back only the availability read to its database calculation, keep T07's Redis lock fail-closed and its booking correctness unchanged, and invalidate only after commit.
- [Log persistence outage] → Isolate best-effort writes and preserve the primary response; emit bounded diagnostic evidence without recursion.
- [Duplicate post-commit delivery] → Require deterministic recipient/type/bizId identity and serialize the recipient check-and-insert; a future durable outbox is a separate change.
- [Statistics query plan is slow] → Capture MySQL 8 `EXPLAIN`, file an index request to the SQL owner, and do not widen ranges or add migrations.
- [Feature flag default false hides incomplete wiring] → Each acceptance record must show the flag state, owner handoff, and an explicit enabled-path test; disabled behavior is not claimed as feature delivery.

## Migration Plan

1. Obtain Gates A–C from the proposal/design and verify the base commit and clean worktree.
2. Implement and verify operation log, then cache, then notifications, then statistics, each behind its own default-false flag and with its own acceptance evidence.
3. Add only agreed P1 frontend modules after route/API handoff; run the frontend build when UI files exist.
4. Roll back by setting the affected flag to `false` and reverting only the owned package/endpoint change. No SQL or data rollback is required because this change adds no schema.
5. After implementation (not in this planning turn), run strict change validation, sync finalized deltas to main specs, validate main specs, and keep the PR Draft until all gates pass.

## Integration Gates

- Gate A: auditable T06 `add-resource-availability`, T07 `add-concurrent-booking-core`, T09 `add-booking-approval-cancellation`, and T10 `add-checkin-no-show-violation` artifacts/owner handoffs exist as sibling planning files, but must be committed, reviewed, and available at a descendant of `0e53b7e`; uncommitted planning or an unreviewed worktree does not satisfy the gate.
- Gate B: `add-redis-concurrency-foundation` has sibling planning artifacts and must provide a committed, reviewed Redis client/config port without requiring T12 to edit pom/config; its planning-only branch state does not satisfy the gate.
- Gate C: shared-config and frontend single-writer owners must accept independent flags and route/API handoffs. Missing acceptance stops implementation.
- Gate D: T12 must pass security 401/403 tests, real Redis/cache behavior, MySQL 8 integration and `EXPLAIN`, `mvn verify` (when backend implementation exists), frontend build (when UI is added), strict OpenSpec validation, and `git diff --check`; no unrun result is reported as passed.
