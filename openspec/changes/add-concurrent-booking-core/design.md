## Context

The frozen DDL already provides `booking`, `booking_slot`, `uk_resource_slot(resource_id,slot_time)`, booking indexes, logical deletion for booking history, and physical occupancy rows. The shared contracts fix canonical envelopes, Long/time serialization, roles, booking statuses, and 30-minute same-day slots. Identity-access owns authentication/principal construction; resource-catalog owns resource, time-rule, and closure behavior; T06 is intended to own availability calculation. This change therefore consumes those contracts and adds only the booking package implementation described in proposal.md and `specs/booking-core/spec.md`.

Two repository facts are preconditions rather than implementation work: reviewed T06 planning artifacts exist in the sibling worktree `D:/Projects/project1_campus/target/worktrees/add-resource-availability` but are not merged into this base, and the reviewed T01 Redis foundation planning exists in `D:/Projects/project1_campus/target/worktrees/add-redis-concurrency-foundation/openspec/changes/add-redis-concurrency-foundation/` but is not merged. Apply must record each merge/rebase commit, re-read the merged artifacts/contracts, and pause if either gate is not satisfied; the current `pom.xml` has no Redisson or Spring Data Redis dependency.

## Goals / Non-Goals

**Goals:**

- Keep create/list/detail behavior and error envelopes exact, including ownership masking and deterministic ordering.
- Re-check all conflict-sensitive rules inside a resource/date critical section, then persist booking plus every slot atomically.
- Make MySQL uniqueness the final correctness boundary while using a resource/date Redisson lock to reduce avoidable conflicts.
- Preserve a small, explicit domain-action boundary that T09/T10 can extend for approval, cancellation, and check-in.
- Produce reproducible evidence for pure slot boundaries, rollback, unique conflicts, real Redis locking, same-slot contention, and independent lock domains.

**Non-Goals:**

- No edits to SQL migrations, `booking-api/pom.xml`, `application.yml`, common/exception/security infrastructure, auth/user/resource/availability/frontend modules, or deploy files.
- No approval, cancellation, check-in, no-show, completion, violation, notification, or cache routes; no arbitrary status update API. This change defines only an optional post-commit handoff for T12 cache invalidation and does not implement a cache.
- No Redis cache, cache-aside implementation, fallback lock implementation, reflection-based dependency loading, hand-written `SETNX` lock, or weakening/removal of `uk_resource_slot`. A T12 cache failure may use T12's database fallback for availability reads, but it is never a booking rollback signal.
- No implementation while T06/T01 preconditions are unmet; this change is planning-only until a separate apply request.

## Decisions

### 1. Keep one booking capability inside the T07 ownership fence

Implement controller, DTO, VO, entity, mapper/XML, service, model, and support types only below `com.yu030x.booking.booking`, with tests below the matching booking test tree. Existing identity and resource services/mappers are read-only collaborators. This avoids modifying shared files and keeps SQL ownership with T01. A booking mapper may read the frozen resource/user/rule/closure/blacklist data through already-merged contracts, but must not create availability rows or change catalog behavior.

### 2. Split lock coordination from the transaction bean

Use an outer booking application service to perform cheap request parsing and preliminary checks, acquire `booking:lock:{resourceId}:{bookingDate}`, and call a separate transaction-owned creator bean. The creator re-runs all creation-sensitive checks while the lock is held, builds the business number and initial status, inserts the booking, generates `[start,end)` 30-minute slots, and batch-inserts every slot. `@Transactional` is on the independent creator bean so a same-class call cannot bypass the proxy. The lock wrapper always uses `tryLock(3, TimeUnit.SECONDS)` with wait time only, then `finally` plus `isHeldByCurrentThread()` before `unlock()`.

Alternative considered: one large service method with `this.createBooking()` was rejected because Spring self-invocation can bypass the transaction proxy. A global lock was rejected because it needlessly serializes different resources and dates.

### 3. Treat MySQL uniqueness as correctness and Redis as an optimization gate

The database unique key remains untouched. Any `DuplicateKeyException` from any generated slot is translated to HTTP 409/code `43000` with `该时段已被占用，请刷新后重试` (`SLOT_CONFLICT`) and causes the whole transaction to roll back. Redis communication failure or a failed lock acquisition fails closed with HTTP 409/code `43000`, `data:null`, and `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`); it never silently executes an unlocked or DB-only path. This is stricter than the older docs/15 suggestion that allowed a database-only fallback, and follows the T07 freeze supplied for this change. Different resource/date keys remain independent.

Alternative considered: continue on Redis failure and rely only on MySQL. That would preserve eventual database correctness but violate the explicit T07 fail-closed contract and would make lock health invisible to callers, so it is not selected. T08/frontend handoff must keep `SLOT_CONFLICT` and `SYSTEM_BUSY` distinct even though both use code `43000`.

### 4. Make validation order explicit and repeat the critical subset

