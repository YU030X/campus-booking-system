# Admin Web Operations

## Purpose

This capability provides the administrator-facing approval and user-status workflows on the two already-frozen admin routes. It consumes backend source-of-truth contracts, keeps authorization in the T04 shell plus server responses, and makes every loading, confirmation, error, and refresh outcome observable and testable without changing shared frontend infrastructure.

## ADDED Requirements

### Requirement: Frozen route ownership and administrator visibility

The capability MUST reuse exactly `/admin/approvals` and `/admin/users` from the frozen 12-route table. It MUST keep route components and guards owned by T04, and MUST limit editable implementation scope to `booking-web/src/views/admin/approvals/**`, `booking-web/src/views/admin/users/**`, their admin-operations components/API/store modules, and scoped tests/fixtures. Only an authenticated `ADMIN` may see or operate these views. An authenticated `STUDENT` MUST receive the existing in-layout 403/forbidden experience, preserve the session, and cause no admin API request.

#### Scenario: student is denied without a request

- **WHEN** an authenticated `STUDENT` navigates to either admin route
- **THEN** the in-layout forbidden state is shown, the session remains available, and no `/api/v1/admin/approvals` or `/api/v1/admin/users` request is issued.

#### Scenario: administrator enters an admin route

- **WHEN** an authenticated `ADMIN` enters `/admin/approvals` or `/admin/users`
- **THEN** the corresponding view may load through its scoped API/store boundary and no new route is introduced.

### Requirement: Pending approval list and exact backend contract

The approvals view MUST consume `GET /api/v1/admin/approvals` with `pageNumber` and `pageSize`, never sending `pageSize > 100`. It MUST request and display only non-deleted records whose server-returned status is `PENDING_APPROVAL`, preserving the backend's deterministic `createdAt ASC,id ASC` ordering and canonical page envelope. It MUST preserve every `BookingView`/`ApprovalView` Long identifier as a decimal JSON string, render server timestamps in `yyyy-MM-dd HH:mm:ss` Asia/Shanghai form, and use the exact frozen status vocabulary. The final request/response field mapping is gated on the T09 sibling handoff after its merge/rebase commit is recorded; the frontend MUST NOT invent or infer fields absent from that reread.

#### Scenario: pending page

- **WHEN** an administrator opens the approvals view or changes its page
- **THEN** one de-duplicated list request uses valid pagination, displays the returned pending records in server order, and exposes loading, empty, and error/retry states without changing server status locally.

#### Scenario: non-pending response item

- **WHEN** the server response contains an item whose status is not `PENDING_APPROVAL`
- **THEN** approve/reject controls are not rendered for that item, and the client does not reinterpret it into another status.

### Requirement: Approval actions, validation, confirmation, and recovery

Approve MUST call `POST /api/v1/admin/bookings/{id}/approve` with the exact T09 request contract; its optional comment MUST be trimmed, map blank to `null`, and contain at most 500 Unicode code points. Reject MUST call `POST /api/v1/admin/bookings/{id}/reject` with the exact T09 request contract; its comment MUST be trimmed and contain 1..500 Unicode code points. Both actions MUST require a two-step confirmation before sending, disable duplicate submission while loading, and allow only records currently returned as `PENDING_APPROVAL` to expose the action. Repeating the same action after its target state is reached MUST be treated as HTTP 200 with no side effects; an opposite or otherwise illegal action MUST surface HTTP 409/code `43000`, never as a fabricated success. On success, the client MUST refresh the pending list and the affected detail/status from server truth. On failure, it MUST refetch server truth, surface actionable 401/403/404/409 messaging, and preserve useful user-entered form input where safe.

#### Scenario: reject validation blocks a request

- **WHEN** a reject form contains only whitespace, exceeds 500 Unicode code points after trimming, or has no comment
- **THEN** validation is shown locally, confirmation/request are blocked, and the comment remains available for correction.

#### Scenario: confirmed approval is de-duplicated

- **WHEN** an administrator confirms an approve or reject action and clicks again while it is loading
- **THEN** exactly one matching POST is sent and the control remains loading until it resolves.

#### Scenario: successful action refreshes truth

- **WHEN** the backend accepts an approve or reject action
- **THEN** the client refetches the pending list and affected detail/status, removes or updates the item only according to the returned response, and clears transient action state.

#### Scenario: failed action refetches truth

- **WHEN** the backend returns 401, 403, 404, or 409, or another actionable failure
- **THEN** the client shows the mapped error, refetches the relevant list/detail, preserves useful unsent form input, and never claims a state transition that the server did not return.

### Requirement: Administrator user list, filters, and pagination

The users view MUST consume merged T02 `GET /api/v1/admin/users` with `pageNumber`, `pageSize` (1..100), trimmed `keyword`, `role` (`STUDENT|ADMIN`), and `status` (`0|1`). It MUST use the exact `UserView` fields: `id` (string), `username`, `realName`, `studentNo`, `phone`, `email`, `avatar`, `role`, `creditScore`, `status`, `createdAt`, and `updatedAt`, excluding password/deleted fields. It MUST preserve backend stable ordering, support loading/empty/error/retry states, and treat unknown filter values safely by omitting or normalizing them rather than sending arbitrary values.

#### Scenario: filtered page

- **WHEN** an administrator enters a keyword or selects a valid role/status filter
- **THEN** the query is trimmed/normalized, pagination resets as appropriate, and the view renders the canonical `PageResult<UserView>` returned by the server.

#### Scenario: unknown filter is safe

- **WHEN** a stale URL, fixture, or control produces an unknown role/status/filter value
- **THEN** the client does not send an unsafe arbitrary value, does not crash, and presents the unfiltered or safely normalized result with an actionable state if needed.

### Requirement: User status lifecycle and self-disable handling

The users view MUST call `PATCH /api/v1/admin/users/{id}/status` only with numeric body `{status:0|1}` and the exact T02 contract. Repeating the current status MUST be treated as idempotent success using the returned `UserView`. Attempting to disable the current administrator MUST surface the backend 409 actionably, preserve the session, and refetch the row/list. The client MUST not infer arbitrary target statuses or bypass the backend lifecycle.

#### Scenario: status change succeeds

- **WHEN** an administrator confirms enabling or disabling another user with target status `0` or `1`
- **THEN** one PATCH is sent, the result is refetched or applied from returned `UserView`, and pagination/filter state remains coherent.

#### Scenario: self-disable is rejected

- **WHEN** an administrator attempts to set their own status to `0`
- **THEN** the 409 is shown as an actionable error, the session is preserved, no logout is fabricated, and the server row/list is refetched.

### Requirement: Authentication error boundaries and verification evidence

The capability MUST honor T04 HTTP behavior: 401 clears the session through the shared auth boundary, while a non-ADMIN caller of any admin endpoint receives HTTP 403/code `40300`, preserves the session, and renders forbidden in-layout; 404/409 remain actionable. It MUST use pure fixtures for unit tests and MUST NOT introduce global mocks. Real headless-browser checks, screenshots, and request/network evidence are required only after T04/T09 gates and backend availability are satisfied; popup-based evidence is forbidden.

#### Scenario: auth failure handling

- **WHEN** an admin operation receives 401 or 403
- **THEN** 401 follows the shared session-clearing flow, while 403 preserves the session and shows the in-layout forbidden state without retry loops.
