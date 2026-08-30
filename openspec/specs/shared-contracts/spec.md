# Shared Contracts Specification

## Purpose

Defines stable API envelopes, errors, identity and booking vocabularies, and frontend integration boundaries shared across modules.

## Requirements

### Requirement: Envelope and pagination
Success MUST be `{code:0,message:"success",data:<payload>}`. Pages MUST be `{pageNumber,pageSize,total,records}` with `pageSize <= 100`, including notification pages.

#### Scenario: Supporting page
- **WHEN** a valid notification page succeeds
- **THEN** the response uses the canonical envelope and page fields with `pageSize <= 100`.

#### Scenario: Envelope
- **WHEN** a page succeeds
- **THEN** canonical envelope and fields are returned.

### Requirement: HTTP and error codes
Malformed input/unauthenticated/forbidden/not-found/conflict/unknown MUST map to HTTP 400/401/403/404/409/500 respectively. Error-code ranges are authoritative by module: `40000-40099` general parameters, `40100-40199` authentication, `40300-40399` authorization, `40400-40499` not found, `41000-41099` user business, `42000-42099` resource business, and `43000-43099` booking business. HTTP 400 uses the general-parameter range, 401 authentication, 403 authorization, 404 not-found, 409 the relevant business-module range, and 500 the unknown/server-error category. T12 endpoints MUST use these existing ranges and MUST return `data:null` on errors.

#### Scenario: Supporting error
- **WHEN** a notification ownership check or statistics date validation fails
- **THEN** the response uses the existing canonical HTTP/code mapping and null error data.

#### Scenario: Error mapping
- **WHEN** malformed input occurs
- **THEN** HTTP 400 and reserved error code are returned.

### Requirement: Identity, time, and roles
Java `Long` values MUST serialize as strings. Date/time uses `yyyy-MM-dd HH:mm:ss`, Asia/Shanghai. Roles are exactly `STUDENT` and `ADMIN`.

#### Scenario: Serialization
- **WHEN** an ID is returned
- **THEN** it is a JSON string.

### Requirement: Status vocabulary and legal flow
Booking statuses MUST be `PENDING_APPROVAL`, `CONFIRMED`, `CHECKED_IN`, `COMPLETED`, `REJECTED`, `CANCELLED`, `NO_SHOW`; legal transitions are `PENDING_APPROVAL→CONFIRMED|REJECTED|CANCELLED`, `CONFIRMED→CHECKED_IN|CANCELLED|NO_SHOW`, `CHECKED_IN→COMPLETED`, with terminal states immutable. Resources not requiring approval create `CONFIRMED` directly. Slots are 30-minute left-closed/right-open, aligned to `:00`/`:30`, and may not cross calendar days.

#### Scenario: Status flow
- **WHEN** a transition is requested
- **THEN** only listed transitions succeed.

### Requirement: Frontend integration boundaries
The API module matrix and router table MUST remain single-writer owned in `src/api/contracts.js` and `src/router/index.js`. The P0 routes are `/login,/register,/resources,/resources/:id,/bookings,/bookings/:id,/admin/categories,/admin/resources,/admin/rules,/admin/closures,/admin/approvals,/admin/users`; the accepted T12 P1 routes append `/notifications` and `/admin/statistics`. Canonical T12 API paths are `/api/v1/notifications`, `/api/v1/notifications/{id}/read`, `/api/v1/admin/statistics/resources`, and `/api/v1/admin/statistics/bookings`. T01 creates constants/contracts and safe placeholder routes only; T04 implements login/register pages, auth store/API, guards, and accepted shared-file handoffs. Axios client behavior remains selected by `VITE_API_MODE=mock|real`.

#### Scenario: P1 route handoff
- **WHEN** the corresponding shared owner accepts the T12 contract handoff
- **THEN** that single writer appends the notification and admin-statistics P1 entries without changing existing P0 routes or mode semantics.

#### Scenario: No shared-file bypass
- **WHEN** a feature implementation needs to change the router, Axios client, or contracts file
- **THEN** it stops for owner review and does not bypass the shared-file owner.

#### Scenario: Frontend mode
- **WHEN** mode is `mock` or `real`
- **THEN** matching adapter is selected.

#### Scenario: Contract validation
- **WHEN** contract tests run
- **THEN** envelope, pagination, errors, Long/time, roles, statuses, API paths, and frontend mode constants match this specification.
