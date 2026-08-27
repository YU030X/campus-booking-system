# web-resource-management Specification

## Purpose
This capability gives authenticated students a reliable resource catalogue and gives administrators bounded screens for maintaining categories, resources, opening rules, and closure days. After T04 merge `679bea8`, it may substitute components only on the six existing router records `/resources`, `/resources/:id`, `/admin/categories`, `/admin/resources`, `/admin/rules`, and `/admin/closures`; the router MUST retain exactly 12 records, all paths, metadata, guards, auth handlers, and bookings/approvals/users placeholders unchanged. It translates the frozen T03 resource-catalog contracts into observable web behavior without changing shared transport or booking behavior.

## Requirements

### Requirement: Student resource discovery

The web client MUST provide an authenticated resource list and detail experience backed by `GET /api/v1/resources` and `GET /api/v1/resources/{id}`. The list MUST send only the frozen `pageNumber`, `pageSize`, `categoryId`, `status`, and `keyword` filters, render the canonical `Result<PageResult<ResourceView>>` payload (`pageNumber`, `pageSize`, `total`, `records`), and preserve Long identifiers as strings. A resource id of `0` MUST NOT be treated as a resource detail.

#### Scenario: Filtered page succeeds
- **WHEN** an authenticated student selects a category, status, keyword, and valid page size
- **THEN** the client requests the matching frozen query parameters and renders records, total, current page, and page controls

#### Scenario: Empty or failed list
- **WHEN** the server returns an empty `records` array or a non-success envelope
- **THEN** the client shows an explicit empty state or error state, stops loading, and offers a retry without fabricating records

#### Scenario: Detail missing
- **WHEN** a detail request returns HTTP 404/code `40400` for a missing or deleted resource
- **THEN** the client shows a not-found state and does not render an editable resource form

### Requirement: Administrator category management

The web client MUST expose category-tree management only to `ADMIN` users, using `GET /api/v1/categories` for the tree and the exact admin POST/PUT/DELETE category routes. Forms MUST trim `name`, enforce 1..50 characters, default `parentId` to string `"0"` and `sortOrder` to `0`, enforce sort order `-100000..100000`, and convert blank optional `icon` to null. Client validation MUST prevent submission outside these boundaries while server 400/code `40000`, 403/code `40300`, and 409/code `42000` remain visible as actionable errors.

#### Scenario: Category create/update
- **WHEN** an administrator submits a valid category form
- **THEN** the client sends the frozen DTO fields to `POST /api/v1/admin/categories` or `PUT /api/v1/admin/categories/{id}`, closes the form only after success, and refreshes the tree

#### Scenario: Invalid or forbidden category mutation
- **WHEN** a form is invalid, the user is not an administrator, or the server rejects a cycle/deletion conflict
- **THEN** the client blocks the request or displays the returned error and leaves the current tree and form state intact

### Requirement: Administrator resource management

The web client MUST expose administrator resource create, edit, and status management through the frozen resource routes: `POST /api/v1/admin/resources`, `PUT /api/v1/admin/resources/{id}`, and `PATCH /api/v1/admin/resources/{id}/status`. Forms MUST enforce T03 boundaries: trimmed name 1..100, location/images blank-to-null with maximum lengths 200/1000, description blank-to-null with UTF-8 length at most 10000, nullable capacity or capacity greater than 0, max advance days 0..365, positive duration values in 30-minute multiples with minimum no greater than maximum, and status restricted to `0|1|2`.

#### Scenario: Resource status toggle
- **WHEN** an administrator changes a resource status and the PATCH succeeds
- **THEN** the client updates the displayed status, announces success, and refreshes the current page using the same filters and pagination

#### Scenario: Resource mutation boundary error
- **WHEN** a resource form violates a boundary or the server returns 400/403/404/409
- **THEN** the client identifies the invalid or forbidden operation, keeps unsaved values available for correction, and does not optimistically alter the list

### Requirement: Opening rules and closure-day management

The web client MUST let administrators replace weekly opening rules with `PUT /api/v1/admin/resources/{id}/time-rules` and manage closure days with the exact nested POST/DELETE closure routes. Rule intervals MUST be represented at half-hour boundaries, contain no overlaps, and allow an empty array to close all weekly periods. Closure forms MUST use `closureDate` (`yyyy-MM-dd`) and optional `reason`; closure scope MUST be explicit, with id `0` reserved for global closures and never treated as a real resource. The client MUST preserve the distinction between duplicate-scope conflicts (409/code `42000`) and missing scope/closure (404/code `40400`).

#### Scenario: Replace rules and retain authoritative response
- **WHEN** an administrator submits a valid rule set, including an empty set
- **THEN** the client sends the complete replacement, reports success, and renders the successful PUT response as the current session's authoritative rule state; it MUST NOT imply a historical reload from a time-rules GET endpoint that does not exist

#### Scenario: Add and remove a global closure
- **WHEN** an administrator adds or removes a closure for scope id `0`
- **THEN** the client calls `/api/v1/admin/resources/0/closures` or `/api/v1/admin/resources/0/closures/{closureId}`, displays the successful POST response as the authoritative record for the current session, deletes only by a returned closure id, and never claims that the displayed closure set is historical-complete or offers a resource-detail route for id `0`

### Requirement: Shared loading, permission, and session behavior

Every resource-management view MUST expose deterministic loading, empty, success, and error states and MUST prevent duplicate submissions while a mutation is pending. Student users MAY read catalogue data but MUST see an explicit forbidden state for admin views; non-authenticated requests MUST follow the T04 authentication-shell behavior. HTTP 401 MUST clear the session and route through the auth shell, while HTTP 403 MUST render a no-permission state. Until T04 is merged, browser evidence MAY use only its reviewed zero-network mock endpoints (`POST /auth/register`, `POST /auth/login`, `GET/PUT /users/me`); unknown mock endpoints MUST remain 404/code `40400` and no resource mock may be invented.

#### Scenario: Student opens an admin view
- **WHEN** a signed-in `STUDENT` navigates to an administrator resource-management surface
- **THEN** the view does not issue an admin mutation request and shows a forbidden state consistent with the auth shell

#### Scenario: Mutation success and retry
- **WHEN** a mutation finishes successfully or fails transiently
- **THEN** the client re-enables controls, refreshes readable affected data only after success, preserves successful PUT/POST response state for endpoints without GET reads, and provides a retry path after failure

### Requirement: Scope excludes booking workflows

This capability MUST NOT query available slots, create or submit bookings, write `booking_slot`, alter booking status transitions, or decide booking success from cache. It MUST NOT add/remove router records or alter their paths, metadata, guards, auth handlers, or non-resource placeholders; only the six named resource records may receive component substitutions. It MUST NOT modify the HTTP client, auth handlers, shared packages, or appointment views.

#### Scenario: Resource detail is viewed
- **WHEN** a student opens a resource detail page
- **THEN** the client renders catalogue metadata only and does not submit a booking or available-slots request as part of this change
