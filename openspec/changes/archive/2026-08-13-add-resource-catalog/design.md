## Context

This plan follows docs/11 DDL and docs/15 frozen routes. Foundation dependencies arrive separately; T02 supplies ADMIN/STUDENT authorities.

## Decisions

1. Routes include `/api/v1`: categories (`GET /categories`, admin POST/PUT/DELETE), resources (`GET /resources`, detail, admin POST/PUT/PATCH status), time rules, and nested closure POST/DELETE. Global closure uses `{id}=0`; resource id 0 is not valid and detail `/0` is 404. DELETE verifies `closure.resource_id == path id`.
2. Pagination accepts only `pageNumber` (1-based, default 1) and `pageSize` (1..100, default 10). Filters are categoryId (string/Long), status 0|1|2, and trimmed keyword <=100. Stable ordering is categories `sort_order,name,id`, resources `created_at DESC,id DESC`. Use canonical `Result/PageResult`.
3. `resource_closure` exactly matches docs/11: id, non-null resource_id (0 global), single `closure_date`, optional reason, created_at, unique `(resource_id,closure_date)`. No deleted/ranges. Create body is `{closureDate:"yyyy-MM-dd",reason?}`; duplicate 409; physical delete and missing/wrong-scope/repeat delete 404. Global/resource same date may coexist.
4. Category/resource/time_rule logical-delete; closures physical. Time-rule replacement logically deletes active rules then inserts new rows atomically. Entities match nullable/timestamp DDL fields. Capacity null means undeclared; otherwise >0.
5. Validate and normalize exact DTO fields and constraints: category `name` trim 1..50, decimal-string `parentId` default `"0"`, integer `sortOrder` default 0 and range -100000..100000, optional `icon` trim blank=>null <=255; resource `name` trim 1..100, optional `location` trim blank=>null <=200, nullable `capacity` otherwise >0, nullable `description` whitespace-only=>null while preserving non-blank text (API UTF-8 character count <=10000; DB remains TEXT), optional `images` trim blank=>null <=1000; plus advance <=365, positive 30-minute durations min<=max, boolean needApproval. Category parent non-deleted, no self/descendant cycle, delete blocked by children/active refs.
6. Rules use day 1..7 and `HH:mm:ss` with only `00/30` minutes and seconds `00`; start<end, half-open adjacent allowed, overlap 400, empty array closes all. Lock resource row (`SELECT ... FOR UPDATE` equivalent) for concurrent replacement; MySQL8 tests assert serial last-committer/no mixed rows.
7. Errors: missing 404/40400, validation 400/40000, duplicate/reference/status conflict 409/42000, `data:null`; Long responses strings, request path/query may decimal strings; deleted never exposed.

## Non-Goals / Risks

No SQL, pom/yaml, common/auth/booking/frontend or slot behavior. Availability later consumes single-day closures. T02 rebase is a release gate; test-only mock principals are allowed.
