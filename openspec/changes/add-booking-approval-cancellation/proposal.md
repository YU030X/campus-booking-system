## Why

Bookings that require approval currently have no bounded lifecycle for an administrator to approve or reject, and students cannot safely cancel a pending or confirmed booking. This change is needed now because T07 establishes the concurrent booking core and reserves the domain action/release seams; T09 must complete the approval/cancellation behavior without bypassing that state machine or leaking ownership.

## What Changes

- Add authenticated student cancellation at `POST /api/v1/bookings/{id}/cancel` with ownership masking, start-time protection, the two-hour late-cancel boundary, and slot release.
- Add administrator-only pending-approval pagination at `GET /api/v1/admin/approvals?pageNumber&pageSize` with stable ordering and a maximum page size of 100.
- Add administrator-only approve/reject actions at `POST /api/v1/admin/bookings/{id}/approve` and `/reject`, including trimmed comment validation and immutable approval records.
- Enforce explicit domain actions and conditional state updates: approve `PENDING_APPROVAL → CONFIRMED`, reject `PENDING_APPROVAL → REJECTED`, and cancel `PENDING_APPROVAL|CONFIRMED → CANCELLED` only before start.
- Make reject/cancel release every `booking_slot` and any approval/late-cancel side effect atomically; make repeated identical approve/reject/cancel actions return HTTP 200 with the current `BookingView`, without duplicate records or side effects.
- Define exact request/response, error, masking, pagination, Long-string, unknown-JSON, and transaction/concurrency acceptance contracts.
- **Do not** add arbitrary `updateStatus`, edit `booking/**` without an explicit T07 owner handoff request, edit SQL/common/resource/user/frontend/pom/config, or implement T10-owned violation/user persistence or credit logic; T09 only invokes T10's required `ViolationPort`.

## Capabilities

### New Capabilities

- `booking-approval-cancellation`: Administrator approval actions, student cancellation, lifecycle state guards, immutable approval records, slot release, late-cancel integration seam, and their API contracts.

### Modified Capabilities

- None. Existing shared, data, identity, and resource requirements are consumed as frozen contracts; this change adds the booking lifecycle capability without revising them.

## Impact

- Implementation scope is limited to `booking-api/.../approval/**` and its tests, plus calls through T07's published booking action/release ports and T10's required `ViolationPort`. Any required edit under `booking/**` must stop and produce a T07 owner handoff request before apply resumes.
- The T07 sibling planning and T10 sibling planning are currently unmerged facts. T09 and T10 may develop in parallel: T10 first merges the transaction-participating `ViolationPort` without waiting for T09; T09 may apply approval, normal-cancel, and port adaptation, then rebases onto T10 and runs the late-cancel integration before its final merge/completion gate.
- MySQL 8 integration tests must prove transaction rollback, slot release, conditional concurrency behavior, idempotency, ownership masking, and the exact HTTP/error contracts. No dependency, migration, configuration, frontend, or shared-code changes are part of this change.
