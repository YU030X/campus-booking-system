## Purpose

This capability defines the student booking API and the correctness contract for creating and reading same-day campus-resource bookings. It makes slot occupancy, validation, ownership masking, and concurrent conflict behavior observable and compatible with the frozen shared, identity, resource, and data-schema contracts.

## ADDED Requirements

### Requirement: Booking creation request and canonical response

The service MUST expose `STUDENT POST /api/v1/bookings`. `CreateBookingRequest.resourceId` MUST be a decimal string; `startTime` and `endTime` MUST use `yyyy-MM-dd HH:mm:ss` in `Asia/Shanghai`; `purpose` MAY be null, MUST be trimmed, blank MUST become null, and non-null content MUST be at most 500 Unicode code points; `attendeeCount` MUST be a required integer greater than or equal to 1. Unknown JSON fields MUST return HTTP 400, code `40000`, and `data:null`. A successful creation MUST return HTTP 201 with the canonical `Result<BookingView>` envelope.

#### Scenario: Normalize a valid create request

- **WHEN** an authenticated student submits a valid decimal resource ID, Shanghai-local aligned times, surrounding whitespace in `purpose`, and a positive attendee count
- **THEN** the service trims the purpose (or stores null when blank), creates one booking, and returns HTTP 201 with `code:0`, `message:"success"`, and a `BookingView` payload.

#### Scenario: Reject malformed or unknown request data

- **WHEN** the JSON contains an unknown field, a non-decimal resource ID, an invalid timestamp, a missing/non-integer/less-than-one attendee count, or a purpose longer than 500 Unicode code points
- **THEN** the service returns HTTP 400, code `40000`, and `data:null` without creating a booking or slot.

### Requirement: Booking view, list, detail, and ownership masking

`BookingView` MUST contain exactly `id,bookingNo,userId,resourceId,startTime,endTime,purpose,attendeeCount,status,checkinTime,cancelTime,cancelReason,createdAt,updatedAt`. Long IDs MUST serialize as strings; nullable values MUST be JSON null; every time value MUST use `yyyy-MM-dd HH:mm:ss` in `Asia/Shanghai`; the business number MUST not expose auto-increment semantics. Authenticated callers MUST be able to `GET /api/v1/bookings?pageNumber&pageSize&status` for only their own records, with canonical `PageResult` fields and stable `createdAt DESC,id DESC` ordering, and `GET /api/v1/bookings/{id}` for their own record. A missing record, deleted record, or another user's record MUST be indistinguishable as HTTP 404, code `40400`, `data:null` (no enumeration).

#### Scenario: List and detail only the current user's bookings

- **WHEN** an authenticated user requests a valid page and optional booking status
- **THEN** only that user's non-deleted bookings are returned in stable `createdAt` descending then `id` descending order, and a detail request for that user's record returns the exact view fields.

#### Scenario: Mask another user's booking

- **WHEN** a user requests a booking ID owned by another user, missing, or logically deleted
- **THEN** the service returns the same HTTP 404/code `40400`/`data:null` response used for an absent record.

### Requirement: Ordered creation validation and read-only availability contract

Before creating occupancy, the service MUST apply the following 16-step core sequence from `docs/15-项目一开发实施手册.md:462-481`: (1) request shape and timestamp parsing; (2) `startTime < endTime`; (3) same calendar day; (4) `:00` or `:30` minute boundaries with seconds and nanoseconds equal to zero; (5) `startTime > now` in `Asia/Shanghai`; (6) duration satisfies resource minimum and maximum; (7) booking date is within the resource's inclusive `maxAdvanceDays`; (8) resource exists, is not logically deleted, and has status `1`; (9) non-null resource capacity is at least `attendeeCount`; (10) neither global nor resource closure applies; (11) the interval is fully contained by one or more configured open rules; (12) the user is enabled and not logically deleted; (13) an active blacklist row does not satisfy `start_date <= today <= end_date`; (14) the user's active booking count is below the configured maximum, default `3`; (15) acquire the resource/date booking lock; and (16) create the booking and slots in one transaction. This is alignment with the older draft order, not a behavior expansion. T06 availability may be consumed only as a read-only/pure calculation contract; it MUST NOT be treated as the final authority for creation, which MUST re-check conflict-sensitive rules while holding the booking lock. The document's step 17 (`docs/15-项目一开发实施手册.md:482`) is a separate, optional T12 after-commit cache-invalidation handoff: only after the transaction commits successfully may T12 clear `resource:available-slots:{resourceId}:{date}`. If T12 is not merged, this capability MUST NOT create a cache or invalidation path. If invalidation fails, T12 MUST use its database fallback for availability reads; it MUST NOT roll back the successful booking, and it MUST NOT alter the T07 Redis lock fail-closed behavior.

#### Scenario: Reject a rule violation before persistence

