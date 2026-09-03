# Booking Approval Cancellation Specification

## Purpose

This capability completes the approval and student-cancellation lifecycle around T07's concurrent booking core. It exposes bounded, auditable actions while preserving ownership masking, slot occupancy correctness, and the T10 late-cancel integration boundary.

## Requirements

### Requirement: Exact action endpoints and request validation

The API MUST expose authenticated `POST /api/v1/bookings/{id}/cancel`, ADMIN-only `GET /api/v1/admin/approvals?pageNumber&pageSize`, ADMIN-only `POST /api/v1/admin/bookings/{id}/approve`, and ADMIN-only `POST /api/v1/admin/bookings/{id}/reject`. Requests MUST use the shared success/error envelopes. Unknown JSON properties MUST return HTTP 400 with code `40000` and `data:null`. `ApproveRequest.comment` and `CancelRequest.cancelReason` MUST trim whitespace, convert blank to null, and reject values over 500 and 200 Unicode code points respectively; `RejectRequest.comment` MUST trim to 1..500 Unicode code points and be required. BookingView and ApprovalView Long identifiers MUST serialize as decimal JSON strings.

#### Scenario: Valid approve request
- **WHEN** an authenticated ADMIN posts an empty or whitespace-only approve comment for a pending booking
- **THEN** the request succeeds with HTTP 200, the normalized comment is null, and the response uses the shared `Result<BookingView>` envelope with string-valued Long fields

#### Scenario: Unknown field is rejected
- **WHEN** any action request contains a JSON property not defined by its request DTO
- **THEN** the API returns HTTP 400, code `40000`, `data:null`, without changing booking, slot, approval, or late-cancel state

### Requirement: Pending approval listing

The approval list MUST return only non-deleted bookings whose status is `PENDING_APPROVAL`. It MUST use the shared page shape, require `pageNumber >= 1`, enforce `1 <= pageSize <= 100` (with the shared default when omitted), and order records deterministically by `createdAt ASC, id ASC` (or an explicitly documented equivalent stable ordering). Every BookingView/ApprovalView Long identifier in the page MUST be a JSON string.

#### Scenario: Stable pending page
- **WHEN** an ADMIN requests page 1 with pageSize 100 while confirmed, rejected, cancelled, and pending bookings exist
- **THEN** only pending non-deleted bookings are returned in createdAt/id ascending order and no non-pending record is included

#### Scenario: Page size boundary
- **WHEN** a caller supplies pageSize 101 or pageSize 0
- **THEN** the API returns HTTP 400, code `40000`, `data:null`, and performs no query-side mutation

### Requirement: Administrator approval actions

An ADMIN approve action MUST transition only `PENDING_APPROVAL` to `CONFIRMED`; an ADMIN reject action MUST transition only `PENDING_APPROVAL` to `REJECTED`. Each winning transition MUST append exactly one immutable `approval_record` with booking id, approver id, action `APPROVE` or `REJECT`, normalized comment, and creation time; a repeated identical action is a 200 response with no new record. Approving MUST retain occupied slots. Rejecting MUST physically delete all booking slots in the same transaction as the conditional state update and approval record insert.

#### Scenario: Approve pending booking
- **WHEN** an ADMIN approves a pending booking whose slots are occupied
- **THEN** the booking becomes CONFIRMED, its slots remain occupied, one APPROVE record is inserted, and HTTP 200 returns the current BookingView

#### Scenario: Reject pending booking
- **WHEN** an ADMIN rejects a pending booking with a trimmed non-blank comment
- **THEN** the booking becomes REJECTED, every booking_slot row is physically deleted, one REJECT record is inserted atomically, and HTTP 200 returns the current BookingView

### Requirement: Student cancellation and late-cancel boundary

The cancel action MUST be limited to the current authenticated student's own, non-deleted booking. Before the booking start, `PENDING_APPROVAL` and `CONFIRMED` MAY transition to `CANCELLED`; at or after the start, cancellation MUST be rejected. A winning cancellation at least two hours before start MUST have no violation side effect. A winning cancellation less than two hours before start MUST call the T10-required transaction-participating `ViolationPort` exactly once; this change MUST NOT implement T10-owned violation or user persistence or credit mutation. The T10 port contract MUST apply `LATE_CANCEL = -5` and `NO_SHOW = -10`, flooring credit at 0. Every winning cancellation MUST physically release all booking slots in the same transaction as the state update and its applicable late-cancel port call; a repeated identical cancellation performs neither operation again.

