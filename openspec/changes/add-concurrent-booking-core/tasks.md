## 1. Preconditions and scope gate

- [x] 1.1 Verify the apply base is the reviewed main/T06 merge or rebase. T06 planning artifacts exist and have been reviewed in sibling worktree `D:/Projects/project1_campus/target/worktrees/add-resource-availability`, but are not merged; record the T06 merge/rebase commit, re-read the merged read-only/pure-function contract, and stop without implementation if that evidence is missing. Evidence: the worktree was fast-forwarded to apply base `025b400`, which contains T06 merge commit `2d5aee4`; the archived T06 design and merged `resource-availability` read-only/pure-function contract were re-read before implementation.
- [x] 1.2 Verify the reviewed T01 Redis foundation planning at `D:/Projects/project1_campus/target/worktrees/add-redis-concurrency-foundation/openspec/changes/add-redis-concurrency-foundation/` has merged/rebased. Record its merge/rebase commit and re-read the shared contract before apply; stop until then. Do not edit `pom.xml`, `application.yml`, SQL, common, auth, user, resource/availability, frontend, or deploy files. Evidence: apply base `025b400` contains Redis implementation tip `0b05aa6` and archive commit `f8d1671`; the archived Redis design plus merged shared Redis and backend dependency contracts were re-read before implementation.
- [x] 1.3 Confirm the implementation worktree is `D:/Projects/project1_campus/target/worktrees/add-concurrent-booking-core` on `codex/add-concurrent-booking-core`, with only the booking ownership paths permitted by this change. Confirmed after fast-forward: branch `codex/add-concurrent-booking-core` at `025b400`; existing staged `docs/**` files are preserved as unrelated user content and will be excluded from every T07 commit.

## 2. Booking contracts and persistence boundary

- [ ] 2.1 Add the booking-owned request/response models for `CreateBookingRequest` and the exact `BookingView` field set, including unknown-field rejection, decimal-string resource ID, purpose trim/Unicode-code-point limit, attendee count, Shanghai time format, Long-as-string serialization, and nullable values.
- [ ] 2.2 Add booking and `booking_slot` entities, mappers, and XML under the booking package against the frozen DDL/indexes; preserve `uk_resource_slot`, physical slot deletion semantics, and booking logical-delete filtering without changing SQL migrations.
- [ ] 2.3 Add the pure `[start,end)` 30-minute slot splitter and reject cross-day, non-`:00`/`:30`, nonzero-second/nanosecond, empty, or reversed intervals before it is called; keep the function independent enough for deterministic unit tests.

## 3. API and ordered business validation

- [ ] 3.1 Expose `STUDENT POST /api/v1/bookings`, authenticated `GET /api/v1/bookings?pageNumber&pageSize&status`, and authenticated `GET /api/v1/bookings/{id}` with canonical Result/PageResult envelopes, HTTP 201 for create, HTTP 200 for list/detail, and no cancel/check-in/approval routes.
- [ ] 3.2 Implement current-user list/detail queries with `user_id` ownership predicates, stable `createdAt DESC,id DESC` ordering, page bounds, optional status filtering, and 404/40400 masking for missing, deleted, or foreign IDs.
- [ ] 3.3 Implement the 16-step core order from `docs/15-项目一开发实施手册.md:462-481`: request shape; `startTime < endTime`; same-day; `:00`/`:30` alignment with seconds and nanoseconds zero; `startTime > now`; duration; inclusive `maxAdvanceDays`; resource exists/not deleted/status `1`; capacity; global/resource closure; open-rule containment; user enabled/not deleted; blacklist (`start_date <= today <= end_date`); active-booking count (default `3`); resource/date lock; transaction. This aligns the older draft core sequence and does not expand behavior.
- [ ] 3.4 Consume T06 availability only as a read-only/pure input and repeat all conflict-sensitive resource, rule, closure, user, blacklist, active-count, and slot checks inside the lock; do not use cache or availability output as final booking authority.
- [ ] 3.5 Define the optional step-17 handoff only: after a successful transaction commit, T12 may invalidate `resource:available-slots:{resourceId}:{date}`. If T12 is not merged, do not build a cache or invalidation path. If invalidation fails, rely on T12's database fallback for availability reads, keep the booking committed, and leave T07's Redis lock fail-closed policy unchanged.

## 4. Locking, transaction, and lifecycle seam

- [ ] 4.1 Implement the resource/date lock coordinator with key `booking:lock:{resourceId}:{bookingDate}`, `tryLock(3,TimeUnit.SECONDS)` wait time only, watchdog-compatible behavior, owner-checked `finally` unlock, independent resource/date domains, and fail-closed Redis/lock failure mapping to HTTP 409/code `43000`/`data:null` with `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`); never use a DB-only downgrade.
- [ ] 4.2 Implement an independent transactional creator bean that re-checks the critical rules, chooses `PENDING_APPROVAL` or `CONFIRMED` from `needApproval`, generates a non-sequential business number, inserts one booking, splits all slots, and batch-inserts every slot in the same transaction.
- [ ] 4.3 Translate any `DuplicateKeyException` from slot insertion to HTTP 409/code `43000`/`data:null` with `该时段已被占用，请刷新后重试` (`SLOT_CONFLICT`); keep it distinct from `SYSTEM_BUSY`'s `当前预约请求较多，请稍后重试` even though the code is shared. Verify the booking row and all partial slots roll back and never swallow the exception as success; later frontend work must map by message/category without conflation.
- [ ] 4.4 Define explicit booking domain action/release seams for T09/T10 (approval, reject, cancel, check-in, no-show and terminal slot release) without implementing those routes or exposing arbitrary `updateStatus(target)`.

## 5. Verification evidence

- [ ] 5.1 Add pure-function tests for 14:00–14:30 (one slot), 14:00–16:00 (four slots), end excluded, adjacent half-open intervals, invalid alignment, cross-day, start-after-now, duration, capacity, closure, open-rule containment, blacklist date bounds, and active-booking limit.
- [ ] 5.2 Add MySQL 8 integration tests proving booking-plus-all-slots atomicity, rollback after a later slot conflict, frozen unique-key behavior, initial approval/no-approval states, ownership masking, list ordering, and canonical error envelopes.
- [ ] 5.3 Add real Redis integration tests for lock acquisition, three-second contention behavior, owner-only unlock, Redis failure mapping, and no global serialization across different resources/dates. External MySQL/Redis tests MUST fail or be marked unavailable with an explicit failure; they MUST NOT silently skip and be reported as passing.
- [ ] 5.4 Run a 50–100 concurrent same-slot test and record that exactly one request returns 201, all other contenders return 409/43000, and exactly one booking plus complete slot set remains.
- [ ] 5.5 Run independent-resource and independent-date concurrency tests and record evidence that requests are not globally serialized while correctness is preserved.
- [ ] 5.6 Reproduce the historical check-then-insert bug only in an isolated, non-deployed test fixture or evidence harness, capture the reproduction, and remove/disable the vulnerable path before final verification; do not retain or deploy the bug.

## 6. Final planning/apply gate validation

- [ ] 6.1 Run `mvn verify` from `booking-api` after all required external dependencies and services are available, and record the real result (including any explicit unavailable-service failure).
- [ ] 6.2 Run `openspec validate add-concurrent-booking-core --type change --strict --no-interactive` from the worktree and resolve every artifact/spec error.
- [ ] 6.3 Run `git diff --check`, `git status --short`, and a path audit proving only the two booking ownership trees plus this change's OpenSpec artifacts changed; stop and report any scope breach instead of broadening the change.
