## Purpose

Extend the shared API/frontend matrix for T12's two P1 endpoint families while preserving the existing envelope, pagination, Long/time, role, and security contracts.

## MODIFIED Requirements

### Requirement: Frontend integration boundaries

The existing routes and frozen API contracts in `src/api/contracts.js` and `src/router/index.js` MUST remain single-writer owned; T12 MUST NOT edit them directly. The canonical API paths remain `/api/v1/notifications`, `/api/v1/notifications/{id}/read`, `/api/v1/admin/statistics/resources`, and `/api/v1/admin/statistics/bookings`. P1 route/API entries may be appended only by the corresponding owner after that owner accepts the T12 handoff; T12 may add only the corresponding P1 API/view modules. Axios behavior remains selected by `VITE_API_MODE=mock|real`.

#### Scenario: P1 route handoff

- **WHEN** the corresponding shared owner accepts the T12 contract handoff
- **THEN** that single writer appends the notification and admin-statistics P1 entries without changing existing P0 routes or mode semantics.

#### Scenario: No shared-file bypass

- **WHEN** a T12 implementation needs to change the router, Axios client, or contracts file
- **THEN** it stops for owner review and does not edit the shared file directly.

#### Scenario: Frontend mode

- **WHEN** mode is `mock` or `real`
- **THEN** the matching adapter is selected.

#### Scenario: Contract validation

- **WHEN** contract tests run
- **THEN** envelope, pagination, errors, Long/time, roles, statuses, API paths, and frontend mode constants match this specification.

### Requirement: Envelope and pagination

Success MUST be `{code:0,message:"success",data:<payload>}`. Pages MUST be `{pageNumber,pageSize,total,records}` with `pageSize <= 100`, including notification pages.

#### Scenario: Supporting page

- **WHEN** a valid notification page succeeds
- **THEN** the response uses the canonical envelope and page fields with `pageSize <= 100`.

#### Scenario: Envelope

- **WHEN** a page succeeds
- **THEN** canonical envelope and fields are returned.

### Requirement: HTTP and error codes

Malformed input/unauthenticated/forbidden/not-found/conflict/unknown MUST map to HTTP 400/401/403/404/409/500 respectively; T12 endpoints MUST use the existing general, auth, authorization, not-found, and relevant business error ranges and MUST return `data:null` on errors.

#### Scenario: Supporting error

- **WHEN** a notification ownership check or statistics date validation fails
- **THEN** the response uses the existing canonical HTTP/code mapping and null error data.

#### Scenario: Error mapping

- **WHEN** malformed input occurs
- **THEN** HTTP 400 and the reserved error code are returned.
