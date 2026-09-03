## Why

The P0 booking flow needs operational visibility and optional read performance improvements, but the repository currently has no implementation contract for operation logs, availability caching, in-app notifications, or administrator statistics. T06/T07/T09/T10 and Redis-concurrency sibling planning artifacts exist, but remain unmerged and unreviewed; T12 is deliberately deferred until the P0 owners have handed off stable ports and events from committed, reviewed work. This change records the independently switchable P1 contracts so each capability can be accepted or cut without weakening booking correctness.

## What Changes

- Add an allowlisted AOP operation-log capability that writes the exact `operation_log` DDL shape, bounds and redacts parameters, records success/error/cost/IP/user/time, and isolates log-write failures from the primary request.
- Add an optional Cache Aside capability for the availability GET path using the exact `resource:available-slots:{resourceId}:{date}` key, a deterministic final TTL of 300–900 seconds (5–15 minutes), post-commit invalidation, and MySQL calculation fallback whenever Redis/cache is unavailable.
- Add an optional in-app notification capability with the two frozen `/api/v1` endpoints, owner-only idempotent read marking, stable pagination, post-commit creation events, and deterministic duplicate suppression; no email or SMS.
- Add an optional ADMIN statistics capability with bounded date ranges, exact aggregate DTOs, MySQL 8 `EXPLAIN` evidence, and no raw PII or schema migration.
- Give operation log, cache, notifications, and statistics independent feature flags whose shared-config owner supplies environment binding and default `false`; T12 does not edit shared configuration.
- Define the corresponding frontend API/view handoffs without editing frozen routes/contracts or their shared router, Axios client, contracts file, build files, SQL, common, booking, resource, availability, user, or deploy ownership areas; P1 entries may be appended only by the corresponding single-writer owner after an accepted handoff.
- Preserve the strict delivery order: implement/verify observable logs first, then cache, then notifications, then statistics. If scope must be cut, remove statistics first, then notifications, then cache; cutting cache must never remove or weaken T07's booking Redis lock or its fail-closed behavior, and P0 correctness and operation-log contracts remain unaffected.

## Capabilities

### New Capabilities

- `supporting-cache`: feature-gated availability Cache Aside reads and after-commit invalidation.
- `operation-log`: feature-gated allowlisted AOP operation logging with redaction and failure isolation.
- `notifications`: feature-gated in-app notification listing, read acknowledgement, and post-commit idempotent creation.
- `statistics`: feature-gated ADMIN resource-usage and booking-status aggregate queries.

### Modified Capabilities

- `shared-contracts`: extend the frozen API/frontend matrix for notification and administrator-statistics endpoints and their P1 route handoffs.

## Impact

- Owned implementation/test paths: `booking-api/src/main/java/com/yu030x/booking/cache/**`, `notification/**`, `log/**`, `statistics/**`, their matching test trees, and the corresponding P1 frontend directories only.
- Cross-module calls are ports/events owned by T03/T06/T07/T09/T10 and the shared-config/router owners. T12 must not edit their packages or shared files; sibling planning artifacts are present but unmerged/unreviewed, and only committed, reviewed handoffs are release gates.
- No `pom.xml`, configuration, SQL, common, booking/resource/availability/user, router/http, or deploy changes are authorized by this change.
- Required acceptance evidence includes real Redis hit/miss/failure and commit/rollback invalidation tests, AOP redaction/failure-isolation tests, notification ownership/idempotency/security tests, MySQL 8 aggregate tests with `EXPLAIN`, strict OpenSpec validation, `git diff --check`, `mvn verify`, and frontend build when UI is added. Unrun checks must remain explicitly unclaimed.
- Gate before implementation: auditable T06/T07/T09/T10 and `add-redis-concurrency-foundation` artifacts/owner handoffs at or after `main` `2ffae9d` (the verified equivalent shared base; the originally pinned `0e53b7e` is an unreachable historical object). The artifacts exist in sibling worktrees as planning/unmerged material; uncommitted planning, an unreviewed worktree, or a branch still at the base commit does not satisfy the merged/reviewed gate.