- **WHEN** any ordered rule fails, including a past start, cross-day interval, non-aligned time, inactive resource, duration/advance/capacity violation, incomplete open-rule containment, closure, disabled/deleted user, active blacklist, or active-booking limit
- **THEN** the service returns the corresponding canonical 400/404/409 business error with `data:null` and creates no booking or slot rows.

#### Scenario: Re-check availability inside the critical section

- **WHEN** a request passed preliminary checks but a resource rule, closure, user, or slot condition changes before persistence
- **THEN** the service re-evaluates the creation-sensitive condition while holding the resource/date critical section and refuses the creation if it is no longer valid.

#### Scenario: Optional post-commit availability-cache handoff

- **WHEN** the booking transaction commits successfully and the optional T12 cache integration is present
- **THEN** the booking flow hands off invalidation of `resource:available-slots:{resourceId}:{date}` after commit; a cache-invalidation failure leaves the successful booking committed and T12 serves availability from its database fallback.

- **WHEN** T12 is not merged
- **THEN** this capability creates no cache or invalidation path, and T07's Redis lock failure remains fail-closed.

### Requirement: Initial state and atomic slot occupancy

Creation MUST choose `PENDING_APPROVAL` when the resource requires approval and `CONFIRMED` otherwise. Both initial states MUST immediately occupy every 30-minute slot in the left-closed/right-open interval `[startTime,endTime)`, with the end time excluded. The booking row and all `booking_slot` rows MUST be one atomic transaction; if any slot insert fails, the booking and every slot insert MUST roll back. `booking_slot` MUST remain physically managed with the frozen unique key `(resource_id,slot_time)`; this capability MUST NOT logically delete slots or alter that key.

#### Scenario: Persist an approval-required booking

- **WHEN** a valid request targets a resource with approval required
- **THEN** one booking is created as `PENDING_APPROVAL` and all of its 30-minute slots are immediately present and unavailable to other bookings.

#### Scenario: Roll back on a partial slot conflict

- **WHEN** any generated slot conflicts with the existing `(resource_id,slot_time)` uniqueness constraint
- **THEN** the entire creation transaction rolls back, no new booking or partial slot remains, and the response is HTTP 409, code `43000`, `data:null`, with message `该时段已被占用，请刷新后重试`.

### Requirement: Resource/date concurrency control and failure behavior

Concurrent creation requests MUST coordinate by resource and booking date using lock key `booking:lock:{resourceId}:{bookingDate}`. The lock acquisition contract MUST wait at most three seconds, use a watchdog-compatible wait-time-only call (`tryLock(3,TimeUnit.SECONDS)` with no explicit lease time), and unlock only in `finally` when the current thread still owns the lock. Failure to acquire the lock or failure to reach Redis MUST fail closed with HTTP 409, code `43000`, `data:null`, and message `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`); the service MUST NOT silently continue as if the lock succeeded or downgrade to DB-only. Database uniqueness remains the final correctness guarantee. Requests for different resources or different dates MUST be able to proceed without a single global booking lock.

#### Scenario: One winner for a hot slot

- **WHEN** 50–100 concurrent requests target the same resource and same slot
- **THEN** exactly one request returns HTTP 201, all other conflicts return HTTP 409/code `43000`, and the database contains one booking with its complete slot set.

#### Scenario: Parallelism across independent lock domains

- **WHEN** concurrent requests target different resources or different booking dates
- **THEN** they are not serialized behind one global lock and can make progress independently while preserving per-resource/date correctness.

#### Scenario: Redis is unavailable

- **WHEN** lock acquisition cannot communicate with Redis
- **THEN** the request returns HTTP 409/code `43000`/`data:null` with `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`), never pretends that a distributed lock was acquired, and never falls back to DB-only; the database unique constraint remains present for all successful paths.

#### Scenario: Slot duplicate versus lock busy handoff

- **WHEN** a generated `booking_slot` insert hits the database unique key, or when lock acquisition fails/Redis is unreachable
- **THEN** the duplicate path returns HTTP 409/code `43000`/`data:null` with `该时段已被占用，请刷新后重试` (`SLOT_CONFLICT`), while the lock/Redis path returns the same code with `当前预约请求较多，请稍后重试` (`SYSTEM_BUSY`); later frontend work MUST map by message/category and MUST NOT conflate the two outcomes.

### Requirement: Explicit lifecycle handoff boundary

This capability MUST define the booking domain's explicit action boundary for later approval, cancellation, and check-in changes, including the slot-release operation needed by terminal transitions. It MUST NOT implement those T09/T10 routes in this change and MUST NOT expose an arbitrary target-state update operation. Later changes MUST call explicit domain actions and release slots in the same transaction when their transition reaches `REJECTED`, `CANCELLED`, or `NO_SHOW`.

#### Scenario: Later lifecycle change uses the handoff

- **WHEN** a downstream approval, cancellation, or check-in change is applied
- **THEN** it extends the explicit booking action boundary, preserves the frozen legal state machine, and does not directly mutate status or bypass the booking transaction/slot-release contract.
