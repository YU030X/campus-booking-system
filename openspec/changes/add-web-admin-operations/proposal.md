## Why

The frozen frontend route table has administrator entry points for approvals and user administration, but no scoped web capability turns the reviewed T09 approval contract and merged T02 user contract into safe, testable workflows. T11 is needed now to complete the P0 administrator path while preserving T04's authentication shell and the backend as the source of truth. The T09 sibling worktree contains the planning artifacts, but they remain planning/unmerged and are not asserted as merged input here.

## What Changes

- Add an administrator-only operations capability for the existing `/admin/approvals` and `/admin/users` routes.
- Render a pending-only approvals list/detail workflow backed by T09's exact list and approve/reject endpoints, including `GET /api/v1/admin/approvals?pageNumber&pageSize` ordered by `createdAt ASC,id ASC`, bounded Unicode-code-point comments, two-step confirmation, request de-duplication, and server-truth refreshes.
- Render paginated/filterable user administration backed by the merged T02 list and status endpoints, including idempotent status changes and self-disable conflict handling.
- Keep Long identifiers as strings, canonical timestamps/status values, and shared envelope/error handling at the API boundary.
- Add pure fixture unit coverage for role guards, no-request student denial, approval confirmation/loading/error behavior, reject validation, refetch behavior, user pagination/filter/status behavior, and empty/error/retry states.
- Reserve real headless-browser evidence, screenshots, and network traces for the post-backend-gate validation phase; do not add popup or global mock infrastructure.
- Explicitly gate implementation on the T04 and T09 sibling handoffs. Both sibling worktrees contain planning artifacts that remain unmerged; before any apply, record the actual T09 merge/rebase commit and reread the exact handoff. The reviewed T09 planning contract is: ADMIN-only endpoints return `40300` for non-ADMIN callers; approvals are pending-only and ordered `createdAt ASC,id ASC`; approve/reject use `POST /api/v1/admin/bookings/{id}/approve` and `/reject`; approve comments trim with blank→`null` and allow at most 500 Unicode code points; reject comments trim to 1..500 Unicode code points; `BookingView`/`ApprovalView` Long IDs serialize as strings; repeated identical actions return 200 with no side effects; opposite or illegal actions return 409/code `43000`. This is planning input, not a claim that T09 is merged.

## Capabilities

### New Capabilities

- `admin-web-operations`: Administrator approval operations and user status administration within the two frozen admin routes.

### Modified Capabilities

- None. Existing identity-access and shared-contract requirements remain authoritative; this change consumes them without altering their API requirements.

## Impact

- Owned implementation area: `booking-web/src/views/admin/approvals/**`, `booking-web/src/views/admin/users/**`, their admin-operations components/API/store modules, and scoped tests/fixtures only.
- Existing T04-owned router, HTTP client, auth store, layouts, and route components are integration boundaries, not editable files.
- Approval flows consume `GET /api/v1/admin/approvals`, `POST /api/v1/admin/bookings/{id}/approve`, and `POST /api/v1/admin/bookings/{id}/reject` after T09 freezes request/response DTOs. User flows consume merged T02 `GET /api/v1/admin/users` and `PATCH /api/v1/admin/users/{id}/status`.
- No backend, resource-admin, student-booking, shared-contract, router, package-manifest, or global-mock changes are in scope.
