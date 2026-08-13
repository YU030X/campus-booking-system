## Purpose

Freeze the resource catalog contract for downstream slot/booking work.

## Requirements

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

### Requirement: Atomic time rules
Time-rule replacement MUST validate half-hour non-overlap, lock the resource row, logically delete active rules, and insert the submitted set atomically; empty arrays close all periods.
#### Scenario: Atomic rule replacement
- **WHEN** valid rules are submitted concurrently
- **THEN** commits serialize per resource and one complete set is visible, never mixed rows.

### Requirement: Closures
Closures MUST support global scope id `0`, unique resource/date pairs, physical deletion, and scope-checked 404/409 behavior.
#### Scenario: Closure scope
- **WHEN** the same date is created globally and for a resource
- **THEN** both coexist while duplicate scope/date conflicts.

### Requirement: Authorization and errors
Reads MUST require authentication; writes MUST require ADMIN. Missing records MUST return 404/40400, validation 400/40000, conflicts 409/42000, with `data:null`.
#### Scenario: Missing record
- **WHEN** a request targets a missing or deleted resource
- **THEN** a 404 response with null data is returned.

### Requirement: Scope exclusions
This capability MUST perform no slot generation, booking state changes, SQL/common/auth/frontend edits. Production/test paths are limited to the resource package trees.
#### Scenario: Scope fence
- **WHEN** the capability is implemented
- **THEN** no slot rows or booking transitions are changed.