The application service follows the 16-step core sequence in `docs/15-项目一开发实施手册.md:462-481`: request shape; `startTime < endTime`; same-day; `:00`/`:30` alignment with seconds and nanoseconds zero; `startTime > now`; duration; inclusive `maxAdvanceDays`; resource exists/not deleted/status `1`; capacity; global/resource closure; open-rule containment; user enabled/not deleted; blacklist; active-booking count; resource/date lock; transaction. This is alignment with the older draft order, not a behavior expansion. After lock acquisition, the transaction bean re-reads the conflict-sensitive resource/rule/closure/user/blacklist/active-count/slot conditions before insertion. T06 availability is a read-only/pure input and never an authority for success. The date uses `Asia/Shanghai`; capacity is enforced only when non-null; open rules must fully contain the interval.

The document's step 17 (`docs/15-项目一开发实施手册.md:482`) is outside that validation sequence. After the booking transaction has committed successfully, this change may emit an optional T12 after-commit cache-invalidation handoff for `resource:available-slots:{resourceId}:{date}`. T07 does not create or own the cache; if T12 has not merged, no cache path is added. If T12 cannot invalidate the cache, T12 uses its database fallback for availability reads, while the already-successful booking remains committed. This cache failure is independent of lock failure and MUST NOT weaken the T07 Redis fail-closed policy.

### 5. Keep response mapping separate from persistence models

CreateRequest, persistence entities, and BookingView remain separate. BookingView is assembled with exactly the DDL-backed field set, Long values serialized as strings, nulls preserved, and Shanghai-local formatted timestamps. List queries constrain `user_id` to the authenticated principal and order by `created_at DESC,id DESC`; detail queries use the same ownership predicate so missing and foreign IDs naturally share 404/40400 behavior. The business number is generated independently of the auto-increment ID and is the only public booking identifier besides the serialized ID.

### 6. Expose an explicit downstream lifecycle seam

The booking domain owns the legal status vocabulary and exposes named action/release seams for later changes, but this change wires only initial state. T09/T10 must call those explicit actions for approve/reject/cancel/check-in/no-show and must release slots transactionally for terminal transitions. No public `updateStatus(target)` is introduced. This preserves the T07 ownership of the state machine while allowing later changes to add their routes without direct status SQL.

### 7. Use a shared-file change request instead of local dependency edits

The planning artifacts record a precondition/request to T01: the sibling Redis foundation planning at `D:/Projects/project1_campus/target/worktrees/add-redis-concurrency-foundation/openspec/changes/add-redis-concurrency-foundation/` must be merged/rebased; the merge/rebase commit must be recorded and the merged dependency/configuration contract re-read before apply. T01 selects and locks a Boot-3-compatible Redisson/Spring Data Redis version and exposes only env-backed `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`. T07 will consume the merged API/configuration and must not edit `pom.xml`, application configuration, or dependency files. Reflection or an invented lock substitute is prohibited. If the request is not merged, apply stops before implementation.

## Risks / Trade-offs

- **[T06 planning not merged]** → The reviewed planning exists in the sibling worktree, but not in this base. Record the T06 merge/rebase commit, re-read its contract, and stop apply if that gate is not satisfied; do not infer or recreate availability.
- **[Shared Redisson dependency not merged]** → The reviewed Redis foundation planning exists in its sibling worktree but is not merged. Record the merge/rebase commit and re-read the shared-file contract; do not add a local dependency or configuration workaround.
- **[Redis outage reduces availability]** → Return explicit 409/43000 system-busy rather than pretending the lock exists; retain MySQL uniqueness for all paths.
- **[Long bookings create many slot rows]** → Enforce resource min/max duration and 30-minute boundaries; batch insert with one transaction and test rollback.
- **[Lock contention can add latency]** → Key by resource and date, cap wait at three seconds, and measure same-slot versus different-domain concurrency.
- **[Historical document drift]** → Treat docs/15 and docs/16's resource/date lock as authoritative for T07 over docs/06's older resource-only example; note docs/11's duplicate numbered bullet as a documentation defect without changing schema.

## Migration Plan

1. Before apply, verify T06 and the T01 Redis foundation have merged/rebased onto the selected base, record both merge/rebase commits, and re-read both merged planning/contracts; otherwise stop.
2. Apply only the booking-owned Java/XML/test paths in the dedicated worktree. No migration or shared-file change is part of this change.
3. Run pure tests, MySQL 8 transaction/rollback/unique-conflict tests, real Redis lock tests, the 50–100 request contention test, independent resource/date parallelism, `mvn verify`, OpenSpec strict validation, and `git diff --check`.
4. If implementation must be rolled back, revert the booking-only change; leave the frozen schema and shared dependency commit intact. Do not retain the intentionally vulnerable baseline in a deployed branch.

## Open Questions

None that may safely be deferred: the lock failure policy, exact key, validation order, response fields, ownership masking, transaction boundary, dependency ownership, and T06/T09/T10 handoffs are frozen by this plan. T01 alone decides the compatible library version within its separate shared-file change.
