## Why

T07 needs a concurrency-safe booking core that turns a validated same-day interval into 30-minute occupancy rows, so overlapping requests cannot create duplicate reservations. The repository already freezes the `booking`/`booking_slot` schema and the surrounding identity/resource contracts, but no active booking implementation or planning change exists; this change defines the API, transaction boundary, lock behavior, and evidence required before implementation.

## What Changes

- Add the student booking-create endpoint and authenticated current-user list/detail endpoints with canonical result/page envelopes, exact request normalization, ownership masking, and frozen date/ID serialization.
- Add booking and `booking_slot` entities, mappers/XML, booking services, pure slot splitting, and booking-focused unit/integration/concurrency tests under the T07 ownership boundary only.
- Align the creation validation order with the 16-step core sequence in `docs/15-项目一开发实施手册.md:462-481`: request shape; `startTime < endTime`; same-day; `:00`/`:30` alignment with seconds and nanoseconds zero; `startTime > now`; duration; inclusive `maxAdvanceDays`; resource exists/not deleted/status `1`; capacity; global/resource closure; open-rule containment; user enabled/not deleted; blacklist; active-booking count; resource/date lock; transaction. This is alignment with the older draft order, not a behavior expansion. The document's step 17 (`:482`) is a separate, optional T12 after-commit cache-invalidation handoff for the `resource:available-slots:{resourceId}:{date}` key; it is not part of T07's validation sequence or booking correctness path.
- Provide only the handoff contract for that optional step 17: after a successful booking transaction commits, T12 may invalidate the resource/date availability key. If T12 is not merged, T07 must not build a cache or cache invalidation path. If T12's cache invalidation fails, T12's availability reads fall back to the database; the successful booking must not be rolled back, and this must not change T07's fail-closed Redis-lock behavior.
- Create `PENDING_APPROVAL` or `CONFIRMED` according to `resource.needApproval`; both states immediately occupy all generated slots.
- Keep booking plus every slot insert in one independent `@Transactional` bean; map any slot uniqueness conflict to HTTP 409/code `43000` with `data:null` and the frozen refresh message.
- Use Redisson `booking:lock:{resourceId}:{bookingDate}` with `tryLock(3, TimeUnit.SECONDS)` (wait time only), owner-checked unlock, fail-closed Redis-outage/lock-busy HTTP 409/code `43000` system-busy behavior, and the database unique key as final correctness; never downgrade to DB-only when Redis is unavailable.
- Preserve the T08 conflict handoff: slot duplicate returns HTTP 409/code `43000` with `该时段已被占用，请刷新后重试` (`SLOT_CONFLICT`), while lock/Redis busy returns the same code with `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`); later frontend work must map by message/category and must not conflate them.
- Define explicit domain-method handoff points for T09/T10 without implementing approval, cancellation, check-in, or arbitrary `updateStatus` in this change.
- Add a shared-file change request/precondition for T01-owned Redisson dependency/config compatibility; do not edit `pom.xml`, `application.yml`, SQL, common, auth, user, resource/availability, or frontend files here.

## Capabilities

### New Capabilities

- `booking-core`: Student booking creation and authenticated current-user list/detail, validation, state-at-create, slot discretization, transactional occupancy, resource/date locking, conflict/error mapping, and concurrency evidence.

### Modified Capabilities

- None. Existing shared-contracts, data-schema, resource-catalog, and identity-access requirements are consumed as frozen contracts; this change does not revise them.

## Impact

- **Owned implementation area:** `booking-api/src/main/java/com/yu030x/booking/booking/**` and `booking-api/src/test/java/com/yu030x/booking/booking/**`, including `booking` and `booking_slot` entity/mapper/XML, transaction services, pure slot splitting, and concurrency tests.
- **External preconditions:** T06 `add-resource-availability` planning artifacts exist and have been reviewed in the sibling worktree `D:/Projects/project1_campus/target/worktrees/add-resource-availability`; they are not merged into this base. Before apply, record the merge/rebase commit, re-read the merged T06 artifacts and contract, and stop if that evidence is missing.
- **Shared-file request:** The reviewed T01 Redis foundation planning exists but is not merged at `D:/Projects/project1_campus/target/worktrees/add-redis-concurrency-foundation/openspec/changes/add-redis-concurrency-foundation/`. Before apply, record its merge/rebase commit and re-read the merged dependency/configuration contract. T01 (the sole owner of `pom.xml`, shared configuration, and dependency versions) must lock a compatible Redisson/Spring Data Redis version and env-only `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`. This change must not edit those files; apply pauses until that gate is satisfied.
- **Downstream handoff:** T09/T10 may extend the explicit booking domain actions and slot-release interface through their own changes; they must not bypass the booking domain service or issue arbitrary status updates.
- **Validation evidence:** pure-function boundary tests; MySQL 8 transaction/rollback/unique-conflict tests; real Redis lock tests; 50–100 same-slot concurrency (one 201, remaining 409); different-resource/date parallelism; reproducible historical bug-baseline evidence without retaining a production vulnerability; `mvn verify`; OpenSpec strict validation and `git diff --check`.
