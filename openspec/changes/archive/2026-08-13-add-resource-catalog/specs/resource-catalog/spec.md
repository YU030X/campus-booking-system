## Purpose

Freeze the resource catalog contract for downstream slot/booking work.

## ADDED Requirements

### Requirement: Exact routes and envelopes
The service MUST expose authenticated `GET /api/v1/categories`; ADMIN `POST /api/v1/admin/categories`, `PUT /api/v1/admin/categories/{id}`, `DELETE /api/v1/admin/categories/{id}`; authenticated `GET /api/v1/resources` and `/api/v1/resources/{id}`; ADMIN resource POST/PUT/PATCH status, nested time-rules PUT, and nested closures POST/DELETE exactly as frozen in docs/15. No top-level closure routes exist. Responses use canonical `Result/PageResult`; response Long IDs are strings and `deleted` is omitted.
#### Scenario: Frozen route request
- **WHEN** an authenticated caller requests a documented `/api/v1` route
- **THEN** the matching canonical envelope is returned and undocumented top-level closure routes are absent.

### Requirement: Categories
Category body MUST be `name,parentId(default "0"),sortOrder(default 0),icon?`. Trim `name` and require 1..50 characters; parse `parentId` as a decimal Long string, defaulting to `"0"`; constrain `sortOrder` to integer -100000..100000, default 0. Trim optional `icon`, converting blank to null, max 255. Parent must be non-deleted; self/descendant cycles are invalid. Logical delete returns 409 when children or active resource references exist. Tree order is `sort_order,name,id`.
#### Scenario: Category tree validation
- **WHEN** an admin selects a deleted parent or creates a cycle
- **THEN** validation returns 400 and no category changes.

#### Scenario: Category normalization and defaults
- **WHEN** an admin submits a category with surrounding whitespace and omits `parentId`/`sortOrder`
- **THEN** the stored name is trimmed, `parentId` is decimal string `"0"`, `sortOrder` is integer `0`, and blank `icon` is normalized to null.

#### Scenario: Category boundary rejection
- **WHEN** `name`, `icon`, or `sortOrder` exceeds its limit (or `sortOrder` is outside -100000..100000)
- **THEN** the API returns HTTP 400 with code `40000` and `data:null`.

### Requirement: Resource discovery and validation
Resource fields MUST be `categoryId,name,location,capacity?,description,images,needApproval,maxAdvanceDays,minDurationMinutes,maxDurationMinutes,status`. Trim `name` and require 1..100; trim optional `location` (blank=>null, <=200); `capacity` is nullable, otherwise >0; normalize nullable `description` whitespace-only to null while preserving non-blank text and enforce API UTF-8 character count <=10000 (DB remains TEXT); trim optional `images` (blank=>null, <=1000). `maxAdvanceDays` 0..365; durations positive 30-minute multiples with min<=max; status is 0|1|2. List accepts only `pageNumber` (>=1, default1), `pageSize` 1..100 (default10), filters categoryId, status, trimmed keyword <=100. Order is `created_at DESC,id DESC`.
#### Scenario: Resource page
- **WHEN** a caller supplies valid filters and pageNumber/pageSize
- **THEN** only non-deleted matches are returned in stable order.

#### Scenario: Resource normalization and defaults
- **WHEN** a caller submits valid resource fields with surrounding whitespace and blank optional `location`, `description`, or `images`
- **THEN** required `name` is trimmed, blank optionals become null, non-blank description text is preserved, and nullable `capacity` remains null when omitted.

#### Scenario: Resource boundary rejection
- **WHEN** a resource field exceeds its documented limit or non-null `capacity` is not positive
- **THEN** the API returns HTTP 400 with code `40000` and `data:null`.

### Requirement: Time rules
`PUT /api/v1/admin/resources/{id}/time-rules` MUST atomically replace rules. Body is an array; dayOfWeek 1..7, startTime/endTime `HH:mm:ss` only minute 00/30 and seconds 00, start<end. Half-open adjacent intervals are allowed; same-day overlap is 400. Empty array closes all periods. Lock the resource row for concurrent updates and ensure no mixed rows.
#### Scenario: Atomic rule replacement
- **WHEN** valid rules are submitted concurrently
- **THEN** commits serialize per resource and one complete set is visible, never mixed rows.

### Requirement: Closures
`POST /api/v1/admin/resources/{id}/closures` and `DELETE /api/v1/admin/resources/{id}/closures/{closureId}` MUST use body `{closureDate:"yyyy-MM-dd",reason?}`. `{id}=0` is global scope; resource id 0 is never real and detail `/0` is 404. `resource_closure` stores one date, resource_id NOT NULL (0 global), reason, created_at, and unique `(resource_id,closure_date)`; no deleted/ranges. Duplicate same scope/date =>409; global and resource same date coexist. Delete physically and requires closure.resource_id == path id; missing, wrong scope, or repeat =>404.
#### Scenario: Closure scope
- **WHEN** an admin creates the same date globally and for resource 3
- **THEN** both rows are accepted, while a second row in either scope returns 409.

### Requirement: Authorization, errors, deletion
Reads MUST require authentication; writes ADMIN. Missing records => HTTP 404/40400; validation =>400/40000; duplicate/reference/status conflicts=>409/42000; error data is null. Logical deletion applies category/resource/time_rule only; closures are physical. Deleted rows are never exposed.
#### Scenario: Missing record
- **WHEN** a request targets a missing or deleted resource
- **THEN** response is 404/40400 with `data:null`.

### Requirement: Scope exclusions
This capability MUST perform no slot generation, booking state changes, SQL/common/auth/frontend edits. Production/test paths are limited to the resource package trees.
#### Scenario: Scope fence
- **WHEN** the capability is implemented
- **THEN** no slot rows or booking transitions are changed.
