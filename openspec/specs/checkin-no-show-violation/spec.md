# Check-in No-show Violation Specification

## Purpose

This capability closes the confirmed-booking lifecycle with owner-authorized check-in, deterministic no-show handling, idempotent violation records, credit deductions, and a private current-user violation history while preserving the frozen booking and data contracts.

## Requirements

### Requirement: Owner check-in action and ownership masking

The system MUST expose authenticated `POST /api/v1/bookings/{id}/check-in` for the current booking owner. A missing, logically deleted, or foreign booking MUST be indistinguishable and return HTTP 404, code `40400`, with `data:null`; unauthenticated requests MUST return HTTP 401 using the shared authentication error contract. A successful response MUST use the canonical envelope and the exact booking view contract.

#### Scenario: Current owner checks in

- **WHEN** an authenticated student posts check-in for an eligible booking they own
- **THEN** the service returns HTTP 200 with `code:0`, `message:"success"`, and the current booking view.

#### Scenario: Foreign or hidden booking

- **WHEN** an authenticated student posts check-in for another user's, missing, or logically deleted booking
- **THEN** the service returns the same HTTP 404/code `40400`/`data:null` response for all three cases.

#### Scenario: Missing authentication

- **WHEN** a request has no valid authenticated principal
- **THEN** the service returns HTTP 401 with the shared authentication error and performs no state change.

### Requirement: Check-in state and inclusive Shanghai window

Only a `CONFIRMED` booking MAY be checked in. The request MUST be accepted when the current time in `Asia/Shanghai` is inclusively within `[startTime - 15 minutes, startTime + 15 minutes]`; exactly either boundary is valid. A valid check-in MUST transition the booking to `CHECKED_IN` and record `checkinTime` using the shared timestamp format. A booking in any other status, or a request outside the window, MUST return HTTP 409 with a booking error code in `43000-43099`, `data:null`, and MUST not change the booking.

#### Scenario: Lower and upper boundaries are valid

- **WHEN** the fixed Shanghai clock is exactly 15 minutes before or exactly 15 minutes after the booking start
- **THEN** check-in succeeds, status becomes `CHECKED_IN`, and one check-in timestamp is recorded.

#### Scenario: Outside the window or wrong status

- **WHEN** the fixed clock is outside the inclusive window or the booking is not `CONFIRMED`
- **THEN** the service returns HTTP 409 with a `43000-43099` booking error, leaves status and timestamp unchanged, and creates no violation.

#### Scenario: Repeated check-in is idempotent

- **WHEN** the same owner repeats check-in after the booking is already `CHECKED_IN`
- **THEN** the service returns HTTP 200 with the current booking view and causes no duplicate state transition, timestamp replacement, violation, credit change, or slot effect.

### Requirement: Per-minute no-show selection and isolated processing

The system MUST run a no-show scan at least once per minute and select only bookings satisfying `status = CONFIRMED AND startTime < now(Asia/Shanghai) - 15 minutes`. The strict inequality means a booking at exactly `startTime + 15 minutes` is not yet a no-show candidate; it is first eligible at the first scan strictly after that instant, preserving the inclusive check-in window. Each selected booking MUST be processed in its own transaction boundary. A successful item MUST transition only `CONFIRMED -> NO_SHOW`, record the corresponding violation, apply the credit change, and physically delete all of its booking slots atomically. A failure for one item MUST be recorded and MUST NOT prevent remaining selected items from being attempted.

#### Scenario: Exact upper check-in boundary wins the race

- **WHEN** a scan observes a confirmed booking whose start time is exactly 15 minutes in the past
- **THEN** that booking is not selected for `NO_SHOW` at that instant, remains eligible for check-in at the inclusive upper boundary, and is selected only by the first scan strictly after the boundary.

#### Scenario: Non-confirmed booking is ignored

