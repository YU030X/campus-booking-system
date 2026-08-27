## Context

This design implements the behavior in `specs/checkin-no-show-violation/spec.md` within the T10 ownership fence. The base is main commit `0e53b7e`; the repository has frozen shared/data/identity contracts and DDL, but no implementation code for these modules. T07 and T09 sibling artifacts exist but are not merged. T10's core apply preconditions are the reviewed T07 action/release seams and T02's current-user and credit-update port; T09 is a downstream consumer and is not an apply gate.

## Goals / Non-Goals

**Goals:**

- Keep HTTP behavior, ownership masking, Shanghai time boundaries, status transitions, violation fields, and pagination observable exactly as specified.
- Make each no-show item independently transactional and idempotent while preserving slot release and credit/violation atomicity.
- Provide a narrow transaction-participating violation port that T09 can call for `LATE_CANCEL` without allowing arbitrary status writes.
- Keep all changes below `checkin/**`, `violation/**`, `task/**`, and their tests; use reviewed T07/T02 seams for shared behavior.
- Produce deterministic fixed-clock, MySQL 8, authorization, rollback, and failure-isolation evidence.

**Non-Goals:**

- No edits to `booking/**`, `user/**`, `auth/**`, `common/**`, `sql/**`, `pom.xml`, application configuration, frontend, or shared migrations.
- No direct user mapper access, direct booking status SQL, or arbitrary `updateStatus(target)` API.
- No automatic blacklist creation, notification system, distributed scheduler lock, or automatic `COMPLETED` implementation in this P1 change.
- No implementation while the T07 action/release or T02 credit/current-user handoffs remain unavailable or unreviewed. T09's later cancellation consumption is not a T10 implementation gate.

## Decisions

### 1. Route ownership and current-user boundary

The check-in controller and violation-history controller live only in the T10 package tree. They obtain the authenticated principal through the T02 public accessor, then pass the current user ID into services. Booking lookup and check-in action must be delegated to T07's explicit domain seam with an ownership predicate; missing, deleted, and foreign rows map to the same 404 response. The violation query likewise scopes its persistence query to the current user and never accepts a user ID from the request.

Alternative rejected: sharing T07's detail controller or adding a generic cross-module controller. That would violate the single-owner directory fence and risks bypassing T02's principal boundary.

### 2. Check-in clock and state transition

Inject a clock abstraction at the service boundary so unit tests can pin `Asia/Shanghai` instants. The service checks `CONFIRMED` and the inclusive fifteen-minute window before calling T07's named check-in action. The action and `checkinTime` write execute in one transaction. A repeated call observes `CHECKED_IN` and returns the current view without rewriting the timestamp or invoking any violation/credit/slot side effect.

Alternative rejected: comparing server-default local time or letting a controller mutate status. Both would make boundary behavior environment-dependent and bypass the T07 state machine.

### 3. No-show scan and transaction isolation

The task entry point runs once per minute and reads only `CONFIRMED` bookings with `start_time < now(Asia/Shanghai) - 15 minutes`. This deliberately corrects the older `<=` wording: a booking remains check-in eligible at exactly `start+15m`; it becomes a no-show candidate only at the first scan strictly after that instant. The task does not open a batch transaction. For every candidate it invokes a separate transaction-owned bean method marked `REQUIRES_NEW`; the caller catches and records an item failure before continuing. The item method performs a conditional `CONFIRMED -> NO_SHOW` transition through T07, records `NO_SHOW`, calls the T02 credit port, and calls T07's terminal slot-release seam before commit. Any exception rolls back that item only.

The conditional transition count is the race gate: zero rows means another lifecycle action won, so no violation, credit, or slot deletion is attempted. A duplicate violation-key result is treated as an idempotent already-processed outcome and cannot trigger a second deduction.

Alternative rejected: one `@Transactional` method around the whole candidate list. It would allow a single bad row to roll back unrelated bookings and violates the T10 acceptance contract.

### 4. Violation persistence and score policy

The violation module owns persistence models and the REQUIRED `ViolationPort` with two operations: no-show recording for its own task and transaction-participating late-cancel recording for T09. Both use the frozen `uk_booking_type` uniqueness. The module keeps `NO_SHOW_SCORE_CHANGE = -10` and `LATE_CANCEL_SCORE_CHANGE = -5` as local constants unless a separately approved shared configuration request is accepted; T10 will not edit shared config. Every credit update uses the frozen formula `resultingCredit = max(0, currentCredit + scoreChange)` through T02's public port.

