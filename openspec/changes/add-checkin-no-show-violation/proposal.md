## Why

The booking core needs a user-owned check-in action and a reliable timeout path so confirmed bookings cannot retain occupied slots after a no-show. T10 also owns the violation record/query surface. The T07 and T09 sibling artifacts exist but are not merged; T10's core handoffs are limited to T07's action/release seams and T02's current-user/credit port, so this change must define the behavior and explicit apply gates before implementation.

## What Changes

- Add authenticated `POST /api/v1/bookings/{id}/check-in` for the current booking owner, with Shanghai-local inclusive `[-15,+15]` minute window, `CONFIRMED`-only transition, repeat-call idempotency, and 404 masking for foreign/missing/deleted bookings.
- Add a per-minute no-show task that selects only `CONFIRMED` bookings with `start_time < now(Asia/Shanghai) - 15 minutes` (the first scan strictly after the `start+15m` boundary), processes each item in an independent `REQUIRES_NEW` transaction/bean, conditionally transitions to `NO_SHOW`, records one `NO_SHOW` violation, applies the frozen score rule `resultingCredit = max(0, currentCredit + scoreChange)` with `-10`, and physically releases every booking slot.
- Add the authenticated current-user paginated `GET /api/v1/users/me/violations` endpoint with the exact DDL-backed `ViolationView` fields, stable ordering, Long-as-string IDs, and `pageSize <= 100`.
- Add the REQUIRED idempotent `ViolationPort` for later T09 `LATE_CANCEL` integration: unique `(booking_id, violation_type)`, frozen `-5` score change under the same credit-floor rule, and same-transaction participation. T10 delivers this port with its check-in/no-show/history work; T09 later rebases and consumes it. T09 may place its integration test in the completed T09 change, while T10 retains the consumer contract/handoff test.
- Consume T07's explicit booking action and terminal slot-release seams and T02's public current-user/credit update port; do not modify booking, user, common, SQL, `pom.xml`, or configuration ownership areas.
- Document the single-instance Spring Task limitation and defer an optional distributed task lock unless a shared dependency change is separately approved. Keep automatic blacklist and automatic `COMPLETED` outside P1, with only an explicit future handoff.

## Capabilities

### New Capabilities

- `checkin-no-show-violation`: Check-in, no-show processing, violation recording/querying, credit deduction, slot release, and T09 late-cancel integration boundaries.

### Modified Capabilities

- None. Existing shared-contracts, data-schema, and identity-access requirements remain frozen; this change consumes their envelopes, status vocabulary, DDL uniqueness, authentication, and current-user boundaries.

## Impact

- **Owned implementation paths:** `booking-api/src/main/java/com/yu030x/booking/checkin/**`, `violation/**`, `task/**`, plus matching tests only.
- **External handoffs/gates:** T07 must expose reviewed booking action and terminal slot-release ports; T02 must expose authenticated current-user and credit-update handoffs. These are the only T10 core apply gates. T09's unmerged sibling artifact is a downstream consumer: it later rebases onto the REQUIRED `ViolationPort`, and its integration test belongs to the T09 change rather than blocking T10 apply.
- **Observable APIs:** check-in action and current-user violation page; no administrator or foreign-user violation access is introduced.
- **Data/transaction impact:** consumes the frozen `violation_record.uk_booking_type` uniqueness and booking-slot physical deletion rule; no migration is proposed.
- **Verification:** fixed-clock boundary tests, ownership/401/404/409 cases, repeated scheduler/idempotency tests, rollback and per-item failure isolation, MySQL 8 transaction evidence, the T10 `ViolationPort` consumer contract/handoff test, `mvn verify`, strict OpenSpec validation, and diff/status audits. T09 owns its later end-to-end integration test after rebasing. External-service tests must fail explicitly or report unavailable, never silently skip.
