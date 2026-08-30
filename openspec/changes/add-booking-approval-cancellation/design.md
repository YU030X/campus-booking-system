## Context

See `proposal.md` for motivation. T07 owns the booking state machine and concurrent slot core; T09 may write only under `booking-api/.../approval/**` and tests, and must consume T07's reviewed action/release ports. The T07 sibling planning is currently unmerged. Shared contracts already define Result/PageResult, Long-as-string serialization, role/error ranges, and 30-minute slot semantics. T10 owns violation persistence, user credit mutation, and the required transaction-participating `ViolationPort`; the T10 sibling planning is also currently unmerged. T08/T11 consume the exact DTO and error behavior.

## Goals / Non-Goals

**Goals:**

- Add an approval application boundary that validates requests, enforces ADMIN/current-user policy, and delegates state changes to T07's published domain actions.
- Make approval records, slot release, and late-cancel handoff transactionally consistent and idempotent under concurrent duplicate requests.
- Provide deterministic pending pagination and exact DTO/VO contracts for T08/T11.
- Produce MySQL 8 and real-API evidence for boundaries, rollback, ownership masking, and concurrency.

**Non-Goals:**

- No edits to `booking/**`, SQL migrations, common errors/envelopes, resource/user/auth modules, frontend, `pom.xml`, or configuration.
- No arbitrary status setter, direct booking/slot mapper access that bypasses T07, T10 violation-record/credit implementation, or T11/T08 UI/API work.
- No schema migration or new dependency.

## Decisions

1. **T07 port first, approval-owned orchestration second.** Before T09 final merge, verify T07 is merged/rebased and document the exact reviewed action/release signatures. Approval services call those ports rather than updating booking tables directly. If a required port is absent or requires a `booking/**` edit, stop and create a T07 owner handoff request; do not implement around it.

2. **Conditional action result drives idempotency.** Each action is an explicit approve/reject/cancel command with an allowed source state. The T07 port performs the conditional transition and returns a winner/already-completed/invalid result. Only the winner appends an approval record, deletes slots, or emits the late-cancel handoff; an already-completed identical action reads the current view and returns 200.

3. **One transaction for terminal side effects.** Approval application code joins the transaction boundary supplied by the T07 action/release seam. Reject and cancel couple state update, physical slot deletion, immutable approval record (reject), and the required T10 `ViolationPort` call (late cancel). A failure rolls back the complete unit. Approve deliberately does not delete slots.

4. **Parallel T09/T10 handoff and late-cancel scoring.** T09 and T10 may develop in parallel. T10 must first merge a transaction-participating `ViolationPort`; it does not wait for T09. T09 may apply approval, normal cancellation, and port adaptation, then rebase onto T10 and run the real late-cancel integration before final merge. T09 only calls the port with booking/user/start-time context and an idempotency key; it never writes violation or user data. The T10-owned port applies `LATE_CANCEL = -5` and `NO_SHOW = -10`, with credit floored at 0.

5. **Single source of request normalization.** DTO binding rejects unknown properties through the existing shared Jackson policy; DTO validation trims comments/reasons, maps blank to null where allowed, and enforces the stated lengths in Unicode code points (not UTF-16 code units): approve/reject comments max 500 and cancel reasons max 200. Controllers perform only HTTP/auth/validation entry; services return separate BookingView/ApprovalView objects with Long values serialized as strings.

6. **Active idempotency contract.** A repeated identical approve, reject, or cancel returns HTTP 200 with the current `BookingView` and has no side effects. This active contract intentionally supersedes the older `docs/15-项目一开发实施手册.md` wording that repeated cancellation is not cancellable; opposite or otherwise illegal transitions return HTTP 409 with code `43000`.

7. **Stable read path.** The pending list filters status and deleted flag, applies page bounds, and orders by `created_at ASC, id ASC`. It never broadens to all active bookings and never changes booking state.

### Alternatives considered

- Direct `bookingMapper.update(status)` was rejected because it violates T07 ownership and permits illegal transitions.
- Adding a database unique index for approval actions was rejected because SQL/schema ownership is frozen; conditional winner semantics prevent duplicate records without a migration.
- Implementing late-cancel violation logic locally was rejected because T10 owns violation persistence and credit mutation and would create split transaction semantics.
- Returning 404 only for missing bookings was rejected because foreign/deleted cancellation would leak ownership information.

## Risks / Trade-offs

- [T07 port drift] → Require a merge/rebase check and exact handoff artifact before final merge; stop on signature mismatch.
- [Duplicate side effects under retries] → Gate side effects on the conditional-transition winner and verify concurrent duplicate tests against MySQL 8.
- [Cross-change late-cancel failure] → Require T10's merged transaction-participating `ViolationPort`, rebase T09 onto it, and run the late-cancel integration gate before final completion; leave the change explicitly incomplete otherwise.
- [Approval list changes while paging] → Use deterministic createdAt/id ordering and document that pagination is a stable snapshot per query, not a global snapshot across requests.
- [Boundary-time ambiguity] → Use Asia/Shanghai `startTime.minusHours(2)` with an inclusive `>= 2h` exemption and test exactly 2:00:00, 1:59:59, and start_time.

## Migration Plan

No migration is required. T09/T10 development and T09's approval/normal-cancel apply may proceed in parallel, but T10 must merge the transaction-participating `ViolationPort` first; T09 then rebases onto T10 and runs late-cancel integration before final merge/completion. T07 must be merged/rebased before T09's final merge. Rollback is removal/revert of the approval change while preserving existing booking rows and immutable approval records; no SQL or configuration rollback is needed.

## Open Questions

None that may safely remain open. The T07/T10 gates are explicit completion conditions, not deferred design choices.