The repository documents `score_change` and the unique key but does not currently contain an authoritative `-5` value. The user-provided freeze is recorded here as the REQUIRED cross-change contract that T10 delivers; T09 later rebases and consumes it. T10 must not silently infer a different value from the older documents.

Alternative rejected: a configurable database rule table or automatic blacklist side effect. Both expand ownership and make the frozen P1 behavior non-deterministic.

### 5. T09 same-transaction integration

T09 owns cancellation orchestration and slot release. T10 first delivers and merges the REQUIRED late-cancel operation on `ViolationPort`; T09 then rebases and consumes it with normal `REQUIRED` participation (no new transaction), so cancellation, `CANCELLED` state, slot deletion, unique violation insert, and credit update commit or roll back together. The port is idempotent by booking/type and refuses calls for an already-recorded type. T09 may complete its integration test in the T09 change; T10 retains a consumer contract/handoff test for the port and does not wait for the T09 merge.

Alternative rejected: T10 opening a `REQUIRES_NEW` transaction for late cancel. That could commit a violation while T09 cancellation later rolls back, violating the DDL transaction invariant.

### 6. Violation view and pagination

The service maps only `id,userId,bookingId,violationType,scoreChange,remark,createdAt` to the VO, relying on the shared Long-as-string and Shanghai timestamp serializers. The query uses the current user predicate, validates `pageNumber >= 1` and `1 <= pageSize <= 100`, and orders by `created_at DESC, id DESC`. No foreign-key joins or user-supplied owner filters are accepted.

### 7. Scheduler deployment limitation and future handoffs

The task uses the existing Spring scheduling facility only after T01's shared dependency/configuration gate is satisfied; it does not add Redisson/ShedLock/XXL-Job. Documentation explicitly states that multiple instances may scan concurrently and that conditional state updates plus unique violation keys provide correctness. An optional distributed lock and automatic `CHECKED_IN -> COMPLETED` action are recorded as future handoffs, not hidden implementation work.

## Risks / Trade-offs

- **[T07 sibling artifact is not merged]** → Stop apply until a reviewed action/release port is available; do not issue direct booking SQL.
- **[T02 credit/current-user port is absent or changes shape]** → Stop apply and request a T02 handoff; do not access the user mapper or modify identity/common code.
- **[T09 sibling artifact is not merged]** → Deliver the explicit `-5` `ViolationPort` contract and consumer handoff in T10; let T09 rebase and run its own integration test later, reporting any disagreement instead of guessing.
- **[Multiple scheduler instances race]** → Keep conditional status update and `uk_booking_type` uniqueness; document the limitation and defer a lock to a separately approved dependency change.
- **[Credit update and violation can diverge]** → Require the T02 port to participate in the caller transaction and test rollback with MySQL 8; reject an asynchronous or `REQUIRES_NEW` credit update.
- **[Clock/time-zone drift]** → Use an injected fixed clock and explicit `Asia/Shanghai` conversion in unit and integration tests.
- **[One malformed candidate aborts a scan]** → Catch per-item failures at the scheduler boundary and assert remaining candidates are attempted.

## Migration Plan

1. Before apply, verify the selected worktree still starts at `0e53b7e` plus the reviewed T07 and T02 handoffs; record their commits and exact port signatures. Record the existing but unmerged T07/T09 sibling artifacts. If either core gate is missing, stop without implementation; do not wait for a T09 merge.
2. Apply only the T10-owned Java/XML/test paths and the change artifacts; keep schema, shared config, dependencies, and other module trees untouched.
3. Run fixed-clock unit tests, authorization/API tests, MySQL 8 transaction/rollback and slot-release tests, repeated-task/idempotency tests, per-item failure isolation, and the T10 `ViolationPort` consumer contract/handoff test. T09 runs its late-cancel integration after rebasing in the T09 change. External MySQL/Redis checks must fail explicitly or report unavailable rather than silently skip.
4. Run `mvn verify`, strict OpenSpec validation, `git diff --check`, and a path/status audit. If implementation is later rolled back, revert only the T10-owned paths; retain the frozen schema and upstream contracts.

## Open Questions

None are safely deferrable. The score values, transaction participation, ownership gates, status/window boundaries, and excluded P1 features are frozen by this plan; any change requires a new or explicitly updated OpenSpec change.
