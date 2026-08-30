## 1. Preconditions, ownership, and contract gates

- [x] 1.1 Verify this worktree is `D:/Projects/project1_campus/target/worktrees/add-checkin-no-show-violation` on `codex/add-checkin-no-show-violation` at the selected main base, and record the exact T07 and T02 handoff commits before any implementation; record the T07/T09 sibling artifacts as existing but unmerged.
- [x] 1.2 Confirm the reviewed T07 action/release handoff is available and exposes named check-in/no-show actions plus terminal slot-release ports; the sibling artifact may remain unmerged, but stop if only direct booking status/slot SQL is available.
- [x] 1.3 Confirm T02 exposes the authenticated current-user accessor and transaction-participating credit update with a non-negative floor; stop instead of editing `user/**`, `auth/**`, `common/**`, or shared config when absent.
- [x] 1.4 Record that the T09 sibling artifact exists but is not merged. Do not make its cancellation seam a T10 apply gate: deliver the REQUIRED `ViolationPort` here, then let T09 rebase and consume it; report any disagreement about the frozen `-5` contract instead of guessing.
- [x] 1.5 Confirm no T11/T12 implementation or handoff is required for the T10 backend scope; record the future UI/support handoff without adding frontend, notification, or statistics files.

## 2. Check-in API and domain integration

- [x] 2.1 Add check-in request/controller/service tests and implementation only under `booking-api/.../checkin/**` and matching tests, using T02's principal and T07's explicit booking action seam.
- [x] 2.2 Implement current-owner lookup with missing/foreign/deleted 404 masking, shared 401 handling, canonical result envelope, exact booking view, Long-as-string IDs, and Shanghai timestamp serialization.
- [x] 2.3 Implement fixed-clock `CONFIRMED` eligibility for the inclusive `[start-15m,start+15m]` window, returning HTTP 409 and `43000-43099` for wrong status or outside-window requests without side effects.
- [x] 2.4 Make repeated check-in return HTTP 200 with the current view and prove no duplicate timestamp, violation, credit, or slot effect; reject arbitrary target-state updates.

## 3. Violation module and transaction port

- [x] 3.1 Add violation persistence/query models and the exact `ViolationView` field mapping under `violation/**`; preserve the frozen DDL and `uk_booking_type(booking_id,violation_type)` without SQL changes.
- [x] 3.2 Define local score constants `NO_SHOW = -10` and `LATE_CANCEL = -5`, document their source/freeze, and route all user credit mutations through the accepted T02 port using `resultingCredit = max(0, currentCredit + scoreChange)`.
- [x] 3.3 Implement idempotent no-show recording so duplicate keys cannot cause a second credit deduction, and expose a transaction-participating `LATE_CANCEL` port for T09 without opening a new transaction.
- [x] 3.4 Implement authenticated `GET /api/v1/users/me/violations` with current-user scoping, `pageNumber/pageSize` validation (`pageSize <= 100`), stable `createdAt DESC,id DESC` ordering, canonical page envelope, and no foreign data.

## 4. Per-item no-show task

- [x] 4.1 Add the once-per-minute task entry point under `task/**` that selects only `CONFIRMED` bookings with `start_time < now(Asia/Shanghai) - 15 minutes`; at exactly `start+15m` check-in still wins, and the first scan strictly after that boundary may process `NO_SHOW`; document the multi-instance limitation and deferred distributed-lock option.
- [x] 4.2 Add an independent transaction-owned item processor with `REQUIRES_NEW`; conditionally perform only `CONFIRMED -> NO_SHOW`, then record `NO_SHOW`, update credit, and release all slots through T07 in one transaction.
- [x] 4.3 Catch and record one-item failures at the scheduler boundary so later candidates continue; ensure a failed item leaves no partial state and no released slots outside its transaction.
- [x] 4.4 Verify repeated scans, concurrent check-in races, duplicate violation keys, credit floor, and physical slot deletion are all idempotent and do not double-deduct. Evidence: `NoShowMysqlIntegrationTest` 4/4 passed against Compose MySQL 8, covering repeated processing, two-thread conditional winner, one violation/credit deduction, rollback, credit floor, and slot deletion; fixed-clock/action outcome tests cover check-in race losers without side effects.

## 5. Verification evidence

- [x] 5.1 Add fixed-clock unit tests for exactly start-15m, start+15m, one-second outside each boundary, Shanghai conversion, eligible status, wrong status, and repeated check-in.
- [x] 5.2 Add API/security tests for unauthenticated 401, foreign/missing/deleted 404 masking, invalid page bounds, current-user violation scoping, canonical envelopes, and 409/43000 business errors.
- [x] 5.3 Add MySQL 8 integration tests proving no-show status, violation insert, credit floor/update, and complete physical slot release commit together; force a later-step failure and prove the item rolls back. Evidence: Compose-backed `NoShowMysqlIntegrationTest` 4/4 passed; successful item committed status/violation/credit/slot release, and missing-user failure rolled back status, slots, and violation.
- [x] 5.4 Add repeated-scheduler and concurrency tests proving unique `(booking_id,violation_type)` idempotency, no double deduction, conditional state race handling, and per-item failure isolation with remaining items processed. Evidence: MySQL concurrency/repeat tests and task isolation unit tests passed; `ViolationPortLateCancelMysqlIntegrationTest` 3/3 additionally proved unique late-cancel record, caller rollback, no double deduction, and credit floor.
- [x] 5.5 Add the T10 consumer contract/handoff test proving the REQUIRED `ViolationPort` exposes `LATE_CANCEL = -5`, uniqueness, credit-floor formula, and normal caller-transaction participation. T09 adds the end-to-end cancellation/slot-release integration test in its later change after rebasing.
- [x] 5.6 Ensure external MySQL/Redis/service-dependent tests fail explicitly or report unavailable with evidence; they MUST NOT silently skip and be reported as passing.

## 6. Final planning/apply gate validation

- [x] 6.1 Run `mvn verify` from `booking-api` after all approved handoffs and required services are available, recording the real result and any explicit unavailable-service failure. Evidence: with root Compose MySQL 8/Redis 7 healthy and temporary process-only credentials, `mvn verify` ran 218 tests with 0 failures, 0 errors, 0 skipped and produced the repackaged JAR (`BUILD SUCCESS`, 52.663 s).
- [x] 6.2 Run `openspec validate add-checkin-no-show-violation --type change --strict --no-interactive` from this worktree and resolve every artifact/spec error.
- [x] 6.3 Run `git diff --check`, `git status --short`, and a path audit proving only `checkin/**`, `violation/**`, `task/**`, matching tests, and this change's OpenSpec artifacts changed; stop on any ownership breach.
- [x] 6.4 Record T11/T12 handoff notes and the optional automatic-completion/distributed-lock follow-up without implementing those features; keep this change uncommitted and unpushed until a separate apply/commit request. Evidence: T11 may consume the check-in/violation APIs and T12 may separately propose notification/support UI; automatic `CHECKED_IN -> COMPLETED` and a distributed scheduler lock remain explicit future changes. No frontend, notification, statistics, completion, or distributed-lock implementation was added, and T10 implementation remains uncommitted/unpushed.
