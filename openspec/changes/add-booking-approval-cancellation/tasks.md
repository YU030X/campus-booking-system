## 1. Preconditions and ownership gates

- [ ] 1.1 Record that the T07 `add-concurrent-booking-core` sibling planning is currently unmerged; before T09 final merge, confirm its merged/rebased contract and record the exact public booking action/release ports and transaction behavior. **Stop and file a T07 owner handoff request if the contract cannot be confirmed; do not edit `booking/**`.**
- [ ] 1.2 Record that the T10 `add-checkin-no-show-violation` sibling planning is currently unmerged. T09/T10 may develop in parallel: T10 first merges the transaction-participating `ViolationPort` without waiting for T09; T09 may apply approval, normal cancellation, and port adaptation, then rebase onto T10 and run late-cancel integration before final merge.
- [ ] 1.3 Obtain exact DTO/error handoff notes for T08 and T11 (field names, normalization, 40000/40100/40300/40400/43000 behavior) and preserve them in implementation review notes without changing their owned paths.

## 2. Approval package contract (approval/** only)

- [ ] 2.1 Add approval request DTOs with unknown-field rejection, trim/blank normalization, nullable approve/cancel comments, required reject comment, and limits measured in Unicode code points: approve/reject comments 500, cancel reason 200.
- [ ] 2.2 Add BookingView/ApprovalView mappings and controller responses using shared Result/PageResult envelopes, Asia/Shanghai time formatting, and JSON-string Long identifiers.
- [ ] 2.3 Add the four controllers/routes with authentication and ADMIN guards; keep controllers limited to binding, validation, authorization entry, and service calls.
- [ ] 2.4 Add the pending-approval query restricted to non-deleted `PENDING_APPROVAL`, page bounds 1..100, and deterministic createdAt/id ascending ordering.

## 3. Domain action orchestration and transaction boundary

- [ ] 3.1 Implement approval service calls to the reviewed T07 approve/reject/cancel action and slot-release ports; do not access booking or booking_slot persistence directly.
- [ ] 3.2 Ensure approval actions conditionally transition and atomically insert one immutable APPROVE/REJECT record as appropriate; only the winning transition creates the record, and approve retains slots while reject releases them.
- [ ] 3.3 Ensure cancel accepts only owner + PENDING_APPROVAL/CONFIRMED before start, physically releases slots in the same transaction, and returns the current view for an already-cancelled duplicate.
- [ ] 3.4 Call the T10-required transaction-participating `ViolationPort` only for `< 2h`, with booking/user/start-time context and an idempotency key, in the same transaction as cancel state and slot deletion; implement no violation/user persistence or credit mutation in T09. The T10 port contract applies `LATE_CANCEL = -5`, `NO_SHOW = -10`, and credit floor 0.
- [ ] 3.5 Map missing/foreign/deleted cancellation uniformly to 40400, students to 40300 on admin routes, unauthenticated requests to 40100, and illegal/opposite terminal actions to HTTP 409/code 43000. Repeated identical approve/reject/cancel actions return HTTP 200 with the current BookingView and have no side effects.

## 4. Verification tests (MySQL 8 and real API)

- [ ] 4.1 Add unit tests for comment/reason normalization, unknown JSON 40000, page bounds, stable ordering, Long-string views, and the exact two-hour/start boundary.
- [ ] 4.2 Add MySQL 8 integration tests proving pending slots are occupied, approve keeps slots, reject/cancel release slots, and rollback leaves booking/slots/approval/late side effects unchanged.
- [ ] 4.3 Add concurrency tests for duplicate approve/reject/cancel: one conditional winner, one approval record at most, no double slot deletion, and no duplicate late-cancel event/port; every repeated identical action returns HTTP 200 with the current BookingView.
- [ ] 4.4 Add authorization/ownership tests for 401, student 403, foreign/missing/deleted cancellation 404 masking, and 409/43000 invalid transitions.
- [ ] 4.5 Add real API tests for all four endpoints and assert no external test silently skips; record exact failures instead of claiming pass.
- [ ] 4.6 After T10 merges the required `ViolationPort`, rebase T09 onto T10 and run the real late-cancel violation/credit integration, asserting `LATE_CANCEL=-5`, `NO_SHOW=-10`, credit floor 0, and same-transaction state/slot/side-effect behavior. Until then, retain only port assertions and leave the final gate incomplete.

## 5. Handoff, validation, and stop conditions

- [ ] 5.1 Publish exact DTO/error examples to T08/T11 and request review of the approval package contract; do not modify their frontend paths.
- [ ] 5.2 Run `cd booking-api && mvn verify`, the relevant MySQL/real-API test profile, `openspec validate add-booking-approval-cancellation --type change --strict`, and `git diff --check`; record actual output and environment prerequisites.
- [ ] 5.3 Audit `git status --short` and the diff for ownership: only `booking-api/.../approval/**`, its tests, and approved OpenSpec artifacts may change. Any SQL/common/resource/user/frontend/pom/config or unapproved `booking/**` change is a stop condition.
- [ ] 5.4 T09 apply may complete approval, normal cancellation, and port adaptation before T10 merges, but do not final-merge or mark the change complete until T07 is merged/rebased, T10's `ViolationPort` is merged, T09 has rebased onto T10, and the late-cancel integration gate passes. Otherwise stop with the precise handoff/blocker and keep the change active.
