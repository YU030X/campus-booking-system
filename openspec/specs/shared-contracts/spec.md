# Shared Contracts Specification

## Purpose

Defines stable API envelopes, errors, identity and booking vocabularies, and frontend integration boundaries shared across modules.

## Requirements

### Requirement: Envelope and pagination
Success MUST be `{code:0,message:"success",data:<payload>}`. Pages MUST be `{pageNumber,pageSize,total,records}` with `pageSize <= 100`.

#### Scenario: Envelope
- **WHEN** a page succeeds
- **THEN** canonical envelope and fields are returned.

### Requirement: HTTP and error codes
Malformed input/unauthenticated/forbidden/not-found/conflict/unknown MUST map to HTTP 400/401/403/404/409/500 respectively. Error-code ranges are authoritative by module: `40000-40099` general parameters, `40100-40199` authentication, `40300-40399` authorization, `40400-40499` not found, `41000-41099` user business, `42000-42099` resource business, and `43000-43099` booking business. HTTP 400 uses the general-parameter range, 401 authentication, 403 authorization, 404 not-found, 409 the relevant business-module range, and 500 the unknown/server-error category.

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
The API module matrix and router table MUST be frozen in `src/api/contracts.js` and `src/router/index.js`; routes are exactly `/login,/register,/resources,/resources/:id,/bookings,/bookings/:id,/admin/categories,/admin/resources,/admin/rules,/admin/closures,/admin/approvals,/admin/users`. T01 creates constants/contracts and safe placeholder routes only. T04 implements login/register pages, auth store/API, and guards. Axios client behavior is selected by `VITE_API_MODE=mock|real`.

#### Scenario: Frontend mode
- **WHEN** mode is `mock` or `real`
- **THEN** matching adapter is selected.

#### Scenario: Contract validation
- **WHEN** contract tests run
- **THEN** envelope, pagination, errors, Long/time, roles, statuses, API paths, and frontend mode constants match this specification.