- **WHEN** a scan observes a `CHECKED_IN`, `CANCELLED`, `REJECTED`, `NO_SHOW`, or `COMPLETED` booking meeting the strict time predicate
- **THEN** no state, violation, credit, or slot mutation is performed for that booking.

#### Scenario: One item fails without cancelling the batch

- **WHEN** processing one selected booking fails before commit
- **THEN** that item's transaction rolls back and the scan continues attempting other selected bookings, with the failure available in task diagnostics.

#### Scenario: Conditional transition prevents a race

- **WHEN** a concurrent check-in or other lifecycle action changes a selected booking before no-show commit
- **THEN** only a still-`CONFIRMED` row may become `NO_SHOW`; no duplicate violation, credit deduction, or slot deletion is committed for the raced item.

### Requirement: Idempotent violations and frozen score decisions

Each violation MUST be unique by `(bookingId, violationType)` and duplicate execution MUST not create another record or apply another credit deduction. A `NO_SHOW` record MUST use the frozen default `scoreChange = -10`; every credit update MUST commit `resultingCredit = max(0, currentCredit + scoreChange)`. The service MUST expose the REQUIRED transaction-participating `ViolationPort` for later T09 `LATE_CANCEL` integration using the same uniqueness rule and frozen default `scoreChange = -5`. Automatic blacklist creation is not part of this capability.

#### Scenario: Repeated no-show execution

- **WHEN** the no-show scan or retry encounters the same booking more than once
- **THEN** at most one `NO_SHOW` violation and one `-10` credit deduction are committed.

#### Scenario: Credit floor

- **WHEN** a no-show deduction would make the user's credit negative
- **THEN** the committed credit is `max(0, currentCredit - 10)`, which is zero when the deduction would be negative, and the violation remains linked to the booking with `scoreChange = -10`.

#### Scenario: T09 late-cancel handoff

- **WHEN** T09 cancels a booking inside its late-cancel threshold and invokes the accepted T10 violation port
- **THEN** one unique `LATE_CANCEL` record with `scoreChange = -5` and its credit update commit in the same caller transaction as cancellation and slot release; a repeated cancel does not double-deduct.

### Requirement: Current-user violation history

The system MUST expose authenticated `GET /api/v1/users/me/violations` with `pageNumber` defaulting to 1 and `pageSize` defaulting to 10, constrained to `1..100`. The response MUST use the canonical page envelope and `ViolationView` MUST contain exactly `id,userId,bookingId,violationType,scoreChange,remark,createdAt`. Long IDs MUST serialize as strings and timestamps MUST use `yyyy-MM-dd HH:mm:ss` in `Asia/Shanghai`. Results MUST be restricted to the current user and ordered stably by `createdAt DESC, id DESC`; no foreign violation data may be returned.

#### Scenario: Paginated current-user history

- **WHEN** an authenticated user requests a valid page
- **THEN** only that user's violation records are returned with the exact fields, canonical pagination, and stable ordering.

#### Scenario: Invalid page size

- **WHEN** `pageSize` is zero, negative, or greater than 100
- **THEN** the service returns HTTP 400/code `40000` with `data:null` and performs no query that can expose data.

#### Scenario: Empty history

- **WHEN** an authenticated user has no violation records
- **THEN** the service returns HTTP 200 with an empty canonical page and `total:0`.

### Requirement: Deployment-scope limitation and optional completion handoff

The no-show task MUST document that multiple application instances can execute the scan concurrently; correctness MUST still rely on the conditional status transition and violation uniqueness. A distributed task lock MAY be added only through a separately approved shared dependency change. Automatic `CHECKED_IN -> COMPLETED` is excluded from this P1 capability but MAY be handed off as a future explicit lifecycle action.

#### Scenario: Multi-instance limitation is explicit

- **WHEN** operators review task behavior in a multi-instance deployment
- **THEN** the documentation identifies the limitation and the idempotency mechanisms, without claiming a distributed lock that is not configured.