#### Scenario: Pending booking cancelled without penalty
- **WHEN** the owner cancels a pending booking 2 hours or more before its start
- **THEN** the booking becomes CANCELLED, all slots are deleted atomically, no late-cancel side effect is emitted, and HTTP 200 returns BookingView

#### Scenario: Late cancellation is handed off
- **WHEN** the owner cancels a confirmed booking 1 hour 59 minutes before its start
- **THEN** the booking becomes CANCELLED, slots and the exactly-once late-cancel event/port participate in the same transaction, and HTTP 200 returns BookingView; final completion remains gated on T10 integration

#### Scenario: Cancellation at start is forbidden
- **WHEN** the owner attempts to cancel at or after start_time while the booking is PENDING_APPROVAL or CONFIRMED
- **THEN** the API returns HTTP 409 with code `43000`, leaves booking and slots unchanged, and emits no late-cancel side effect

### Requirement: Conditional transitions and idempotency

All approve, reject, and cancel transitions MUST use explicit domain actions and state conditions; an arbitrary target-state `updateStatus` operation MUST NOT be exposed. Concurrent duplicate requests MUST allow at most one winning transition and one approval record or late-cancel side effect. A repeated identical approve, reject, or cancel action after its target state is already reached MUST return HTTP 200 with the current `BookingView` and MUST NOT duplicate records, slots, or side effects. This active contract intentionally supersedes the older `docs/15-项目一开发实施手册.md` wording that repeated cancellation is not cancellable. An opposite action or any illegal terminal transition MUST return HTTP 409 with code `43000`.

#### Scenario: Duplicate reject
- **WHEN** two ADMIN requests concurrently reject the same pending booking
- **THEN** one transition and one REJECT record win, the other request observes REJECTED and returns the same current BookingView without another record or slot delete

#### Scenario: Duplicate approve
- **WHEN** an ADMIN repeats approve after the booking is already CONFIRMED
- **THEN** the response is HTTP 200 with the current BookingView and no second APPROVE record or other side effect is created

#### Scenario: Repeated cancellation
- **WHEN** the owner repeats cancellation after the booking is already CANCELLED
- **THEN** both responses are HTTP 200 with the current BookingView and no second slot-release or late-cancel side effect occurs

#### Scenario: Opposite terminal action
- **WHEN** an ADMIN attempts to approve a REJECTED or CANCELLED booking, or reject a CONFIRMED booking
- **THEN** the API returns HTTP 409, code `43000`, `data:null`, and does not mutate records or slots

### Requirement: Authorization and ownership masking

Unauthenticated calls MUST return the shared HTTP 401/code `40100` response. Students and other non-ADMIN roles MUST receive HTTP 403/code `40300` from all admin endpoints. A cancel request for a foreign, missing, or deleted booking MUST return the same HTTP 404/code `40400` response with `data:null`, without revealing which condition occurred. Admin actions for missing or deleted bookings MUST return the shared 404 response.

#### Scenario: Foreign cancellation is masked
- **WHEN** a student posts cancel for another student's booking id, a missing id, or a deleted booking id
- **THEN** each request returns the identical 40400 response and no booking, slot, approval, or late-cancel data is disclosed

#### Scenario: Student calls admin endpoint
- **WHEN** an authenticated STUDENT requests the approval list or approve/reject action
- **THEN** the API returns HTTP 403/code `40300` and performs no state transition

### Requirement: Transactional rollback and integration gates

Reject and cancel state changes, physical slot deletion, approval-record insertion, and any late-cancel `ViolationPort` operation MUST share one transaction boundary. If any part fails, the transaction MUST roll back without a partial booking state, record, slot release, or late-cancel side effect. The implementation MUST consume T07's reviewed booking domain action/release ports only after T07 is merged or rebased into the implementation base. T09/T10 development may proceed in parallel; T10 must merge the transaction-participating `ViolationPort` first, after which T09 rebases and runs the real integration. Final completion MUST include that T10 late-cancel violation integration; until then, only the durable port seam and its exactly-once behavior may be delivered and the change MUST remain gated.

#### Scenario: Reject rollback
- **WHEN** approval-record insertion fails after a conditional reject transition or slot deletion begins
- **THEN** the transaction rolls back so the booking remains PENDING_APPROVAL, all original slots remain, and no approval record exists

#### Scenario: Cancel rollback
- **WHEN** slot deletion or the late-cancel event/port fails during cancellation
- **THEN** the booking remains in its original state, all original slots remain, and no partial late-cancel effect is visible

