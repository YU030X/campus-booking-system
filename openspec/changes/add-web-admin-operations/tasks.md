## 1. Contract and ownership gates

- [ ] 1.1 Re-read main `openspec/specs/shared-contracts/spec.md` and `openspec/specs/identity-access/spec.md` after rebasing the implementation worktree; record envelope, pagination, roles, Long/time serialization, exact `UserView`, user filters, status body, error mapping, and 12-route ownership.
- [ ] 1.2 Obtain and review the T04 `add-web-auth-shell` sibling handoff after its T02 merge/rebase; its planning artifacts exist but remain unmerged. Verify `/admin/approvals` and `/admin/users` route components, ADMIN-only guard, student in-layout 403, 401 session clearing, 403 session preservation, and the router/HTTP/auth single-writer gate; do not edit those shared files.
- [ ] 1.3 Before any apply, record the actual T09 `add-booking-approval-cancellation` merge/rebase commit, then reread its exact delta/spec/design/tasks and backend handoff. The sibling planning artifacts exist but remain unmerged. Confirm: ADMIN-only admin endpoints return 403/code `40300`; `GET /api/v1/admin/approvals?pageNumber&pageSize` is pending-only, non-deleted, and ordered `createdAt ASC,id ASC`; approve/reject are `POST /api/v1/admin/bookings/{id}/approve` and `/reject`; approve comments trim with blank→`null` and allow <=500 Unicode code points; reject comments trim to 1..500 Unicode code points; `BookingView`/`ApprovalView` Long IDs are strings; repeated identical actions return 200 with no side effects; opposite/illegal actions return 409/code `43000`. Until the commit and reread are recorded, approval implementation remains blocked and no merged fact is asserted.
- [ ] 1.4 Confirm T08 student-booking and T05 resource-admin ownership remains disjoint; reject changes touching router, HTTP/auth/shared files, resource-admin directories, student-booking directories, backend, or package manifests.

## 2. Scoped admin operations boundaries

- [ ] 2.1 Create only the scoped approvals/users view, component, API, store, fixture, and test paths permitted by the proposal; use T04's route handoff and frozen `VITE_API_MODE` boundary.
- [ ] 2.2 Define request-key/in-flight-key handling for list/detail/status/action calls so identical requests and repeated action clicks are de-duplicated without a global mock or shared-client change.
- [ ] 2.3 Define boundary normalization for pageNumber/pageSize (max 100), keyword trim, role/status allow-lists, numeric `{status:0|1}`, Long IDs as strings, canonical timestamps, and exact status vocabulary; do not infer transitions or invent fields.

## 3. Approvals workflow

- [ ] 3.1 Implement pending-only list/detail loading, empty, error, retry, stable server order, and exact T09 field mapping after the T09 gate is satisfied.
- [ ] 3.2 Implement approve with trim-then-normalize blank to `null`, cap at 500 Unicode code points, exact T09 body/path, two-step in-layout confirmation, and one in-flight request per booking/action.
- [ ] 3.3 Implement reject with trim-then-validate 1..500 Unicode-code-point comment, exact T09 body/path, two-step in-layout confirmation, and one in-flight request per booking/action.
- [ ] 3.4 On approve/reject success, refetch pending list and affected detail/status; on 401/403/404/409 or other actionable failure, refetch server truth, preserve useful unsent form input, and expose mapped error without fabricated state.
- [ ] 3.5 Render approve/reject controls only for server-returned `PENDING_APPROVAL`; keep all other statuses read-only and do not add client-side state-machine transitions.

## 4. Users workflow

- [ ] 4.1 Implement the T02 `GET /api/v1/admin/users` list with pageNumber/pageSize/keyword/role/status, exact `UserView` fields, stable order, and loading/empty/error/retry states.
- [ ] 4.2 Implement safe unknown-filter handling and pagination reset/retention rules without sending arbitrary role/status values or mutating shared query infrastructure.
- [ ] 4.3 Implement `PATCH /api/v1/admin/users/{id}/status` with numeric `{status:0|1}`, confirmation/loading de-duplication, idempotent same-status success, and server-returned row/list refresh.
- [ ] 4.4 Handle self-disable 409, 401, 403, 404, and other errors actionably; preserve the session for self-disable/403 and never fabricate logout or status success.

## 5. Pure fixture tests

- [ ] 5.1 Add role-guard tests proving STUDENT admin navigation renders in-layout forbidden and issues zero admin requests, while ADMIN can load each view.
- [ ] 5.2 Add approval tests for pending-only controls, approve trim/blank-to-null and 500-Unicode-code-point limit, reject trim/1..500-Unicode-code-point validation, two-step confirmation, duplicate-click suppression, loading, empty, retry, success refresh, and failure refetch/input preservation.
- [ ] 5.3 Add user tests for exact query normalization, pagination, keyword/role/status filters, unknown-filter safety, loading/empty/error/retry, idempotent status, self-disable 409, and row/list refresh.
- [ ] 5.4 Add 401/403/404/409 boundary tests that assert shared auth behavior is invoked without introducing global mocks or popup dialogs.

## 6. Gated integration evidence and acceptance

- [ ] 6.1 After T04/T09 backend gates are live, run the real headless browser against the built app for ADMIN approvals/users and STUDENT denial; capture screenshots and request/network evidence, including zero admin requests for the denied student path and no popup dialogs.
- [ ] 6.2 Run the frontend build and the repository's strict validation commands from the implementation worktree; include actual output for unit tests, build, and any backend-contract smoke checks rather than inferred results.
- [ ] 6.3 Run `git diff --check`, inspect `git status --short`, and verify every changed path is inside the ownership boundary; stop and report if any shared/resource/backend/package file changed.
- [ ] 6.4 Keep the PR Draft until T04/T09 gates, browser screenshots/network evidence, tests, build, strict validation, and scope checks are all recorded; implementation workflow may then separately sync specs and archive.
